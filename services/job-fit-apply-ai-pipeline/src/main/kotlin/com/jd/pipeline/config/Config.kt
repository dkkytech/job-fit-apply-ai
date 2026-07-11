package com.jd.pipeline.config

import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Config - Central configuration for the JD pipeline.
 * All tunables live here; nodes import from this module.
 */
object Config {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private val PROJECT_DIR: Path = Paths.get(System.getProperty("user.dir"))

    // ── API Keys ────────────────────────────────────────────────────────────────
    val ANTHROPIC_API_KEY: String = get("ANTHROPIC_API_KEY", "")
    val LANGSMITH_API_KEY: String = get("LANGSMITH_API_KEY", "")
    val SUPABASE_PROJECT_URL: String = get("SUPABASE_PROJECT_URL", "")
    val SUPABASE_SERVICE_ROLE_KEY: String = get("SUPABASE_SERVICE_ROLE_KEY", get("SUPABASE_KEY", ""))
    val MINIMAX_API_KEY: String = get("MINIMAX_API_KEY", "")
    val GOOGLE_API_KEY: String = get("GOOGLE_API_KEY", "")
    val DEEPSEEK_API_KEY: String = get("DEEPSEEK_API_KEY", "")
    val JSEARCH_API_KEY: String = get("JSEARCH_API_KEY", "")

    // ── Ollama ───────────────────────────────────────────────────────────────────
    // Local Ollama endpoint (no-suffix model strings).
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
    // Best local: qwen3.5:9b-q4_K_M   Cloud: deepseek-v4-flash:ollama-cloud
    val SCAN_MODEL: String = get("SCAN_MODEL", "qwen3.5:9b-q4_K_M")
    val SCRAPE_MODEL: String = get("SCRAPE_MODEL", SCAN_MODEL)

    // Score: rubric-based fit scoring — needs chain-of-thought reasoning (thinking enabled)
    // Best local: qwen3.5:9b-q4_K_M   Cloud: deepseek-v4-pro:ollama-cloud
    val SCORE_MODEL: String = get("SCORE_MODEL", "qwen3.5:9b-q4_K_M")

    // Cover letter: prose writing quality — larger model pays off here
    // Best local: qwen3.5:9b-q4_K_M   Cloud: glm-5.1:ollama-cloud
    val COVER_LETTER_MODEL: String = get("COVER_LETTER_MODEL", "qwen3.5:9b-q4_K_M")

    // Draft reply: short recruiter email — any capable 7B is fine
    // Best local: qwen3.5:9b-q4_K_M   Cloud: deepseek-v4-flash:ollama-cloud
    val DRAFT_REPLY_MODEL: String = get("DRAFT_REPLY_MODEL", "qwen3.5:9b-q4_K_M")

    // Resume tailoring subgraph — reasoning (summary rewrite, bullet rewrite)
    // Creative (temp=0.4, thinking enabled): prose quality matters
    // Best local: qwen3.5:9b-q4_K_M   Cloud: glm-5.1:ollama-cloud
    val RESUME_REASONING_MODEL: String = get("RESUME_REASONING_MODEL", "qwen3.5:9b-q4_K_M")

    // Skills restructure: judgment-heavy (category ordering, JD phrasing match) but factually grounded.
    // temp=0.2 — enough flexibility to follow multi-constraint instructions without hallucinating skills.
    // Best local: qwen3.5:9b-q4_K_M   Cloud: deepseek-v4-pro:ollama-cloud
    val SKILLS_MODEL: String = get("SKILLS_MODEL", RESUME_REASONING_MODEL)

    // ── Scoring thresholds ───────────────────────────────────────────────────────
    val FIT_THRESHOLD: Float = get("FIT_THRESHOLD", "50").toFloat()
    // Duplicate detection: jobs seen within this window are considered duplicates.
    // Re-opened positions after the window expires are treated as new.
    val DUPLICATE_WINDOW_DAYS: Int = get("DUPLICATE_WINDOW_DAYS", "30").toInt()

    // ── Gmail ────────────────────────────────────────────────────────────────────
    val GMAIL_CREDENTIALS_FILE: String = get("GMAIL_CREDENTIALS_FILE", "gmail_credentials.json")
    val GMAIL_TOKEN_FILE: String = get("GMAIL_TOKEN_FILE", "tokens/gmail_token.json")
    val GMAIL_MAX_EMAILS: Int = get("GMAIL_MAX_EMAILS", "3").toInt()
    val GMAIL_SEARCH_QUERY: String = get("GMAIL_SEARCH_QUERY", "newer_than:7d in:inbox -label:JD_Not_Found -label:Recruiter_Response_Required -label:JD_Processing")

    // ── Skills paths ─────────────────────────────────────────────────────────
    val SKILLS_DIR: Path = PROJECT_DIR.resolve("src/main/resources/skills")
    val SCAN_SKILL: Path = SKILLS_DIR.resolve("SCAN_SKILL.md")
    val SCRAPE_SKILL: Path = SKILLS_DIR.resolve("SCRAPE_SKILL.md")
    val SCORE_SKILL: Path = SKILLS_DIR.resolve("SCORE_SKILL.md")
    // Resume tailoring subgraph skill files
    val JD_EXTRACTION_SKILL: Path = SKILLS_DIR.resolve("JD_EXTRACTION_SKILL.md")
    val GAP_ANALYSIS_SKILL: Path = SKILLS_DIR.resolve("GAP_ANALYSIS_SKILL.md")
    val SUMMARY_REWRITE_SKILL: Path = SKILLS_DIR.resolve("SUMMARY_REWRITE_SKILL.md")
    val BULLET_REWRITE_SKILL: Path = SKILLS_DIR.resolve("BULLET_REWRITE_SKILL.md")
    val SKILLS_RESTRUCTURE_SKILL: Path = SKILLS_DIR.resolve("SKILLS_RESTRUCTURE_SKILL.md")
    val ATS_SCORING_SKILL: Path = SKILLS_DIR.resolve("ATS_SCORING_SKILL.md")

