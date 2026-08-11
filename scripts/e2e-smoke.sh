#!/usr/bin/env bash
#
# End-to-end smoke for the containerized stack: submit a fixture JD to the bridge,
# let the containerized Processor claim + process it (LLM scoring/tailoring on the
# host models, PDF via LaTeX), and assert the artifacts.
#
#   make e2e            # or: ./scripts/e2e-smoke.sh
#
# Env knobs:
#   SMOKE_TIMEOUT       seconds to wait for the job (default 1800 — LLM-bound)
#   E2E_REQUIRE_CDP=1   fail (instead of warn) when the CDP Chrome pre-check fails
#   JD_BRIDGE_PORT      host port of the bridge (default 8765)
#
# Requires: docker compose, python3 (JSON parsing), curl. Read-only except for the
# job it submits and the artifacts the pipeline writes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Instance selection: ENV_FILE (Makefile passes .env.test for `make e2e-smoke INSTANCE=test`)
# carries the instance's ports, container prefix, and data root. Prod default reads .env the
# same way doctor.sh does, so behavior with no ENV_FILE set is unchanged.
ENV_FILE="${ENV_FILE:-.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090,SC1091
  . "./$ENV_FILE"
  set +a
fi
export JFAA_ENV_FILE="$ROOT/$ENV_FILE"
P="${CONTAINER_PREFIX:-jobfit}"
if [ "$ENV_FILE" = ".env" ]; then
  COMPOSE=(docker compose)
else
  COMPOSE=(docker compose --env-file "$ENV_FILE")
fi

BRIDGE="http://127.0.0.1:${JD_BRIDGE_PORT:-8765}"
TIMEOUT="${SMOKE_TIMEOUT:-1800}"
# This smoke drives the BASE compose stack, so it must read the same root-derived host tree the
# processor writes to (#67) — not the pre-migration checkout path.
. "$ROOT/scripts/jfaa-data-root.sh"
OUTPUT_DIR="$(jfaa_pipeline_output)"

say()  { printf "\033[1m[e2e]\033[0m %s\n" "$1"; }
pass() { printf "\033[32m[e2e] ✓ %s\033[0m\n" "$1"; }
fail() { printf "\033[31m[e2e] ✗ %s\033[0m\n" "$1"; exit 1; }

json_get() { # json_get <json> <field>  — top-level string/number field or empty
  python3 -c 'import json,sys; d=json.loads(sys.argv[1]); v=d.get(sys.argv[2]); print("" if v is None else v)' "$1" "$2"
}

# ── 1. Bring up the smoke slice and wait for health ──────────────────────────
# --build so the smoke always exercises current source, not a stale image.
say "Building + starting db + bridge + processor…"
"${COMPOSE[@]}" up -d --build db bridge processor >/dev/null

say "Waiting for containers to report healthy…"
for c in "$P-db" "$P-bridge" "$P-processor"; do
  deadline=$((SECONDS + 180))
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)" = "healthy" ]; do
    [ "$SECONDS" -ge "$deadline" ] && fail "$c not healthy after 180s  →  docker logs $c"
    sleep 3
  done
done
pass "db, bridge, processor healthy"

# ── 2. CDP pre-check: container → host Chrome over CDP (fast, before the long run) ──
say "CDP pre-check (container → host Chrome)…"
if "${COMPOSE[@]}" run --rm processor --test-chrome https://example.com/ 2>&1 | grep -q "Connected to Chrome over CDP"; then
  pass "container reaches the host CDP Chrome (Host-header rewrite works)"
else
  if [ "${E2E_REQUIRE_CDP:-0}" = "1" ]; then
    fail "CDP pre-check failed — launch-chrome-cdp.sh running? (E2E_REQUIRE_CDP=1)"
  fi
  say "WARN: CDP pre-check failed — continuing (the fixture JD needs no scraping)."
  say "      Browser-scraped intake will not work until the CDP Chrome is reachable."
fi

