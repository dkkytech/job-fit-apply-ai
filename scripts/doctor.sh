#!/usr/bin/env bash
#
# Preflight / health check for the Dockerized stack. Read-only — changes nothing.
# Exits 0 when all CRITICAL checks pass (warnings allowed), 1 otherwise.
#
# Covers what a setup script cannot automate (secrets, OAuth, model downloads,
# host daemons) by *checking* it and telling you what's missing.
#
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Match Docker Compose's configuration so path diagnostics cover both the portable
# JFAA_DATA_ROOT layout and legacy per-service overrides. ENV_FILE selects the instance
# (Makefile passes .env.test for `make doctor INSTANCE=test`); default is the prod .env.
# These files are user-owned/gitignored.
ENV_FILE="${ENV_FILE:-.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090,SC1091
  . "./$ENV_FILE"
  set +a
fi
export JFAA_ENV_FILE="$ROOT/$ENV_FILE"
# shellcheck disable=SC1091
. "$ROOT/scripts/jfaa-data-root.sh"

# Instance identity: container names carry the prefix; intake services (poller/jsearch)
# exist only where COMPOSE_PROFILES activates them — a test instance sets it empty.
P="${CONTAINER_PREFIX:-jobfit}"
case "${COMPOSE_PROFILES-intake}" in
  *intake*) INTAKE=1 ;;
  *)        INTAKE=0 ;;
esac

FAILS=0
WARNS=0
ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
warn() { printf "  \033[33m!\033[0m %s\n" "$1"; WARNS=$((WARNS + 1)); }
bad()  { printf "  \033[31m✗\033[0m %s\n" "$1"; FAILS=$((FAILS + 1)); }
hdr()  { printf "\n\033[1m%s\033[0m\n" "$1"; }

running()   { docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$1"; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&-; return 0; } || return 1; }

hdr "Docker"
if docker info >/dev/null 2>&1; then ok "Docker daemon running"; else bad "Docker daemon NOT running (start Docker Desktop)"; fi

hdr "Compose services (prefix: $P)"
if running "$P-db"; then
  if docker exec "$P-db" pg_isready -U "${POSTGRES_USER:-jobfit}" -d "${POSTGRES_DB:-jobfit}" >/dev/null 2>&1; then
    ok "db ($P-db) up & accepting connections"
  else bad "db container up but not accepting connections"; fi
else bad "db ($P-db) not running  →  make up"; fi
if running "$P-bridge"; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-bridge" 2>/dev/null)" = "healthy" ]; then
    ok "bridge ($P-bridge) healthy"
  else warn "bridge container up but not healthy yet"; fi
else bad "bridge ($P-bridge) not running  →  make up"; fi
if running "$P-markserv"; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-markserv" 2>/dev/null)" = "healthy" ]; then
    ok "markserv ($P-markserv) healthy"
  else warn "markserv container up but not healthy yet"; fi
else warn "markserv not running  →  make up"; fi
if running "$P-frontend"; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-frontend" 2>/dev/null)" = "healthy" ]; then
    ok "frontend ($P-frontend) healthy"
  else warn "frontend container up but not healthy yet"; fi
else bad "frontend ($P-frontend) not running  →  make up"; fi
if [ "$INTAKE" = "1" ]; then
  if running "$P-poller"; then
    if [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-poller" 2>/dev/null)" = "healthy" ]; then
      ok "poller ($P-poller) healthy — Gmail intake + write-back"
    else warn "poller container up but not healthy (loops stalled?  →  docker logs $P-poller)"; fi
  else bad "poller ($P-poller) not running  →  make up"; fi
  if running "$P-jsearch"; then
    if [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-jsearch" 2>/dev/null)" = "healthy" ]; then
      ok "jsearch ($P-jsearch) healthy — daily JSearch API intake"
    else warn "jsearch container up but not healthy (needs JSEARCH_API_KEY?  →  docker logs $P-jsearch)"; fi
  else warn "jsearch ($P-jsearch) not running  →  set JSEARCH_API_KEY in .env, then make up"; fi
else
  ok "intake services (poller/jsearch) intentionally absent — COMPOSE_PROFILES has no 'intake' (jobs enter via make replay)"
  if running "$P-poller" || running "$P-jsearch"; then
    bad "intake container running for prefix $P but COMPOSE_PROFILES excludes intake — a second poller races prod on Gmail"
  fi
