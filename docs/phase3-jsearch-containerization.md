# Phase 3 plan — containerize JSearch ingestion

> **Decision (locked):** dedicated module `services/job-fit-apply-ai-jsearch` → `jobfit-jsearch`
> container. **Once/day, 4 calls/run** → ~120 of a **150-calls-per-MONTH** RapidAPI quota. **One-shot
> JVM** (`--once`) wrapped in a shell loop — JVM lives ~5s/run and exits, so steady-state memory is a
> sleeping shell (~3 MB). **Restart-safe** via a persisted last-run timestamp (redeploys don't burn
> calls).
>
> **Status: BUILT (2026-07-05), start pending the API key.** Module + tests (16, incl. real-bridge
> contract) green; image built; compose `jsearch` service + `/state` volume + doctor check added;
> `--jsearch` removed from the pipeline (old cron is now an inert no-op). **To go live:** put
> `JSEARCH_API_KEY` in the repo-root `.env`, then `docker compose up -d jsearch` (first run = 4 calls).

**Goal:** move the daily JSearch job off the host cron (`~/.local/scripts/run_jsearch.sh`, not in
the repo) into a version-controlled Compose service. JSearch is an **intake source** — it fetches
job listings from the JSearch RapidAPI and submits them to the bridge as `JD_SCRAPED` work items;
the Processor drains them. **No Chrome, no LLM, no Playwright** — same lightweight profile as the
Poller, so it containerizes cleanly.

## What it does today (and what simplifies)

- `JSearchClient` — a JDK-HttpClient call to `jsearch.p.rapidapi.com` (needs `JSEARCH_API_KEY` /
  `X-RapidAPI-Key`). `JSearchConfig.DEFAULT_LIST` holds the hardcoded SDET/QA queries (Seattle +
  remote). `JSearchCommandHandler` builds `JdRecord`s (`source=JSEARCH`, `idempotencyKey=jobId`,
  skips `jdText < 150`) and POSTs each to the bridge.
- The current cron script wraps this in a **heartbeat/idle-gate** (wait up to 9h for the machine to
  be idle, Discord-alert on failure) + a 120-min timeout. **All of that is obsolete now:** `--jsearch`
  only ingests; the always-on Processor handles pacing via the per-resource LLM gate. The container
  drops the idle gate, the wait loop, and the Discord alerting entirely.
- **The bridge dedups by `idempotencyKey` (the JSearch `jobId`)**, so re-fetching the same listing
  never creates a duplicate job. This makes scheduling forgiving — overlap/re-runs are harmless.

## Key decision: dedicated module vs fold into the Poller

Both are "non-LLM intake → bridge." Two viable shapes:

| | **Dedicated module** `job-fit-apply-ai-jsearch` | **Fold into the Poller** |
|---|---|---|
| Container | new `jobfit-jsearch` (~another 220 MB JVM) | reuse `jobfit-poller` (a 3rd loop) |
| Separation | clean, self-documenting, isolated failure | Poller becomes "the ingestion service" (email + JSearch) |
| Effort | more boilerplate (own Dockerfile/config/tests) | less — reuse the Poller's bridge client, loop, health, image |
| Coupling | none | JSearch shares the Gmail container's lifecycle (no real dep though) |

**Recommendation: dedicated module.** You're explicitly "adding it to the repo properly," and a
self-contained service is the cleaner long-term artifact — it mirrors the established per-service
module pattern (pipeline / bridge / poller) and keeps the Poller's name honest (Gmail). The cost is
one small extra container. (Fold-into-Poller is the lower-effort alternative if you'd rather not run
a second JVM.)

## Design (dedicated-module shape)

