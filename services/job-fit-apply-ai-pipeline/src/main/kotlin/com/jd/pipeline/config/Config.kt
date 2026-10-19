package com.jd.pipeline.config

import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Config - Central configuration for the JD pipeline.
 * All tunables live here; nodes import from this module.
 */
object Config {
    // Load .env if present. In a container, a bind-mount whose host source doesn't exist
    // shows up as a DIRECTORY at the mount path, which dotenv can't read ("Is a directory")
    // — ignoreIfMissing only covers absence, not that. Treat any load failure like a missing
    // file: real env vars (compose `environment:`) + defaults still apply via get().
    private val DOTENV: Dotenv = run {
        val name = System.getProperty("dotenv.file", ".env")
        try {
            Dotenv.configure().filename(name).ignoreIfMissing().ignoreIfMalformed().load()
        } catch (e: Exception) {
            System.err.println("[config] .env not loaded ($name: ${e.message}) — using environment + defaults")
            Dotenv.configure()
                .directory(System.getProperty("java.io.tmpdir"))
                .filename("jd-pipeline-nonexistent.env")
                .ignoreIfMissing()
                .load()
        }
    }

    private val PROJECT_DIR: Path = Paths.get(System.getProperty("user.dir"))

    // ── API Keys ────────────────────────────────────────────────────────────────
    val ANTHROPIC_API_KEY: String = get("ANTHROPIC_API_KEY", "")
    val LANGSMITH_API_KEY: String = get("LANGSMITH_API_KEY", "")
    val SUPABASE_PROJECT_URL: String = get("SUPABASE_PROJECT_URL", "")
    val SUPABASE_SERVICE_ROLE_KEY: String = get("SUPABASE_SERVICE_ROLE_KEY", get("SUPABASE_KEY", ""))

    // LLM concurrency gate (per physical resource). Local backends (oMLX + Ollama-local)
    // share ONE permit; cloud backends get a larger pool. See client/LlmGate.kt.
    val LOCAL_LLM_MAX_CONCURRENCY: Int = get("LOCAL_LLM_MAX_CONCURRENCY", "1").toInt()
    val CLOUD_LLM_MAX_CONCURRENCY: Int = get("CLOUD_LLM_MAX_CONCURRENCY", "4").toInt()

    // Database backend selection (Supabase → self-hosted Postgres migration).
    //   DB_BACKEND=supabase → SupabaseClient (REST/PostgREST, default)
    //   DB_BACKEND=postgres → PostgresGateway (direct JDBC to the container)
    val DB_BACKEND: String = get("DB_BACKEND", "supabase")
    // libpq-style URL; containers use host "db", host apps use "localhost".
    val DATABASE_URL: String = get("DATABASE_URL", "postgresql://jobfit:jobfit@localhost:5432/jobfit")
    val MINIMAX_API_KEY: String = get("MINIMAX_API_KEY", "")
    val GOOGLE_API_KEY: String = get("GOOGLE_API_KEY", "")
    val DEEPSEEK_API_KEY: String = get("DEEPSEEK_API_KEY", "")

    // ── oMLX (local MLX inference, OpenAI-compatible) ──────────────────────────────
    // Default backend for no-suffix model strings. Served by oMLX on this host.
    val MLX_LOCAL_BASE_URL: String = get("MLX_LOCAL_BASE_URL", "http://127.0.0.1:11436/v1")
    val MLX_API_KEY: String = get("MLX_API_KEY", "11436")

    // ── Ollama ───────────────────────────────────────────────────────────────────
    // Local Ollama endpoint (":ollama-local" suffix escape hatch).
    val OLLAMA_LOCAL_BASE_URL: String = get("OLLAMA_LOCAL_BASE_URL", "http://localhost:11434")
    // Ollama Cloud endpoint (:ollama-cloud suffix model strings).
    val OLLAMA_CLOUD_BASE_URL: String = get("OLLAMA_CLOUD_BASE_URL", "https://ollama.com")
    // Bearer token for Ollama Cloud. Leave empty for local-only pipelines.
    val OLLAMA_API_KEY: String = get("OLLAMA_API_KEY", "")

