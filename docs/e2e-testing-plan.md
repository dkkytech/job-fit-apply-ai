# E2E Testing Plan — v1

Status: implemented and expanded — one structural happy path plus four deterministic
existing-product-path scenarios; Tier B catches silently-degraded runs that Tier A misses.
Scope: Bridge intake → Processor → Notifier, verified via Bridge, Postgres/backlog, and markserv.

---

## 1. Goals

- A reusable scenario harness that submits through Bridge intake routes and captures terminal status,
  scenario-local completed-feed evidence, artifacts, tracking rows, notifications, and LLM calls.
- Runs in GitHub CI on every PR.
- Runs ad hoc against a local stack with one command.
- Deterministic enough to assert *values*, not just "a file exists".
- Reusable later as a synthetic monitor (point it at the real stack) and as a load harness (run N submissions concurrently).

## 2. Non-goals for v1

- The Poller / Gmail write-back path. Direct recruiter intake through `POST /api/emails` and
  `DraftReplyComposer` are covered, but no real Gmail message is read or mutated.
- Steel / Chrome CDP network scraping. The captured-page scenario supplies raw page text and
  exercises `ScrapeJdNode` extraction without starting Steel or fetching an external board.
- Multi-job concurrency and dedup-window behaviour (phase 5).

---

## 3. Architecture decisions

### 3.1 New standalone Gradle project: `services/job-fit-apply-ai-e2e`

Sixth independent build, matching the existing five (own wrapper, own `settings.gradle.kts`, JUnit 5 + Allure like `bridge`).

The forcing argument is CI isolation. The bridge's CI job runs a bare `./gradlew test`, which today also sweeps up `src/test/kotlin/com/jdbridge/e2e/FullFlowE2ETest.kt` into what is supposed to be a fast unit job. Putting real cross-service e2e inside any existing service repeats that mistake at much higher cost. A separate build keeps `./gradlew test` in the five service jobs fast and unconditional.

Kotlin, because the assertions need the same DTO shapes the bridge already defines and the team already reads Kotlin.

### 3.2 No dedicated container in v1

Every endpoint the test needs is already published to the host: bridge `127.0.0.1:8765`, markserv `:8081`, postgres `:5432`, frontend `:3030`. A container buys compose DNS names and nothing else, and costs the fast ad-hoc iteration that is an explicit requirement.

The constraint that matters is **every endpoint is an env var with a localhost default**. Get that right and adding a compose service under a `test` profile later is a ~12-line change, and the monitoring/load use cases get the same knob for free.

As implemented, the e2e slice is a **separate compose project** (`COMPOSE_PROJECT_NAME=jobfit-e2e`) with its own container names (`jobfit-e2e-*`), alternate host ports, and repo-local `./.e2e/` bind-mount sources — so it runs alongside the production stack on the same host without touching its containers, volumes, or real config files. The defaults match those alternate ports:

```
E2E_BRIDGE_URL      default http://127.0.0.1:18765
E2E_MARKSERV_URL    default http://127.0.0.1:18082
E2E_DATABASE_URL    default postgresql://jobfit:***@127.0.0.1:15433/jobfit
E2E_FAKE_LLM_PORT   default 21436   (NOT 11436 — see §4.2; the Makefile sets it to 11436
                                     under REAL_LLM=1 and exports it to up *and* run)
E2E_SINK_PORT       default 18099
E2E_TIMEOUT_SECONDS default 300 (1800 under REAL_LLM=1)
E2E_REAL_LLM        default 0
E2E_OUTPUT_DIR      default ../../.e2e/output (relative to the e2e module)
```

The compose project name is `jobfit-e2e-<hash of the checkout path>`, and container names
track it — `e2e-down` runs `down -v`, so a fixed name would let one worktree delete
another's Postgres volume mid-run. Every e2e service also pins `restart: "no"` (the base
stack's `unless-stopped` would leave a Ctrl-C'd slice holding the ports across reboots),
and `POSTGRES_*` / `NOTIFICATION_FIT_THRESHOLD` are pinned in the override because compose
interpolates the repo-root `.env` regardless of project name.

