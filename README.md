# Job Fit to Apply AI Suite - README

A monorepo AI pipeline that automates the complete job search workflow: Gmail inbox scanning → email classification → digest fan-out → job scraping → fit scoring → resume tailoring → cover letter generation → PDF rendering → recruiter reply drafting → Supabase tracking → dashboard management. A Chrome extension extends the same pipeline to job boards encountered during normal browsing.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────────────────┐
│  Gmail Inbox                                                                   │
│  Recruiter emails + job board digests (LinkedIn, Glassdoor,                    │
│  Indeed, Lensa, Monster, JobLeads, JobRight, WTTJ, ...)                        │
└──────────────────────┬─────────────────────────────────────────────────────────┘
                       │ OAuth2 scan  (cron: every 30 min)
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  services/job-fit-apply-ai-pipeline  —  IngestionPipeline                      │
│  --max-emails N / --email "…"                                                  │
│                                                                                │
│  ScanEmail → [digest fan-out] → ScrapeJd → SaveJD                              │
│  → bridge.submit(JdRecord)  +  apply JD_Processing label                       │
│  → bridge.pollUntilTerminal(jobId)  (blocks until worker done)                 │
│  → apply final Gmail label / create recruiter draft reply                      │
└──────────────────────┬─────────────────────────────────────────────────────────┘
                       │ POST /api/jobs  (HTTP, loopback)
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  services/job-fit-apply-ai-bridge  (Kotlin Ktor, port 8765)      │
│  SQLite job queue — submit / claim / result / artifact endpoints               │
│  Bound to: 127.0.0.1:8765  +  <tailscale-ip>:8765                             │
└──────────┬──────────────────────────────────────────────────┬──────────────────┘
           │ claim()  (worker polls)                          │ POST /api/jobs
           ▼                                                  │ (Tailscale)
┌──────────────────────────────────────┐                      │
│  services/job-fit-apply-ai-pipeline  │             ┌────────┴───────────────────┐
│  --worker  (pm2: jd-worker)          │             │  Chrome Browser            │
│                                      │             │  apps/job-description-to-  │
│  ProcessingPipeline:                 │             │  ai-pipeline-browser-      │
│  CheckDuplicate → ScoreFit           │             │  extension  (MV3)          │
│  → ResumeTailoringSubgraph (6 nodes) │             │  13 ATS extractors         │
│  → GenerateCoverLetter               │             └────────────────────────────┘
│  → RenderResumePdf (Playwright)      │
│  → AddArtifactUrl → SupabaseTrack    │       JSearch API  (cron: daily 5 AM)
│  → postResult()                      │         --jsearch → bridge.submit()
└──────────────────────────────────────┘              (same queue, same worker)


                       │ INSERT/UPDATE tracks table
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  Supabase (PostgreSQL)  —  tracks table                                        │
└──────────────────────┬─────────────────────────────────────────────────────────┘
                       │ reads + writes (status updates only)
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  apps/job-fit-apply-ai-backlog  (React + TypeScript + Vite, port 8080)              │
│  Live dashboard: fit-score filter, status management,                          │
│  collapsible rows, direct PDF + cover letter downloads                         │
└────────────────────────────────────────────────────────────────────────────────┘
```

### Queue concurrency guards

The `--max-emails` cron run is protected against re-entrant overlap at two levels:

1. **`heartbeat_check.sh`** — checks the PID file before starting; exits immediately with `ALREADY_RUNNING` if the previous run is still polling the bridge.
2. **`-label:JD_Processing`** — the Gmail search query excludes emails already labeled in-flight, so a second run that slips past the PID check still won't re-ingest the same email.

---

## Repos

| Repo | Language | Description |
|---|---|---|
| `services/job-fit-apply-ai-pipeline` | Kotlin / JVM 21 | Email ingestion pipeline + processing pipeline + worker; CLI entry point for all modes |
| `services/job-fit-apply-ai-bridge` | Kotlin / JVM 21 | Ktor bridge — SQLite job queue, claim/result/artifact API, artifact file server |
| `apps/job-fit-apply-ai-extension` | JavaScript (MV3) | Chrome extension — JD extraction from job boards, real-time progress UI |
| `apps/job-fit-apply-ai-backlog` | TypeScript / React 18 | Vite dashboard — live job table, status management, artifact downloads |

---

## Prerequisites

### services/job-fit-apply-ai-pipeline
- JDK 21
- Gradle (wrapper included)
- **Ollama** running locally with models pulled, or cloud API keys for MiniMax / DeepSeek
- **Playwright** with Chromium installed
- A Chrome profile logged in to LinkedIn (for LinkedIn scraping)
- **Gmail OAuth credentials** — `credentials.json` from Google Cloud Console (Gmail API enabled, OAuth 2.0 client for desktop app)
- **Supabase** project with `tracks` table (schema below)

### services/job-fit-apply-ai-bridge
- JDK 21
- **Tailscale** (optional — required only for Chrome extension access)

### apps/job-fit-apply-ai-extension
- Chrome with Developer Mode enabled
- **Tailscale** — extension communicates with bridge over MagicDNS address

### apps/job-fit-apply-ai-backlog
- Node.js 18+ or Bun 1.0+
- Same Supabase project as the pipeline

---

## Setup

### 1. Supabase

Create a project at [supabase.com](https://supabase.com) and run:

```sql
create table tracks (
  id              bigserial primary key,
  company         text not null,
  role_title      text not null,
  location        text,
  remote_policy   text,
  fit_score       integer,
  job_url         text,
  artifact_url    text,
  tech_stack      text[],
  status          text default 'backlog',
  duplicate       boolean default false,
  duplicate_id    bigint,
  created_at      timestamptz default now()
);
```

Save your project URL and anon key — used by both the pipeline and the dashboard.

---

### 2. services/job-fit-apply-ai-bridge

```bash
cd services/job-fit-apply-ai-bridge