    // ── MiniMax cloud ─────────────────────────────────────────────────────────────
    val MINIMAX_BASE_URL: String = get("MINIMAX_BASE_URL", "https://api.minimaxi.chat/v1")

    // ── DeepSeek ─────────────────────────────────────────────────────────────────
    val DEEPSEEK_BASE_URL: String = get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")

    // ── Model configuration ───────────────────────────────────────────────────────
    // Scan / scrape: structured JSON extraction — small, fast, deterministic (temp=0)
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: deepseek-v4-flash:ollama-cloud
    val SCAN_MODEL: String = get("SCAN_MODEL", "Qwen3.5-9B-OptiQ-4bit")
    val SCRAPE_MODEL: String = get("SCRAPE_MODEL", SCAN_MODEL)

    // Score: rubric-based fit scoring — needs chain-of-thought reasoning (thinking enabled)
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: deepseek-v4-pro:ollama-cloud
    val SCORE_MODEL: String = get("SCORE_MODEL", "Qwen3.5-9B-OptiQ-4bit")

    // When the cloud LLM quota is used up, the ingest/score lane (scan, digest, scrape, score_fit)
    // switches to this local oMLX model instead of failing; tailoring stays cloud-only. Blank = off.
    val LOCAL_FALLBACK_MODEL: String = get("LOCAL_FALLBACK_MODEL", "")
    // Local models are slower than the cloud ones: the fallback gets at least this much time per call.
    val LOCAL_FALLBACK_TIMEOUT_SECONDS: Long = get("LOCAL_FALLBACK_TIMEOUT_SECONDS", "300").toLongOrNull() ?: 300L
    // After a quota refusal, skip the cloud for this long before trying it again.
    /** Deferred tailoring: TAILOR_REQUESTs the release sweep keeps in flight once the quota is back. */
    val DEFERRED_RELEASE_BATCH: Int = get("DEFERRED_RELEASE_BATCH", "2").toIntOrNull() ?: 2
    /** How often the processor runs the release sweep (between jobs). */
    val DEFERRED_RELEASE_INTERVAL_MINUTES: Long = get("DEFERRED_RELEASE_INTERVAL_MINUTES", "15").toLongOrNull() ?: 15L
    /** A deferred job not tailored within this many days expires with a "tailor by hand" alert. */
    val DEFERRED_MAX_AGE_DAYS: Int = get("DEFERRED_MAX_AGE_DAYS", "5").toIntOrNull() ?: 5
    val CLOUD_QUOTA_RECHECK_MINUTES: Long = get("CLOUD_QUOTA_RECHECK_MINUTES", "30").toLongOrNull() ?: 30L

    // Cover letter: prose writing quality — a dedicated prose model pays off here
    // Best local (oMLX): gemma-4-12B-it-qat-4bit   Cloud: glm-5.1:ollama-cloud
    val COVER_LETTER_MODEL: String = get("COVER_LETTER_MODEL", "gemma-4-12B-it-qat-4bit")

    // Draft reply: short recruiter email — any capable small model is fine
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: deepseek-v4-flash:ollama-cloud
    val DRAFT_REPLY_MODEL: String = get("DRAFT_REPLY_MODEL", "Qwen3.5-9B-OptiQ-4bit")

    // Resume tailoring subgraph — reasoning (summary rewrite, bullet rewrite)
    // Creative (temp=0.4): prose quality matters
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: glm-5.1:ollama-cloud
    val RESUME_REASONING_MODEL: String = get("RESUME_REASONING_MODEL", "Qwen3.5-9B-OptiQ-4bit")
    // When false (default), /no_think is prepended to qwen3 reasoning calls to avoid 300s+ thinking timeouts.
    // Set to true only if the local model is fast enough to finish thinking within the 300s timeout.
    val RESUME_REASONING_THINKING: Boolean = get("RESUME_REASONING_THINKING", "false").toBoolean()

