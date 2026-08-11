#!/usr/bin/env bash
#
# Replay jobs from a source bridge's SQLite store into another (test) bridge instance.
#
# The bridge keeps every submitted payload verbatim in jobs.jd_json, discriminated by
# jobs.type — so replay is: read the row (READ-ONLY), re-POST the stored body to the
# matching intake endpoint of the target bridge. This is both the intake path for a
# test instance (which runs no poller/jsearch — see docs/multi-instance.md) and the
# mechanism for A/B runs on identical inputs.
#
#   scripts/replay-jobs.sh                         # replay the most recent done job
#   scripts/replay-jobs.sh --last 5                # the 5 most recent done jobs
#   scripts/replay-jobs.sh --id <job_id> [--id …]  # specific source rows
#   scripts/replay-jobs.sh --since 2026-08-01      # everything done since a date
#   scripts/replay-jobs.sh --status all --last 3   # include pending/claimed/error rows
#   scripts/replay-jobs.sh --force …               # beat the target's dedup window
#   scripts/replay-jobs.sh --to http://127.0.0.1:28765   # target bridge (default)
#
# Type → endpoint routing (Routes.kt):
#   JD_SCRAPED  → POST /api/jobs      EMAIL_RAW → POST /api/emails
#   JD_PAGE_RAW → POST /api/pages
#
# --force perturbs the payload minimally so the target's dedup (idempotency_key OR
# job_url/url within the window) cannot absorb the replay: the idempotency key gets a
# run suffix, and any job_url/url gets a `#replay-<ts>` fragment (ignored by HTTP
# fetches, but distinct to the dedup query). Without --force, payloads are re-POSTed
# byte-verbatim and repeat replays dedup — that is the safe default.
#
# The source store is opened read-only (sqlite3 file:…?mode=ro) and is resolved the
# same way compose resolves the bridge's /data mount (scripts/jfaa-data-root.sh);
# `--store <dir>` points at any other store (e.g. an e2e slice's ./.e2e-src/bridge-store).
#
# Gmail safety: replayed jobs that go terminal in the target accumulate
# writeback_done=false rows there; only a poller drains those, and a test instance
# runs none — nothing in the target can touch Gmail.
#
# Deps: sqlite3, jq, curl.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/scripts/jfaa-data-root.sh"

TO="http://127.0.0.1:28765"
STORE_DIR=""
LAST=""
SINCE=""
STATUS="done"
FORCE=0
JSON_OUT=0
IDS=()

usage() { sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --last)   LAST="$2"; shift 2 ;;
    --id)     IDS+=("$2"); shift 2 ;;
    --since)  SINCE="$2"; shift 2 ;;
    --status) STATUS="$2"; shift 2 ;;
    --to)     TO="${2%/}"; shift 2 ;;
    --store)  STORE_DIR="$2"; shift 2 ;;
    --force)  FORCE=1; shift ;;
    --json)   JSON_OUT=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

for dep in sqlite3 jq curl; do
  command -v "$dep" >/dev/null 2>&1 || { echo "ERROR: $dep is required" >&2; exit 1; }
done

case "$STATUS" in done|all) ;; *) echo "ERROR: --status must be done or all" >&2; exit 2 ;; esac

STORE_DIR="${STORE_DIR:-$(jfaa_bridge_store)}"
DB="$STORE_DIR/jobs.db"
[ -f "$DB" ] || { echo "ERROR: no bridge store at $DB (override with --store <dir>)" >&2; exit 1; }

curl -sf -m 5 "$TO/health" >/dev/null || {
  echo "ERROR: target bridge not reachable at $TO (is the test instance up? make up INSTANCE=test)" >&2
  exit 1
}

# ── Build the row selection ───────────────────────────────────────────────────
WHERE="jd_json IS NOT NULL"
[ "$STATUS" = "done" ] && WHERE="$WHERE AND status='done'"

if [ -n "$SINCE" ]; then
  if [[ "$SINCE" =~ ^[0-9]+$ ]]; then
    EPOCH="$SINCE"
  else
    # created_at is epoch SECONDS. Try BSD date (macOS) first, then GNU date.
    EPOCH="$(date -j -f %Y-%m-%d "$SINCE" +%s 2>/dev/null || date -d "$SINCE" +%s 2>/dev/null)" \
      || { echo "ERROR: cannot parse --since '$SINCE' (use YYYY-MM-DD or epoch seconds)" >&2; exit 2; }
  fi
  WHERE="$WHERE AND created_at >= $EPOCH"