# Build the fat JAR
./gradlew shadowJar

# Start via pm2 (recommended — auto-restarts on reboot)
pm2 start --name "jd-bridge" bash -- \
  -c "cd $(pwd) && java -jar build/libs/jd-bridge-ktor-0.1.0.jar"
pm2 save
```

The bridge binds to `127.0.0.1:8765` and, if Tailscale is running, also to your Tailscale IP. Override with `JD_BRIDGE_HOST` and `JD_BRIDGE_PORT` in `.env`.

---

### 3. services/job-fit-apply-ai-pipeline

```bash
cd services/job-fit-apply-ai-pipeline

# Initialize your profile (generates candidate_profile.json & generated_resume.html)
./gradlew run --args="--init-profile path/to/your_resume.pdf"

# First-time Gmail OAuth
./gradlew run --args="--reauth"

# Verify token
./gradlew run --args="--check-token"

# Test end-to-end on a sample JD (no Gmail / Supabase required)
./gradlew run --args="--test"

# Start the worker (processes jobs queued by ingestion runs and the browser extension)
pm2 start --name "jd-worker" bash -- \
  -c "cd $(pwd) && ./gradlew run --args='--worker'"
pm2 save
```

---

### 4. Chrome Extension

1. Chrome → `chrome://extensions` → enable Developer mode
2. Load unpacked → select `apps/job-fit-apply-ai-extension/`
3. Set your bridge address in `config.js`:

```js
export const BRIDGE_API_URL = 'http://your-machine.ts.net:8765';
```

---

### 5. apps/job-fit-apply-ai-backlog

```bash
cd apps/job-fit-apply-ai-backlog
npm install

cat > .env << EOF
VITE_SUPABASE_URL=https://your-project.supabase.co
VITE_SUPABASE_ANON_KEY=your-anon-key
EOF

npm run dev           # http://localhost:8080
npm run build         # production build → dist/
```

For cloud hosting, deploy to Vercel: set the two `VITE_SUPABASE_*` environment variables and it auto-deploys on push to main.

---

## Automation (cron + pm2)

