# Containerizing the Processor — challenges & analysis (SUPERSEDED)

> **Status: taken up and superseded (2026-07-09)** — see
> [phase5-processor-containerization.md](phase5-processor-containerization.md). Two premises
> changed since the 2026-07-05 deferral: (1) Docker Desktop 29.x's host proxy DOES reach
> `127.0.0.1`-bound host ports via `host.docker.internal` (verified empirically), so no
> socat/rebind is needed; (2) PDF rendering moved to YAML→LaTeX (tectonic + committed Roboto),
> eliminating Chromium/fonts from the image entirely. Scraping is CDP-only (host Chrome).
> The analysis below is kept for the record.

## TL;DR

Containerizing the Processor is **tidiness, not capability** — after all the work below it would
*still* be pinned to this machine (host GPU LLMs + a logged-in Chrome), gaining no portability or
scaling. The heavy lifting is **host-service reachability (×3)** and **Chromium + fonts** (with a
real risk to PDF quality). Only worth it if "retire the last PM2 process" is a goal for its own sake.

## What the Processor does (per job)

- **Ingestion (EMAIL_RAW only):** `ScanEmail` (LLM) → `ScrapeJd` (Playwright + logged-in CDP Chrome).
- **Processing (every job):** `CheckDuplicate` (Postgres) → `ScoreFit` (LLM) → `ResumeTailoring`
  (6 LLM nodes) → `GenerateCoverLetter` (LLM) → `RenderResumePdf` (Playwright headless Chromium) →
  `AddArtifactUrl` → `DraftReplyComposer` (LLM) → `writeMetadata` (fs) → `SupabaseTrack` (Postgres).

It's a single synchronous, stateful computation (one `JDState` flowing through). Unlike the
poller/jsearch/notifier (async edge concerns with independent lifecycles), these steps share one
lifecycle — the tell that they belong together.

## Challenges (ranked by how much they bite)

### 1. Host-service reachability — three services, all loopback-bound (dominant)
The Processor calls three **host** services, each bound to `127.0.0.1` (verified):
- oMLX (local MLX inference) — `127.0.0.1:11436`
- Ollama-local — `127.0.0.1:11434`
- Chrome CDP — `127.0.0.1:9222`

Docker Desktop on Mac has **no `network_mode: host`** (it's a Linux VM). A container reaches the host
only via `host.docker.internal` (the gateway IP), which **cannot reach a `127.0.0.1`-bound port.**
So each of the three needs either:
- a **rebind to `0.0.0.0`** (widens exposure — and for CDP that's *unauthenticated full browser
  control*), or
- a **`socat`/proxy shim** on the host bridging the Docker interface → `127.0.0.1:<port>`.

Three separate reachability hacks. **Silver lining:** moving models to **Ollama Cloud**
(`https://ollama.com`, a real network endpoint) works fine from a container — to the extent LLM
usage goes all-cloud, this shrinks toward "just Chrome." Any oMLX/Ollama-local node re-adds it.

### 2. Chromium + system libs + FONTS in the image
Two Chromium uses — the **scrape driver** (`ScrapeJdNode`) *and* **PDF rendering**
(`RenderResumePdfNode` renders `tailored_resume.html` → PDF via headless Chromium). Bundling
Chromium into a Linux image is ~1 GB + ~30 apt shared libs. The sneaky part is **fonts**: on the
Mac, Chromium uses system fonts; a bare Linux container has almost none, so PDFs render with
**substituted/garbled fonts** unless you install and match the exact font set the resume template
expects. This directly degrades the product output (the resume PDF) and is easy to miss.

### 3. Shared output volume + slow Mac bind-mount IO
The Processor writes `report.md` + the resume PDF to `OUTPUT_DIR` (at the time of writing
`services/job-fit-apply-ai-pipeline/output`; since #67 the host source is
`${JFAA_DATA_ROOT}/pipeline-output`), which **markserv mounts and serves**. The container
must write the same volume. Docker Desktop bind-mount IO on Mac (gRPC-FUSE) is **slow**, and the
Processor is fs-heavy (PDF writes + template/profile/skills reads every job) → throughput hit.

### 4. The logged-in Chrome
`ChromeCdpBrowser` **attaches** to a *user-launched* Chrome (`connectOverCDP`, detaches on close —
never launches or kills it). Chrome is started by `scripts/launch-chrome-cdp.sh` with
`--remote-debugging-port=9222` + a **dedicated logged-in profile** (Chrome refuses CDP on the Default
profile). So Chrome's lifecycle is *already* separate from the Processor — containerizing doesn't
create a "manage Chrome" need; it creates the reachability problem in #1. The login is interactive
and expires (LinkedIn/Glassdoor) — same shape as the Gmail token reauth, so "always available" is
only partly automatable regardless of containers.

### 5. macOS → Linux parity
Scraping + PDF would run under Linux Chromium, not Mac Chrome. Anti-bot fingerprinting, font
metrics, and locale differ — re-verify scrape success rates and PDF fidelity after a move; don't
assume parity.

### 6. Config/input plumbing
`candidate_profile.json`, `skills/*.md`, the resume base template, fonts, and `.env` (DB creds +
per-node model config + cloud keys) all need to be baked into the image or mounted. Manageable
(like the other services), just more mount surface.

## What is NOT a problem (for balance)
- **Bridge + Postgres:** already reachable over the Compose network by service name (`bridge:8765`,
  `db:5432`) — the Poller/JSearch/Notifier already do this.
- **Cloud LLMs** (Ollama Cloud / Deepseek / Minimax): plain network calls, no host dep.
- **The LLM concurrency gate:** in-process, unaffected.

## If taken up later — recommended sequence
1. **Go all-cloud for LLMs first** (move the remaining oMLX/Ollama-local nodes to Ollama
   Cloud/Deepseek/Minimax). This removes two of the three host-service reachability hacks and is
   independently reversible. Watch quality — dense local models were chosen for resume content.
2. **Solve Chrome reachability**: a host `socat` shim (Docker bridge → `127.0.0.1:9222`) rather than
   exposing CDP on `0.0.0.0`. Or reconsider whether scraping should stay a host concern.
3. **Build the image**: Linux Chromium + the resume template's **font set** + Playwright deps. Verify
   PDF fidelity byte-for-byte against a host-rendered reference.
4. **Shared output volume** with markserv; expect slower bind-mount IO on Mac.
5. **Compose service** + `--health`/doctor; cutover mirrors the Poller (backup dist, verify, retire
   PM2). Rollback: keep the host dist.
6. **Re-verify** scrape success + PDF quality live before deleting the PM2 process.

## Key facts (verified)
- Bind interfaces: oMLX/Ollama-local/Chrome all `127.0.0.1` (not reachable from a container without a
  rebind/proxy).
- `ChromeCdpBrowser` = attach-only; launch is `scripts/launch-chrome-cdp.sh`.
- `OUTPUT_DIR` is shared with markserv (which mounts it read-only for serving).
- Config endpoints: `MLX_LOCAL_BASE_URL`, `OLLAMA_LOCAL_BASE_URL` (127.0.0.1), `OLLAMA_CLOUD_BASE_URL`
  (`https://ollama.com`), `CHROME_CDP_ENDPOINT`.
