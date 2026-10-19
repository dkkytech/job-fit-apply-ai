# JobBot

JobBot is the Telegram agent behind **@DkkyAIJobBot**. It handles the buttons on JFAA's high-fit cards and answers when you reply to a card. It is a [Hermes](https://hermes-agent.nousresearch.com) gateway (`docker/jobbot`) plus a small Kotlin service, `jobbot-mcp` (`services/job-fit-apply-ai-jobbot-mcp`), which is its only door into JFAA.

```
Processor ──► Bridge (completed feed: completed_seq, job_url, message_id, is_recruiter, terminal_label…)
                │ GET
                ├──────► Notifier ──sendMessage only──► Telegram @DkkyAIJobBot
                │          card ends with  #J<seq>              │ ▲
                │          buttons carry   <verb>:<seq>          │ │ answers, card edits, replies
                │                                               ▼ │
                │                                  getUpdates (the ONLY reader)
                │                                     jobbot  (Hermes, network `jobbot` only)
                │                                      ├─ jobbot_actions plugin: taps → /plugin/tap
                │                                      └─ agent: JFAA MCP tools (mcp__jfaa__*)
                │                                               │ Bearer JOBBOT_MCP_TOKEN
                └──── GET (route allowlist) ───────── jobbot-mcp  (networks default + jobbot + jobbot-browser)
                      POST only tracks/{id}/events|status,  ├─ pipeline-output (ro), resume/profile YAML (ro)
                      jobs/{id}/tailor                 ├─ poller-secrets (ro): Gmail token, refreshed in memory
                                                       └─ /state/actions.db (taps, undo records, errors)
```

## The rules it is built on

- **One bot, split by direction.** The notifier only calls `sendMessage`; its Telegram client cannot build any other method. The `jobbot` container is the token's **only** `getUpdates` reader. Telegram allows one, and a second reader gets HTTP 409. `make doctor` fails while the retired `pm2 nanobot-jobbot` is online.
- **Alerts never depend on JobBot.** The notifier works with `jobbot` stopped. Without JobBot, cards just have no working action buttons, so leave `NOTIFIER_TELEGRAM_ACTIONS` blank whenever JobBot is down for long.
- **Buttons are code, not prompts.** A tap is decided by `jobbot-mcp`'s `TapService`, which is deterministic Kotlin. The model never sees a tap unless a verb hands work to it (Reply, later).
- **The model's tools are narrow.** Its Telegram toolsets are `web`, `memory`, `clarify`, `session_search` and the JFAA MCP server (`mcp__jfaa__*`), which has up to 16 tools:
  - **Read:** `get_job`, `list_high_fit`, `read_job_file`, `list_tracks`, `get_profile`, `get_track_timeline`, plus `get_job_email` and `get_reply_draft` with Gmail, and `get_alert_template` with a template file.
  - **Tracks:** `add_track_note` and `set_track_status`. They append to a job's history in the bridge's `track_events` as `source=jobbot`, and only on a track matched by `track_id` or `artifact_url`, never a fuzzy match.
  - **Gmail:** `write_reply_draft` (saves a draft, never sends) and `request_send_approval` (posts a ✅ Send preview; see Sending replies). Registered only when the Gmail token and credentials files are set.
  - **Card template:** `preview_alert_template`, `update_alert_template` and `revert_alert_template` (see Card template). Registered only when `JOBBOT_TEMPLATE_FILE` is set.

  Hermes's terminal, file, browser, code-execution, delegation, cron and skill-editing toolsets are disabled. Config, persona and plugins are re-seeded from the image on every start (`docker/jobbot/seed.py`).
- **Untrusted content is data.** Job postings, reports quoting them, and emails never become instructions. File text from `read_job_file` is prefixed with a notice saying so.

## Card buttons

The notifier sends a verb only when it is listed in `NOTIFIER_TELEGRAM_ACTIONS` **and** the event qualifies. `jobbot-mcp` re-checks the same rules on every tap. Both sides test against `docker/jobbot/contract/button_eligibility.json`.