    // Skills restructure: judgment-heavy (category ordering, JD phrasing match) but factually grounded.
    // temp=0.2 — enough flexibility to follow multi-constraint instructions without hallucinating skills.
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: deepseek-v4-pro:ollama-cloud
    val SKILLS_MODEL: String = get("SKILLS_MODEL", RESUME_REASONING_MODEL)

    // ── Scoring thresholds ───────────────────────────────────────────────────────
    val FIT_THRESHOLD: Float = get("FIT_THRESHOLD", "50").toFloat()
    /**
     * Fits at or above this are tailored automatically. Non-recruiter fits from FIT_THRESHOLD up to
     * it are scored and OFFERed instead — pinged with a Tailor button, tailored only on a tap — so
     * the expensive tailoring models are spent on the best fits. Blank means FIT_THRESHOLD: every
     * fit is tailored, as before on-demand tailoring existed. Recruiter emails are always tailored.
     */
    val AUTO_TAILOR_MIN_SCORE: Float = get("AUTO_TAILOR_MIN_SCORE", "").toFloatOrNull() ?: FIT_THRESHOLD
    // ATS refinement pass: when the validation report is actionable (missing supported
    // must-haves, leaked unsupported terms, doubled words) or the overall score lands below
    // the threshold, the subgraph re-runs the rewrite nodes once with the concrete gap list
    // in the prompts and keeps the better-scoring pass. Adds up to one extra rewrite/score
    // round per job (~several minutes on local models) — disable for latency-sensitive runs.
    val ATS_REFINE_ENABLED: Boolean = get("ATS_REFINE_ENABLED", "true").toBoolean()
    val ATS_REFINE_THRESHOLD: Int = get("ATS_REFINE_THRESHOLD", "80").toInt()
    // Bullet caps applied by the deterministic reorder node (recruiter-skim readability):
    // the most recent role keeps up to CAP_RECENT bullets, older roles CAP_OLDER. The
    // lowest-scoring bullets are dropped whole — text is never truncated.
    val TAILOR_BULLET_CAP_ENABLED: Boolean = get("TAILOR_BULLET_CAP_ENABLED", "true").toBoolean()
    val TAILOR_BULLET_CAP_RECENT: Int = get("TAILOR_BULLET_CAP_RECENT", "6").toInt()
    val TAILOR_BULLET_CAP_OLDER: Int = get("TAILOR_BULLET_CAP_OLDER", "3").toInt()
    // Duplicate detection: jobs seen within this window are considered duplicates.
    // Re-opened positions after the window expires are treated as new.
    val DUPLICATE_WINDOW_DAYS: Int = get("DUPLICATE_WINDOW_DAYS", "30").toInt()

    // Gmail intake/auth moved to the Poller service (Phase 1) — no Gmail config here.

    // ── Skills paths ─────────────────────────────────────────────────────────
    val SKILLS_DIR: Path = PROJECT_DIR.resolve("src/main/resources/skills")
    val SCAN_SKILL: Path = SKILLS_DIR.resolve("SCAN_SKILL.md")
    val SCRAPE_SKILL: Path = SKILLS_DIR.resolve("SCRAPE_SKILL.md")
    val SCORE_SKILL: Path = SKILLS_DIR.resolve("SCORE_SKILL.md")
    // Resume tailoring subgraph skill files — all under skills/tailor/, sharing one rubric
    val TAILOR_SKILLS_DIR: Path = SKILLS_DIR.resolve("tailor")
    /** Shared ATS/recruiter/HM-depth/integrity rules prepended to every tailor node prompt. */
    val RESUME_RUBRIC: Path = TAILOR_SKILLS_DIR.resolve("RESUME_RUBRIC.md")
    val JD_EXTRACTION_SKILL: Path = TAILOR_SKILLS_DIR.resolve("JD_EXTRACTION_SKILL.md")
    val GAP_ANALYSIS_SKILL: Path = TAILOR_SKILLS_DIR.resolve("GAP_ANALYSIS_SKILL.md")
    val SUMMARY_REWRITE_SKILL: Path = TAILOR_SKILLS_DIR.resolve("SUMMARY_REWRITE_SKILL.md")
    val BULLET_REWRITE_SKILL: Path = TAILOR_SKILLS_DIR.resolve("BULLET_REWRITE_SKILL.md")
    val SKILLS_RESTRUCTURE_SKILL: Path = TAILOR_SKILLS_DIR.resolve("SKILLS_RESTRUCTURE_SKILL.md")
    val ATS_VALIDATION_SKILL: Path = TAILOR_SKILLS_DIR.resolve("ATS_VALIDATION_SKILL.md")

