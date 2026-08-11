#!/usr/bin/env bash
#
# Compare prod vs test pipeline results for the same job inputs (A/B on replayed jobs).
# Read-only: SELECTs against both Postgres instances, prints rows side-by-side.
#
#   scripts/compare-ab.sh --last 5          # newest 5 prod tracks + their test rows
#   scripts/compare-ab.sh --url <job_url>   # one job by URL (repeatable)
#
# Ports/credentials come from the instance env files (.env, .env.test) with the
# usual defaults. Deps: psql.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LAST=5
URLS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --last) LAST="$2"; shift 2 ;;
    --url)  URLS+=("$2"); shift 2 ;;
    -h|--help) sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

env_get() { # env_get <file> <KEY> <default>
  local value=""
  [ -f "$1" ] && value="$(grep -E "^[[:space:]]*$2=" "$1" | tail -1 | cut -d= -f2- | sed "s/^[\"']//; s/[\"']$//")"
  printf '%s' "${value:-$3}"
}

connect() { # connect <envfile> <default-port>
  local port user pass db
  port="$(env_get "$1" POSTGRES_PORT "$2")"
  user="$(env_get "$1" POSTGRES_USER jobfit)"
  pass="$(env_get "$1" POSTGRES_PASSWORD jobfit)"
  db="$(env_get "$1" POSTGRES_DB jobfit)"
  printf 'postgresql://%s:%s@127.0.0.1:%s/%s' "$user" "$pass" "$port" "$db"
}

PROD="$(connect .env 5432)"
TEST="$(connect .env.test 25432)"

if [ ${#URLS[@]} -gt 0 ]; then
  COND=""
  for u in "${URLS[@]}"; do COND="$COND,'${u//\'/''}'"; done
  FILTER="job_url IN (${COND#,})"
else
  FILTER="job_url IN (SELECT job_url FROM tracks WHERE job_url <> '' ORDER BY id DESC LIMIT $LAST)"
fi

QUERY="SELECT company, role_title, fit_score, pipeline_action, coalesce(artifact_url,'') AS report, job_url
       FROM tracks WHERE $FILTER ORDER BY id DESC;"

echo "── PROD (${PROD##*@}) ──────────────────────────────────────"
psql "$PROD" -P pager=off -c "$QUERY" || echo "(prod query failed — is the prod stack up?)"
echo
echo "── TEST (${TEST##*@}) ──────────────────────────────────────"
psql "$TEST" -P pager=off -c "$QUERY" || echo "(test query failed — is the test stack up? make up INSTANCE=test)"