fi
if running "$P-notifier"; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-notifier" 2>/dev/null)" = "healthy" ]; then
    ok "notifier ($P-notifier) healthy — Discord/Telegram from the completed-event stream"
  else warn "notifier container up but not healthy  →  docker logs $P-notifier"; fi
else warn "notifier ($P-notifier) not running  →  make up"; fi
if running "$P-processor"; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' "$P-processor" 2>/dev/null)" = "healthy" ]; then
    ok "processor ($P-processor) healthy — scan/scrape/score/tailor"
  else warn "processor container up but not healthy (loop stalled?  →  docker logs $P-processor)"; fi
else bad "processor ($P-processor) not running  →  make up"; fi

hdr "Postgres data"
CNT="$(docker exec "$P-db" psql -U "${POSTGRES_USER:-jobfit}" -d "${POSTGRES_DB:-jobfit}" -tAc 'SELECT count(*) FROM tracks;' 2>/dev/null || true)"
if [ -n "$CNT" ]; then ok "tracks table present (${CNT} rows)"; else warn "could not query tracks table"; fi

hdr ".env files"
for f in .env \
         services/job-fit-apply-ai-pipeline/.env \
         services/job-fit-apply-ai-bridge/.env \
         services/job-fit-apply-ai-poller/.env \
         apps/job-fit-apply-ai-backlog/.env; do
  if [ -f "$f" ]; then ok "present: $f"; else warn "missing: $f  (copy from $f.example)"; fi
done

hdr "Tailscale"
TS="${TAILSCALE_BIN:-tailscale}"; command -v "$TS" >/dev/null 2>&1 || TS=/usr/local/bin/tailscale
if "$TS" status >/dev/null 2>&1; then ok "Tailscale up & logged in"; else bad "Tailscale not running / not logged in"; fi
if "$TS" serve status 2>/dev/null | grep -qi 'tailnet only'; then ok "Tailscale Serve configured"; else warn "no Tailscale Serve config  →  make serve"; fi

hdr "Tailnet-exposed services (host loopback)"
MARKSERV_P="${MARKSERV_PORT:-8081}"
BRIDGE_P="${JD_BRIDGE_PORT:-8765}"
FRONTEND_P="${FRONTEND_PORT:-3030}"
port_open "$MARKSERV_P" && ok "markserv on 127.0.0.1:$MARKSERV_P" || warn "nothing on 127.0.0.1:$MARKSERV_P"
port_open "$BRIDGE_P"   && ok "bridge on 127.0.0.1:$BRIDGE_P"     || warn "nothing on 127.0.0.1:$BRIDGE_P"
port_open "$FRONTEND_P" && ok "frontend on 127.0.0.1:$FRONTEND_P" || warn "nothing on 127.0.0.1:$FRONTEND_P"

hdr "Host services needed by the pipeline"
port_open 11436 && ok "MLX/oMLX :11436" || warn "MLX/oMLX not reachable on :11436 (scoring/tailoring)"
port_open 11434 && ok "Ollama :11434"   || warn "Ollama not reachable on :11434"
port_open 9222  && ok "Chrome CDP :9222" || warn "Chrome CDP not reachable on :9222 (JD scraping)"
# The processor container dials these via host.docker.internal (works on Docker Desktop 29.x —
# its host-side proxy connects to loopback-bound ports). Probe the path from inside the container.
if running "$P-processor"; then
  if docker exec "$P-processor" curl -sf -m 5 http://host.docker.internal:11434/api/version >/dev/null 2>&1; then
    ok "container → host.docker.internal reaches host services"
  else warn "container cannot reach host.docker.internal:11434 — processor's local LLM/CDP calls will fail"; fi
fi

hdr "Processor container inputs (bind-mount sources)"
# Compose bind-mounts these three; if one is absent Docker silently creates a DIRECTORY in its
# place and the processor gets an unreadable/empty input.
for f in services/job-fit-apply-ai-pipeline/.env \
         services/job-fit-apply-ai-pipeline/src/main/resources/resume/resume.yaml \
         services/job-fit-apply-ai-pipeline/config/candidate_profile.yaml; do
  if [ -f "$f" ]; then ok "present: $f"
  elif [ -d "$f" ]; then bad "$f is a DIRECTORY (created by docker before the file existed) — remove it and create the real file"
  else bad "missing: $f — the processor container needs it (bind-mounted read-only)"; fi