| Process | Type | Command | Schedule |
|---|---|---|---|
| `jd-bridge` | pm2 (always-on) | `java -jar jd-bridge-ktor-0.1.0.jar` | continuous |
| `jd-worker` | pm2 (always-on) | `./gradlew run --args='--worker'` | continuous |
| Email ingestion | cron | `run_jd_pipeline.sh` (`--max-emails 3`) | every 30 min |
| JSearch ingestion | cron | `run_jsearch.sh` (`--jsearch`) | daily 5 AM |

The bridge and worker must be running before the cron jobs fire.

---

## LLM Configuration (`Config.kt`)

By default, the pipeline uses local Qwen models via Ollama. You can route to cloud providers (Ollama Cloud, DeepSeek, MiniMax) by setting the respective `*_BASE_URL` and `*_API_KEY` in `.env`.

```kotlin
val SCAN_MODEL               = "qwen3.5:9b-q4_K_M"   // fast classification, local
val SCRAPE_MODEL             = "qwen3.5:9b-q4_K_M"
val SCORE_MODEL              = "qwen3.5:9b-q4_K_M"   // rubric-based fit scoring
val RESUME_REASONING_MODEL   = "qwen3.5:9b-q4_K_M"   // deep reasoning
val COVER_LETTER_MODEL       = "qwen3.5:9b-q4_K_M"
val DRAFT_REPLY_MODEL        = "qwen3.5:9b-q4_K_M"
val SKILLS_MODEL             = "qwen3.5:9b-q4_K_M"   // skills restructure

val FIT_THRESHOLD            = 50   // below this: tracked but not tailored
val DUPLICATE_WINDOW_DAYS    = 30   // dedup window: company × role × location
val GMAIL_MAX_EMAILS         = 3    // emails per batch run
```

---

## Gmail Search Query

The default query fetches emails from the last 7 days, from INBOX only, excluding already-processed or in-flight messages:

```
newer_than:7d in:inbox -label:JD_Not_Found -label:Recruiter_Response_Required -label:JD_Processing
```

Override `GMAIL_SEARCH_QUERY` in `.env` or `Config.kt` to target different senders or date ranges.

**Gmail labels applied by the pipeline:**

| Outcome | Label | Inbox Action |
|---|---|---|
| Submitted to bridge, awaiting worker | `JD_Processing` | Kept in INBOX |
| Recruiter draft created | `Recruiter_Response_Required` | Star, mark unread, keep in INBOX |
| Not a job posting | `JD_Not_Found` | Mark unread, keep in INBOX |
| Digest processed | `JD_Processed_Digest` | Archive |
| Job processed | `JD_Processed` | Archive |

---

## Skill Files (Prompt Templates)

All LLM prompts live in `src/main/resources/skills/` as `.md` files. Loaded at runtime — edit without recompiling.

| File | Node |
|---|---|
| `SCAN_SKILL.md` | ScanEmailNode — recruiter email extraction |
| `SCRAPE_SKILL.md` | ScrapeJdNode — job page structured extraction |
| `SCORE_SKILL.md` | ScoreFitNode — fit scoring + JD field extraction |
| `JD_EXTRACTION_SKILL.md` | JdExtractionNode |
| `GAP_ANALYSIS_SKILL.md` | GapAnalysisNode |
| `SUMMARY_REWRITE_SKILL.md` | SummaryRewriteNode |
| `BULLET_REWRITE_SKILL.md` | BulletRewriteNode |
| `SKILLS_RESTRUCTURE_SKILL.md` | SkillsRestructureNode |
| `ATS_SCORING_SKILL.md` | AtsScoringNode |
| `DRAFT_REPLY_SKILL.md` | CreateDraftReply — recruiter reply generation |

---

## Adapting for Your Own Job Search

1. **Initialize your profile** — Run `./gradlew run --args="--init-profile path/to/resume.pdf"` to generate your `candidate_profile.json` and personal `generated_resume.html`.
2. **Update `SCORE_SKILL.md`** — rewrite the scoring rubric to reflect your background and target roles.
3. **Tune `FIT_THRESHOLD`** — lower for more tailoring, raise to be more selective.
4. **Tune `DUPLICATE_WINDOW_DAYS`** — how far back to look when deduplicating.
5. **Update `GMAIL_SEARCH_QUERY`** — adjust to match your inbox structure.
6. **Update `DRAFT_REPLY_SKILL.md`** — personalize the recruiter reply tone and signature.