# ── 3. Submit a fixture JD (nonce in company + idempotency key beat the 30-day dedupe) ──
NONCE="$(date +%s)"
COMPANY="Acme E2E $NONCE"
say "Submitting fixture JD (company: $COMPANY)…"
SUBMIT_BODY="$(python3 - "$COMPANY" "$NONCE" <<'PY'
import json, sys
company, nonce = sys.argv[1], sys.argv[2]
jd = """Staff Software Engineer in Test — {c} (Remote)

About the role:
We are looking for a Staff SDET to lead quality engineering across our mobile and
backend platforms. You will own the test strategy, build automation frameworks from
scratch, and partner closely with engineering leadership to define quality gates.

Requirements:
- 7+ years of software engineering experience with a focus on test automation
- Expert-level proficiency in Kotlin and/or Swift for mobile test automation
- Hands-on experience with Espresso, XCUITest, and Jetpack Compose UI testing
- Strong CI/CD skills — Bitrise, GitHub Actions, or CircleCI pipeline ownership
- Experience building test infrastructure: device farms (Firebase Test Lab, BrowserStack)
- Solid understanding of API testing (REST, gRPC) and contract testing (Pact)
- Ability to mentor engineers and drive testing culture across teams
- Experience with observability tools: Datadog, Grafana, or equivalent
""".format(c=company)
print(json.dumps({
    "jd_text": jd,
    "company": company,
    "role_title": "Staff Software Engineer in Test",
    "location": "Remote (US)",
    "source": "MANUAL",
    "idempotency_key": f"e2e-smoke-{nonce}",
}))
PY
)"
RESP="$(curl -sf -X POST "$BRIDGE/api/jobs" -H 'Content-Type: application/json' -d "$SUBMIT_BODY")" \
  || fail "POST $BRIDGE/api/jobs failed — bridge logs: docker logs $P-bridge"
JOB_ID="$(json_get "$RESP" job_id)"
[ -n "$JOB_ID" ] || fail "no job_id in submit response: $RESP"
pass "submitted job $JOB_ID"

# ── 4. Poll until the processor completes it ─────────────────────────────────
say "Waiting for the processor (timeout ${TIMEOUT}s — LLM-bound, typically minutes)…"
deadline=$((SECONDS + TIMEOUT))
STATUS=""
while :; do
  [ "$SECONDS" -ge "$deadline" ] && fail "job $JOB_ID still '$STATUS' after ${TIMEOUT}s  →  docker logs $P-processor"
  STATE="$(curl -sf "$BRIDGE/api/jobs/$JOB_ID" || echo '{}')"
  STATUS="$(json_get "$STATE" status)"
  case "$STATUS" in
    done)  break ;;
    error) fail "job $JOB_ID errored: $(json_get "$STATE" error)  →  docker logs $P-processor" ;;
  esac
  sleep 10
done
ACTION="$(json_get "$STATE" pipeline_action)"
SCORE="$(json_get "$STATE" fit_score)"
pass "job done — action=$ACTION score=$SCORE"

# ── 5. Assert artifacts ───────────────────────────────────────────────────────
[ "$ACTION" = "TAILOR" ] || fail "expected pipeline_action=TAILOR, got '$ACTION' (score=$SCORE) — no PDF was produced; check FIT_THRESHOLD / model config"

say "Checking the PDF artifact via the bridge…"
PDF_HEAD="$(curl -sf "$BRIDGE/api/jobs/$JOB_ID/resume.pdf" | head -c 5)" \
  || fail "GET /api/jobs/$JOB_ID/resume.pdf failed"
[ "$PDF_HEAD" = "%PDF-" ] || fail "resume.pdf does not start with %PDF- (got: $PDF_HEAD)"
pass "bridge serves a real PDF"

say "Checking the shared output dir (markserv view)…"
JOB_DIR="$(ls -td "$OUTPUT_DIR"/*/ 2>/dev/null | head -1)"
[ -n "$JOB_DIR" ] || fail "no job dir under $OUTPUT_DIR"
for f in tailored_resume.yaml tailored_resume.tex tailored_resume.html report.md; do
  [ -f "$JOB_DIR/$f" ] || fail "missing $f in $JOB_DIR"
done
ls "$JOB_DIR"/*.pdf >/dev/null 2>&1 || fail "no PDF in $JOB_DIR"
[ ! -e "$JOB_DIR/fonts" ]          || fail "leftover fonts/ in $JOB_DIR (render cleanup regressed)"
[ ! -e "$JOB_DIR/render_pdf.log" ] || fail "leftover render_pdf.log in $JOB_DIR (success should remove it)"
pass "output dir complete: $(basename "$JOB_DIR") (yaml + tex + html + report + PDF, no litter)"

printf "\n\033[32m[e2e] ALL CHECKS PASSED\033[0m — job %s, artifacts in %s\n" "$JOB_ID" "$JOB_DIR"
