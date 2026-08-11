# run-analyzer

Analyzes the JD pipeline's **recently-completed jobs**, judges health + scoring quality with a
mix of **deterministic rules and a model**, surfaces **only what changed** (new / worsening /
resolved), and — gated/opt-in — turns high-severity findings into a **living draft PR** and then
**verifies whether the fix actually moved its target metric**.

There is **no batch to trigger**. The pipeline runs continuously — the `jd-poller` feeds
Gmail→bridge and the `jobfit-processor` container drains the bridge. A "run" is the set of jobs
that completed since the analyzer's **cursor**. The analyzer consumes the bridge completed-event
feed (`GET /api/jobs/completed`) with its own independent cursor.

## How a run works

1. **Cadence gate** — drain the cursor→head delta. If fewer than `MIN_BATCH` new jobs have accrued
   and the oldest has waited < `MAX_DEFER_HOURS`, **defer** (leave the cursor; re-accrue). This
   decouples window size from arrival rate so a slow day doesn't degrade into per-job analysis.
2. **Two windows** — the **report window** (the new jobs; findings anchor here) plus a read-only
   **context window** of the last `CONTEXT_N` completed jobs, for rates / per-board patterns /
   regressions. The bridge feed is the spine; `run_log.jsonl` (via `RunReport.kt`) enriches each
   record with `jdTextLen`, `durationMs`, `isDigest`, `board` (missing → `run_log_missing`).
3. **Deterministic detectors** — zero-token rules emit findings for the unambiguous classes
   (OOM, timeouts, a board's scrapes blocked, thin digests, run_log gaps, TAILOR-after-error,
   rich-JD-scored-0). These run before the model, so they survive a model outage.
4. **Model triage** — the LLM gets the metrics, both windows, the baseline, and the deterministic
   findings, and adds only **net-new** issues + root-cause/`agent_prompt` narration + regression
   judgement. Findings are merged deduped by fingerprint (`id + category`).
5. **Scoring audit** — a bounded pass verifies whether triaged scores are *justified by evidence*
   (deterministic grounding of `score_fit.txt` against the JD, then a model verdict), using Postgres
   `tracks` + per-job output files.
6. **Delta + notify** — classify each finding vs the findings ledger as NEW / WORSENING / UNCHANGED;
   Discord/Telegram is pinged **only for what changed** (steady state stays quiet).