### 3.3 Submit via `POST /api/jobs` (type `JD_SCRAPED`)

This is JSearch's route. Beyond avoiding the Poller problem, it skips ingestion entirely: `ProcessorCommandHandler.kt:74` passes the record straight through, so `ScrapeJdNode` never runs and neither Steel nor Chrome CDP is touched. That removes the flakiest dependency in the stack from the critical path.

`ProcessingPipeline` then runs: `checkDuplicate → scoreFit → tailor → coverLetter → renderPdf → addArtifactUrl → supabaseTrack`. PDF rendering needs only `python3` + pyyaml/jinja2 + `tectonic`, all already in the pipeline Dockerfile — no browser.

The Extension route (`POST /api/pages`, `JD_PAGE_RAW`) is covered with captured text and one
`scrape_jd` extraction call. The recruiter route (`POST /api/emails`, `EMAIL_RAW`) is covered
with one `scan_email` call and `job_url: null`, so no external scrape runs. A direct recruiter
email continues as the same claimed Bridge work item; it does not enqueue a child job.

---

## 4. The load-bearing component: the fake LLM

The Processor has no offline mode — `--test` is a real run against real models, and there is no `DRY_RUN`/`STUB` flag anywhere in `src/main`. In GitHub CI there is no oMLX. So a fake OpenAI-compatible server is what makes this plan work at all.

It is also what makes assertions *meaningful*: against a real model you can only assert "a PDF exists"; against canned responses you can assert `fit_score == 72`.

### 4.1 Transport

Every default model string is suffix-free, so `LlmClient.backendFor()` routes everything to `MLX_LOCAL`:

```
POST {MLX_LOCAL_BASE_URL}/chat/completions
Authorization: Bearer {MLX_API_KEY}
{"model":"…","messages":[{"role":"user","content":"…"}],"stream":false,
 "response_format":{"type":"json_object"},"temperature":0.0}
```

Response only needs `choices[0].message.content` to be non-blank. There is **no system message** — dispatch must be on `messages[0].content`.

Three quirks the fake must handle:

1. **`summary_rewrite` requests `json_object` but rejects anything starting with `{`.** The fake must ignore `response_format` and return prose on that route.
2. **`/no_think\n` is prepended** to content for qwen3-family models. Match markers with `contains`, not `startsWith`.
3. `LlmGate` serialises all MLX_LOCAL calls behind a semaphore of 1, so the fake never sees concurrent requests on default config.

### 4.2 Placement

The fake runs **in-process in the test JVM**, listening on `0.0.0.0:$E2E_FAKE_LLM_PORT`. The processor container reaches it via `host.docker.internal`, which compose already wires up (`extra_hosts: host-gateway`). No sidecar container, no separate lifecycle.

**The default port is 21436, deliberately not 11436.** 11436 is the production oMLX port, and `docker-compose.yml` points the production processor at it — sharing it breaks isolation in *both* directions, and both failure modes are silent:

- **oMLX up.** The fake binds `0.0.0.0:11436` successfully even while oMLX holds `127.0.0.1:11436` (the JDK sets `SO_REUSEADDR` by default), but a `host.docker.internal` connection arrives on the host as loopback and the kernel routes it to the *more specific* socket — oMLX. The fake serves nothing, the run does real inference under the 300s fake-LLM budget, and it dies as a bare timeout with an empty call list.
- **oMLX down.** The fake owns the port, and the running *production* processor gets fixture responses: canned `fit_score` and canned content on a real Gmail-sourced job, written to the real Postgres and pushed to the real Discord/Telegram.

Binding alone therefore proves nothing, so `FakeLlmServer.start()` refuses to start if anything already answers on the port — turning what was a five-minute mystery timeout into an immediate, named diagnosis.