### Module `services/job-fit-apply-ai-jsearch` (`com.jd.jsearch`)
- `JSearchClient` — moved/duplicated from the pipeline (pure HTTP + Jackson).
- `JSearchConfig` + `JobListing` DTO — duplicated (per the project's per-service DTO convention).
- `bridge/JsearchBridgeClient` — `submitJd(JdRecord): SubmitResult` → `POST /api/jobs`. Duplicate
  the minimal DTOs (`JdRecord` wire shape + `SubmitJobResponse`).
- `IngestRunner` — fetch listings → build records → submit; returns a summary (fetched/queued/
  deduped/skipped). This is the current `JSearchCommandHandler.run()` logic, bridge-only.
- `cli/Main` — `--once` (single ingest, for cron/`docker compose run`), `--loop` (long-running,
  default), `--health`.
- `JsearchConfig` — `JD_BRIDGE_URL`, `JSEARCH_API_KEY`, `JSEARCH_INTERVAL_MS` (default 24h),
  `HEARTBEAT_FILE`, `HEALTH_MAX_AGE_MS`.

### Scheduling — one-shot JVM in a shell loop, gated by persisted state (memory-frugal + quota-safe)
The JVM runs `--once` and **exits**; a shell loop is the container entrypoint. The JVM itself gates
on persisted state so restarts don't burn API calls:
```sh
while true; do
  /app/bin/jsearch --once   # JVM: read state → maybe fetch+submit → update state → EXIT (~5s)
  sleep "${JSEARCH_CHECK_INTERVAL_S:-3600}"   # coarse tick; the JVM decides if it's actually time
done
```
`--once` logic (state in a small mounted volume, e.g. `/state/jsearch.json`):
1. If `now - last_run < JSEARCH_INTERVAL_S` (default 24h) → **skip** (no API calls), exit. *(restart-safe:
   a redeploy/reboot re-reads `last_run` and won't re-fetch if it already ran today.)*
2. Else fetch (4 calls) + submit to the bridge; set `last_run=now`, `touch $HEARTBEAT_FILE`; log the
   per-run call count.

Steady-state memory ≈ a sleeping shell (~3 MB); the JVM only materializes ~once/day (plus brief
cold-start no-op checks). No host cron, no scheduler container. Bridge dedup (`jobId`) makes any
overlap harmless.

**Quota:** **150 calls/MONTH**; `DEFAULT_LIST` = **4 calls/run**, once/day ≈ **120/month** (~20%
margin). The persisted last-run keeps restarts from adding calls. Levers for more cushion:
every-other-day (60/mo) or trim `DEFAULT_LIST` to one config (2 calls/run → 60/mo). Note: manual
`--once` test runs each cost 4 real calls.

**Healthcheck:** a cheap `CMD-SHELL` mtime check on `$HEARTBEAT_FILE` (window >24h, e.g. 26h) — NOT a
`--health` JVM exec.

**State volume:** small RW bind mount at `/state`, with its host source now derived from
`JFAA_DATA_ROOT` (or `JD_JSEARCH_STATE_HOST` for a service-specific compatibility override). See
`docs/data-root-migration.md` for the current deployment procedure.

### Image + Compose
- `Dockerfile` — lean JRE (no browser), `HEALTHCHECK` execs `--health` (heartbeat window >24h, e.g.
  26h — coarse liveness for a daily job).
- `docker-compose.yml` `jsearch` service: `depends_on: bridge (healthy)`, `JD_BRIDGE_URL=http://bridge:8765`,
  `JSEARCH_API_KEY` from env, `restart: unless-stopped`. No ports, no volumes.
- **Secret:** `JSEARCH_API_KEY` moves from `~/.nanobot/.env` into the repo's env handling (a
  gitignored `.env` / compose env var — never committed).

## Task breakdown

1. Scaffold `services/job-fit-apply-ai-jsearch` (gradle wrapper, build.gradle, settings include).
2. Move/duplicate `JSearchClient` + `JSearchConfig` + `JobListing` + the JdRecord/bridge DTOs;
   `JsearchBridgeClient.submitJd`; `IngestRunner`.
3. `cli/Main` (`--once`/`--loop`/`--health`) + heartbeat + config.
4. **Tests** — unit (JobListing parsing from a JSearch response fixture; `IngestRunner` submitting to
   an in-process fake bridge; short-jd skip; dedup handling); integration (real-bridge contract:
   `submitJd` → job appears as `JD_SCRAPED`, reuse the disposable-bridge harness); health/heartbeat.
5. Dockerfile + `.dockerignore`; build + smoke test (`--once` against a disposable bridge; `--health`).
6. Compose `jsearch` service + `JSEARCH_API_KEY` wiring; `doctor.sh` check for `jobfit-jsearch`.
7. Cutover: `docker compose up -d jsearch`, verify a real ingest submits `JD_SCRAPED` jobs, then
   disable the `run_jsearch` host cron (`crontab` edit) + remove the external script.
8. Remove the now-dead `--jsearch` command + `JSearchClient`/config/handler from the **pipeline**
   module (it moved out), keeping the pipeline focused on processing. Update docs.

## Risks / open questions

- **API quota (150/month — the hard constraint)** — kept to ~120/month by daily cadence + the
  persisted last-run (restart-safe). No hard ceiling, so watch out: manual `--once` runs each cost 4
  calls. Tests cover the restart gate (skip-when-recent, fire-when-stale).
- **Query config** — hardcoded in `JSearchConfig.DEFAULT_LIST`. Fine for v1; externalizing to a
  mounted config/env is a future nicety.
- **Secret handling** — `JSEARCH_API_KEY` must reach the container without being committed.
- **Step 8 (removing `--jsearch` from the pipeline)** is optional; could keep it as a manual fallback.
