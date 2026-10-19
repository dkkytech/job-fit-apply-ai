package com.jd.jobbot.jobs

import com.jd.jobbot.bridge.bool
import com.jd.jobbot.bridge.long
import com.jd.jobbot.bridge.str
import kotlinx.serialization.json.JsonObject

/**
 * Which action buttons a completed event qualifies for. The notifier applies the same rules when
 * it sends a card (Notifier.eligible); both sides are pinned by
 * docker/jobbot/contract/button_eligibility.json, so a rule change has to land in both.
 */
object Eligibility {
    val VERBS = listOf("apply", "reply", "archive", "tailor")

    /** Terminal labels whose email the poller leaves in the inbox (poller LabelApplier). */
    val INBOX_TERMINAL_LABELS = setOf(
        "Recruiter_Response_Required",
        "JD_Not_Found",
        "JD_Application_Update",
        "JD_Error",
        "JD_Scrape_Failed",
    )

    fun eligible(verb: String, event: JsonObject): Boolean {
        if ((event.long("completed_seq") ?: 0) <= 0) return false
        return when (verb) {
            // Apply uploads the tailored resume: a skipped (e.g. pay-gated) job has none.
            "apply" -> event.str("job_url") != null && event.str("pipeline_action").equals("TAILOR", ignoreCase = true)
            "reply" -> event.bool("is_recruiter") && event.str("message_id") != null
            "archive" -> event.str("message_id") != null && event.str("terminal_label") in INBOX_TERMINAL_LABELS
            // Tailor builds the resume an offer (a fit below AUTO_TAILOR_MIN_SCORE) did not.
            "tailor" -> event.str("pipeline_action").equals("OFFER", ignoreCase = true)
            else -> false
        }
    }

    fun eligibleVerbs(event: JsonObject): List<String> = VERBS.filter { eligible(it, event) }
}
