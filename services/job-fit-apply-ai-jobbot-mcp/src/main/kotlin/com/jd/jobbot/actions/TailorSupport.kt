package com.jd.jobbot.actions

import com.jd.jobbot.bridge.TailorRequester
import com.jd.jobbot.bridge.str
import com.jd.jobbot.jobs.Eligibility
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tailor (tap = approval): an offered job — a fit below AUTO_TAILOR_MIN_SCORE, scored but not
 * tailored up front — gets its resume built now. The bridge queues the TAILOR_REQUEST and keeps it
 * once per job; the tailored result arrives as a new card, like any other completed job.
 */
class TailorSupport(
    private val requester: TailorRequester,
    private val handledVerbs: Set<String>,
) {
    val tailor = VerbHandler { ctx, store ->
        val jobId = ctx.event.str("job_id")
            ?: return@VerbHandler TapResponse(Outcomes.REFUSED, toast = "This card has no job id, so it can't be tailored.")
        when (val outcome = requester.request(jobId)) {
            is TailorRequester.Outcome.Queued -> {
                val details = buildJsonObject { put("job_id", jobId); put("tailor_job_id", outcome.jobId) }.toString()
                store.insert("tailor", ctx.jobKey, ctx.request.seq, Outcomes.DONE, ctx.request.chatId, ctx.request.messageId?.toString(), details)
                TapResponse(Outcomes.DONE, toast = "Tailoring ${ctx.ref}. The resume card follows in a few minutes.", actionRow = rowAfterTap(ctx))
            }
            is TailorRequester.Outcome.Already -> TapResponse(
                Outcomes.ALREADY,
                toast = if (outcome.status == "done") "Already tailored. Look for its resume card." else "Already tailoring. The resume card is on its way.",
                actionRow = rowAfterTap(ctx),
            )
            is TailorRequester.Outcome.Refused ->
                TapResponse(Outcomes.REFUSED, toast = "Can't tailor ${ctx.ref}: ${outcome.detail}".take(TOAST_MAX))
        }
    }

    /** The card's action row once Tailor has been tapped: the job's other verbs, without Tailor. */
    internal fun rowAfterTap(ctx: TapContext): List<InlineButton> =
        Eligibility.eligibleVerbs(ctx.event).filter { it in handledVerbs && it != "tailor" }
            .map { InlineButton(it.replaceFirstChar { c -> c.uppercase() }, "$it:${ctx.request.seq}") }

    private companion object {
        const val TOAST_MAX = 200
    }
}
