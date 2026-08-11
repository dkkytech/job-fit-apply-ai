package com.jd.pipeline.client

import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Severity of an operational alert; drives the emoji prefix. */
enum class Severity(val emoji: String) {
    INFO("ℹ️"), WARN("⚠️"), ERROR("🔴")
}

/** A single operational alert. [linkUrl], when present, is rendered as a clickable Telegram link. */
data class Alert(
    val severity: Severity,
    val title: String,
    val message: String,
    val linkUrl: String? = null,
)

/**
 * Project-wide operational alerting (Telegram + Discord), distinct from the per-job result
 * notifications in [com.jd.pipeline.cli.BatchNotificationService]. Use it anywhere the system
 * needs the user's attention: a site needs re-authentication, the debug Chrome is down, a
 * pipeline timed out, and so on.
 *
 * Transport is the shared [NotificationClient], so alerts are silent no-ops when no channel is
 * configured. [send] de-duplicates on an optional key for the lifetime of the process, so a
 * condition that recurs across many jobs in one run alerts once instead of spamming.
 */
class AlertService(private val client: NotificationClient = NotificationClient()) {

    private val log = LoggerFactory.getLogger(AlertService::class.java)
    private val seen = mutableSetOf<String>()

    private fun now(): String = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm z")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())

    /**
     * Dispatch [alert] to every configured channel. When [dedupKey] is non-null, repeated alerts
     * with the same key are suppressed for the lifetime of this process.
     */
    @Synchronized
    fun send(alert: Alert, dedupKey: String? = null) {
        if (dedupKey != null && !seen.add(dedupKey)) {
            log.debug("Alert suppressed (already sent this run): {}", dedupKey)
            return
        }
        log.info("[alert] {} {} — {}", alert.severity.name, alert.title, alert.message)

        val header = "${alert.severity.emoji} ${alert.title}"
        val timestamp = now()
        val link = alert.linkUrl?.takeIf { it.isNotBlank() }

        // Discord: markdown.
        client.postDiscord(buildString {
            append("$header\n${alert.message}")
            link?.let { append("\n$it") }
            append("\n_${timestamp}_")
        })

        // Telegram: HTML, escaped, optional anchor.
        client.postTelegramHtml(buildString {
            append("<b>${htmlEscape(header)}</b>\n${htmlEscape(alert.message)}")
            link?.let { append("\n<a href=\"${htmlEscape(it)}\">${htmlEscape(it)}</a>") }
            append("\n$timestamp")
        })
    }

    // ── Convenience helpers for the alerts wired today ──────────────────────────

    /**
     * A job board needs an interactive sign-in. When [linkUrl] is given (the Steel session's
     * interactive debug URL, reachable over Tailscale), the user can tap it on a phone and sign in
     * there — the refreshed session is then reused. De-duped per site for the run.
     */
    fun reauthRequired(site: String, detail: String? = null, linkUrl: String? = null) {
        val msg = buildString {
            append("$site needs sign-in. ")
            if (!linkUrl.isNullOrBlank()) {
                append("Tap the link below on your phone — it opens a browser on the sign-in page. ")
                append("Cookies are captured automatically; there's nothing to confirm.")
            } else {
                append("Sign in to the browser session — the pipeline will reuse it.")
            }
            detail?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
        }
        send(Alert(Severity.WARN, "Sign-in required: $site", msg, linkUrl = linkUrl), dedupKey = "reauth:$site")
    }

    /**
     * A sign-in landed and its cookies were persisted. Closes the loop opened by [reauthRequired] so
     * the user knows the tap worked without having to check a log or re-run a scrape. Not de-duped:
     * each successful sign-in is a distinct event worth confirming.
     */
    fun reauthCaptured(site: String, cookies: Int) {
        send(
            Alert(
                Severity.INFO,
                "Signed in: $site",
                "Captured $cookies cookies — scraping will reuse this session on the next run.",
            )
        )
    }

    /** The browser backend (Steel / debug Chrome) could not be reached. */
    fun chromeDebugUnavailable(endpoint: String) {
        val msg = "Could not reach the browser backend at $endpoint. Browser-only scrapes " +
            "(LinkedIn, forced-CDP domains) will fail until it's back — plain-HTTP scraping still works."
        send(Alert(Severity.WARN, "Browser backend unreachable", msg), dedupKey = "cdp-down")
    }

    /** A pipeline run exceeded its time budget and was killed. */
    fun pipelineTimeout(minutes: Int) {
        send(Alert(Severity.ERROR, "JD Pipeline timed out", "Run exceeded $minutes min and was killed."))
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
