package com.jd.jobbot.config

import io.github.cdimascio.dotenv.Dotenv

/**
 * jobbot-mcp configuration: dotenv first, then the process environment (same pattern as the
 * other services). Compose passes every value explicitly, so the defaults here are for local runs.
 */
object Config {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private fun get(key: String, default: String): String =
        DOTENV.get(key)?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

    private fun list(key: String, default: String): List<String> =
        get(key, default).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val PORT: Int = get("JOBBOT_MCP_PORT", "8790").toInt()

    /** Host header values accepted on /mcp (DNS-rebinding guard). The compose service name. */
    val ALLOWED_HOSTS: List<String> = list("JOBBOT_MCP_ALLOWED_HOSTS", "jobbot-mcp,localhost,127.0.0.1")

    /**
     * Bearer token the Hermes container presents. The /plugin routes (deterministic tap handling) and /mcp
     * (model tools) both require it — nothing else on the jobbot network should drive them.
     */
    val API_TOKEN: String = get("JOBBOT_MCP_TOKEN", "")

    val BRIDGE_URL: String = get("JD_BRIDGE_URL", "http://127.0.0.1:8765")

    /** Telegram user ids allowed to tap action buttons. */
    val ALLOWED_USERS: Set<String> = list("JOBBOT_TELEGRAM_ALLOWED_USERS", "").toSet()

    /** Verbs this deployment handles. A tap on any other verb is refused, not guessed at. */
    val HANDLED_VERBS: Set<String> = list("JOBBOT_ACTIONS", "apply").map { it.lowercase() }.toSet()

    /** Validate and log taps, but take no action and wake no agent. */
    val DRY_RUN: Boolean = get("JOBBOT_DRY_RUN", "false").equals("true", ignoreCase = true)

    /** Cards older than this stop accepting taps (every button, Undo included). */
    val CARD_TTL_DAYS: Long = get("JOBBOT_CARD_TTL_DAYS", "7").toLong()

    val STATE_DIR: String = get("JOBBOT_STATE_DIR", "./state")
    val OUTPUT_ROOT: String = get("JOBBOT_OUTPUT_ROOT", "/jfaa/pipeline-output")
    val RESUME_YAML: String = get("JOBBOT_RESUME_YAML", "/jfaa/profile/resume.yaml")
    val CANDIDATE_PROFILE_YAML: String = get("JOBBOT_CANDIDATE_PROFILE_YAML", "/jfaa/profile/candidate_profile.yaml")

    /**
     * The poller's Gmail token and OAuth client, mounted read-only from poller-secrets. Blank
     * disables Gmail (archive/undo and get_job_email). jobbot-mcp never writes or re-auths these.
     */
    val GMAIL_TOKEN_FILE: String = get("JOBBOT_GMAIL_TOKEN_FILE", "")
    val GMAIL_CREDENTIALS_FILE: String = get("JOBBOT_GMAIL_CREDENTIALS_FILE", "")

    /** Kill switch for sending replies. Off until a send has been verified end to end. */
    val SEND_ENABLED: Boolean = get("JOBBOT_SEND_ENABLED", "false").equals("true", ignoreCase = true)
    val MAX_SENDS_PER_DAY: Int = get("JOBBOT_MAX_SENDS_PER_DAY", "10").toInt()

    // ── Apply (Phase 4) ─────────────────────────────────────────────────────────
    /** Off = an Apply tap answers "Not implemented yet" (Q4). */
    val APPLY_ENABLED: Boolean = get("JOBBOT_APPLY_ENABLED", "false").equals("true", ignoreCase = true)
    /** JobBot's browser (the jobbot-browser container) over CDP. The JOBBOT_APPLY_* names are the old ones. */
    val BROWSER_CDP_URL: String = get("JOBBOT_BROWSER_CDP_URL", get("JOBBOT_APPLY_CDP_URL", "http://jobbot-browser:9223"))
    /** Where Richard watches and takes over (tailnet URL of the jobbot-browser viewer). */
    val BROWSER_VIEWER_URL: String = get("JOBBOT_BROWSER_VIEWER_URL", get("JOBBOT_APPLY_VIEWER_URL", ""))
    val APPLY_REVIEW_TTL_HOURS: Long = get("JOBBOT_APPLY_REVIEW_TTL_HOURS", "4").toLong()
    val APPLY_REMINDER_HOURS: Long = get("JOBBOT_APPLY_REMINDER_HOURS", "3").toLong()
    /** The identity every site account uses. */
    val ACCOUNT_EMAIL: String = get("JOBBOT_ACCOUNT_EMAIL", "dkkytech@gmail.com")
    val CREDENTIALS_FILE: String = get("JOBBOT_CREDENTIALS_FILE", "/secrets/site-credentials.json")
    /** The fill loop's model: the same Ollama route and model family as the agent. */
    val LLM_URL: String = get("JOBBOT_LLM_URL", "http://host.docker.internal:11434/v1")
    val FILL_MODEL: String = get("JOBBOT_FILL_MODEL", get("JOBBOT_MODEL", "deepseek-v4.1-flash:cloud"))

    /** The notifier's card template (shared volume; the notifier mounts it read-only). Blank = no template tools. */
    val TEMPLATE_FILE: String = get("JOBBOT_TEMPLATE_FILE", "")

    /** How far back list_high_fit scans the completed feed. */
    val HIGH_FIT_SCAN: Int = get("JOBBOT_HIGH_FIT_SCAN", "400").toInt()
    val FIT_THRESHOLD: Int = get("FIT_THRESHOLD", "55").toFloat().toInt()
}