| Verb | Shown when | Tap does |
|---|---|---|
| `apply` | the job has a `job_url` and was tailored (`pipeline_action` TAILOR). A skipped job, e.g. pay-gated, has no tailored resume to upload | with `JOBBOT_APPLY_ENABLED`: fills the application in JobBot's browser and posts it under **✅ Submit / ✖ Discard** (see Apply below). Otherwise it replies "Not implemented yet" |
| `reply` | `is_recruiter` and a source `message_id` | posts the reply draft (the poller's, or one built from JFAA's `draft_text`, with the resume attached) under **✅ Send / ✖ Cancel**. If neither exists, the agent writes one |
| `archive` | a source `message_id` the poller left in the inbox | removes `INBOX` from that email only; the button becomes **↩ Undo archive** (`undo:<seq>`), which restores exactly the labels it removed. Mail already out of the inbox, archived by Muse for example, gets "Already out of the inbox." |
| `tailor` | the job was offered (`pipeline_action` OFFER): a fit from `FIT_THRESHOLD` up to `AUTO_TAILOR_MIN_SCORE`, scored but not tailored up front | asks the bridge to tailor it (`POST /api/jobs/{job_id}/tailor`, the only queue write JobBot makes). The resume arrives as a new card a few minutes later; the button goes away. A second tap answers "Already tailoring" or "Already tailored" |

`callback_data` is `<verb>:<completed_seq>`. The seq is the bridge's id for the completion, so nothing is registered before the send.

**Every tap is checked in this order:**
1. The verb is known.
2. The user is in `JOBBOT_TELEGRAM_ALLOWED_USERS`. Blank means nobody.
3. The card is at most 7 days old (`JOBBOT_CARD_TTL_DAYS`).
4. The verb is in `JOBBOT_ACTIONS`.
5. The seq resolves on the bridge.
6. The event qualifies for the verb.

Then the verb's handler runs. With `JOBBOT_DRY_RUN=true`, it stops after the checks, logs the tap, and toasts `[dry run] Would …`.

`/jdstatus` shows the mode, the handled verbs, the latest job, pending actions, recent actions and recent errors. Inbox sweeps are not JobBot's: Muse still does them.

## Skipped high fits (e.g. pay-gated)

A job can score at or above `FIT_THRESHOLD` and still be skipped by a hard gate: posted pay more than 40% below the profile's minimum (`minimum_total_compensation`; `minimum_contract_rate_hourly` for hourly contract pay; hourly pay on a full- or part-time role is annualized at 2,080 hours and compared with `minimum_total_compensation`), or an onsite or hybrid office outside Washington. It still gets a card, with three differences:
- The card adds a **Skipped:** line, e.g. "Skipped: Posted pay (max $85K) is more than 40% below the $145K target". The text comes from the completed feed's `skip_reason`. The line also appears under an edited card template.
- **View Report** opens the scoring notes: `report.md` beside `score_fit.txt`, published at `artifact_url`. There is no **View Resume**, because nothing was tailored.
- There is no **Apply**. An Apply tap on an older card is refused, with the reason.

## Apply: filled by code, submitted only by ✅ Submit

**How it works**
- **Tap Apply.** `jobbot-mcp` queues a fill. Fills run one at a time.
- **Model-planned, code-executed.** The fill loop (`FillAgent`) asks the model (`JOBBOT_MODEL`) for symbolic steps on element ids, and executes them in **JobBot's browser**: the `jobbot-browser` container, a headed Chromium 154 with a persistent profile.
- **Review card.** When the form is ready, JobBot posts a screenshot and every filled field (passwords masked) under ✅ Submit / ✖ Discard.
- **Submit.** ✅ Submit clicks the site's submit button **only** if the form still matches what the card showed (field fingerprint) and exactly one submit control exists. It then sets the track to `applied` and adds an `application_submitted` event.
- **Review window.** A filled form waits **4 h** (`JOBBOT_APPLY_REVIEW_TTL_HOURS`), with a reminder at 3 h. After that it expires and nothing is submitted.
- **Hand-offs.** A CAPTCHA, phone or ID check, a sign-in Google won't auto-complete, or a question the profile can't answer is posted with the viewer link and **▶ Continue**.
- **No mouse fights.** The loop doesn't touch the page while Richard works in the viewer.