Because the port is fixed (compose baked it into the containers at `up` time, so it cannot be per-class ephemeral), the fake and the notification sink are owned by **one JVM-wide `SharedE2eHarness`**, not one instance per test class. Two instances would compete for the same port pair — today only sequential class execution and prompt Netty shutdown keep them apart, and when that slips the symptom is the occupied-port refusal above, which blames a stray oMLX and points the investigation at the wrong machine. Started on first use, stopped by a JVM shutdown hook: no `@AfterAll` can own it, because the next class still needs the servers up.

For local runs against a real oMLX, `REAL_LLM=1` skips starting the fake *and* sets `E2E_FAKE_LLM_PORT=11436` for both `up` and `run`, so the container points at the real backend (see §7.3 — the toggle is really a launcher flag, since the var is baked into the container at `up` time).

### 4.3 Call inventory and dispatch table

Eight calls on the lean happy path; ingestion scenarios add explicit routes. Match on
`messages[0].content` substring:

| # | Marker | Route | Mode |
|---|---|---|---|
| 1 | `Write a professional yet casual cover letter` | cover_letter | prose |
| 1a | `# Draft Reply Skill` | draft_reply | prose |
| 1b | `# SCAN_SKILL` | scan_email | JSON |
| 1c | `# SCRAPE_SKILL` | scrape_jd | JSON |
| 2 | `# JD_EXTRACTION_SKILL` / `<job_description>` | jd_extraction | JSON |
| 3 | `# GAP_ANALYSIS_SKILL` | gap_analysis | JSON |
| 4 | `# SUMMARY_REWRITE_SKILL` + `YOUR PREVIOUS OUTPUT WAS INVALID` | summary retry-1 | prose |
| 5 | `# SUMMARY_REWRITE_SKILL` + `REJECTED DRAFT` | summary retry-2 | prose |
| 6 | `# SUMMARY_REWRITE_SKILL` | summary primary | prose |
| 7 | `# BULLET_REWRITE_SKILL` | bullet_rewrite | JSON |
| 8 | `# SKILLS_RESTRUCTURE_SKILL` | skills_restructure | JSON |
| 9 | `# ATS_VALIDATION_SKILL` | ats_validation | JSON |
| 10 | `# SCORE_SKILL` / `\n\nJOB DESCRIPTION:\n` | score_fit | JSON |

A secondary check on `PREVIOUS VALIDATION FEEDBACK (revision pass` distinguishes refine-pass variants.

The fake records every request it serves. **The test asserts the exact call sequence** — that alone catches a surprising amount of pipeline regression, and it fails loudly if a prompt marker drifts (see §9.1).

### 4.4 Canned responses: the constraints that actually bite

These are validation gates that will silently degrade or kill a run if the canned fixtures are careless.

**score_fit** — must return `fit_score >= 50` (`FIT_THRESHOLD`) and `hard_gate_violations: []`, or the run short-circuits to `SKIP` and tailor never executes. Also set `posted_comp_max: null`, `work_arrangement: "remote"`, `office_location: ""` to avoid the deterministic hard gates in `computeHardGates`.

**jd_extraction** — `target_title` must be non-blank and `must_have` non-empty, or the node errors and *every* downstream tailor node fails with a null `jdRequirements`.

**gap_analysis** — `supported` and `unsupported` cannot both be empty. Note `enforceNeverClaim` force-adds the profile's `neverClaim` terms into `unsupported`, and `unsupported` is later scanned literally against the rendered resume. Keep those terms out of the canned summary/bullets/skills.

**summary_rewrite** — prose, ≤1200 chars, must not start with `{`, `[`, `-`, `*`, or `#`. Must not share word-trigrams with the fixture profile's `background.summary` (>0.5 containment triggers an anti-parrot retry).

**bullet_rewrite** — the response is joined to roles on `role|company|start_date` lowercased. A mismatch silently discards the rewrite. **The fake parses the roles JSON out of the prompt and echoes the keys back** rather than hardcoding them — more robust against fixture edits. `must_have_hits` and `quantified` are recomputed in code and ignored.

The echoed `rewritten` text is the original **plus a marker** (`FakeLlmServer.BULLET_MARKER`), never the identity: `BulletRewriteNode` keeps the original bullet when the join key misses, so an identity echo makes "rewrite applied" and "every rewrite silently discarded" byte-identical and unobservable. Tier B asserts the marker reaches `tailored_resume.yaml`.