---

## Digest Fan-Out: Supported Platforms

The pipeline extracts individual jobs from digest emails sent by:

| Platform | Parser |
|---|---|
| LinkedIn | Line-block splitting on `---` separators |
| Glassdoor | HTML anchor scraping with salary extraction |
| Lensa | `<table>` card extraction |
| Monster | `<a strong>` element matching |
| JobLeads | `View job:` line extraction |
| JobRight | `jobright.ai/jobs/info/` URL extraction + match-percentage parsing |
| Welcome to the Jungle | SendGrid click URL extraction |

Max 25 jobs per digest. Each child job is independently scraped, deduplicated, scored, and — if fit qualifies — tailored.

---

## Recruiter Reply Draft Flow

For recruiter emails that complete the tailor path:

1. `DRAFT_REPLY_SKILL.md` template is filled with role, company, fit score, and strengths
2. LLM generates a reply (temp=0.3 for natural prose variation)
3. Recruiter email body is **sanitized** before reaching the LLM — lines matching prompt injection patterns are stripped
4. RFC 2822 MIME message built with threading headers (In-Reply-To, References)
5. Tailored resume PDF and cover letter attached
6. Gmail Draft created via Compose API
7. Original email labeled `Recruiter_Response_Required`, starred, marked unread

**Nothing is sent automatically.** The user reviews and sends the draft manually.

---

## Output Structure

```
output/
└── 20260405_143022_acme_corp_staff_sdet/
    ├── tailored_resume.html          # Tailored HTML (source for PDF)
    ├── YourName_Staff_SDET.pdf       # Playwright-rendered PDF
    ├── cover_letter.txt              # Cover letter
    ├── score_fit.txt                 # Fit score + reasoning
    ├── gap_analysis.json             # Skills gap table
    ├── tailored_summary.txt          # Rewritten professional summary
    ├── tailored_bullets.txt          # Rewritten experience bullets
    ├── restructured_skills.txt       # Reordered skills section
    └── ats_score.txt                 # ATS composite scorecard
```

For browser-triggered jobs, artifacts are also served by the bridge API at `GET /api/jobs/{id}/artifact/{filename}`.

---

## Testing

```bash
# Pipeline (Kotlin) — unit tests
cd services/job-fit-apply-ai-pipeline && ./gradlew test

# Bridge — unit + integration tests
cd services/job-fit-apply-ai-bridge && ./gradlew test

# Dashboard — unit tests
cd apps/job-fit-apply-ai-backlog && npm run test:unit

# Dashboard — E2E (Playwright, requires built app on :8080)
cd apps/job-fit-apply-ai-backlog && npm run test:e2e

# Extension
cd apps/job-fit-apply-ai-extension && npm test
```

CI runs all four test suites in parallel on every push to `main` and publishes a combined Allure report to GitHub Pages.

---

## Known Constraints

- **LinkedIn scraping requires a logged-in Chrome profile.** Set `CHROME_PROFILE_DIRECTORY` in `.env` to a Chrome profile already authenticated to LinkedIn.
- **Local LLM quality scales with model size.** The 6-node tailoring subgraph produces significantly better results with 70B+ models. Smaller models tend to hallucinate resume content.
- **The bridge and worker must be running before the cron jobs fire.** Use `pm2 start ecosystem.config.js` and `pm2 save` to ensure they survive reboots.
- **Tailscale is required for the extension → bridge connection by default.** To run on localhost instead, update `BRIDGE_API_URL` in `config.js`.
- **Gmail OAuth tokens expire.** Run `./gradlew run --args="--reauth"` to refresh. Use `--check-token` to verify status without triggering a full run.
- **Fit scores are LLM-generated and model-dependent.** Tune the scoring rubric in `SCORE_SKILL.md` until scores feel calibrated to your profile.
- **Draft replies are not sent automatically.** Review every draft in Gmail before sending.