    // ── ScanEmailTuner assets ────────────────────────────────────────────────
    val SCAN_EMAIL_TUNER_DIR: Path = PROJECT_DIR.resolve("tuner").resolve("scan-email-tuner")
    val SCAN_EMAIL_TUNER_DATASET_DIR: Path = SCAN_EMAIL_TUNER_DIR.resolve("data-set")
    val SCAN_EMAIL_TUNER_SKILL: Path = SCAN_EMAIL_TUNER_DIR.resolve("SCAN_EMAIL_TUNER_SKILL.md")

    // ── ScrapeJdUrlTuner assets ──────────────────────────────────────────────
    val SCRAPE_JD_URL_TUNER_DIR: Path = PROJECT_DIR.resolve("tuner").resolve("scrape-jd-url-tuner")
    val SCRAPE_JD_URL_TUNER_DATASET_DIR: Path = SCRAPE_JD_URL_TUNER_DIR.resolve("data-set")
    val SCRAPE_JD_URL_TUNER_SKILL: Path = SCRAPE_JD_URL_TUNER_DIR.resolve("SCRAPE_JD_URL_TUNER_SKILL.md")

    // ── Resume ───────────────────────────────────────────────────────────────────
    /** Generic, committed HTML template used by `--init-profile` to render a personal resume. */
    val BASE_RESUME_TEMPLATE_PATH: Path = PROJECT_DIR.resolve("src/main/resources/resume").resolve("base_resume.template.html")
    /** Personal HTML resume rendered from the candidate profile. Gitignored — produced by `--init-profile`. */
    val GENERATED_RESUME_HTML_PATH: Path = PROJECT_DIR.resolve("src/main/resources/resume").resolve("generated_resume.html")

    // ── User Profile ─────────────────────────────────────────────────────────────
    val CANDIDATE_PROFILE_PATH: Path = PROJECT_DIR.resolve("config").resolve("candidate_profile.json")

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
    val PLAYWRIGHT_HEADLESS: Boolean = get("PLAYWRIGHT_HEADLESS", "false").toBoolean()
    // When true, sites blocked by HTTP (403, CAPTCHA, Cloudflare) are retried with a clean Playwright session.
    val PLAYWRIGHT_FALLBACK_ON_CAPTCHA: Boolean = get("PLAYWRIGHT_FALLBACK_ON_CAPTCHA", "true").toBoolean()

    // ── Resume generation from DOCX/PDF ─────────────────────────────────────────
    // Needs strong instruction-following to replicate HTML structure from a template.
    // Best local: qwen3.5:9b-q4_K_M   Cloud: deepseek-v4-pro:ollama-cloud
    val RESUME_GEN_MODEL: String = get("RESUME_GEN_MODEL", "qwen3.5:9b-q4_K_M")
    val RESUME_GEN_SKILL: Path = SKILLS_DIR.resolve("RESUME_GEN_SKILL.md")

    // ── Candidate profile generation (--init-profile) ───────────────────────────
    /** Model for parsing a resume into a structured candidate profile. Defaults to RESUME_GEN_MODEL. */
    val PROFILE_GEN_MODEL: String = get("PROFILE_GEN_MODEL", RESUME_GEN_MODEL)
    val PROFILE_GEN_SKILL: Path = SKILLS_DIR.resolve("PROFILE_GEN_SKILL.md")
    /** Committed JSON template + schema reference for `candidate_profile.json`. */
    val CANDIDATE_PROFILE_TEMPLATE_PATH: Path = PROJECT_DIR.resolve("config").resolve("candidate_profile.template.json")
    /** Committed TAILOR_SKILL template with `{{CANDIDATE_CONTEXT}}` placeholder. */
    val TAILOR_SKILL_TEMPLATE_PATH: Path = SKILLS_DIR.resolve("TAILOR_SKILL.template.md")
    /** Rendered TAILOR_SKILL.md, gitignored — produced by `--init-profile`. */
    val TAILOR_SKILL_PATH: Path = SKILLS_DIR.resolve("TAILOR_SKILL.md")

    // ── Notifications (Discord + Telegram) ───────────────────────────────────────
    val DISCORD_BOT_TOKEN: String  = get("DISCORD_BOT_TOKEN", "")
    val DISCORD_CHANNEL_ID: String = get("DISCORD_CHANNEL_ID", "")
    val TELEGRAM_BOT_TOKEN: String = get("TELEGRAM_BOT_TOKEN", "")
    val TELEGRAM_CHAT_ID: String   = get("TELEGRAM_CHAT_ID", "")
    val NOTIFICATION_FIT_THRESHOLD: Int = get("NOTIFICATION_FIT_THRESHOLD", "50").toInt()

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
