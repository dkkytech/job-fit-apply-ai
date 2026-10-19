#!/usr/bin/env bash
#
# run_analyzer.sh — analyze the JD pipeline's recently-completed jobs and (optionally,
# gated) open a living draft PR with fixes. Replaces the old analyze_run.sh.
#
# There is NO batch to trigger any more: the poller feeds Gmail->bridge and the processor
# drains the bridge continuously. A "run" is the set of jobs completed since the analyzer's
# persisted cursor. The analyzer consumes the bridge completed-event feed with its own
# independent cursor (it never acks writeback — that is the poller's job).
#
# Usage:
#   run_analyzer.sh              analysis + trend + notify (cheap; hourly on the schedule)
#   run_analyzer.sh --autofix    gated auto-fix -> living draft PR -> notify (twice daily; opt-in)
#
# Config (env):
#   RUN_ANALYZER_MODEL     analysis model (oMLX local default Qwen3.5-9B-OptiQ-4bit)
#   JD_BRIDGE_URL          bridge base URL (default http://127.0.0.1:8765)
#   RUN_ANALYZER_AUTOFIX   set to 1 to arm the --autofix loop (default off)
#   RUN_ANALYZER_TELEGRAM_BOT_TOKEN / _CHAT_ID
#                         analyzer Telegram destination (falls back to TELEGRAM_*)
#   PROJECT_DIR            pipeline checkout (auto-detected from this script's location)
#   Analysis context:
#     RUN_ANALYZER_CONTEXT_N       rolling context-window size          (default 40)
#     RUN_ANALYZER_RESOLVE_RUNS    runs each side of a merge to judge outcome (default 3)
#     RUN_ANALYZER_RESOLVE_MIN_JOBS jobs each side of a merge to judge outcome (default 30)
#
set -uo pipefail

# launchd (and cron) hand us a minimal PATH (/usr/bin:/bin:/usr/sbin:/sbin) that omits
# docker, gh, and claude — without them the analysis pass silently loses its `docker logs`
# tail + the Postgres audit, and autofix can't find its tools. Prepend the usual locations
# so the script behaves identically however it's invoked. This keeps the launchd plists
# env-agnostic (no per-plist PATH injection needed).
export PATH="/usr/local/bin:/opt/homebrew/bin:$HOME/.local/bin:$HOME/homebrew/bin:$PATH"

# A Homebrew python3 (now first on PATH) ships no CA bundle, so HTTPS to the cloud model
# fails under launchd with CERTIFICATE_VERIFY_FAILED. Point OpenSSL at a system CA bundle
# if the environment hasn't already set one. Interpreter-agnostic.
if [ -z "${SSL_CERT_FILE:-}" ]; then
    for _ca in /etc/ssl/cert.pem /private/etc/ssl/cert.pem /usr/local/etc/openssl@3/cert.pem \
               "$HOME/homebrew/etc/openssl@3/cert.pem" /opt/homebrew/etc/openssl@3/cert.pem; do
        [ -f "$_ca" ] && { export SSL_CERT_FILE="$_ca"; break; }
    done
fi

MODE="analyze"
[ "${1:-}" = "--autofix" ] && MODE="autofix"

# ── Resolve paths (worktree-friendly: derive from this script's location) ────────────
ANALYZER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$ANALYZER_DIR/../.." && pwd)}"
REPO_ROOT="$(cd "$PROJECT_DIR/../.." && pwd)"
STATE_DIR="$ANALYZER_DIR/state"
mkdir -p "$STATE_DIR"

RUN_TS="$(date +%Y%m%d_%H%M%S)"
export RUN_TS
# The processor writes run_log.jsonl into its /app/output bind, whose host source moved out of the
# checkout in #67. Resolve it the same way compose does — a hardcoded $PROJECT_DIR/output would read
# a frozen pre-migration copy and report run_log_missing for every job.
. "$REPO_ROOT/scripts/jfaa-data-root.sh"
export RUN_LOG="$(jfaa_pipeline_output)/runs/run_log.jsonl"
export FINDINGS_DIR="$ANALYZER_DIR/findings/$RUN_TS"
export CURSOR_FILE="$STATE_DIR/cursor"
export METRICS_FILE="$STATE_DIR/last_metrics.json"
export HISTORY_FILE="$STATE_DIR/metrics_history.jsonl"
export FINDINGS_LEDGER_FILE="$STATE_DIR/findings_ledger.jsonl"
export LEDGER_FILE="$STATE_DIR/autofix_ledger.jsonl"
export SKILL_FILE="$ANALYZER_DIR/RUN_ANALYZER_SKILL.md"
export PROMPT_FILE="$ANALYZER_DIR/PROMPT.md"
export JD_BRIDGE_URL="${JD_BRIDGE_URL:-http://127.0.0.1:8765}"
export ANALYZER_DIR PROJECT_DIR

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [run_analyzer] $*"; }

