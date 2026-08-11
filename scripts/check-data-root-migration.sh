#!/usr/bin/env bash
#
# Refuse to bring the stack up on an unmigrated Pipeline data root (#67).
#
# Moving a Compose bind source does not move the data. Without this guard, `docker compose up`
# after the #67 upgrade silently creates EMPTY pipeline-output/pipeline-state directories:
# Markserv then 404s every previously published artifact URL and Steel loses its authenticated
# session state — with no error anywhere. That failure is silent and looks like data loss, so it
# is worth blocking the deploy for.
#
# Exits 0 when there is nothing to migrate (fresh install, or already migrated).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/scripts/jfaa-data-root.sh"

blocked=0
report() { # report <label> <legacy> <current>
    local label="$1" legacy="$2" current="$3"
    if jfaa_unmigrated "$legacy" "$current"; then
        printf "\033[31m✗ %s not migrated\033[0m\n" "$label"
        printf "    legacy (has data): %s\n" "$legacy"
        printf "    current (empty):   %s\n" "$current"
        blocked=1
    fi
}

report "Pipeline output" "$(jfaa_legacy_pipeline_output)" "$(jfaa_pipeline_output)"
report "Pipeline state"  "$(jfaa_legacy_pipeline_state)"  "$(jfaa_pipeline_state)"

if [ "$blocked" -eq 1 ]; then
    cat >&2 <<'EOF'

Starting now would bind empty directories and drop every existing artifact URL and browser
session. Migrate first — see docs/data-root-migration.md ("Safe migration"), which walks the
guarded exact-mirror procedure.

Override only if you intend to start fresh:  JFAA_SKIP_DATA_ROOT_CHECK=1
EOF
    [ "${JFAA_SKIP_DATA_ROOT_CHECK:-}" = "1" ] || exit 1
    printf "\033[33m! JFAA_SKIP_DATA_ROOT_CHECK=1 — continuing on an unmigrated root\033[0m\n" >&2
fi