    // ── ScanEmailTuner assets ────────────────────────────────────────────────
    val SCAN_EMAIL_TUNER_DIR: Path = PROJECT_DIR.resolve("tuner").resolve("scan-email-tuner")
    val SCAN_EMAIL_TUNER_DATASET_DIR: Path = SCAN_EMAIL_TUNER_DIR.resolve("data-set")
    val SCAN_EMAIL_TUNER_SKILL: Path = SCAN_EMAIL_TUNER_DIR.resolve("SCAN_EMAIL_TUNER_SKILL.md")

    // ── ScrapeJdUrlTuner assets ──────────────────────────────────────────────
    val SCRAPE_JD_URL_TUNER_DIR: Path = PROJECT_DIR.resolve("tuner").resolve("scrape-jd-url-tuner")
    val SCRAPE_JD_URL_TUNER_DATASET_DIR: Path = SCRAPE_JD_URL_TUNER_DIR.resolve("data-set")
    val SCRAPE_JD_URL_TUNER_SKILL: Path = SCRAPE_JD_URL_TUNER_DIR.resolve("SCRAPE_JD_URL_TUNER_SKILL.md")

    // ── Resume ───────────────────────────────────────────────────────────────────
    /** Canonical, candidate-authored résumé (structured YAML) — the source of all résumé content.
     *  Gitignored (personal). Override with the RESUME_YAML_PATH env var to point elsewhere
     *  (e.g. a private profile repo checkout); unset falls back to the in-tree path. */
    val RESUME_YAML_PATH: Path =
        resolveProfilePath(
            get("RESUME_YAML_PATH", ""),
            PROJECT_DIR.resolve("src/main/resources/resume").resolve("resume.yaml"),
        )
    /** Committed example résumé YAML — reference + `--init-profile` starting point. */
    val RESUME_YAML_TEMPLATE_PATH: Path = PROJECT_DIR.resolve("src/main/resources/resume").resolve("resume.template.yaml")
    /** Committed HTML head+CSS skeleton (with a `<!-- RESUME_BODY -->` sentinel) the deterministic renderer fills in. */
    val BASE_RESUME_TEMPLATE_PATH: Path = PROJECT_DIR.resolve("src/main/resources/resume").resolve("base_resume.template.html")
    /** Personal HTML résumé rendered from resume.yaml. Gitignored — produced by `--init-profile` / `--resume-gen`. */
    val GENERATED_RESUME_HTML_PATH: Path = PROJECT_DIR.resolve("src/main/resources/resume").resolve("generated_resume.html")