That assertion immediately earned its keep. `CANDIDATE ROLES` occurs **twice** in the assembled prompt — once in the prepended `BULLET_REWRITE_SKILL.md` prose, directly above an *example* JSON array, and again as the data-section header. Anchoring the parse on the first occurrence read the example, so every echoed join key was a placeholder, the fold-back join matched nothing, and **every bullet rewrite was being dropped** while the suite stayed green. The fake now anchors on the data header (`CANDIDATE ROLES (career history + projects)`), with a `FakeLlmServerTest` case that reproduces the two-marker prompt shape.

**skills_restructure** — `grouped_by_category` must be non-empty with non-empty lists, and must not contain placeholder-looking values (`skill1`, `<placeholder>`, etc.) — there is an explicit `EXAMPLE_TELLS` rejection list.

**Suppressing the refine pass** (keeps the run at exactly 8 calls and deterministic). Refine triggers when `overallScore < 80` or any of leaked-terms / doubled-words / missing-terms is non-empty. The clean lever: from `jd_extraction`, return `exact_match_terms: []` and make every `must_have` entry longer than five words. `coverageKeywords()` then yields an empty universe and coverage is hardcoded to 100. Combined with high `ats_validation` sub-scores, no refine fires.

The refinement scenario deliberately queues a low first ATS response and a passing second
response. It asserts one 12-call flow, including `summary_rewrite:refine`,
`bullet_rewrite:refine`, and `skills_restructure:refine`, and verifies refined content reaches
the final YAML.

### 4.5 Fixture profile and résumé

CI must not depend on the real `resume.yaml` / `candidate_profile.yaml` — they are uncommitted, and the anti-parrot and never-claim gates make the canned responses coupled to their content.

Commit `services/job-fit-apply-ai-e2e/fixtures/{resume.yaml,candidate_profile.yaml}`: a synthetic candidate, stable, tuned so the canned responses pass every gate. `scripts/e2e-ci-prepare.sh` copies them into the paths the compose bind-mounts expect.

---

## 5. Required code change: Notifier base URLs

`https://discord.com/...` and `https://api.telegram.org/...` are hardcoded string literals in:

- `services/job-fit-apply-ai-notifier/.../notify/NotificationClient.kt:34,56`
- `services/job-fit-apply-ai-pipeline/.../client/NotificationClient.kt:36,71`

With blank credentials both methods silently no-op, so "the notifier test passed" would mean nothing.

Add `DISCORD_API_BASE` (default `https://discord.com`) and `TELEGRAM_API_BASE` (default `https://api.telegram.org`) to both `Config.kt` files and thread them through. Small, low-risk, and it's the difference between asserting a payload and asserting nothing.

The e2e module then runs a **mock sink** — a second in-process Ktor server on `E2E_SINK_PORT` — capturing both. Note the notifier service has no `extra_hosts` in compose today; the CI override must add `host.docker.internal:host-gateway` to it.

(`tuner/run-analyzer/analyzer/notify.py` has the same literals but is not on the e2e path — out of scope.)

---

## 6. Assertions

### Tier A — structural (run under both fake and real LLM)

