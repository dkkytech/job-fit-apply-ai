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
    private val fitThreshold: Int = Config.FIT_THRESHOLD,
    private val buttonsEnabled: Boolean = Config.TELEGRAM_BUTTONS_ENABLED,
    private val linkButtonsEnabled: Boolean = Config.TELEGRAM_LINK_BUTTONS_ENABLED,
    private val actions: Set<TelegramButtons.Action> = TelegramButtons.parseActions(Config.TELEGRAM_ACTIONS),
    private val links: ArtifactLinks = ArtifactLinks(),
    private val template: AlertTemplate = AlertTemplate(Config.TELEGRAM_TEMPLATE_FILE.takeIf { it.isNotBlank() }?.let { java.nio.file.Path.of(it) }),
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
        // Non-job terminal events (digest parents, not-a-job) carry no company, no error and no
        // fit — the old Processor never notified these; skip them. Error events still send, and so
        // does a high fit whose company was not extracted: its card reads "Unknown".
        if (event.company.isNullOrBlank() && event.error == null && (event.fitScore ?: 0) < fitThreshold) return NotifyOutcome()

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
        val builtIn = builtInCard(event)
        // The skip reason rides under any template too: a card without a resume must say why.
        val templated = templated(event)?.let { t -> skipText(event)?.let { "$t\n$it" } ?: t }
        fun send(text: String) = if (buttonsEnabled) client.postTelegramHtmlWithButtons(text, buttonsFor(event)) else client.postTelegramHtml(text)
        val telegramResult = when {
            (event.fitScore ?: 0) < fitThreshold -> DeliveryResult.SKIPPED
            "telegram" in alreadyDelivered -> DeliveryResult.DELIVERED
            templated != null -> send(templated).let { r ->
                // Telegram refused the templated HTML: one re-send in the built-in format, so a bad
                // template can never cost the alert.
                if (r == DeliveryResult.PERMANENT) send(builtIn) else r
            }
            else -> send(builtIn)
        }
        return NotifyOutcome(discord = discordResult, telegram = telegramResult)
    }

    /**
     * View Report / View Resume plus the eligible action buttons for one event.
     *
     * Links and actions are independently gated: links are inert URLs, while an action hands
     * work to the JobBot agent. An action is sent only when it is enabled *and* [eligible] for
     * this event — the agent's plugin re-checks the same rules on every tap.
     */
    private fun buttonsFor(event: CompletedEvent): List<List<TelegramButtons.Button>> {
        val resolved = when {
            !linkButtonsEnabled -> ArtifactLinks.Links(null, null)
            // Skipped or offered: the report is the scoring notes, and there is no resume to look up.
            event.skipped() || event.offered() || event.deferred() -> ArtifactLinks.Links(reportUrlOf(event), null)
            else -> links.resolve(event.artifactUrl, event.artifacts?.resumePdf)
        }
        return TelegramButtons.forHighFit(
            reportUrl = resolved.reportUrl,
            resumeUrl = resolved.resumeUrl,
            actions = actionsFor(event),
            completedSeq = event.completedSeq,
        )
    }

    /** The ping from the editable template, or null for the built-in format (no template, or any problem). */
    private fun templated(e: CompletedEvent): String? = runCatching {
        if (e.completedSeq <= 0) return null
        template.render(values(e))
    }.getOrNull()

    /** Enabled actions this event qualifies for, in display order. */
    internal fun actionsFor(event: CompletedEvent): List<TelegramButtons.Action> =
        if (event.completedSeq <= 0) {
            emptyList()
        } else {
            TelegramButtons.Action.entries.filter { it in actions && eligible(it, event) }
        }

    private fun values(e: CompletedEvent) = AlertTemplate.Values(
        company = e.company.orUnknown(),
        title = e.roleTitle.orUnknown(),
        score = e.fitScore?.toString() ?: "?",
        action = e.pipelineAction ?: "",
        ref = "#J${e.completedSeq}",
        jobUrl = e.jobUrl,
        reportUrl = reportUrlOf(e),
        location = e.location,
        remotePolicy = e.remotePolicy,
        salary = e.salaryRange,
        source = e.source,
        strengths = e.strengths.orEmpty(),
        gaps = e.gaps.orEmpty(),
    )

    /**
     * The built-in card (Richard's layout, 2026-10-02):
     *
     *     Sparksoft: Automation Engineer (63)
     *     Remote · $80K–$85K · via jobright.ai
     *
     *     Fit:
     *     • …
     *
     *     Gap: …
     *     Skipped: …
     *     #J7708
     *
     * Each line is left out when unknown; the blank lines frame the Fit block only, so a card
     * without strengths stays compact (an old event is just the first line and the ref).
     */
    private fun builtInCard(e: CompletedEvent): String {
        val v = values(e)
        val lines = mutableListOf("${telegramJobLabel(e)} (${e.fitScore ?: "?"})")
        AlertTemplate.details(v).takeIf { it.isNotEmpty() }?.let { lines += htmlEscape(it) }
        AlertTemplate.strengths(v).takeIf { it.isNotEmpty() }?.let { lines += listOf("", "Fit:", htmlEscape(it), "") }
        AlertTemplate.gap(v).takeIf { it.isNotEmpty() }?.let { lines += "Gap: " + htmlEscape(it) }
        skipText(e)?.let { lines += it }
        if (e.completedSeq > 0) lines += "#J${e.completedSeq}"
        return lines.joinToString("\n").trimEnd()
    }

    /**
     * "Skipped: <reason>" for a scored job that was not tailored, "Not tailored yet: <reason>" for an
     * offered one (its Tailor button builds the resume), "Tailoring deferred: …" for one waiting on
     * the cloud quota, else null.
     */
    private fun skipText(e: CompletedEvent): String? = when {
        e.skipped() -> "Skipped: " + htmlEscape(e.skipReason?.trim()?.ifBlank { null } ?: "not tailored, so there's no resume")
        e.offered() -> "Not tailored yet: " +
            htmlEscape(e.skipReason?.trim()?.ifBlank { null } ?: "below the auto-tailor score") +
            (if (buttonsEnabled && TelegramButtons.Action.TAILOR in actions && e.completedSeq > 0) ". Tap Tailor for a resume." else ".")
        e.deferred() -> "Tailoring deferred: the cloud LLM quota is used up. It is tailored automatically when the quota resets, best fits first."
        else -> null
    }

    private fun reportUrlOf(e: CompletedEvent): String? =
        e.artifactUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/report.md" }

    /** `Company — [Title](artifactUrl)` — the title links to its report when present. */
    private fun discordJobLabel(e: CompletedEvent): String {
        val role = e.roleTitle ?: ""
        val title = role.ifBlank { "*(no title)*" }
        val url = e.artifactUrl?.takeIf { it.isNotBlank() }
        val titlePart = if (url != null && role.isNotBlank()) "[$title]($url)" else title
        return "${e.company.orUnknown()} — $titlePart"
    }

    /** `<a href=jobUrl>Company</a>: <a href=report>Title</a>` (HTML for Telegram), "Unknown" for either when blank. */
    private fun telegramJobLabel(e: CompletedEvent): String {
        val company = htmlEscape(e.company.orUnknown())
        val title = htmlEscape(e.roleTitle.orUnknown())
        val reportUrl = e.artifactUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/report.md" }
        val jobUrl = e.jobUrl?.takeIf { it.isNotBlank() }
        val companyPart = if (jobUrl != null) "<a href=\"${htmlEscape(jobUrl)}\">$company</a>" else company
        val titlePart = if (reportUrl != null) "<a href=\"${htmlEscape(reportUrl)}\">$title</a>" else title
        return "$companyPart: $titlePart"
    }

    /** A blank company or title reads "Unknown" on the card. */
    private fun String?.orUnknown(): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val TAILOR = "TAILOR"
        const val UNKNOWN = "Unknown"

        /**
         * Terminal labels whose email the poller leaves in the inbox (LabelApplier). Everything
         * else — JD_Processed, JD_Processed_Digest — is archived by the poller already, so an
         * Archive button there would do nothing.
         */
        val INBOX_TERMINAL_LABELS = setOf(
            "Recruiter_Response_Required",
            "JD_Not_Found",
            "JD_Application_Update",
            "JD_Error",
            "JD_Scrape_Failed",
        )

        /**
         * Whether [action] makes sense for [e]. Mirrored by the agent's `jobbot_actions` plugin;
         * both sides are pinned by `docker/jobbot/contract/button_eligibility.json`.
         */
        fun eligible(action: TelegramButtons.Action, e: CompletedEvent): Boolean = when (action) {
            // Apply uploads the tailored resume: a skipped (e.g. pay-gated) job has none.
            TelegramButtons.Action.APPLY -> !e.jobUrl.isNullOrBlank() && e.pipelineAction.equals(TAILOR, ignoreCase = true)
            TelegramButtons.Action.REPLY -> e.isRecruiter && !e.messageId.isNullOrBlank()
            TelegramButtons.Action.ARCHIVE ->
                !e.messageId.isNullOrBlank() && e.terminalLabel in INBOX_TERMINAL_LABELS
            // Tailor queues the resume an offer skipped; a tailored or skipped job has nothing to offer.
            TelegramButtons.Action.TAILOR -> e.offered()
        }
    }
}