**Hard limits, in code (`SitePolicy`, `FillAgent`)**
- **Never submits.** A click on any submit-type control is refused.
- **Navigation is limited.** The page may only go to:
  - the job's own domain
  - known ATS and job-board domains
  - a domain carrying the company's name
  - Google's sign-in and consent pages

  Gmail, Drive and Google account settings are blocked at the network layer, because the browser holds a live Google session for dkkytech@.
- **Accounts.** "Sign in with Google" wherever a site offers it, enforced in code. The password action is refused on such a site unless it already has a password account. The snapshot finds Google's embedded button (an `accounts.google.com/gsi/button` iframe) as well as a site's own "Continue with Google" control. JobBot follows the Google popup, or Chrome's FedCM account dialog, by rule until it closes. Sites without Google get an account as `JOBBOT_ACCOUNT_EMAIL` with a **per-site generated password**.
  - The password is saved `pending` in `jobbot-secrets/site-credentials.json` (mode 600, read only by jobbot-mcp) **before** use, and activated once the fill gets through.
  - The model never sees a password: the snapshot never exposes input values as text, and every prompt is scrubbed of passwords used in the run.
  - `/jdaccounts` lists the accounts.
- **Google consent.** Approved automatically only for basic sign-in scopes (name, email, profile). The account's own address on the screen doesn't count as a Gmail scope. Anything else is handed to Richard.
- **After a restart.** A fill waiting on a hand-off or review loses its tab when jobbot-mcp restarts. ▶ Continue then expires it, and the card's Apply button starts a fresh fill.
- **Verification email.** Codes and links come only from the site's (or its ATS's) own sender, and links must point back at the site.
- **CAPTCHAs** are never solved. They are handed off.

**JobBot's browser** (`docker/jobbot-browser`, profile `jobbot-browser`; called `apply-browser` before 2026-10-02)
- **Image:** `linuxserver/chromium`, pinned by digest. Its Selkies web viewer streams over WebRTC/websockets.
- **CDP:** socat exposes Chromium's DevTools on `:9223`, reachable only on the private `jobbot-browser` network (jobbot-browser + jobbot-mcp). DevTools rejects non-IP Host headers, so jobbot-mcp resolves the address first.
- **Google session:** a managed policy turns browser sign-in off (`BrowserSignin: 0`, `SyncDisabled`). Without it, this Chromium (no Google API keys) signed dkkytech@ out of Google on every restart: DICE failed to fetch a browser token after each web sign-in, and the startup reconcile then cleared the web session. Sign in on the web (accounts.google.com) only. Doctor checks the policy is present.
- **Viewer:** published on `127.0.0.1:3200` behind `JOBBOT_BROWSER_PASSWORD` (user `JOBBOT_BROWSER_USER`, default `richard`). `scripts/setup-tailscale-serve.sh` serves it as `https://<tailnet-name>:3200`. The container **refuses to start** without the password.
- **Profile lock:** cleared at init. A stale `SingletonLock` from a previous container made Chromium skip DevTools; this was caught in the spike.
- **Accepted risk:** the image runs Chromium with `--no-sandbox`. The container is the boundary: it has its own network, no host mounts beyond its profile, and a 3 GB memory limit.

**First-time setup (Richard)**
1. Set `JOBBOT_BROWSER_PASSWORD` and `JOBBOT_BROWSER_VIEWER_URL` in the root `.env`, and add `jobbot-browser` to `COMPOSE_PROFILES`. The old names (`APPLY_BROWSER_*`, `JOBBOT_APPLY_VIEWER_URL`, profile `apply`) still work, and doctor warns about them.
2. Run `scripts/setup-tailscale-serve.sh`.
3. Open the viewer and sign in to Google as dkkytech@ once. That also covers 2-step verification.
4. Set `JOBBOT_APPLY_ENABLED=true`.

