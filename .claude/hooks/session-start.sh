#!/usr/bin/env bash
#
# SessionStart hook for Claude Code cloud sessions (claude.ai/code). Prepares the VM so
# `make e2e`, the Gradle test suites and the npm lint/test scripts work out of the box.
# A no-op everywhere else: it exits immediately unless CLAUDE_CODE_REMOTE=true, so a
# local Claude Code session on the Mac is untouched.
#
# What a cloud VM is missing, and what this does about it:
#   1. dockerd isn't running                      → start it and wait for the socket.
#   2. Container egress is TLS-intercepted by the  → rebuild the upstream base images the
#      sandbox egress gateway, whose CA the stock    repo's Dockerfiles use, with those CAs
#      images don't trust (Gradle/npm/curl in a      trusted, re-tagged under the SAME name,
#      `docker build` fail with PKIX errors).        so the Dockerfiles stay unchanged.
#   3. Docker Hub and Maven Central rate-limit     → fall back to mirror.gcr.io for image
#      (429) the shared egress IP.                   pulls; a Gradle init script routes Maven
#                                                    Central through Google's mirror, both on
#                                                    the VM and inside the patched JDK image.
#   4. Cold dependency caches                      → pre-fetch Gradle + npm dependencies.
#
# Idempotent: patched images carry a label with the CA fingerprint and are skipped when it
# matches; npm installs are skipped when node_modules is current. Every step is best-effort
# — a failure is logged as a warning and never blocks the session from starting.
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$ROOT" || exit 0

log()  { echo "[cloud-setup] $*"; }
warn() { echo "[cloud-setup] WARNING: $*" >&2; WARNINGS=$((WARNINGS + 1)); }
WARNINGS=0

# Base images the repo's Dockerfiles build FROM (and that make network calls during the
# build). nginx/postgres/alpine are only pulled — nothing in them fetches over TLS at build.
PATCH_JAVA_IMAGES=(eclipse-temurin:21-jdk-jammy eclipse-temurin:21-jre-jammy)
PATCH_NODE_IMAGES=(node:20 node:20-alpine)
PULL_ONLY_IMAGES=(postgres:16-alpine alpine:3)

# ── 1. Docker daemon ─────────────────────────────────────────────────────────
start_docker() {
  if docker info >/dev/null 2>&1; then return 0; fi
  if ! command -v dockerd >/dev/null 2>&1; then
    warn "dockerd not installed — skipping all Docker setup"; return 1
  fi
  log "starting dockerd"
  nohup dockerd >/var/log/dockerd.log 2>&1 &
  for _ in $(seq 1 30); do
    docker info >/dev/null 2>&1 && return 0
    sleep 1
  done
  warn "dockerd did not come up in 30s (see /var/log/dockerd.log)"; return 1
}

# ── 2. Egress CAs ────────────────────────────────────────────────────────────
# The session's CA bundle holds the public roots plus the sandbox's own interception CAs.
# Only the latter (O = Anthropic) are added to the images.
CA_BUNDLE="${SSL_CERT_FILE:-/root/.ccr/ca-bundle.crt}"
CA_DIR="$(mktemp -d)"
trap 'rm -rf "$CA_DIR"' EXIT