fi

if [ ${#IDS[@]} -gt 0 ]; then
  QUOTED=""
  for id in "${IDS[@]}"; do
    [[ "$id" == *"'"* ]] && { echo "ERROR: invalid --id: $id" >&2; exit 2; }
    QUOTED="$QUOTED,'$id'"
  done
  WHERE="$WHERE AND id IN (${QUOTED#,})"
  LIMIT=""
else
  LIMIT="LIMIT ${LAST:-1}"
fi

SQL="SELECT id, type, status, jd_json FROM jobs WHERE $WHERE ORDER BY created_at DESC $LIMIT;"

# mode=ro: the source store is never written, even while its bridge is live.
ROWS="$(sqlite3 -json -cmd '.timeout 2000' "file:$DB?mode=ro" "$SQL")"
COUNT="$(printf '%s' "$ROWS" | jq 'length' 2>/dev/null || echo 0)"
if [ "${COUNT:-0}" -eq 0 ]; then
  echo "No matching rows in $DB (status=$STATUS${SINCE:+, since=$SINCE}${IDS:+, ids=${IDS[*]}})" >&2
  exit 1
fi

RUN_TS="$(date +%s)"
FAILED=0
RESP_FILE="$(mktemp)"
trap 'rm -f "$RESP_FILE"' EXIT

# Rows come newest-first from the query; replay oldest-first to preserve intake order.
# Process substitution (not a pipe) so FAILED survives the loop.
while IFS= read -r row; do
  source_id="$(printf '%s' "$row" | jq -r '.id')"
  type="$(printf '%s' "$row" | jq -r '.type')"
  payload="$(printf '%s' "$row" | jq -r '.jd_json')"

  case "$type" in
    JD_SCRAPED)  endpoint="/api/jobs" ;;
    EMAIL_RAW)   endpoint="/api/emails" ;;
    JD_PAGE_RAW) endpoint="/api/pages" ;;
    *) echo "⚠ skip $source_id: unknown type '$type'" >&2; continue ;;
  esac

  if [ "$FORCE" = "1" ]; then
    payload="$(printf '%s' "$payload" | jq -c --arg ts "$RUN_TS" --arg sid "$source_id" '
      .idempotency_key = ((.idempotency_key // ("replay-" + $sid)) + "-f" + $ts)
      | (if (.job_url // "") != "" then .job_url += ("#replay-" + $ts) else . end)
      | (if (.url // "") != "" then .url += ("#replay-" + $ts) else . end)')"
  fi

  http_status="$(curl -s -o "$RESP_FILE" -w '%{http_code}' \
    -X POST "$TO$endpoint" -H 'Content-Type: application/json' -d "$payload")"
  response="$(cat "$RESP_FILE" 2>/dev/null || echo '{}')"
  printf '%s' "$response" | jq -e . >/dev/null 2>&1 || response='{}'
  case "$http_status" in 2*) ;; *) FAILED=1 ;; esac

  if [ "$JSON_OUT" = "1" ]; then
    jq -cn --arg sid "$source_id" --arg type "$type" --arg ep "$endpoint" \
       --arg code "$http_status" --argjson resp "$response" \
       '{source_id:$sid, type:$type, endpoint:$ep, http_status:($code|tonumber), response:$resp}'
  else
    deduped="$(printf '%s' "$response" | jq -r '.deduped // false')"
    job_id="$(printf '%s' "$response" | jq -r '.job_id // empty')"
    case "$http_status" in
      2*) echo "→ $source_id ($type) → POST $endpoint → $http_status job_id=${job_id:-?} deduped=$deduped" ;;
      *)  echo "✗ $source_id ($type) → POST $endpoint → HTTP $http_status: $(printf '%s' "$response" | head -c 200)" >&2 ;;
    esac
  fi
done < <(printf '%s' "$ROWS" | jq -c 'reverse | .[]')

exit $FAILED