    // ── Resume PDF (YAML → LaTeX → PDF via yaml_to_tex.py + tectonic) ────────────
    /** Python interpreter used to run yaml_to_tex.py (needs pyyaml + jinja2 on its path). */
    val PYTHON_BIN: String = get("PYTHON_BIN", "python3")
    /** The YAML→TeX renderer script; compiles to PDF with tectonic when run with --pdf. */
    val YAML_TO_TEX_SCRIPT: Path = get("YAML_TO_TEX_SCRIPT", "").let { override ->
        if (override.isNotBlank()) Paths.get(override)
        else PROJECT_DIR.resolve("src/main/resources/resume").resolve("yaml_to_tex.py")
    }
    /** Hard cap on one render+compile run (tectonic is seconds once its cache is warm). */
    val RENDER_PDF_TIMEOUT_MS: Long = get("RENDER_PDF_TIMEOUT_MS", "180000").toLong()

    // ── User Profile ─────────────────────────────────────────────────────────────
    /** Slim pipeline config (preferences + scoring aids). Gitignored — produced by `--init-profile`.
     *  Override with the CANDIDATE_PROFILE_YAML_PATH env var to point elsewhere (e.g. a private
     *  profile repo checkout); unset falls back to the in-tree path. */
    val CANDIDATE_PROFILE_PATH: Path =
        resolveProfilePath(
            get("CANDIDATE_PROFILE_YAML_PATH", ""),
            PROJECT_DIR.resolve("config").resolve("candidate_profile.yaml"),
        )

    // ── Output ───────────────────────────────────────────────────────────────────
    val OUTPUT_DIR: Path = PROJECT_DIR.resolve("output")

    // ── Playwright / Chrome ──────────────────────────────────────────────────────
    val PLAYWRIGHT_TIMEOUT_MS: Double = get("PLAYWRIGHT_TIMEOUT_MS", "45000").toDouble()
    val CHROME_EXECUTABLE_PATH: String = get(
        "CHROME_EXECUTABLE_PATH",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    )
    val CHROME_USER_DATA_DIR: String = get(
        "CHROME_USER_DATA_DIR",
        Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Google", "Chrome").toString()
    )
    val CHROME_PROFILE_DIRECTORY: String = get("CHROME_PROFILE_DIRECTORY", "Default")
    // CDP (Chrome DevTools Protocol) connection to a long-lived, user-launched Chrome.
    // When set (e.g. http://localhost:9222), the scraper connects to that already-running
    // Chrome and reuses its logged-in profile + warm session instead of launching a fresh,
    // profile-copied browser per job. Empty = disabled (legacy per-job launch path).
    // Launch the target Chrome with scripts/launch-chrome-cdp.sh.
    val CHROME_CDP_ENDPOINT: String = get("CHROME_CDP_ENDPOINT", "")
    // Remote-debugging port the launch script opens; also used to surface a helpful endpoint hint.
    val CHROME_DEBUG_PORT: String = get("CHROME_DEBUG_PORT", "9222")
    // Dedicated user-data-dir for the CDP debug Chrome (used by scripts/launch-chrome-cdp.sh).
    // Must NOT be the Default profile — current Chrome refuses remote debugging on the default dir
    // ("DevTools remote debugging requires a non-default data directory"). This profile can run
    // alongside your everyday Chrome; sign into the job boards in it once (the login persists here).
    val CHROME_CDP_USER_DATA_DIR: String = get(
        "CHROME_CDP_USER_DATA_DIR",
        Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Google", "Chrome-CDP").toString()
    )
    // Comma-separated domains that skip the HTTP fetch and scrape via the CDP browser directly
    // (proactive — for sites that soft-block or challenge plain HTTP, e.g. Glassdoor's Cloudflare).
    // Suffix match (a domain also matches its subdomains). Requires the debug Chrome to be up.
    val CDP_FORCE_DOMAINS: String = get("CDP_FORCE_DOMAINS", "")
    // How long a site stays skipped after a site-wide block (403 / 429 / bot check) or an auth wall.
    // The Processor is one long-lived loop, so without a lapse a single block used to skip that site
    // until the next restart (Dice was skipped for 12 days in September). Default 30 min.
    val SCRAPE_DOMAIN_SKIP_TTL_MS: Long = get("SCRAPE_DOMAIN_SKIP_TTL_MS", "1800000").toLong()
    // An alert with a dedup key (e.g. "Sign-in required: LinkedIn") repeats at most this often while
    // the condition persists. It used to fire once per process lifetime. Default 6 h.
    val ALERT_REPEAT_AFTER_MS: Long = get("ALERT_REPEAT_AFTER_MS", "21600000").toLong()
    val PLAYWRIGHT_HEADLESS: Boolean = get("PLAYWRIGHT_HEADLESS", "false").toBoolean()
    // When true, sites blocked by HTTP (403, CAPTCHA, Cloudflare) are retried with a clean Playwright session.
    val PLAYWRIGHT_FALLBACK_ON_CAPTCHA: Boolean = get("PLAYWRIGHT_FALLBACK_ON_CAPTCHA", "true").toBoolean()
    // When true, pages that return fewer than PLAYWRIGHT_FALLBACK_MIN_CONTENT_LENGTH chars via HTTP
    // (JS-rendered SPAs like Jobright/Next.js) are retried with a clean Playwright session.
    val PLAYWRIGHT_FALLBACK_ON_THIN_CONTENT: Boolean = get("PLAYWRIGHT_FALLBACK_ON_THIN_CONTENT", "true").toBoolean()

