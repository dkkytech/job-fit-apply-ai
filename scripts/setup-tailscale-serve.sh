#!/usr/bin/env bash
#
# Configure Tailscale Serve for the tailnet-exposed containers.
# See docs/tailscale-serve.md. Idempotent — safe to re-run.
#
# Each container binds 127.0.0.1:<port> (compose); the host's already-signed-in
# tailscaled proxies the tailnet to it. No Tailscale runs inside Docker.
#
set -euo pipefail

# Resolve the tailscale CLI (override with TAILSCALE_BIN).
TS="${TAILSCALE_BIN:-}"
if [ -z "$TS" ]; then
  if command -v tailscale >/dev/null 2>&1; then TS="tailscale"
  elif [ -x /usr/local/bin/tailscale ]; then TS="/usr/local/bin/tailscale"
  else echo "ERROR: tailscale CLI not found (set TAILSCALE_BIN)"; exit 1; fi
fi

# Instance-aware ports: read the compose env file (ENV_FILE, default .env) for the
# host ports this instance publishes, falling back to the prod defaults. Each instance's
# ports are distinct, so both instances' serve mappings coexist — `tailscale serve` binds
# each tailnet port once and we never reuse one across instances.
ENV_FILE="${ENV_FILE:-.env}"
env_port() { # env_port <VAR> <default> — read VAR from ENV_FILE (env var wins), strip quotes
  local key="$1" default="$2" value=""
  value="${!key:-}"
  if [ -z "$value" ] && [ -f "$ENV_FILE" ]; then
    value="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true)"
    value="${value%\"}"; value="${value#\"}"
  fi
  printf '%s' "${value:-$default}"
}
MARKSERV_PORT="$(env_port MARKSERV_PORT 8081)"
BRIDGE_PORT="$(env_port JD_BRIDGE_PORT 8765)"
FRONTEND_PORT="$(env_port FRONTEND_PORT 3030)"

# One line per tailnet-exposed service: "<tailnet-port>|<local-target>|<label>".
SERVICES="
$MARKSERV_PORT|http://127.0.0.1:$MARKSERV_PORT|markserv
$BRIDGE_PORT|http://127.0.0.1:$BRIDGE_PORT|bridge
$FRONTEND_PORT|http://127.0.0.1:$FRONTEND_PORT|frontend
"

# TCP connect check on the host loopback (bash /dev/tcp).
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&-; return 0; } || return 1; }

echo "$SERVICES" | while IFS='|' read -r port target label; do
  [ -z "${port:-}" ] && continue
  if port_open "$port"; then
    echo "→ serve $label: http://<tailnet-name>:$port  →  $target"
    "$TS" serve --bg --http="$port" "$target"
  else
    echo "⚠ skip $label: nothing listening on 127.0.0.1:$port (start its container first)"
  fi
done

echo
echo "Current Tailscale Serve config:"
"$TS" serve status
