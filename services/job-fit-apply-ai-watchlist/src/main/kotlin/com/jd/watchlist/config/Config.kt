package com.jd.watchlist.config

import io.github.cdimascio.dotenv.Dotenv
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Configuration for the watchlist ingestion service: once a day, poll the public job feeds of a
 * curated list of employers (employers.yaml) and submit pre-filtered postings to the bridge as
 * JD_SCRAPED with source CAREER_SITE. No browser, no LLM.
 */
object Config {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private fun get(key: String, default: String): String =
        DOTENV.get(key)?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

    /** A malformed number falls back to [default] with a warning instead of crashing startup. */
    private fun getLong(key: String, default: Long): Long = parseLong(key, get(key, default.toString()), default)

    internal fun parseLong(key: String, raw: String, default: Long): Long =
        raw.trim().toLongOrNull() ?: default.also { System.err.println("[config] $key=\"$raw\" is not a number — using $default") }

    val JD_BRIDGE_URL: String = get("JD_BRIDGE_URL", "http://127.0.0.1:8765")

    /** Minimum spacing between real polls — the last-run gate. Default 24h. */
    val WATCHLIST_INTERVAL_MS: Long = getLong("WATCHLIST_INTERVAL_MS", 86_400_000L)

    /** Fixed local times to sweep at ("05:30,17:30"), replacing the interval; blank = use the interval. */
    val RUN_AT: List<LocalTime> = parseRunAt(get("WATCHLIST_RUN_AT", ""))

    /** Time zone for [RUN_AT] (an IANA id such as America/Los_Angeles); blank = the JVM's default. */
    val TZ: ZoneId = parseZone(get("WATCHLIST_TZ", ""))

    /** Where `--once` writes the epoch second the next sweep is due; the shell loop sleeps until then. */
    val NEXT_DUE_FILE: String = get("WATCHLIST_NEXT_DUE_FILE", "/tmp/watchlist-next-due")

    /** "05:30, 17:30" → times; an entry that isn't H:mm is dropped with a warning. */
    internal fun parseRunAt(raw: String): List<LocalTime> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull {
        runCatching { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
            .onFailure { _ -> System.err.println("[config] WATCHLIST_RUN_AT entry \"$it\" is not H:mm — ignored") }
            .getOrNull()
    }

    internal fun parseZone(raw: String): ZoneId = if (raw.isBlank()) ZoneId.systemDefault() else
        runCatching { ZoneId.of(raw.trim()) }.getOrElse {
            System.err.println("[config] WATCHLIST_TZ=\"$raw\" is not a time zone — using ${ZoneId.systemDefault()}")
            ZoneId.systemDefault()
        }

    /** Persisted state (last_run) — a small mounted volume so restarts don't re-poll. */
    val STATE_FILE: String = get("WATCHLIST_STATE_FILE", "/state/watchlist.json")

    /** Per-employer outcome of the last run, for doctor.sh and humans. */
    val HEALTH_FILE: String = get("WATCHLIST_HEALTH_FILE", "/state/watchlist-health.json")

    /** Employer list on disk; blank = the bundled classpath resource employers.yaml. */
    val EMPLOYERS_FILE: String = get("WATCHLIST_EMPLOYERS_FILE", "")

    /** Titles must match this (case-insensitive) to reach the bridge — the LLM only sees QE-ish roles. */
    val TITLE_INCLUDE: String = get(
        "WATCHLIST_TITLE_INCLUDE",
        """\b(qa|qe|sdet|quality|test|tester|testing|verification|validation)\b""",
    )

    /** Titles matching this are dropped even when they match [TITLE_INCLUDE]. */
    val TITLE_EXCLUDE: String = get(
        "WATCHLIST_TITLE_EXCLUDE",
        """\b(intern|internship|technician|clinical|nurse|driver|warehouse|asic|fpga|rtl|soc|silicon|design verification|formal verification|microbiolog\w*|laboratory|aml)\b""",
    )

    /**
     * Platforms without a cheap "list everything" call (Workday, Eightfold, Jibe, Amazon) are
     * searched by these terms; titles are still matched client-side afterwards.
     * WATCHLIST_WORKDAY_SEARCH_TERMS is the original, Workday-only name and still honoured.
     */
    val SEARCH_TERMS: List<String> =
        get("WATCHLIST_SEARCH_TERMS", get("WATCHLIST_WORKDAY_SEARCH_TERMS", "test,quality,sdet,qa"))
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Pause between paged requests to one employer. */
    val PAGE_DELAY_MS: Long = getLong("WATCHLIST_PAGE_DELAY_MS", 300L)

    /** Eightfold (Microsoft, Starbucks…) rate-limits per IP over a long window: pace it slower. */
    val EIGHTFOLD_PAGE_DELAY_MS: Long = getLong("WATCHLIST_EIGHTFOLD_PAGE_DELAY_MS", 2_000L)

    /** Alert once an employer's feed has failed or listed nothing for this many runs in a row. */
    val ALERT_AFTER_RUNS: Int = getLong("WATCHLIST_ALERT_AFTER_RUNS", 2L).toInt()

    // Operational alert channels — the same bot settings the processor's AlertService uses.
    val TELEGRAM_BOT_TOKEN: String = get("TELEGRAM_BOT_TOKEN", "")
    val TELEGRAM_CHAT_ID: String = get("TELEGRAM_CHAT_ID", "")
    val DISCORD_BOT_TOKEN: String = get("DISCORD_BOT_TOKEN", "")
    val DISCORD_CHANNEL_ID: String = get("DISCORD_CHANNEL_ID", "")
    val TELEGRAM_API_BASE: String = get("TELEGRAM_API_BASE", "https://api.telegram.org")
    val DISCORD_API_BASE: String = get("DISCORD_API_BASE", "https://discord.com")

    /** Pause between employers so the daily sweep stays polite. */
    val INTER_EMPLOYER_DELAY_MS: Long = getLong("WATCHLIST_INTER_EMPLOYER_DELAY_MS", 750L)
}
