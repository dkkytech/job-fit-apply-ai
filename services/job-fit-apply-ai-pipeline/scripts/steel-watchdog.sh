#!/usr/bin/env bash
#
# steel-watchdog.sh — keep the self-hosted Steel Browser backend actually able to scrape.
#
# The Steel container can wedge while still reporting HEALTHY: its Docker healthcheck (and the
# processor's GET /v1/sessions) only prove the Fastify API is up. But Steel keeps ONE long-lived
# Chrome and reuses it across sessions; if that browser's primary page dies, every
# `POST /v1/sessions` fails with HTTP 500 ("Failed to refresh primary page when reusing browser
# instance") until the container restarts. Docker's `restart: unless-stopped` never fires — an
# unhealthy-but-running container isn't a process exit — so it stays wedged indefinitely and all
# browser-only scrapes (glassdoor/LinkedIn, forced-CDP domains) silently fall back to the thin
# email snippet, tanking scoring quality.
#
# This watchdog runs under the com.jd.steel-watchdog launchd agent (StartInterval=120), so it must
# be SHORT-LIVED: it does ONE real createSession probe — the only thing that reliably detects the
# wedge — and, only on TWO consecutive failures (a strike file guards against a transient blip or a
# race with a real scrape), `docker restart`s the container. On success it releases the probe
# session so it doesn't leak.
#
# Manual use:  scripts/steel-watchdog.sh        (no-op if a session can be created)
# Config:      STEEL_BIND_ADDR from the repo-root .env (compose publishes the port there)
set -euo pipefail
# launchd hands jobs a minimal PATH; make sure docker + curl resolve (see run_analyzer.sh).
export PATH="/usr/local/bin:/opt/homebrew/bin:$HOME/homebrew/bin:/Applications/Docker.app/Contents/Resources/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Repo-root .env (scripts -> pipeline -> services -> root); the compose file reads STEEL_BIND_ADDR there.
ROOT_ENV="${STEEL_ENV_FILE:-$SCRIPT_DIR/../../../.env}"

CONTAINER="${STEEL_CONTAINER:-jobfit-steel}"
STRIKE_FILE="${STEEL_STRIKE_FILE:-/tmp/jd-steel-watchdog.strike}"
PORT="${STEEL_PORT:-3000}"

# Read a single KEY=value from .env; real env vars win.
read_env() { grep -E "^$1=" "$ROOT_ENV" 2>/dev/null | tail -n1 | cut -d= -f2- || true; }
say() { printf '%s [steel-watchdog] %s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')" "$*"; }

# The port is published on ${STEEL_BIND_ADDR:-127.0.0.1}:3000 (loopback default, tailnet IP here).
ADDR="${STEEL_BIND_ADDR:-$(read_env STEEL_BIND_ADDR)}"; ADDR="${ADDR:-127.0.0.1}"
BASE="http://$ADDR:$PORT"

# Probe: create a real session (short timeout), report ok/fail, and release it on success so the
# probe never leaks a browser. Echoes the created session id (if any) on stdout for the release.
probe() {
  local body code sid
  body="$(curl -s -m 25 -o - -w $'\n%{http_code}' -X POST "$BASE/v1/sessions" \
            -H 'Content-Type: application/json' -d '{"timeout":30000}' 2>/dev/null || true)"
  code="${body##*$'\n'}"
  if [ "$code" = "200" ]; then
    sid="$(printf '%s' "${body%$'\n'*}" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -n1)"
    [ -n "$sid" ] && curl -s -m 10 -o /dev/null -X POST "$BASE/v1/sessions/$sid/release" 2>/dev/null || true
    return 0
  fi
  return 1
}

# 1) Healthy — clear any strike and stay quiet. This is the common path, so the watchdog is cheap.
if probe; then
  rm -f "$STRIKE_FILE" 2>/dev/null || true
  exit 0
fi

# 2) First failure: record a strike and wait for the next tick. Avoids restarting on a one-off blip
#    or a probe that raced a real scrape.
if [ ! -f "$STRIKE_FILE" ]; then
  : > "$STRIKE_FILE"
  say "createSession failed at $BASE (strike 1/2) — will restart '$CONTAINER' next tick if still down."
  exit 0
fi

# 3) Second consecutive failure: the browser is wedged (or the container is down). Restart it —
#    `docker restart` starts a stopped container too, and a fresh Chrome clears the dead-page state.
say "createSession still failing (strike 2/2) — restarting '$CONTAINER'."
rm -f "$STRIKE_FILE" 2>/dev/null || true
if ! docker restart "$CONTAINER" >/dev/null 2>&1; then
  say "WARNING: 'docker restart $CONTAINER' failed (docker down or container missing?)."
  exit 0
fi

# 4) Confirm recovery for the log (Steel needs ~dbus+Xvfb+Chrome relaunch; start_period is 40s).
#    Don't fail the job if it's slow — the next tick re-probes.
for _ in $(seq 1 15); do
  if probe; then
    say "Steel recovered — createSession succeeds again."
    exit 0
  fi
  sleep 4
done
say "WARNING: Steel still not creating sessions after restart — check 'docker logs $CONTAINER'."
exit 0
