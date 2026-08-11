#!/usr/bin/env bash
#
# Resolve JFAA's root-derived host paths exactly the way docker-compose.yml does, for the
# host-side consumers that read the same trees (doctor.sh, e2e-smoke.sh, run_analyzer.sh).
#
# Precedence per mount, matching compose:
#   per-service override  →  JFAA_DATA_ROOT  →  portable fallback (${HOME}/.local/share/jfaa)
#
# Source it, then call the resolvers:
#   . "$REPO_ROOT/scripts/jfaa-data-root.sh"
#   output_dir="$(jfaa_pipeline_output)"
#
# Compose reads JFAA_DATA_ROOT from the gitignored repo-root .env automatically; a plain shell
# does not. So when the variable is not already exported we read that file — otherwise a
# launchd/cron consumer would silently resolve to the portable fallback while the containers
# use the configured root.

# Worktree-friendly: derive the repo root from this script's own location.
JFAA_REPO_ROOT="${JFAA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# Read one KEY from the instance env file without executing it (JFAA_ENV_FILE overrides the
# repo-root .env — that is how instance-aware consumers point at .env.test). Values may contain
# spaces and are often quoted (a macOS "Application Support" root), so strip one layer of quotes.
_jfaa_dotenv() {
    local key="$1" file="${JFAA_ENV_FILE:-$JFAA_REPO_ROOT/.env}" value
    [ -f "$file" ] || return 0
    value="$(grep -E "^[[:space:]]*${key}=" "$file" | tail -1 | cut -d= -f2- || true)"
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"
    printf '%s' "$value"
}

jfaa_data_root() {
    local root="${JFAA_DATA_ROOT:-$(_jfaa_dotenv JFAA_DATA_ROOT)}"
    printf '%s' "${root:-$HOME/.local/share/jfaa}"
}

jfaa_pipeline_output() {
    local override="${JD_PIPELINE_OUTPUT_HOST:-$(_jfaa_dotenv JD_PIPELINE_OUTPUT_HOST)}"
    printf '%s' "${override:-$(jfaa_data_root)/pipeline-output}"
}

jfaa_pipeline_state() {
    local override="${JD_PIPELINE_STATE_HOST:-$(_jfaa_dotenv JD_PIPELINE_STATE_HOST)}"
    printf '%s' "${override:-$(jfaa_data_root)/pipeline-state}"
}

# The bridge's durable store (SQLite queue at <dir>/jobs.db) — same precedence compose
# uses for the bridge's /data mount. Host-side readers (replay-jobs.sh) resolve it here
# rather than hardcoding a path.
jfaa_bridge_store() {
    local override="${JD_BRIDGE_STORE_HOST:-$(_jfaa_dotenv JD_BRIDGE_STORE_HOST)}"
    printf '%s' "${override:-$(jfaa_data_root)/bridge}"
}

# Legacy (pre-#67) repo-local Pipeline trees: the migration source, and the rollback copy after.
jfaa_legacy_pipeline_output() {
    printf '%s' "$JFAA_REPO_ROOT/services/job-fit-apply-ai-pipeline/output"
}
jfaa_legacy_pipeline_state() {
    printf '%s' "$JFAA_REPO_ROOT/services/job-fit-apply-ai-pipeline/state"
}

# True when a legacy tree still holds data and the root-derived one is empty — i.e. compose would
# bind a fresh empty directory and the stack would come up having silently lost every existing
# artifact URL / authenticated browser session. Detects the unmigrated deploy, not a fresh install.
jfaa_unmigrated() { # jfaa_unmigrated <legacy_dir> <current_dir>
    local legacy="$1" current="$2"
    [ -d "$legacy" ] || return 1
    [ -n "$(ls -A "$legacy" 2>/dev/null)" ] || return 1
    [ -z "$(ls -A "$current" 2>/dev/null)" ]
}