    // Job links come from untrusted email and from POST /api/urls, so the scraper refuses private
    // network targets (localhost, the Docker host, metadata endpoints, tailnet devices). Only the
    // e2e slice turns this on — it serves its test job page from the host.
    val SCRAPE_ALLOW_PRIVATE_HOSTS: Boolean = get("SCRAPE_ALLOW_PRIVATE_HOSTS", "false").toBoolean()
    val PLAYWRIGHT_FALLBACK_MIN_CONTENT_LENGTH: Int = get("PLAYWRIGHT_FALLBACK_MIN_CONTENT_LENGTH", "500").toInt()

    // ── Steel Browser (self-hosted) ───────────────────────────────────────────────
    // When set (e.g. http://steel:3000), the scraper drives a self-hosted Steel Browser instead
    // of a raw host Chrome: it creates a Steel session, connects Playwright over the session's CDP
    // websocket, and persists the logged-in cookies via Steel's sessionContext. Empty = disabled →
    // falls back to CHROME_CDP_ENDPOINT (raw host Chrome). This is the Steel on/off flag; keep it in
    // the processor's .env so it can be blanked to fall back without editing compose.
    val STEEL_BASE_URL: String = get("STEEL_BASE_URL", "")
    // Tailnet-reachable Steel base used in the re-auth alert's interactive debug link (phone
    // sign-in), e.g. http://<tailscale-host>:3000. Falls back to STEEL_BASE_URL when blank.
    val STEEL_UI_URL: String = get("STEEL_UI_URL", "")
    // Persisted cookies+localStorage (Steel sessionContext), exported on close and injected on
    // session create, so the logged-in session survives Steel container / profile restarts.
    val STEEL_STORAGE_STATE_PATH: String = get(
        "STEEL_STORAGE_STATE_PATH",
        PROJECT_DIR.resolve("state").resolve("steel-storage-state.json").toString()
    )
    // Steel session idle timeout (ms); the browser session is released after this long unused.
    val STEEL_SESSION_TIMEOUT_MS: Long = get("STEEL_SESSION_TIMEOUT_MS", "600000").toLong()
    // Cooldown (ms) between connect attempts after a failed one. A down/wedged Steel backend is
    // re-probed at most once per cooldown (not hammered every scrape), but is NOT latched off for
    // the whole batch — so once the watchdog restarts the container, scraping resumes on the next
    // attempt past the cooldown with no pipeline restart. Default 30s (< the 120s watchdog cycle).
    val STEEL_RECONNECT_COOLDOWN_MS: Long = get("STEEL_RECONNECT_COOLDOWN_MS", "30000").toLong()
    // In-scrape connect retries before a connect attempt is given up and the cooldown is armed. A
    // transient createSession 500 (a SingletonLock race while Chrome relaunches) usually clears within
    // a second, so a couple of quick retries recover the current job instead of dropping it to thin
    // email-only JD text. Each attempt is still bounded by the createSession HTTP timeout. Default 3.
    val STEEL_CONNECT_MAX_ATTEMPTS: Int = get("STEEL_CONNECT_MAX_ATTEMPTS", "3").toInt()
    // Base backoff (ms) between in-scrape connect retries; doubled each retry (500 → 1000 → 2000…).
    val STEEL_CONNECT_BACKOFF_BASE_MS: Long = get("STEEL_CONNECT_BACKOFF_BASE_MS", "500").toLong()
    // Circuit breaker: after this many consecutive failed connect *cycles* (each already exhausted its
    // in-scrape retries), the backend is treated as wedged — the breaker opens and the cooldown widens
    // to STEEL_CIRCUIT_OPEN_COOLDOWN_MS so the pipeline stops re-probing a dead backend on every job
    // while the host steel-watchdog restarts the container. Reset to closed on the next good connect.
    val STEEL_CIRCUIT_BREAKER_THRESHOLD: Int = get("STEEL_CIRCUIT_BREAKER_THRESHOLD", "3").toInt()
    // Widened cooldown (ms) while the circuit breaker is open. Default 120s — one full watchdog cycle,
    // long enough for the host watchdog to `docker restart` the wedged Steel container before we re-probe.
    val STEEL_CIRCUIT_OPEN_COOLDOWN_MS: Long = get("STEEL_CIRCUIT_OPEN_COOLDOWN_MS", "120000").toLong()
    // How long an interactive sign-in session ([SteelSigninSession]) stays open. Sized for human
    // latency, not scrape latency: the user has to notice the alert, pick up a phone, and get through
    // an SSO round-trip. Much longer than STEEL_SESSION_TIMEOUT_MS on purpose — that 10-min scrape
    // window is why a re-auth link is usually dead by the time it's tapped. Default 30 min.
    val STEEL_SIGNIN_WINDOW_MS: Long = get("STEEL_SIGNIN_WINDOW_MS", "1800000").toLong()
    // How often a sign-in session exports and merges its cookies. Every poll persists, so the sign-in
    // survives a closed tab or an expired window; this is just how much of the tail can be lost.
    // Default 10s — cheap next to a 30-min window.
    val STEEL_SIGNIN_POLL_MS: Long = get("STEEL_SIGNIN_POLL_MS", "10000").toLong()
    // Tap-to-sign-in endpoint (SigninServer), hosted in the Processor loop. The re-auth alert links
    // here instead of at a live scrape session, because that session is released at batch close and
    // is almost always gone by the time a human taps the link.
    // Port and bind address. Loopback by default — the endpoint creates browser sessions and hands
    // out an UNAUTHENTICATED live view, so treat it exactly like the steel service's own port: set
    // this to the host's tailnet IP to reach it from a phone, never 0.0.0.0.
    val STEEL_SIGNIN_PORT: Int = get("STEEL_SIGNIN_PORT", "3100").toInt()
    val STEEL_SIGNIN_BIND_ADDR: String = get("STEEL_SIGNIN_BIND_ADDR", "127.0.0.1")
    // Tailnet-reachable base for the link put in alerts, e.g. http://<tailscale-host>:3100. Blank
    // (default) means the endpoint is neither started nor advertised, and re-auth alerts fall back to
    // the old short-lived debug link — so this single value is the on/off switch.
    val STEEL_SIGNIN_PUBLIC_URL: String = get("STEEL_SIGNIN_PUBLIC_URL", "")
    // Optional shared secret. When set, `?token=` is required on every request and is included in the
    // alert link. Worth setting even on a tailnet: unlike the Steel UI, this endpoint *creates*
    // sessions, so an accidental fetch of a bare URL is enough to take the browser for 30 minutes.
    val STEEL_SIGNIN_TOKEN: String = get("STEEL_SIGNIN_TOKEN", "")