done

hdr "Pipeline data root (durable artifacts + browser session state)"
# Same precedence compose uses; see scripts/jfaa-data-root.sh.
PIPELINE_OUTPUT="$(jfaa_pipeline_output)"
PIPELINE_STATE="$(jfaa_pipeline_state)"
for pair in "output:$PIPELINE_OUTPUT:$(jfaa_legacy_pipeline_output)" \
            "state:$PIPELINE_STATE:$(jfaa_legacy_pipeline_state)"; do
  what="${pair%%:*}"; rest="${pair#*:}"; cur="${rest%%:*}"; legacy="${rest#*:}"
  # The legacy tree is prod's pre-#67 repo-local directory — comparing it against a named
  # instance's root is meaningless (a fresh instance root is empty by design, and would
  # always misfire "unmigrated" against prod's old data). Only prod's default .env runs
  # this check; see the matching skip in the Makefile's data-root-check target.
  if [ "$ENV_FILE" != ".env" ]; then
    if [ -d "$cur" ]; then ok "pipeline $what: $cur"
    else warn "pipeline $what dir does not exist yet: $cur (docker will create it on first start)"; fi
  elif jfaa_unmigrated "$legacy" "$cur"; then
    bad "pipeline $what NOT migrated — $legacy has data, $cur is empty (see docs/data-root-migration.md)"
  elif [ -d "$cur" ]; then ok "pipeline $what: $cur"
  else warn "pipeline $what dir does not exist yet: $cur (docker will create it on first start)"; fi
done
# Authenticated Steel/browser cookies live here; markserv must never serve them.
if [ -d "$PIPELINE_STATE" ] && [ "$(stat -f '%Lp' "$PIPELINE_STATE" 2>/dev/null || stat -c '%a' "$PIPELINE_STATE" 2>/dev/null)" != "700" ]; then
  warn "pipeline state is not mode 700: $PIPELINE_STATE (holds authenticated browser session state)"
fi

hdr "Gmail (containerized Poller owns all Gmail)"
if [ "$INTAKE" != "1" ]; then
  ok "skipped — this instance runs no Poller (COMPOSE_PROFILES has no 'intake'), so it never touches Gmail"
else
  if [ -n "${JD_POLLER_SECRETS_HOST:-}" ]; then
    POLLER_SECRETS="$JD_POLLER_SECRETS_HOST"
  elif [ -n "${JFAA_DATA_ROOT:-}" ]; then
    POLLER_SECRETS="$JFAA_DATA_ROOT/poller-secrets"
  else
    POLLER_SECRETS="$HOME/.local/share/jfaa/poller-secrets"
  fi
  if [ -f "$POLLER_SECRETS/tokens/gmail_token.json" ]; then
    # Warn if the token is stale (Testing-mode refresh token expires ~weekly).
    if [ -n "$(find "$POLLER_SECRETS/tokens/gmail_token.json" -mtime +6 2>/dev/null)" ]; then
      warn "Gmail token >6 days old — refresh soon: docker compose run --rm poller --reauth"
    else ok "Gmail token present in poller secrets  ($POLLER_SECRETS)"; fi
  else bad "Gmail token missing in $POLLER_SECRETS/tokens  →  docker compose run --rm poller --reauth"; fi
fi

hdr "PM2 (fully retired — everything is Compose now)"
if command -v pm2 >/dev/null 2>&1; then
  if pm2 list 2>/dev/null | grep -E "\bjd-(worker|poller|processor)\b" | grep -qi online; then
    warn "pm2 jd-worker/jd-poller/jd-processor still online — should be gone (all services are containers now)"
  else
    ok "no pipeline processes under pm2"
  fi
else ok "pm2 not on PATH (expected — fully migrated to Compose)"; fi

echo
warnsuffix=""
[ "$WARNS" -gt 0 ] && warnsuffix="$(printf " \033[33m(%d warning(s))\033[0m" "$WARNS")"
if [ "$FAILS" -eq 0 ]; then
  printf "\033[32mAll critical checks passed.\033[0m%s\n" "$warnsuffix"; exit 0
else
  printf "\033[31m%d critical check(s) failed.\033[0m%s\n" "$FAILS" "$warnsuffix"; exit 1
fi
