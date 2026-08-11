package com.jd.notifier.notify

import com.jd.notifier.bridge.CompletedEvent
import com.jd.notifier.config.Config

/**
 * What one event's delivery achieved, per channel.
 *
 * [acked] is the loop's cursor gate. The policy is explicit because partial success is the
 * interesting case: an event is acked when nothing is left that a retry could fix. A PERMANENT
 * failure counts as acked — retrying a bad token forever would block every later event behind it,
 * turning one misconfiguration into a total outage.
 */
data class NotifyOutcome(
    val discord: DeliveryResult = DeliveryResult.SKIPPED,
    val telegram: DeliveryResult = DeliveryResult.SKIPPED,
) {
    val acked: Boolean
        get() = discord != DeliveryResult.RETRYABLE && telegram != DeliveryResult.RETRYABLE

    val sentAnything: Boolean
        get() = discord == DeliveryResult.DELIVERED || telegram == DeliveryResult.DELIVERED

    /** Channels already landed, so a retry of this event does not re-send them. */
    fun deliveredChannels(): Set<String> = buildSet {
        if (discord == DeliveryResult.DELIVERED) add("discord")
        if (telegram == DeliveryResult.DELIVERED) add("telegram")
    }

    /** Fold a retry's result into what previously landed. */
    fun merge(previous: NotifyOutcome): NotifyOutcome = NotifyOutcome(
        discord = if (previous.discord == DeliveryResult.DELIVERED) previous.discord else discord,
        telegram = if (previous.telegram == DeliveryResult.DELIVERED) previous.telegram else telegram,
    )
}

/**
 * Formats and sends the per-job messages for a completed event (moved from the pipeline's
 * BatchNotificationService.notifyJobResult): a Discord line per job, and a Telegram high-fit ping
 * when `fit_score >= fitThreshold`. Pure orchestration over [NotificationClient].
 */
class Notifier(
    private val client: NotificationClient = NotificationClient(),
    private val fitThreshold: Int = Config.NOTIFICATION_FIT_THRESHOLD,
) {
    /**
     * Deliver [event]. [alreadyDelivered] names channels that landed on a previous attempt of this
     * same event; they are not re-sent, so a retry provoked by one channel failing does not
     * duplicate the channel that succeeded.
     *
     * A nothing-to-do event (no channels configured, or a non-job terminal like a digest parent)
     * comes back fully SKIPPED, which is [NotifyOutcome.acked] — the cursor must move past it.
     */
    fun notify(event: CompletedEvent, alreadyDelivered: Set<String> = emptySet()): NotifyOutcome {
        if (!client.discordConfigured && !client.telegramConfigured) return NotifyOutcome()
        // Non-job terminal events (digest parents, not-a-job) carry no company and no error — the
        // old Processor never notified these; skip them. Error events (company may be null) still send.
        if (event.company.isNullOrBlank() && event.error == null) return NotifyOutcome()

        fun discord(text: String) =
            if ("discord" in alreadyDelivered) DeliveryResult.DELIVERED else client.postDiscord(text)

        val company = event.company ?: ""
        val title = (event.roleTitle ?: "").ifBlank { "*(no title)*" }
        if (event.error != null) {
            return NotifyOutcome(discord = discord("• $company — $title — error: ${event.error}"))
        }
        val score = event.fitScore?.toString() ?: "?"
        val action = event.pipelineAction ?: "?"
        val discordResult = discord("• ${discordJobLabel(event)} — **$score** ($action)")
        val telegramResult = when {
            (event.fitScore ?: 0) < fitThreshold -> DeliveryResult.SKIPPED
            "telegram" in alreadyDelivered -> DeliveryResult.DELIVERED
            else -> client.postTelegramHtml("High-fit: ${telegramJobLabel(event)} — ${event.fitScore}")
        }
        return NotifyOutcome(discord = discordResult, telegram = telegramResult)
    }

    /** `Company — [Title](artifactUrl)` — the title links to its report when present. */
    private fun discordJobLabel(e: CompletedEvent): String {
        val role = e.roleTitle ?: ""
        val title = role.ifBlank { "*(no title)*" }
        val url = e.artifactUrl?.takeIf { it.isNotBlank() }
        val titlePart = if (url != null && role.isNotBlank()) "[$title]($url)" else title
        return "${e.company ?: ""} — $titlePart"
    }

    /** `<a href=jobUrl>Company</a> — <a href=report>Title</a>` (HTML for Telegram). */
    private fun telegramJobLabel(e: CompletedEvent): String {
        val company = htmlEscape(e.company ?: "")
        val title = htmlEscape((e.roleTitle ?: "").ifBlank { "(no title)" })
        val reportUrl = e.artifactUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/report.md" }
        val jobUrl = e.jobUrl?.takeIf { it.isNotBlank() }
        val companyPart = if (jobUrl != null) "<a href=\"${htmlEscape(jobUrl)}\">$company</a>" else company
        val titlePart = if (reportUrl != null) "<a href=\"${htmlEscape(reportUrl)}\">$title</a>" else title
        return "$companyPart — $titlePart"
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