## Card template

Richard can ask JobBot to change how high-fit cards look. Without a template, the built-in card reads:
```
Sparksoft: Automation Engineer (63)
Remote · $80K–$85K · via jobright.ai

Fit:
• Web/API automation tools match
• Healthcare domain expertise overlap
• Core tech stack overlap

Gap: Seniority level is a step down
Skipped: Posted pay (max $85K) is more than 40% below the $145K target
#J7708
```
The company links to the posting and the title to the report. A missing company or title reads "Unknown". Each line is left out when its value is unknown, and the blank lines only frame the Fit block.
- **Tools:** `get_alert_template` and `preview_alert_template` (with a real `#J` or a sample), then `update_alert_template`, which `revert_alert_template` undoes.
- **Storage:** the template lives in `jobbot-templates/high-fit.html`. jobbot-mcp writes it; the notifier mounts it read-only and re-reads it per card once `NOTIFIER_TELEGRAM_TEMPLATE_FILE=/templates/high-fit.html` is set.
- **Rules:**
  - `{placeholder}` substitution only: `{company} {title} {company_link} {title_link} {score} {action} {ref} {details} {strengths} {gap}`
  - `{details}` is location · remote policy · salary · via source (whatever is known), `{strengths}` is the top three as bullets, and `{gap}` is the first gap
  - a line whose placeholders all render empty is dropped, so `<b>Gap:</b> {gap}` disappears when there is no gap
  - values are HTML-escaped
  - links are built in code, and `<a>` is refused
  - tags allowed: `b i u s code pre blockquote`, balanced
  - `{ref}` is required, and the template is at most 1000 characters

  Both sides test against `docker/jobbot/contract/alert_template_vectors.json`.
- **Never costs a card:** a missing or invalid template falls back to the built-in format, and a template Telegram rejects is re-sent once in the built-in format.

## Sending replies: the ✅ Send tap is the only way

The model has **no send tool**.
- **Model tools:** it can `write_reply_draft`, which only ever saves a Gmail draft, and `request_send_approval`.
- **Preview:** `request_send_approval` stores a pending approval with a SHA-256 fingerprint of the draft (recipients, subject, text, attachment names). The plugin's `post_tool_call` hook then posts that exact preview with `send:<id>` / `cancel:<id>` buttons.
- **Checks at Send.** A tap on ✅ Send is checked again in `jobbot-mcp`:
  - the allowlist
  - the preview's age
  - `JOBBOT_SEND_ENABLED` (off by default)
  - `JOBBOT_MAX_SENDS_PER_DAY`
  - the draft still matches the fingerprint
  - every recipient is already in the thread
  - Richard hasn't replied in the thread since, for example via Muse

  Then `drafts.send`. A second tap answers "Already sent."
- **Why not Hermes's approval gate?** Hermes's `pre_tool_call` → `approve` gate is skipped by the gateway's `/yolo` toggle, and no config locks `/yolo`. A deterministic tap is immune to both the model and `/yolo`.

## Gmail (Archive / Undo / get_job_email)

`jobbot-mcp` uses **the poller's token**: the same dkkytech@ account and scopes, since neither is useful without the other.
- **Mount:** `poller-secrets` is mounted read-only as a directory. A poller re-auth recreates the token file, and the new file is picked up without a restart.
- **Never written:** the token file is never written, deleted or re-authorized here. Access tokens stay in memory.
- **Dead token:** `invalid_grant` puts `/jdstatus` on `Gmail: NEEDS RE-AUTH`. The fix is the poller's own `docker compose run --rm poller --reauth`.
- **Production app:** the OAuth app is in Production, so tokens don't expire weekly.
- **What it touches:** Archive and Undo touch only the `INBOX` label. `TRASH` and `SPAM` are refused in code.

## Configuration (root `.env`)