# ── Single-instance lock (portable mkdir lock; macOS has no flock) ───────────────────
LOCKDIR="/tmp/jd-run-analyzer.lock"
if ! mkdir "$LOCKDIR" 2>/dev/null; then
    if [ -f "$LOCKDIR/pid" ] && kill -0 "$(cat "$LOCKDIR/pid" 2>/dev/null)" 2>/dev/null; then
        log "another run-analyzer is running (pid $(cat "$LOCKDIR/pid")) — exiting"
        exit 0
    fi
    rm -rf "$LOCKDIR"
    mkdir "$LOCKDIR" 2>/dev/null || { log "lock race — exiting"; exit 0; }
fi
echo $$ > "$LOCKDIR/pid"
trap 'rm -rf "$LOCKDIR"' EXIT

cd "$PROJECT_DIR" || { log "project dir not found: $PROJECT_DIR"; exit 1; }

# ── Make LLM-routing creds available (cloud auth + local oMLX) and pick the model ─────
MODEL="${RUN_ANALYZER_MODEL:-Qwen3.5-9B-OptiQ-4bit}"
if [ -f "$PROJECT_DIR/.env" ]; then
    export OLLAMA_API_KEY="${OLLAMA_API_KEY:-$(grep -E '^OLLAMA_API_KEY=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export OLLAMA_CLOUD_BASE_URL="${OLLAMA_CLOUD_BASE_URL:-$(grep -E '^OLLAMA_CLOUD_BASE_URL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export MLX_LOCAL_BASE_URL="${MLX_LOCAL_BASE_URL:-$(grep -E '^MLX_LOCAL_BASE_URL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export MLX_API_KEY="${MLX_API_KEY:-$(grep -E '^MLX_API_KEY=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    # Notification creds — namespaced analyzer Telegram values win; generic values remain
    # as a backward-compatible fallback in analyzer/notify.py.
    export DISCORD_BOT_TOKEN="${DISCORD_BOT_TOKEN:-$(grep -E '^DISCORD_BOT_TOKEN=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export DISCORD_CHANNEL_ID="${DISCORD_CHANNEL_ID:-$(grep -E '^DISCORD_CHANNEL_ID=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export RUN_ANALYZER_TELEGRAM_BOT_TOKEN="${RUN_ANALYZER_TELEGRAM_BOT_TOKEN:-$(grep -E '^RUN_ANALYZER_TELEGRAM_BOT_TOKEN=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export RUN_ANALYZER_TELEGRAM_CHAT_ID="${RUN_ANALYZER_TELEGRAM_CHAT_ID:-$(grep -E '^RUN_ANALYZER_TELEGRAM_CHAT_ID=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-$(grep -E '^TELEGRAM_BOT_TOKEN=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export TELEGRAM_CHAT_ID="${TELEGRAM_CHAT_ID:-$(grep -E '^TELEGRAM_CHAT_ID=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    MODEL="${RUN_ANALYZER_MODEL:-$(grep -E '^RUN_ANALYZER_MODEL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    MODEL="${MODEL:-Qwen3.5-9B-OptiQ-4bit}"
fi
export MODEL

# ── Capture the processor log tail for root-causing (pm2 is retired — this is Compose) ─
export PROCESSOR_LOG_TAIL="$(docker logs --tail 200 jobfit-processor 2>&1 || true)"

# ── Dispatch ─────────────────────────────────────────────────────────────────────────
cd "$ANALYZER_DIR" || { log "analyzer dir not found: $ANALYZER_DIR"; exit 1; }
if [ "$MODE" = "autofix" ]; then
    log "autofix mode (model=$MODEL)"
    python3 -m analyzer.autofix
else
    log "analysis mode (model=$MODEL)"
    python3 analyze.py
fi
RC=$?

log "done (mode=$MODE, findings in $FINDINGS_DIR)"
exit $RC