extract_cas() {
  [ -s "$CA_BUNDLE" ] || { warn "no CA bundle at $CA_BUNDLE — images left unpatched"; return 1; }
  mkdir -p "$CA_DIR/certs"
  awk -v dir="$CA_DIR" '/BEGIN CERTIFICATE/{n++} n{print > sprintf("%s/raw-%03d.pem", dir, n)}' "$CA_BUNDLE"
  local f subject i=0
  for f in "$CA_DIR"/raw-*.pem; do
    subject="$(openssl x509 -in "$f" -noout -subject 2>/dev/null)" || continue
    case "$subject" in
      *Anthropic*) i=$((i + 1)); cp "$f" "$CA_DIR/certs/sandbox-egress-$i.crt" ;;
    esac
  done
  rm -f "$CA_DIR"/raw-*.pem
  [ "$i" -gt 0 ] || { warn "no sandbox CAs found in $CA_BUNDLE — images left unpatched"; return 1; }
  cat "$CA_DIR"/certs/*.crt > "$CA_DIR/sandbox-egress-cas.pem"
  # Covers the Gradle init script too, so editing it re-patches the JDK images.
  write_gradle_init "$CA_DIR/$GRADLE_INIT_NAME"
  CA_FINGERPRINT="$(cat "$CA_DIR/sandbox-egress-cas.pem" "$CA_DIR/$GRADLE_INIT_NAME" | sha256sum | cut -c1-16)"
  log "found $i sandbox CA(s), fingerprint $CA_FINGERPRINT"
}

# ── 3. Maven Central mirror ──────────────────────────────────────────────────
# Gradle init script (applies to every build under this Gradle user home). The URL check
# leaves google() and any other repository alone; the plugin portal is kept after the
# mirror because it redirects plugin POMs to repo.maven.apache.org.
GRADLE_INIT_NAME="cloud-maven-central-mirror.gradle"
write_gradle_init() {
  cat > "$1" <<'EOF'
// Written by .claude/hooks/session-start.sh — Claude Code cloud sessions only.
// Maven Central 429s the sandbox's shared egress IP; Google's mirror serves the same content.
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def useMirror = { RepositoryHandler repos ->
    repos.withType(MavenArtifactRepository).configureEach { repo ->
        if (repo.url.toString().startsWith('https://repo.maven.apache.org/maven2')) repo.url = mirror
    }
}
beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { url mirror }
        gradlePluginPortal()
    }
}
allprojects { project ->
    useMirror(project.buildscript.repositories)
    useMirror(project.repositories)
}
EOF
}

cat_dockerfile_java() {
  cat <<EOF
ARG BASE
FROM \${BASE}
ARG CA_FINGERPRINT
LABEL jfaa.cloud-ca-fingerprint=\${CA_FINGERPRINT}
COPY $GRADLE_INIT_NAME /root/.gradle/init.d/$GRADLE_INIT_NAME
EOF
  cat <<'EOF'
COPY certs/ /usr/local/share/ca-certificates/
RUN update-ca-certificates \
 && for f in /usr/local/share/ca-certificates/sandbox-egress-*.crt; do \
      keytool -importcert -noprompt -alias "$(basename "$f" .crt)" -file "$f" -cacerts -storepass changeit; \
    done
EOF
}

cat_dockerfile_node() {
  cat <<'EOF'
ARG BASE
FROM ${BASE}
ARG CA_FINGERPRINT
LABEL jfaa.cloud-ca-fingerprint=${CA_FINGERPRINT}
COPY sandbox-egress-cas.pem /usr/local/share/ca-certificates/sandbox-egress-cas.crt
ENV NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/sandbox-egress-cas.crt
RUN cat /usr/local/share/ca-certificates/sandbox-egress-cas.crt >> /etc/ssl/certs/ca-certificates.crt
EOF
}

# Pull from Docker Hub, falling back to Google's Docker Hub mirror on a 429.
pull_image() {
  local img="$1" attempt
  for attempt in 1 2; do
    docker pull -q "$img" >/dev/null 2>&1 && return 0
    if docker pull -q "mirror.gcr.io/library/$img" >/dev/null 2>&1; then
      docker tag "mirror.gcr.io/library/$img" "$img" && return 0
    fi
    sleep $((attempt * 15))
  done
  return 1
}

patch_image() {
  local img="$1" kind="$2" current
  current="$(docker image inspect -f '{{ index .Config.Labels "jfaa.cloud-ca-fingerprint" }}' "$img" 2>/dev/null || true)"
  if [ "$current" = "$CA_FINGERPRINT" ]; then
    log "$img already trusts the sandbox CAs"; return 0
  fi
  # Always patch a fresh upstream copy, never stack a patch on an older patch.
  if ! pull_image "$img"; then
    warn "could not pull $img (Docker Hub and mirror.gcr.io both failed)"; return 1
  fi
  "cat_dockerfile_$kind" > "$CA_DIR/Dockerfile.$kind"
  if docker build -q -f "$CA_DIR/Dockerfile.$kind" \
       --build-arg BASE="$img" --build-arg CA_FINGERPRINT="$CA_FINGERPRINT" \
       -t "$img" "$CA_DIR" >/dev/null; then
    log "patched $img"
  else
    warn "failed to patch $img"; return 1
  fi
}

# ── 4. Dependency pre-fetch ──────────────────────────────────────────────────
# Compiling the test classes resolves every compile/test dependency. The mirror makes 429s
# rare; the retry covers the ones that remain (the plugin portal still hits Central).
gradle_prefetch() {
  local dir="$1" attempt
  [ -x "$dir/gradlew" ] || return 0
  for attempt in 1 2 3; do
    if (cd "$dir" && ./gradlew -q testClasses >/tmp/cloud-setup-gradle.log 2>&1); then
      log "gradle deps ready: $dir"; return 0
    fi
    grep -q '429' /tmp/cloud-setup-gradle.log || break
    log "Maven Central rate-limited $dir (attempt $attempt), retrying"
    sleep $((attempt * 20))
  done
  warn "gradle pre-fetch failed for $dir — see /tmp/cloud-setup-gradle.log"
  return 1
}

npm_prefetch() {
  local dir="$1"
  [ -f "$dir/package-lock.json" ] || return 0
  if [ -f "$dir/node_modules/.package-lock.json" ] && \
     [ "$dir/node_modules/.package-lock.json" -nt "$dir/package-lock.json" ]; then
    log "npm deps current: $dir"; return 0
  fi
  if (cd "$dir" && npm ci --no-audit --no-fund --no-update-notifier --loglevel=error >/dev/null); then
    log "npm deps ready: $dir"
  else
    warn "npm ci failed in $dir"; return 1
  fi
}

# ── Run ──────────────────────────────────────────────────────────────────────
mkdir -p "$HOME/.gradle/init.d" && write_gradle_init "$HOME/.gradle/init.d/$GRADLE_INIT_NAME" \
  && log "Gradle routes Maven Central through Google's mirror"

if start_docker; then
  if extract_cas; then
    for img in "${PATCH_JAVA_IMAGES[@]}"; do patch_image "$img" java; done
    for img in "${PATCH_NODE_IMAGES[@]}"; do patch_image "$img" node; done
  fi
  for img in "${PULL_ONLY_IMAGES[@]}"; do
    docker image inspect "$img" >/dev/null 2>&1 || pull_image "$img" || warn "could not pull $img"
  done
fi

for dir in services/job-fit-apply-ai-e2e services/job-fit-apply-ai-pipeline services/job-fit-apply-ai-bridge; do
  gradle_prefetch "$dir"
done

for dir in apps/job-fit-apply-ai-extension apps/job-fit-apply-ai-backlog; do
  npm_prefetch "$dir"
done

if [ "$WARNINGS" -eq 0 ]; then
  log "done — run \`make e2e\` for the full stack test"
else
  log "done with $WARNINGS warning(s) — see above"
fi
exit 0