1. `GET /api/jobs/{id}` reaches `status == "done"` within the timeout.
2. `pipeline_action == "TAILOR"`, `fit_score` present and ≥ 50.
3. `GET /api/jobs/{id}/resume.pdf` returns bytes starting `%PDF-`.
4. `GET /api/jobs/{id}/cover_letter.txt` returns non-empty text.
5. The output dir contains `tailored_resume.{yaml,tex,html}`, `report.md`, a `*.pdf`, and *no* leftover `fonts/` or `render_pdf.log`.
6. markserv serves `{artifact_url}report.md` and `{artifact_url}tailored_resume.pdf` with 200.
7. A `tracks` row exists in Postgres matching this run, with the expected `company`, `role_title`, `pipeline_action='tailor'`, and non-null `artifact_url` / `output_path`.
8. `GET /api/tracks` returns that row (the backlog UI's actual data path).
9. The mock sink received a Discord message matching `• {company} — [{title}]({artifactUrl}) — **{score}** (TAILOR)`.
10. `GET /api/jobs/completed?since=0&all=true` includes the job with a monotonic `completed_seq`.

### Tier B — exact values (fake LLM only; skipped under `E2E_REAL_LLM=1`)

The happy path is one scenario-level `@Test`: submission, waiting, evidence gathering, and
verification are all timed as part of that test. Tier A and Tier B are nested `assertAll`
groups, not independent test methods sharing state from `@BeforeAll`. By default the fake
run executes both groups; `./gradlew test -PexcludeTags=tier-b` runs Tier A alone without
also changing the LLM. Real-LLM mode always runs Tier A only.

11. `fit_score` equals the canned value exactly.
12. The fake LLM served exactly the expected 8-call sequence in order.
13. `tailored_resume.yaml` contains the canned summary, the canned skill groups, and the bullet-rewrite marker.
14. `cover_letter.txt` equals the canned cover letter.
15. Telegram sink received the high-fit message, ending in the canned score (anchored — a bare `contains("72")` passes by chance, since the message embeds a 13-digit epoch nonce).

### Deterministic existing-product-path scenarios (fake LLM only)

`ExistingProductPathsE2ETest` is tagged `tier-b`, and `make e2e REAL_LLM=1` passes
`-PexcludeTags=tier-b`, so real-LLM runs keep only structural HappyPath coverage. The tag alone
is not the guard: nothing excludes it unless someone passes the property, and CI runs a bare
`./gradlew test`. So the class also `assumeFalse(E2eConfig.realLlm)` in `@BeforeAll` — every
scenario pins a planned response and an exact call sequence, which cannot hold against a real
model — and `runScenario` refuses outright if a plan is queued while `E2E_REAL_LLM=1`.

| Scenario | Intake / branch | Required evidence |
|---|---|---|
| Low-fit SKIP | `POST /api/jobs`, score `42` | `done/SKIP`, no artifacts, one completed event, Discord only, exactly `score_fit` |
| ATS refinement | `POST /api/jobs`, low then high ATS plan | exactly one refinement pass, 12-call sequence, refined marker in final YAML |
| Captured page | `POST /api/pages`, `JD_PAGE_RAW` | `scrape_jd` first, captured URL/JD in tracking, no Steel/network dependency |
| Direct recruiter email | `POST /api/emails`, `EMAIL_RAW` | same work item completes once, `message_id` preserved, no `scrape_jd`, one `draft_reply` |
| Processor error (#56 s3) | `POST /api/jobs`, injected `score_fit` 500 | terminal `error` naming the stage, nothing published, tracked as `skip` not success, Discord error only, next job completes |
| Duplicate/late completion (#56 s2) | `POST /api/jobs` twice + a replayed result POST | resubmission dedupes to the same job, the replayed result is `already_recorded`, and no second event/track/notification appears |
| Notifier retry (#56 s8) | Discord refuses once via the sink plan | two Discord attempts but one delivery, Telegram not re-sent, cursor recovers for the next job |

Each transaction generates unique correlation data, seeds its own completed-feed cursor, resets
fake/sink observations, captures a local `ScenarioResult`, and filters notifications by company.
Queued fake responses are route-specific FIFO plans, which lets one test process multiple ATS
responses without changing normal happy-path defaults.

### Fault injection

Fixture *text* can only exercise the happy path. The contracts worth testing are the degradation
ones — every tailor-gate failure degrades a node rather than crashing — so both fakes can be told
to misbehave on cue, per route or per channel, FIFO, with defaults resuming once a queue empties:

- `FakeLlmServer.PlannedResponse` — `ok` / `failure(status)` / `malformed` / `empty` / `stall(ms)`.
  A failure is still recorded in `calls`, so exact-sequence assertions survive an injected fault.
  Note `LlmClient` retries **429 only**; every other status throws on the first attempt, so one
  queued 500 is enough to fail a node.
- `MockNotificationSink.PlannedResponse` — `ok` / `failure(status)` / `stall(ms)` per channel,
  with `attempts(channel)` counting refused posts and `delivered(channel)` counting only accepted
  ones. That distinction is what separates "retried once then succeeded" from "delivered once";
  `discordTexts()` / `telegramTexts()` report deliveries.

`ScenarioResult.allCompletedEvents` carries every completed event since the scenario's cursor
regardless of `job_id`, with `eventsForCompany()` over it. Per-job filtering cannot see a
duplicate processed as a *second* job, or a digest child — both carry ids the submitter never saw.

`runScenario(expectTerminal = "error")` asserts about a terminal failure; reaching the other
terminal state fails loudly rather than quietly asserting against a healthy run.

**"Exactly one" and "none at all" get a window.** Those two assertion shapes are the only ones
that can pass by *arriving late* rather than by being right, so the harness never reads them off
a first sighting. It waits for the job in the feed and the Discord message, then sleeps
`E2E_SETTLE_MS` (default 1000) before snapshotting notifications and **re-reading** the completed
feed. Without that, `assertEquals(1, completedEvents.size)` is nearly a tautology — the poll
returned the instant the job appeared — and the SKIP scenario's "no high-fit Telegram" check
misses a real regression by one HTTP round trip, since `Notifier.notify()` posts Discord and
*then* Telegram inside a single call. Positive checks still poll and pay nothing extra.

Plus a Docker-free unit suite on the fake itself (`FakeLlmServerTest`): prompt-marker
aliasing, the loud-500 contract, the bullet echo, and the occupied-port refusal.

### Row identity

There is no `job_id` column on `tracks`. Each run generates a nonce and uses it in `company` — e.g. `E2E Acme {epochMillis}` — which also defeats the bridge's 72h dedup window on `idempotency_key`/`job_url`. `output_path` (timestamped, unique per run) is the secondary handle.

---

## 7. Running it

### 7.1 Compose slice

Only `db`, `bridge`, `markserv`, `processor`, `notifier`. Not `poller` (needs real Gmail secrets), not `jsearch`, not `frontend` (phase 4), not `steel`.

`processor` has a hard `depends_on: steel: service_healthy`, and `depends_on` maps merge rather than replace across override files. As implemented, the override uses the compose `!override` YAML tag on the processor's `depends_on` (bridge + db only), which drops the steel dependency entirely — cleaner than the alpine-stub idea in the original draft, and steel is never built or started. `STEEL_BASE_URL` stays empty; since `JD_SCRAPED` never scrapes, nothing reaches `BrowserFactory`.

### 7.2 `docker-compose.e2e.yml` override

- `processor.environment.MLX_LOCAL_BASE_URL: http://host.docker.internal:${E2E_FAKE_LLM_PORT:-11436}/v1`
- `notifier.environment`: `DISCORD_API_BASE` / `TELEGRAM_API_BASE` → `http://host.docker.internal:${E2E_SINK_PORT:-18099}`, plus dummy token/channel values so the configured-checks pass, plus `NOTIFIER_POLL_INTERVAL_MS: 2000` (default 20s is too slow for a test)
- `notifier.extra_hosts`: `host.docker.internal:host-gateway`
- `steel`: stub as above
- `image:` tags on every service so `buildx bake` can cache them

### 7.3 `scripts/e2e-ci-prepare.sh`

Compose has several bind-mount sources that don't exist in a fresh checkout, and some are **file** mounts — Docker silently creates a *directory* in their place, which breaks the app at runtime. As implemented, the override redirects everything into `./.e2e/` (gitignored) and mounts the committed fixtures **directly** instead of copying them over the real paths — so the suite can never clobber a real `resume.yaml` on a dev machine:

| Mount target | Source |
|---|---|
| processor `/app/output`, markserv `/data` | `./.e2e/output/` (prepare script creates) |
| processor `/app/state` | `./.e2e/state/` |
| bridge `/data` | `./.e2e/bridge-store/` |
| notifier `/state` | `./.e2e/notifier-state/` |
| processor `/app/.env` | `./.e2e/pipeline.env` (**file**, generated by prepare) |
| processor `resume.yaml` / `candidate_profile.yaml` | the committed e2e fixtures, `:ro` |

The script reads the repo-root `.env` the way compose does, so `E2E_MARKSERV_PORT` can't move the container while `ARTIFACT_BASE_URL` keeps pointing at the old port. It rejects unknown arguments (a typo'd `--Fresh` used to no-op silently), removes a stale `pipeline.env` *directory* left by a run that skipped it, and falls back to wiping root-owned container state via a throwaway container — a plain `rm -rf` fails there for an unprivileged user on Linux.

The generated `pipeline.env` carries `ARTIFACT_BASE_URL=http://127.0.0.1:18082`. It must go in the dotenv file, **not** in compose env — an empty `${ARTIFACT_BASE_URL:-}` in compose would beat the mounted file and the node would silently no-op, leaving `artifact_url` null everywhere downstream. `scripts/e2e-ci-prepare.sh --fresh` also wipes per-run state for determinism (`make e2e-up` uses it).

### 7.4 Make targets

```
make e2e-up        # prepare + compose up the slice, wait healthy
make e2e-run       # ./gradlew test in the e2e module against a running stack
make e2e           # up + run + down (tears down on Ctrl-C too)
make e2e REAL_LLM=1  # real oMLX on 11436, Tier A assertions only
```

`make e2e-run` against an already-running stack is the ad-hoc loop — no rebuild, seconds per iteration. It stays correct however long the slice lives: the suite seeds its completed-feed cursor from `/api/jobs/completed/head` before submitting, rather than paging from `since=0` and falling off the 200-event limit.

The health wait fails immediately on a container that has exited or is crash-looping, instead of waiting out its full 240s budget.

The existing `scripts/e2e-smoke.sh` stays as the manual full-fat smoke against real models. It cannot run in CI as written (requires host MLX, pulls in steel via `depends_on`, hard-requires `pipeline_action=TAILOR`).

---

## 8. CI job

New job in `.github/workflows/ci.yml`, matching house style (pinned `@v4` actions, temurin 21, manual `actions/cache` on `~/.gradle`).

```yaml
e2e:
  name: E2E — Bridge → Processor → Notifier
  runs-on: ubuntu-latest
  needs: [bridge, pipeline, notifier]
  continue-on-error: true   # phase 3; remove once stable
```

Steps: checkout → setup-java 21 → setup-buildx → `scripts/e2e-ci-prepare.sh` → `docker buildx bake` → `docker compose up -d --wait --wait-timeout 300` → `./gradlew test` in the e2e module → write the outcome to `$GITHUB_STEP_SUMMARY` → on failure, dump `docker compose logs` as an artifact → upload Allure results and JUnit XML → `docker compose down -v`.

Three things that matter here:

- **Per-target GHA cache scopes.** `type=gha` defaults to `scope=buildkit` and the Actions cache API is write-once per key, so one shared scope makes the four bake targets race — whichever finishes first wins, and the expensive processor image randomly rebuilds cold. The processor exports `mode=min`: `mode=max` on that image would push every intermediate layer into a 10 GB repo-wide LRU cache and evict the `~/.gradle` entries the five service jobs depend on.
- **`up --wait`** replaces a hand-rolled inspect loop that could not tell "crash-looping" from "still starting" and waited its full budget per container, serially.
- **The step summary is load-bearing while the job is `continue-on-error`.** Without it, a failed suite, a slice that never came up, and a job skipped because `bridge`/`pipeline`/`notifier` failed all produce an identical green workflow. Remove it together with `continue-on-error` (exclude the check from branch protection instead, so the job node stays red and visible).

Add `e2e` to the `allure-report` job's `needs:` list so its results are published.

### Build cost

The `processor` image is the expensive one: apt installs, a curl+untar of a pinned tectonic, and a TeX warm-up compile that pulls packages over the network. Multiple minutes cold, and a hard build failure if the tectonic package servers hiccup. All of it is cacheable and stable, so `type=gha` caching pays off enormously here.

One real inefficiency worth a separate small PR: the bridge and pipeline Dockerfiles do `COPY . .` before the Gradle build, so *any* source change busts the layer and re-downloads the entire dependency set. Splitting a dependency-only layer would cut minutes off every CI run. Not a blocker — the runtime tectonic layers are in a different stage and do survive source-only changes.

---

## 9. Risks

### 9.1 Prompt-marker coupling

The fake LLM dispatches on substrings of the skill `.md` prompts. If someone edits a heading in `src/main/resources/skills/`, dispatch silently falls through and the test fails in a confusing way.

Mitigations: the fake returns HTTP 500 with a loud message on an unmatched prompt rather than a default response; the call-sequence assertion (Tier B #12) makes drift obvious; and the matchers anchor on stable structural substrings (`<job_description>`, `grouped_by_category`, `top_improvements`) that also appear in the embedded `DEFAULT_PROMPT` fallbacks.

### 9.2 Canned-fixture brittleness

The canned responses are coupled to the fixture profile through the never-claim, anti-parrot, and coverage gates. Both live in the same directory and change together; the failure mode is a `degraded` node rather than a crash, so **Tier B #12 and #13 are what actually catch it** — Tier A would still pass on a degraded run. This is the main reason exact-value assertions matter.

### 9.2b Port shadowing (closed)

The fake LLM used to default to 11436 — the production oMLX port. See §4.2: binding
succeeds but proves nothing, and the failure was silent in both directions. Now the
default is 21436 and `start()` refuses an occupied port.

### 9.3 Timing

`NOTIFIER_POLL_INTERVAL_MS` default 20s dominates the test's wall clock; the override drops it to 2s. Steel is dropped from the dependency graph, which removes its 45s health start-period. Measured: **~12s per run** with the fake LLM against a warm stack (`make e2e-run`); the first `make e2e-up` is dominated by the processor image build.

### 9.4 Bridge single-process assumptions

`Store.claimNext` is select-then-update rather than an atomic conditional update, and `completed_seq` comes from an in-process `AtomicLong` incremented *before* the UPDATE (so a failed update burns a sequence number). Fine for v1's single job, but load testing (phase 6) will surface both. Not in scope here — flagging for when we get there.

---

## 10. Findings encountered while planning (not in scope)

- `JobStatusResponse.title` and `.company` are declared in `Models.kt:161-170` but never populated by `Routes.kt:135-143`. Any consumer expecting them gets nothing. Either populate them or delete them — the e2e test will not assert on them.
- `services/settings.gradle.kts` declares a five-project aggregate build but has no wrapper and no `build.gradle.kts`, so nothing can invoke it. Either complete it or remove it.
- The bridge CI job's bare `./gradlew test` includes `e2e/FullFlowE2ETest.kt`. Once this plan lands, that test should either move here or be explicitly scoped as an in-process test.
- `POST /api/jobs/batch` has no in-repo caller.
- The Bridge has no authentication on any route; its security model is purely network-level (loopback + tailnet bind). Anything on the tailnet can claim jobs, post results, overwrite artifacts, or mutate `tracks`. Worth a separate conversation.

---

## 11. Phasing

| Phase | Deliverable |
|---|---|
| 0 | Notifier `*_API_BASE` env vars; e2e fixture résumé/profile; `scripts/e2e-ci-prepare.sh` |
| 1 | e2e module + fake LLM + Tier A assertions 1–5, green locally via `make e2e` |
| 2 | Assertions 6–10 (markserv, tracks, `/api/tracks`, mock sink) + all of Tier B |
| 3 | CI job with bake + gha caching, `continue-on-error` → promote to blocking after ~a week green |
| 4 | Wire the existing backlog Playwright suite into CI against the same stack |
| 5 | Negative paths: hard-gate SKIP, processor error → `status=error`, `POST /api/pages` extension route, deliberate refine-pass fixture |
| 6 | Reuse as synthetic monitor (real stack, Tier A only) and load harness (N concurrent submissions) |