    // ── Candidate profile / résumé onboarding (--init-profile) ──────────────────
    // Résumé HTML is now rendered deterministically from resume.yaml (no LLM), and the
    // profile is authored as structured YAML — so RESUME_GEN / PROFILE_GEN models are gone.
    /** Committed slim YAML template for `candidate_profile.yaml` (scoring aids + preferences). */
    val CANDIDATE_PROFILE_TEMPLATE_PATH: Path = PROJECT_DIR.resolve("config").resolve("candidate_profile.template.yaml")

    // ── Digest LLM fallback ───────────────────────────────────────────────────────
    val DIGEST_LLM_FALLBACK_ENABLED: Boolean = get("DIGEST_LLM_FALLBACK_ENABLED", "true").toBoolean()
    val DIGEST_SKILL: Path = SKILLS_DIR.resolve("DIGEST_SKILL.md")

    // ── Notifications (Discord + Telegram) ───────────────────────────────────────
    val DISCORD_BOT_TOKEN: String  = get("DISCORD_BOT_TOKEN", "")
    val DISCORD_CHANNEL_ID: String = get("DISCORD_CHANNEL_ID", "")
    val TELEGRAM_BOT_TOKEN: String = get("TELEGRAM_BOT_TOKEN", "")
    val TELEGRAM_CHAT_ID: String   = get("TELEGRAM_CHAT_ID", "")

