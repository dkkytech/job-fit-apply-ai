# Phase 2 — containerize the Poller (retire it from PM2)

> **Status: DEPLOYED (2026-07-04).** `jobfit-poller` runs as a Compose service (browser-free image,
> `--health` healthcheck, `/secrets` volume managed by the current `JFAA_DATA_ROOT` Compose setting), reaching the
> bridge at `http://bridge:8765`. `jd-poller` removed from PM2 (`pm2 save`d). Processor stays on PM2.
>
> **Current data-root note:** this historical phase record predates the portable data-root refactor.
> Use `docs/data-root-migration.md` and the current `docker-compose.yml` as the deployment source of truth.

**Goal:** move `jd-poller` off PM2 into a Docker Compose service (`poller`). The Processor stays on
the host under PM2 — it needs Chrome/CDP, oMLX, and Ollama, which are host daemons. This retires
*half* the remaining PM2 footprint with low risk, because the Poller's only dependencies are Gmail
(network) and the bridge (already containerized).

## Why this is low-risk

- **Bridge reachability is already solved.** The bridge binds `0.0.0.0` inside its container and
  Compose uses the default network, so the poller service reaches it at `http://bridge:8765` — no
  bridge changes, and the host publish stays loopback-only (`127.0.0.1:8765`).
- **No host deps, no browser.** Unlike the Processor, the Poller needs no Chrome/LLM. Playwright was
  removed entirely — `--reauth` is browser-free (print URL → paste redirect URL), so the image ships
  no browser binaries.
- **The Processor is untouched** — it keeps reaching the bridge at `127.0.0.1:8765` from the host.

## Design

### 1. Image
- `services/job-fit-apply-ai-poller/Dockerfile` (multi-stage: gradle `installDist` → JRE 21 runtime).
- `.dockerignore` (exclude `build/`, `.gradle/`, `.env`, `tokens/`).
- **No browser at all** — Playwright was removed from the module; the image is a lean JRE + jars.

### 2. Gmail secrets (the one stateful concern)
- The container needs `GMAIL_CREDENTIALS_FILE` + `GMAIL_TOKEN_FILE`, and the **token file is written
  on refresh**, so its volume must be **persistent and read-write**.
- Mount a host secrets dir → `/secrets` (RW). Point env at the mounted paths:
  - `GMAIL_CREDENTIALS_FILE=/secrets/dkkytech_credentials.json`
  - `GMAIL_TOKEN_FILE=/secrets/tokens/gmail_token.json`
- Seed `/secrets` from the current pipeline token/credentials (single source of truth continues).

### 3. Compose service
```yaml
poller:
  build: ./services/job-fit-apply-ai-poller
  container_name: jobfit-poller
  restart: unless-stopped
  depends_on: { bridge: { condition: service_healthy } }
  environment:
    JD_BRIDGE_URL: "http://bridge:8765"
    GMAIL_CREDENTIALS_FILE: "/secrets/dkkytech_credentials.json"
    GMAIL_TOKEN_FILE: "/secrets/tokens/gmail_token.json"
    GMAIL_MAX_EMAILS: "10"
  volumes:
    - "${JD_POLLER_SECRETS_HOST:-${JFAA_DATA_ROOT:-${HOME}/.local/share/jfaa}/poller-secrets}:/secrets"
  command: ["--poll"]
```

### 4. `--reauth` in a container (browser-free by design)
- `--poll` never needs it. For token refresh: `docker compose run --rm poller --reauth`.
- `GmailAuth.generateToken()` deletes the old token, prints the consent URL, and reads the pasted
  redirect URL from stdin (`captureCodeManually`) — no Playwright, no Chrome. `docker compose run`
  gives a TTY, so the paste works. New token lands in `/secrets`.
- Same flow works on the host (`poller --reauth`) writing to the shared secrets dir.

### 5. Healthcheck (nice-to-have)
- The Poller has no HTTP port. Add a **heartbeat file** the loops touch each pass; a Compose
  healthcheck asserts its freshness (`find /tmp/poller-heartbeat -mmin -3`). Small poller code change.

## Task breakdown

1. Poller `Dockerfile` + `.dockerignore`; build the image; `docker run` smoke test (`--check-token`
   against a mounted `/secrets`).
2. Seed the host secrets dir; add the `poller` Compose service.
4. (Optional) heartbeat file + Compose healthcheck.
5. Cutover: `docker compose up -d poller` → confirm intake + write-back from container logs against
   the live bridge → `pm2 delete jd-poller` → `pm2 save`.
6. `doctor.sh`: check `jobfit-poller` container health instead of `pm2 jd-poller`; keep the
   `jd-processor` PM2 check.
7. Docs: README process table + a short cutover/rollback runbook.

## Risks / open questions

- **Token-volume writability** — if the mount is read-only, the ~weekly refresh fails silently and
  intake stops. Mount RW; doctor should check token freshness.
- **Clock/refresh** — container time must be correct for OAuth refresh; Docker Desktop handles this.
- **Timezone of the Gmail search query** (`newer_than:7d`) is Gmail-side, unaffected by the container.

## Rollback

`docker compose stop poller && docker compose rm -f poller`, then
`pm2 start <poller bin> --name jd-poller --cwd <poller dir> --interpreter bash -- --poll && pm2 save`.
(The host poller dist and its `.env` remain in place.)
