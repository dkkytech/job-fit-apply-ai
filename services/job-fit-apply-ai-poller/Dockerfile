# ── Build stage: assemble the runnable dist with the project's Gradle wrapper ──
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon installDist

# ── Runtime stage: JRE + the installed dist (no browser — the Poller is browser-free) ──
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/install/job-fit-apply-ai-poller/ /app/

# Container defaults (compose overrides as needed). Gmail secrets mount at /secrets (RW so --reauth
# and a rotated refresh token persist); the heartbeat lives in /tmp for the --health check.
ENV GMAIL_CREDENTIALS_FILE=/secrets/dkkytech_credentials.json \
    GMAIL_TOKEN_FILE=/secrets/tokens/gmail_token.json \
    HEARTBEAT_FILE=/tmp/poller-heartbeat \
    JD_BRIDGE_URL=http://bridge:8765

# Healthcheck execs the same binary with --health (exit 0 = loops alive). No HTTP port, no curl.
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD ["/app/bin/job-fit-apply-ai-poller", "--health"]

ENTRYPOINT ["/app/bin/job-fit-apply-ai-poller"]
CMD ["--poll"]
