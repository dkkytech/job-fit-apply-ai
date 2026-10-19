package com.jd.jobbot.actions

import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.bridge.str
import com.jd.jobbot.gmail.GmailClient
import com.jd.jobbot.jobs.Eligibility
import com.jd.jobbot.jobs.JobLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Archive (tap = approval) and its Undo. Archive removes INBOX from the job's source email and
 * nothing else; the labels it removed are recorded on the action so Undo restores exactly them.
 * The poller's intake query skips Recruiter_Response_Required mail, so restoring it to the inbox
 * cannot make the poller process it again.
 */
class ArchiveSupport(
    private val gmail: GmailClient,
    private val lookup: JobLookup,
    private val tracks: TrackWriter?,
    private val handledVerbs: Set<String>,
    private val store: ActionStore,
) {
    private val log = LoggerFactory.getLogger(ArchiveSupport::class.java)

    val archive = VerbHandler { ctx, store ->
        val messageId = ctx.event.str("message_id")!!
        val labels = gmail.labels(messageId)
        val ours = store.latest("archive", ctx.jobKey)?.status == Outcomes.DONE
        if (INBOX !in labels) {
            return@VerbHandler TapResponse(
                Outcomes.ALREADY,
                toast = "Already out of the inbox.",
                actionRow = row(ctx, archived = ours),
            )
        }
        val details = buildJsonObject {
            put("message_id", messageId)
            put("removed", JsonArray(listOf(JsonPrimitive(INBOX))))
        }.toString()
        val action = store.insert("archive", ctx.jobKey, ctx.request.seq, "started", ctx.request.chatId, ctx.request.messageId?.toString(), details)
        try {
            gmail.modify(messageId, remove = listOf(INBOX))
        } catch (e: Exception) {
            // Never leave a row "started": it would read as pending forever and block Undo.
            store.update(action.id, FAILED)
            throw e
        }
        store.update(action.id, Outcomes.DONE)
        recordTrackEvent(ctx, "archived", "Archived the source email from Telegram")
        TapResponse(Outcomes.DONE, toast = "Archived.", actionRow = row(ctx, archived = true))
    }

    val undo = VerbHandler { ctx, store ->
        val last = store.latest("archive", ctx.jobKey)
        if (last == null || last.status != Outcomes.DONE) {
            return@VerbHandler TapResponse(Outcomes.REFUSED, toast = "Nothing to undo.", actionRow = row(ctx, archived = false))
        }
        val recorded = runCatching { JSON.parseToJsonElement(last.details ?: "{}").jsonObject }.getOrNull()
        val messageId = recorded?.get("message_id")?.jsonPrimitive?.content ?: ctx.event.str("message_id")!!
        val removed = recorded?.get("removed")?.jsonArray?.map { it.jsonPrimitive.content }?.ifEmpty { null } ?: listOf(INBOX)
        gmail.modify(messageId, add = removed)
        store.update(last.id, UNDONE)
        store.insert("undo", ctx.jobKey, ctx.request.seq, Outcomes.DONE, ctx.request.chatId, ctx.request.messageId?.toString())
        recordTrackEvent(ctx, "unarchived", "Restored the source email to the inbox (Undo)")
        TapResponse(Outcomes.DONE, toast = "Back in the inbox.", actionRow = row(ctx, archived = false))
    }

    /** The card's action row after an archive state change: Archive swaps with Undo. */
    internal fun row(ctx: TapContext, archived: Boolean): List<InlineButton> =
        Eligibility.eligibleVerbs(ctx.event).filter { it in handledVerbs }.map { verb ->
            if (verb == "archive" && archived) InlineButton("↩ Undo archive", "undo:${ctx.request.seq}")
            else InlineButton(verb.replaceFirstChar { it.uppercase() }, "$verb:${ctx.request.seq}")
        }

    /** History is best effort: a failed write is logged, never fails the user's action. */
    private fun recordTrackEvent(ctx: TapContext, kind: String, summary: String) {
        val writer = tracks ?: return
        try {
            val found = lookup.find(ctx.request.seq) ?: return
            if (found.trackMatch !in RELIABLE_MATCHES) return
            val id = found.track?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: return
            writer.addEvent(id, kind, summary, buildJsonObject { put("ref", ctx.ref); put("message_id", ctx.event.str("message_id")) })
        } catch (e: Exception) {
            log.warn("track event {} for {} failed: {}", kind, ctx.ref, e.message)
            store.recordError("track event $kind ${ctx.ref}", e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val INBOX = "INBOX"
        const val UNDONE = "undone"
        const val FAILED = "failed"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Only write history to a track we are sure belongs to the job. */
        val RELIABLE_MATCHES = setOf("track_id", "artifact_url")
    }
}