| Variable | Meaning |
|---|---|
| `COMPOSE_PROFILES` | Must include `jobbot` (`intake,jobbot`). |
| `JOBBOT_TELEGRAM_BOT_TOKEN` | The same value as `NOTIFIER_TELEGRAM_BOT_TOKEN`. |
| `JOBBOT_TELEGRAM_ALLOWED_USERS` | Your Telegram user id. |
| `JOBBOT_TELEGRAM_HOME_CHANNEL` | Your chat id. |
| `JOBBOT_MCP_TOKEN` | Shared secret between `jobbot` and `jobbot-mcp` (`openssl rand -hex 32`). |
| `JOBBOT_MODEL` | Chosen by the env-llm-tuner skill. Default `deepseek-v4.1-flash:cloud`. Ollama Cloud ids end in `:cloud`; the bare name 404s. The fallback is oMLX `Qwen3.6-35B-A3B-OptiQ-4bit`. |
| `JOBBOT_ACTIONS` | Verbs JobBot acts on. `NOTIFIER_TELEGRAM_ACTIONS` must be a subset. |
| `JOBBOT_DRY_RUN` | `true` validates and logs taps but does nothing. |
| `JOBBOT_SEND_ENABLED` | Kill switch for ✅ Send (default `false`). |
| `JOBBOT_MAX_SENDS_PER_DAY` | Daily cap on sends (default 10). |
| `NOTIFIER_TELEGRAM_ACTIONS` | Verbs the cards show. |

## Runbook

**Deploy.** Use a release worktree at the merge commit, with the three personal-file symlinks, as for every service. Run from that worktree:

```bash
docker compose --env-file ~/projects/job-fit-apply-ai/.env --profile jobbot up -d --build --no-deps jobbot-mcp jobbot
```

**Check.**
- `make doctor` has a JobBot section.
- `docker logs jobfit-jobbot` should show `[jobbot-seed] wrote config.yaml`, `Wired native handlers from plugin 'jobbot_actions'`, `MCP server 'jfaa' (HTTP): registered 16 tool(s)` (12 without Gmail), and `Connected to Telegram (polling mode)`.
- Gateway logs live in `${JFAA_DATA_ROOT}/jobbot/logs/`.

**One-shot agent test (no Telegram):**

```bash
docker exec jobfit-jobbot hermes chat -q "Look up JFAA job #J7650 and say why it fits."
```

**Rollback to the old reply bot:**

```bash
docker compose --env-file ~/projects/job-fit-apply-ai/.env --profile jobbot stop jobbot
```

```bash
pm2 start nanobot-jobbot
```

Then set `NOTIFIER_TELEGRAM_ACTIONS=` (blank) and recreate the notifier, so cards stop showing buttons nothing handles.

## Tests

| Level | Where |
|---|---|
| Notifier unit + contract | `services/job-fit-apply-ai-notifier`: `./gradlew test` |
| jobbot-mcp unit, fake-bridge HTTP, real MCP client over HTTP | `services/job-fit-apply-ai-jobbot-mcp`: `./gradlew test` |
| Hermes plugin (stubbed Telegram/Hermes) + seed | `docker/jobbot`: `python -m pytest tests` |
| **Live jobbot-browser** (opt-in): real Chromium over CDP + a fixture job site + a scripted model | `JOBBOT_BROWSER_CDP_URL=http://127.0.0.1:19223 FIXTURE_HOST=host.docker.internal ./gradlew test --tests '*ApplyBrowserLiveTest*'` |
| Compose isolation (networks, mounts, ports, profile) | `make compose-data-root-test` |
| Buttons at the wire (Bridge → Processor → Notifier) | `make e2e` |

## Known gaps

- **Memory is Hermes's built-in** (`MEMORY.md` / `USER.md`). The planned separate Hindsight bank needs a provider that isn't in the `v2026.9.24` image.
- **Workflows are in the persona, not skills.** They live in `docker/jobbot/skills/*.md` and are appended to the persona at boot. Hermes's skills toolset is disabled, because it would let the agent write its own skills.