7. **Outcome loop** — reconcile merged autofix PRs (`gh`), then check whether each fixed finding's
   target metric recovered over `RESOLVE_RUNS` runs before vs after the merge → **resolved**
   (auto-retire) or **regressed** (fix didn't help).

Empty/deferred windows exit before any model call, so frequent scheduling is cheap.

### Package layout

```
analyzer/
  cursor.py         persisted independent completed_seq cursor (mirror of Cursor.kt)
  pending.py        'pending since' marker for the accumulate-until-N-or-T gate
  bridge.py         HTTP client for GET /api/jobs/completed(+/head, +fetch_last)
  sources.py        assemble a window: bridge spine + run_log enrichment
  metrics.py        deterministic aggregate + rate metrics
  detectors.py      rule-based findings for the unambiguous problem classes (no LLM)
  history.py        append-only metrics history + rolling baseline
  llm.py            model routing (oMLX / ollama-cloud / ollama-local)
  findings.py       write analysis.json + per-finding task files + fingerprint (id+category)
  findings_ledger.py cross-run NEW/WORSENING/UNCHANGED/RESOLVED classification
  audit.py          deep per-job scoring-correctness audit
  outcomes.py       finding -> fix -> outcome: merge reconciliation + metric recovery
  autofix.py        gated auto-fix -> living draft PR loop
  notify.py         Discord/Telegram messaging
analyze.py          orchestrator (gate -> detectors -> model -> audit -> delta -> outcomes)
run_analyzer.sh     driver (cursor consumer; PATH/CA hardening; single-instance lock; no batch/pm2)
```

## Usage

```bash
# analysis + trend + notify (cheap; run hourly on the schedule)
./tuner/run-analyzer/run_analyzer.sh

# gated auto-fix -> living draft PR -> notify (run daily; opt-in)
RUN_ANALYZER_AUTOFIX=1 ./tuner/run-analyzer/run_analyzer.sh --autofix

# one-off with a stronger cloud model
RUN_ANALYZER_MODEL=minimax-m3:ollama-cloud ./tuner/run-analyzer/run_analyzer.sh
```

Empty windows exit immediately (before any model call), so frequent scheduling is cheap.

Config (env; `run_analyzer.sh` also reads these from
`services/job-fit-apply-ai-pipeline/.env`, not the repository-root Compose `.env`):

| var | default | purpose |
|---|---|---|
| `RUN_ANALYZER_MODEL` | `Qwen3.5-9B-OptiQ-4bit` (local oMLX) | analysis model (deployed: `deepseek-v4-pro:ollama-cloud`) |
| `JD_BRIDGE_URL` | `http://127.0.0.1:8765` | bridge base URL |
| `RUN_ANALYZER_MIN_BATCH` | `10` | analyze once ≥ N new jobs accrue |
| `RUN_ANALYZER_MAX_DEFER_HOURS` | `6` | force a run after T hours waiting |
| `RUN_ANALYZER_CONTEXT_N` | `40` | rolling context-window size |
| `RUN_ANALYZER_AUDIT_MAX` | `8` | max jobs deep-audited per run (0 disables) |
| `RUN_ANALYZER_RESOLVE_RUNS` | `3` | runs each side of a merge to judge outcome |
| `RUN_ANALYZER_AUTOFIX` | *(off)* | set `1` to arm the `--autofix` loop |
| `RUN_ANALYZER_AUTOFIX_SEVERITY` | `high` | min severity to auto-fix (`high` \| `medium` \| `low`) |
| `MLX_LOCAL_BASE_URL` / `MLX_API_KEY` | oMLX local | OpenAI-wire backend |
| `OLLAMA_CLOUD_BASE_URL` / `OLLAMA_API_KEY` | — | for `:ollama-cloud` models |
| `DISCORD_*` | — | Discord notifications (no-op when blank) |
| `RUN_ANALYZER_TELEGRAM_BOT_TOKEN` / `RUN_ANALYZER_TELEGRAM_CHAT_ID` | `TELEGRAM_*` | analyzer-specific Telegram destination; blank values fall back to the legacy generic credentials |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | — | backward-compatible Telegram fallback (no-op when blank) |

The metrics are computed deterministically in Python and are accurate regardless of model; a
**stronger model produces better root-cause + file-path accuracy** in the findings — local
models reliably triage but tend to guess at file paths. Set `RUN_ANALYZER_MODEL=<model>:ollama-cloud`
to route to Ollama Cloud (`Bearer $OLLAMA_API_KEY`).

## State (gitignored, under `state/`)

- `cursor` — last consumed `completed_seq`.
- `pending_since` — epoch of the oldest unanalyzed job (accumulate-until-N-or-T gate).
- `metrics_history.jsonl` — append-only per-run metrics (rolling baseline; Phase 2).
- `findings_ledger.jsonl` — per-fingerprint finding history for NEW/WORSENING/RESOLVED classification.
- `autofix_ledger.jsonl` — fingerprints handled by the autofix loop (`pr_open` → `pr_merged`); the
  analysis pass reads it to check whether a merged fix moved its target metric (auto-retiring resolved
  findings) and reconciles PR merges via `gh`.

Delete `state/cursor` to re-seed at head (skips history). Findings land in `findings/<run-ts>/`.

## Tests

Stdlib `unittest`, no third-party deps, hermetic (a fake bridge + stubbed model — no network,
LLM, or live services). Run from this directory:

```bash
python3 -m unittest discover -s tests -p 'test_*.py'
```

`tests/test_units.py` covers the pure logic (metrics, history/baseline, fingerprint, pending,
run_log join, audit grounding/triage); `tests/test_findings_ledger.py` the NEW/WORSENING/UNCHANGED
classifier; `tests/test_detectors.py` the deterministic detectors (per-board grouping, share-gating);
`tests/test_outcomes.py` the finding→fix→outcome loop (merge reconciliation, metric-recovery
resolved/regressed); `tests/test_integration.py` the cadence-gate decision table and the full
`analyze.py` flow (delta + ledger + history, cross-run dedup, deterministic-findings-survive-outage,
malformed-model degradation). Also run in CI
(`.github/workflows/ci.yml` → `run-analyzer`).

## External dependencies

Running `jd-bridge` (`:8765`), `jobfit-processor` + `jobfit-db` containers; the LLM endpoint
per `RUN_ANALYZER_MODEL`. The `--autofix` loop additionally needs host `git`, an authed `gh`
CLI, and the `claude` CLI.

## Scheduling

Two committed launchd plists (see `scripts/com.jd.run-analyzer*.plist`): the hourly analysis
job and the daily gated `--autofix` job. A shared single-instance lock
(`/tmp/jd-run-analyzer.lock`) guarantees they never overlap (the autofix git ops must not race
the analysis run). The findings task files can be reviewed directly, fed to a coding session
via each file's "Agent prompt" section, or applied by the `--autofix` loop.

## Operational checks (what a healthy run looks like)

Logs: `/tmp/jd-run-analyzer.{out,err}.log`. On a tick you'll see either `defer …` /
`no new completed jobs` (cheap, no model call) or a real `analysis mode` → `health=… batch=N
context=40 … findings=X new=Y worsening=Z resolved=… regressed=…` line at exit 0. To watch the
first **non-deferred** run, look for:

1. **It actually analyzed** — a `batch=N` line (N ≥ `MIN_BATCH`, or a `defer` that later flips
   after `MAX_DEFER_HOURS`), not perpetual `defer`.
2. **Model call succeeded** — `.err.log` has no `CERTIFICATE_VERIFY_FAILED`. If `health=unknown`
   with "model unavailable", the cloud call is failing (check the CA bundle / `SSL_CERT_FILE`);
   deterministic findings should still be written (exit 1).
3. **`run-log-missing` finding** — if it fires, the processor is genuinely skipping
   `run_log.jsonl` for a real share of terminal jobs (a live observability gap worth fixing;
   also blinds the audit's `jdTextLen`/`outputPath`). A few stragglers stay below the threshold.
4. **Audit coverage** — the `[audit] candidates=N audited=M` line; `audited=0` usually means the
   per-job output dirs / tracks weren't readable (often tied to run-log-missing).
5. **Notification** — with `DISCORD_*` or analyzer-specific/legacy Telegram credentials set, a first non-deferred run pings once
   (everything is NEW). No ping ⇒ blank creds or nothing new/worsening.
6. **Trend warms up** — `state/metrics_history.jsonl` grows one line per non-empty run; the
   rolling baseline (and regression judgement) only becomes meaningful after several runs.
7. **Autofix stays off** — no analyzer PRs appear until you arm it (uncomment the autofix plist's
   `EnvironmentVariables`); dry-run first: `RUN_ANALYZER_AUTOFIX=1 RUN_ANALYZER_AUTOFIX_DRYRUN=1
   run_analyzer.sh --autofix`.

Findings land in `findings/<run-ts>/` as task files (each with an `agent_prompt`). If a bridge/DB
schema ever changes, run the suite on the host — `tests/test_contract.py` will flag drift.
