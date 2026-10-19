#!/usr/bin/env bash
#
# gen_registry.sh — derive the env-llm-tuner Section A node registry mechanically.
#
# WHY: Section C.1 previously had the model read Config.kt, LlmClient.kt, and all 13
# node files (~155 KB / ~43k tokens) to reconstruct information that is a handful of
# greppable facts. This script extracts exactly those facts and emits compact JSON,
# so the model reads ~1.5 KB instead. See tuner/env-llm-tuner/README-TOKENS.md.
#
# The fingerprint lets a run SKIP the whole self-scan when nothing relevant changed.
#
# Usage:  ./gen_registry.sh [src_root]     (default: ../../src/main/kotlin/com/jd/pipeline)
# Output: JSON on stdout. Exit 0 always (never block the tuner on a grep miss).
#
set -uo pipefail

# Default src_root resolves relative to THIS SCRIPT, so it works from any cwd:
#   tuner/env-llm-tuner/gen_registry.sh -> ../../src/main/kotlin/com/jd/pipeline
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC="${1:-$SCRIPT_DIR/../../src/main/kotlin/com/jd/pipeline}"
CFG="$SRC/config/Config.kt"
CLIENT="$SRC/client/LlmClient.kt"

NODES=$(grep -rl "LlmClient" "$SRC/nodes/" 2>/dev/null || true)

echo "{"

# ---- 1. config vars: name -> default (literal, or an alias of another var) ----
# Values are ALWAYS emitted as JSON strings; a bare identifier default (e.g. SCRAPE_MODEL
# defaulting to SCAN_MODEL) is emitted as the identifier text, which keeps the JSON valid.
echo '  "config_vars": {'
grep -oE 'val [A-Z_]+_MODEL: String = get\("[A-Z_]+", ("[^"]*"|[A-Z_]+)\)' "$CFG" 2>/dev/null \
  | sed -E 's/val ([A-Z_]+): String = get\("[A-Z_]+", (.*)\)/\1\t\2/' \
  | sed -E 's/^([A-Z_]+)\t"(.*)"$/\1\t\2/' \
  | awk -F'\t' 'BEGIN{n=0} { if(n++) printf ",\n"; printf "    \"%s\": \"%s\"", $1, $2 }'
echo ''
echo '  },'

# ---- 2. factories: config var read + temperature + jsonMode ----
echo '  "factories": {'
if [ -f "$CLIENT" ]; then
  for fn in orchestrationClient reasoningClient skillsClient; do
    blk=$(sed -n "/fun $fn(/,/^        }/p" "$CLIENT" 2>/dev/null)
    var=$(printf '%s' "$blk" | grep -oE 'Config\.[A-Z_]+' | head -1)
    tmp=$(printf '%s' "$blk" | grep -oE 'temperature = [0-9.]+' | head -1 | grep -oE '[0-9.]+')
    think=$(printf '%s' "$blk" | grep -oE 'thinkingEnabled = [^,]*' | head -1)
    printf '    "%s": {"config": "%s", "temperature": %s, "thinking": "%s"},\n' \
      "$fn" "${var:-?}" "${tmp:-null}" "${think:-n/a}"
  done
fi
echo '    "_end": null'
echo '  },'

# ---- 3. node -> factory/config mapping (the actual registry) ----
echo '  "node_calls": {'
first=1
for f in $NODES; do
  n=$(basename "$f" .kt)
  call=$(grep -oE 'LlmClient\.(orchestrationClient|reasoningClient|skillsClient|fromModelString)|Config\.[A-Z_]+_MODEL' "$f" 2>/dev/null \
         | sort -u | paste -sd';' -)
  [ $first -eq 1 ] || printf ',\n'
  first=0
  printf '    "%s": "%s"' "$n" "${call:-none}"
done
echo ''
echo '  },'

# ---- 4. backend routing enum ----
echo -n '  "backends": "'
grep -oE 'enum class LlmBackend \{[^}]*\}' "$CLIENT" 2>/dev/null | sed -E 's/enum class LlmBackend \{//; s/\}//' | tr -s ' ' | tr -d '\n'
echo '",'

# ---- 5. fingerprint of the INPUTS (skip-the-scan signal) ----
printf '  "fingerprint": "'
if [ -n "$NODES" ]; then cat "$CFG" "$CLIENT" $NODES 2>/dev/null | shasum -a 256 | cut -c1-16 | tr -d '\n'; else printf 'no-inputs'; fi
echo '"'
echo "}"
