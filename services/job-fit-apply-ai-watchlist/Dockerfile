# ── Build stage: assemble the runnable dist ──
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon installDist

# ── Runtime stage: JRE + dist + shell-loop entrypoint (no browser, no LLM) ──
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/install/job-fit-apply-ai-watchlist/ /app/
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

ENV JD_BRIDGE_URL=http://bridge:8765 \
    WATCHLIST_STATE_FILE=/state/watchlist.json \
    WATCHLIST_HEALTH_FILE=/state/watchlist-health.json \
    HEARTBEAT_FILE=/tmp/watchlist-heartbeat \
    WATCHLIST_CHECK_INTERVAL_S=3600

# Healthcheck: the shell loop touches the heartbeat each tick (hourly). Healthy if it's <2h old —
# a cheap file-mtime check, NOT a JVM exec.
HEALTHCHECK --interval=60s --timeout=5s --start-period=30s --retries=3 \
    CMD test -n "$(find ${HEARTBEAT_FILE} -mmin -120 2>/dev/null)"

ENTRYPOINT ["/app/docker-entrypoint.sh"]