    /** API hosts, overridable so tests/e2e can point at a local sink. */
    val DISCORD_API_BASE: String  = get("DISCORD_API_BASE", "https://discord.com")
    val TELEGRAM_API_BASE: String = get("TELEGRAM_API_BASE", "https://api.telegram.org")

    // ── Liveness (container healthcheck) ─────────────────────────────────────────
    // The processor loop touches HEARTBEAT_FILE each iteration; `--health` exits 0 when it is
    // fresher than HEALTH_MAX_AGE_MIN. Generous window: the loop only beats between jobs, and a
    // queue of local-LLM jobs can hold one iteration for tens of minutes.
    val HEARTBEAT_FILE: String = get("HEARTBEAT_FILE", "/tmp/processor-heartbeat")
    val HEALTH_MAX_AGE_MIN: Long = get("HEALTH_MAX_AGE_MIN", "90").toLong()

    // ── Artifacts URL (Tailscale file server) ──────────────────────────────────────
    val ARTIFACT_BASE_URL: String = get("ARTIFACT_BASE_URL", "")
    // Local directory corresponding to the server's URL root (e.g., /path/to/your/markserv/output).
    val ARTIFACT_BASE_PATH: String = get("ARTIFACT_BASE_PATH", "")

    /**
     * Get environment variable with default.
     */
    private fun get(key: String, defaultValue: String): String {
        val value = System.getenv(key)
        return if (value.isNullOrEmpty()) {
            DOTENV[key] ?: defaultValue
        } else {
            value
        }
    }

    /**
     * Check if a required API key is configured.
     */
    fun hasApiKey(key: String): Boolean = !get(key, "").isEmpty()

    /**
     * Get required config or throw.
     */
    fun require(key: String): String {
        val value = get(key, "")
        require(value.isNotEmpty()) { "Required config missing: $key" }
        return value
    }
}

/**
 * Resolve a profile file path: an explicit `override` wins, otherwise `defaultPath`.
 *
 * Pure and dependency-injected so the precedence rule is directly testable — the
 * `Config.*` properties themselves are cached at object-init and read the process env,
 * which a test cannot vary. A blank (or whitespace-only) override is treated as unset,
 * matching `Config.get()`'s empty-value handling.
 */
internal fun resolveProfilePath(override: String?, defaultPath: Path): Path =
    if (override.isNullOrBlank()) defaultPath else Paths.get(override.trim())
