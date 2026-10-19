package com.jd.notifier.notify

import org.slf4j.LoggerFactory

/**
 * Inline keyboard buttons for a high-fit Telegram notification.
 *
 * Two button kinds ride in one `reply_markup`:
 *  - **URL buttons** (View Report / View Resume) open the artifact over the tailnet.
 *  - **Action buttons** (Apply / Reply / Archive / Tailor) are callbacks. The JobBot agent is the bot's
 *    only update reader, and its `jobbot_actions` plugin routes a tap by its `callback_data`,
 *    `<verb>:<completed_seq>` (see docs/jobbot.md).
 *
 * The `completed_seq` is the bridge's own id for the completion, so the callback needs no
 * registration step: the agent resolves it against the completed feed, which already exists
 * before the ping is sent. That is what makes a dead button impossible by construction.
 */
object TelegramButtons {
    private val log = LoggerFactory.getLogger(TelegramButtons::class.java)

    /** Telegram's hard cap on `callback_data`, in bytes. */
    const val CALLBACK_DATA_LIMIT = 64

    const val REPORT_TEXT = "View Report"
    const val RESUME_TEXT = "View Resume"

    /**
     * A callback action the agent handles. [verb] is the wire token; renaming one breaks every
     * card already sent, so add verbs rather than change them.
     */
    enum class Action(val verb: String, val text: String) {
        APPLY("apply", "Apply"),
        REPLY("reply", "Reply"),
        ARCHIVE("archive", "Archive"),
        /** An OFFERed job (fit below AUTO_TAILOR_MIN_SCORE): build its resume now. */
        TAILOR("tailor", "Tailor"),
        ;

        companion object {
            fun ofVerb(verb: String): Action? = entries.firstOrNull { it.verb == verb.trim().lowercase() }
        }
    }

    /**
     * Parse `NOTIFIER_TELEGRAM_ACTIONS` (`apply,archive,reply`). Unknown verbs are logged and
     * dropped rather than sent: a button with no handler behind it is exactly the dead button
     * this design exists to prevent.
     */
    fun parseActions(raw: String): Set<Action> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { verb ->
            Action.ofVerb(verb).also { if (it == null) log.warn("ignoring unknown Telegram action '{}'", verb) }
        }.toSet()

    /** `<verb>:<completed_seq>` — what the agent's plugin parses. */
    fun callbackData(action: Action, completedSeq: Long): String {
        require(completedSeq > 0) { "callback data needs a real completed_seq, got $completedSeq" }
        val data = "${action.verb}:$completedSeq"
        check(data.toByteArray(Charsets.UTF_8).size <= CALLBACK_DATA_LIMIT) { "callback_data over 64 bytes: $data" }
        return data
    }

    /** One inline button: exactly one of [url] (opens) or [callbackData] (reports a tap). */
    data class Button(
        val text: String,
        val url: String? = null,
        val callbackData: String? = null,
    )

    fun urlButton(text: String, url: String?): Button? =
        url?.takeIf { it.isNotBlank() }?.trim()
            // A button whose target is not an absolute http(s) URL is unreachable in a chat
            // client — Telegram rejects it and the sender silently loses a button. Dropping it
            // here keeps a bad base URL (or an unresolved bridge-relative path) from turning
            // into a broken button on the user's screen.
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { Button(text = text, url = it) }

    /**
     * A row is only real if it has buttons. Building rows from absent links would otherwise
     * leave an empty inner list, and Telegram rejects `{"inline_keyboard":[[]]}`.
     */
    private fun rowOf(vararg buttons: Button?): List<Button>? =
        buttons.filterNotNull().takeIf { it.isNotEmpty() }

    /**
     * Report + Resume on the first row, the [actions] on the second, dropping any link whose
     * target is unknown. The caller decides which actions are eligible for the event.
     *
     * Returns rows-of-buttons, matching Telegram's `inline_keyboard` shape.
     */
    fun forHighFit(
        reportUrl: String?,
        resumeUrl: String?,
        actions: List<Action> = emptyList(),
        completedSeq: Long = 0,
    ): List<List<Button>> {
        val rows = mutableListOf<List<Button>>()
        rowOf(urlButton(REPORT_TEXT, reportUrl), urlButton(RESUME_TEXT, resumeUrl))?.let { rows += it }
        if (completedSeq > 0) {
            rowOf(*actions.map { Button(it.text, callbackData = callbackData(it, completedSeq)) }.toTypedArray())
                ?.let { rows += it }
        }
        return rows
    }

    /**
     * The Telegram `reply_markup` value for [rows], or null when there is nothing to render.
     * Serialised by hand to keep the wire shape obvious in tests.
     */
    fun replyMarkupJson(rows: List<List<Button>>): String? {
        if (rows.isEmpty()) return null
        val keyboard = rows.joinToString(",") { row ->
            row.joinToString(",", "[", "]") { b ->
                val fields = mutableListOf("\"text\":${json(b.text)}")
                b.url?.let { fields += "\"url\":${json(it)}" }
                b.callbackData?.let { fields += "\"callback_data\":${json(it)}" }
                fields.joinToString(",", "{", "}")
            }
        }
        return "{\"inline_keyboard\":[$keyboard]}"
    }

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
