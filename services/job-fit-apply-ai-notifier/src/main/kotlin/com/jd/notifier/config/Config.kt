package com.jd.notifier.config

import io.github.cdimascio.dotenv.Dotenv

internal fun resolveCredential(primary: String?, legacy: String?): String =
    primary?.trim()?.takeIf { it.isNotEmpty() } ?: legacy?.trim().orEmpty()

/**
 * Notifier configuration. A completed-feed event consumer: polls the bridge event stream and sends
 * Discord (per job) + Telegram (high-fit) messages. Tracks its own cursor.
 */
object Config {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private fun get(key: String, default: String): String =
        DOTENV.get(key) ?: System.getenv(key) ?: default

    val JD_BRIDGE_URL: String = get("JD_BRIDGE_URL", "http://127.0.0.1:8765")

    // ── Messaging channels (silently disabled when creds are blank) ──────────────
    val DISCORD_BOT_TOKEN: String  = get("DISCORD_BOT_TOKEN", "")
    val DISCORD_CHANNEL_ID: String = get("DISCORD_CHANNEL_ID", "")
    val TELEGRAM_BOT_TOKEN: String = resolveCredential(
        get("NOTIFIER_TELEGRAM_BOT_TOKEN", ""),
        get("TELEGRAM_BOT_TOKEN", ""),
    )
    val TELEGRAM_CHAT_ID: String = resolveCredential(
        get("NOTIFIER_TELEGRAM_CHAT_ID", ""),
        get("TELEGRAM_CHAT_ID", ""),
    )

    /** API hosts, overridable so tests/e2e can point at a local sink. */
    val DISCORD_API_BASE: String  = get("DISCORD_API_BASE", "https://discord.com")
    val TELEGRAM_API_BASE: String = get("TELEGRAM_API_BASE", "https://api.telegram.org")

    /** Telegram high-fit ping fires when fit_score >= this. */
    val NOTIFICATION_FIT_THRESHOLD: Int = get("NOTIFICATION_FIT_THRESHOLD", "50").toInt()

    // ── Loop + state ─────────────────────────────────────────────────────────────
    val POLL_INTERVAL_MS: Long = get("NOTIFIER_POLL_INTERVAL_MS", "20000").toLong()
    val CURSOR_FILE: String = get("NOTIFIER_CURSOR_FILE", "/state/notifier-cursor.txt")

    /** Retryable-delivery policy. After MAX_DELIVERY_ATTEMPTS the event is dead-lettered (logged
     *  loudly and skipped) so one poisoned event cannot block every later notification. */
    val MAX_DELIVERY_ATTEMPTS: Int = get("NOTIFIER_MAX_DELIVERY_ATTEMPTS", "5").toInt()
    val DELIVERY_BACKOFF_BASE_MS: Long = get("NOTIFIER_DELIVERY_BACKOFF_BASE_MS", "1000").toLong()
    val DELIVERY_BACKOFF_CAP_MS: Long = get("NOTIFIER_DELIVERY_BACKOFF_CAP_MS", "60000").toLong()
    val HEARTBEAT_FILE: String = get("HEARTBEAT_FILE", "/tmp/notifier-heartbeat")
    val HEALTH_MAX_AGE_MS: Long = get("HEALTH_MAX_AGE_MS", "120000").toLong()
}
