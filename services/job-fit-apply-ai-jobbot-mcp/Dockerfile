# ── Build stage: assemble the runnable dist ──
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon installDist

# ── Runtime stage: JRE + dist ──
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# curl only for the container healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/install/job-fit-apply-ai-jobbot-mcp/ /app/
# Playwright attaches to jobbot-browser over CDP; it never needs its own browsers.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    JOBBOT_STATE_DIR=/state \
    JOBBOT_OUTPUT_ROOT=/jfaa/pipeline-output \
    JOBBOT_RESUME_YAML=/jfaa/profile/resume.yaml \
    JOBBOT_CANDIDATE_PROFILE_YAML=/jfaa/profile/candidate_profile.yaml
EXPOSE 8790
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["curl", "-fsS", "http://localhost:8790/health"]
ENTRYPOINT ["/app/bin/job-fit-apply-ai-jobbot-mcp"]
