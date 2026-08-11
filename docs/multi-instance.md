# Multi-instance JFAA: prod + test stacks side-by-side

The stack can run as **two independent instances on one machine** — the `prod`
instance (today's behavior, untouched) and a `test` instance — so PRs can be
validated in a live-like environment before promotion, and A/B runs can compare
pipeline changes on identical job inputs. (Issue #51; acceptance tests are
scenarios 9 and 10 of issue #56, in `services/job-fit-apply-ai-e2e`.)

An instance is selected by its env file:

```bash
make up                 # prod — plain `docker compose`, reads .env (unchanged)
make up INSTANCE=test   # test — `docker compose --env-file .env.test`
```

**`--env-file` REPLACES `.env` entirely.** Nothing is inherited; `.env.test`
must restate everything the test instance needs. Copy the committed template:

```bash
cp .env.test.example .env.test
cp services/job-fit-apply-ai-pipeline/.env services/job-fit-apply-ai-pipeline/.env.test
```

Then edit both (tailnet name, test-channel notifier creds, `JFAA_DATA_ROOT`,
`ARTIFACT_BASE_URL=http://<ts-name>:28081`, `LOCAL_LLM_MAX_CONCURRENCY=1`).

## What isolates the two instances

| Mechanism | Prod value | Test value |
|---|---|---|
| `COMPOSE_PROJECT_NAME` | `job-fit-apply-ai` (pinned in `.env` — see warning below) | `jobfit-test` |
| `CONTAINER_PREFIX` | `jobfit` (default) | `jobfit-test` |
| `COMPOSE_PROFILES` | `intake` (poller + jsearch run) | *(empty — no Gmail-touching service exists)* |
| `JFAA_DATA_ROOT` | the prod root | a distinct dir — all six durable mounts follow |
| `PIPELINE_ENV_FILE` | pipeline `.env` | pipeline `.env.test` |
| Named volumes (`jobfit-db-data`, `steel-data`) | scoped by project name | separate copies |

The `JFAA_DATA_ROOT` contract (see `docs/data-root-migration.md`) is what makes
per-instance state a one-liner: `bridge/`, `jsearch-state/`, `notifier-state/`,
`poller-secrets/`, `pipeline-output/`, `pipeline-state/` all derive from the one
root, with per-service `JD_*_HOST` overrides still available for exceptions.

⚠ **The one dangerous setting is prod's `COMPOSE_PROJECT_NAME` pin.** It must
match the *currently effective* project name exactly (`docker compose ls`,
`docker volume ls | grep db-data`) — a wrong value boots prod with a fresh empty
DB volume (the old volume survives on disk, but the stack won't be using it).

## Port scheme (test = prod + 20000)

| Service | prod host | test host | tailnet exposed |
|---|---|---|---|
| postgres | 5432 | 25432 | no |
| bridge | 8765 | 28765 | yes (both) |
| frontend | 3030 | 23030 | yes (both) |
| markserv | 8081 | 28081 | yes (both) |
| steel | 3000 | 23000 | optional via `STEEL_BIND_ADDR` |
| sign-in endpoint | 3100 | 23100 | optional via `STEEL_SIGNIN_BIND_ADDR` |

Container-side ports never change; in-network URLs (`http://bridge:8765`,
`db:5432`, `http://steel:3000`) are per-project-network and need no edits.
The scheme avoids the E2E slice (15433/18765/18082/18099/21436, plus
19765/19082/15434 for its replay-source slice) and Roomote A/B/C
(13000–15002, 15432, 18081). `make up INSTANCE=test` adds the three test
Tailscale Serve listeners; `tailscale serve status` should show six mappings
with both instances up.

## How jobs enter the test instance: replay

**Gmail is account-global** — two pollers on one account race and double-process
labels. The test instance therefore runs *no poller and no jsearch*
(`COMPOSE_PROFILES` empty); jobs enter it by replaying real payloads from the
prod bridge store:

```bash
make replay ARGS="--last 1"            # newest done prod job → test bridge
make replay ARGS="--id <job_id>"       # a specific job (repeatable flag)
make replay ARGS="--since 2026-08-01 --last 20"
make replay ARGS="--last 1 --force"    # bypass test-bridge dedupe (re-run A/B)
```

`scripts/replay-jobs.sh` opens the prod SQLite store **read-only**
(`sqlite3 file:…?mode=ro`, resolved via `scripts/jfaa-data-root.sh`, never a
hardcoded path), routes each row by `type` to the matching test-bridge endpoint
(`JD_SCRAPED → /api/jobs`, `EMAIL_RAW → /api/emails`, `JD_PAGE_RAW → /api/pages`)
and re-POSTs the stored `jd_json` verbatim — the full scrape/extract path runs
again in test. `--force` suffixes the idempotency key (and adds a `#replay-…`
fragment to any `job_url`/`url`) so the test bridge's dedup window cannot absorb
a deliberate repeat.

Gmail write-back is safely inert in test: terminal jobs accumulate
`writeback_done=false` rows in the *test* bridge store, only a poller drains
those, and test has none. Nothing in the test instance can touch Gmail.

### Comparing A/B results

```bash
./scripts/compare-ab.sh --last 5          # newest prod tracks vs their test rows
./scripts/compare-ab.sh --url <job_url>
```

Read-only `psql` against both Postgres instances; prints company / title /
fit_score / pipeline_action side-by-side plus both report URLs.

## Reset / teardown

Full test-instance reset (containers, volumes, all state):

```bash
make down INSTANCE=test
docker compose --env-file .env.test down -v
rm -rf "<the test JFAA_DATA_ROOT>"
```

Prod is untouched throughout — distinct project name, volumes, and data root.
`make up INSTANCE=test` skips the data-root migration guard (a fresh test root
is empty *by design*, which the guard would otherwise read as an unmigrated
prod deploy); prod keeps the guard.

## Shared host resources (not isolated — plan around them)

- **Local LLMs**: oMLX `:11436` and Ollama `:11434` are host singletons shared
  by both processors. `LOCAL_LLM_MAX_CONCURRENCY` is per-process, so two
  processors ≈ 2× LLM load — set it to `1` in the test pipeline `.env.test`, or
  point test at Ollama Cloud for the duration of an A/B run.
- **Host CDP Chrome `:9222`**: shared fallback scraping path (both stacks reach
  it via `host.docker.internal`).
- **Disk**: image count roughly doubles (compose names images
  `<project>-<service>`; the build cache is shared so rebuilds are cheap). The
  frontend image is inherently per-instance — `VITE_API_BASE_URL` is baked at
  build time.

## Steel auth seeding (optional)

Test Steel starts with no authenticated sessions. To seed it, copy the prod
storageState once into the test root:

```bash
cp -a "<prod JFAA_DATA_ROOT>/pipeline-state/." "<test JFAA_DATA_ROOT>/pipeline-state/"
```

Note shared cookies can trip logouts on some boards (single-session sites);
skip seeding for unauthenticated testing. Keep `pipeline-state` mode `700` —
it holds authenticated browser sessions and markserv must never serve it.

## Prod-only host assets (deliberately not duplicated)

These launchd/host tools are name-wired to the prod instance and do **not**
cover test: `steel-watchdog` (restarts unhealthy prod Steel — restart test's
manually: `docker restart jobfit-test-steel`), the run-analyzer plists, and
`launch-chrome-cdp`. Clone a plist with the test container name later if a
long-lived test instance earns it.

## Verification checklist

1. **Prod no-regression**: `docker compose config` diff vs pre-change baseline
   is clean (with `COMPOSE_PROFILES=intake`); `make up`; `docker ps` shows the
   same `jobfit-*` names; db volume name unchanged; `make doctor` passes.
2. **Test bring-up**: `make up INSTANCE=test` → `jobfit-test-*` containers
   alongside prod, no poller/jsearch, own db volume, `curl 127.0.0.1:28765/health`
   OK, `psql -p 25432` shows empty `tracks`.
3. **Replay E2E**: `make replay ARGS="--last 1"` → job completes in test, report
   lands under the test root's `pipeline-output` and at tailnet `:28081`,
   notification hits the **test** channel only; prod Gmail labels untouched.
4. **Isolation**: `lsof -iTCP -sTCP:LISTEN` port sets disjoint; prod bridge
   `jobs.db` unchanged while test processes.
5. `make e2e` still passes (the isolated E2E slice is unaffected), and the CI
   e2e job now also runs scenarios 9/10 against a synthetic source slice.
