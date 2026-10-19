package com.jd.jobbot.actions

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.bridge.str
import com.jd.jobbot.gmail.GmailClient
import com.jd.jobbot.gmail.Mime
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.jobs.JobRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * The recruiter-reply workflow. Nothing here sends mail except [send], and [send] runs only from a
 * tap on the ✅ Send button under a preview of the exact draft — a deterministic human approval
 * that neither the model nor Hermes' `/yolo` can bypass. The model can draft and edit; it has no
 * tool that sends.
 *
 * Every send is re-checked at tap time: the draft must still be exactly what the preview showed
 * (fingerprint), recipients must already be in the thread, Richard must not have replied in the
 * thread meanwhile (Muse can reply too), the daily cap and the kill switch must allow it.
 */
class ReplySupport(
    private val gmail: GmailClient,
    private val lookup: JobLookup,
    private val bridge: BridgeReadClient,
    private val tracks: TrackWriter?,
    private val store: ActionStore,
    private val sendEnabled: Boolean,
    private val maxSendsPerDay: Int,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalHandler {
    private val log = LoggerFactory.getLogger(ReplySupport::class.java)

    /**
     * One lock for everything that reads or writes a reply draft from inside JobBot: the model's
     * write_reply_draft (MCP thread) can never land between Send's fingerprint check and the send.
     */
    private val draftLock = Any()

    data class Draft(val id: String, val threadId: String, val view: Mime.View)

    /** The card's Reply tap: show the draft (creating it from JFAA's text if needed) with Send/Cancel. */
    val reply = VerbHandler { ctx, store ->
        val sourceId = ctx.event.str("message_id")!!
        val source = gmail.message(sourceId)
        val threadId = source.threadId ?: error("source email has no thread")
        repliedAlready(threadId)?.let { date ->
            return@VerbHandler TapResponse(
                Outcomes.ALREADY,
                toast = "You already replied in this thread.",
                reply = "You already replied in this thread ($date). Reply to this card if you want another reply drafted.",
            )
        }
        val draft = findDraft(threadId) ?: draftFromJfaaText(ctx.request.seq)
            ?: return@VerbHandler TapResponse(
                Outcomes.AGENT,
                toast = "No draft yet — JobBot will write one.",
                agentPrompt = "[JobBot action] Richard tapped Reply on ${ctx.ref} (${ctx.event.str("company")} — " +
                    "${ctx.event.str("role_title")}). There is no reply draft yet. Read the recruiter's email with " +
                    "mcp__jfaa__get_job_email and the profile with mcp__jfaa__get_profile, then write a short reply with " +
                    "mcp__jfaa__write_reply_draft, then call mcp__jfaa__request_send_approval. Do not claim it was sent.",
            )
        val approval = requestApproval(ctx.request.seq, ctx.jobKey, draft, ctx.request.chatId)
        TapResponse(
            Outcomes.AWAITING_APPROVAL,
            toast = "Draft below — tap Send to send it.",
            reply = approvalText(ctx.ref, draft.view),
            replyRow = approvalButtons(approval.id),
        )
    }

    /** ✅ Send under a preview. [id] is the approval action's id. */
    override fun send(id: Long, req: TapRequest): TapResponse = synchronized(draftLock) { sendLocked(id) }

    private fun sendLocked(id: Long): TapResponse {
        val approval = store.byId(id)?.takeIf { it.verb == SEND }
            ?: return TapResponse(Outcomes.REFUSED, toast = "Unknown approval.", actionRow = emptyList())
        when (approval.status) {
            Outcomes.DONE -> return TapResponse(Outcomes.ALREADY, toast = "Already sent.", actionRow = emptyList())
            CANCELLED, SUPERSEDED -> return TapResponse(Outcomes.REFUSED, toast = "This preview is no longer valid.", actionRow = emptyList())
        }
        if (approval.status == SENDING) {
            recoverInterruptedSend(approval)?.let { return it }
        } else if (approval.status != Outcomes.AWAITING_APPROVAL) {
            return TapResponse(Outcomes.REFUSED, toast = "This send is already in progress.")
        }
        // A preview approves the reply as it was then; a stale one must be looked at again.
        if (Duration.ofMillis(clock.millis() - approval.createdAt) > APPROVAL_TTL) {
            store.update(approval.id, SUPERSEDED)
            return TapResponse(Outcomes.EXPIRED, toast = "This preview is more than ${APPROVAL_TTL.toHours()} h old — nothing sent. Tap Reply again.", actionRow = emptyList())
        }
        if (!sendEnabled) {
            return TapResponse(Outcomes.REFUSED, toast = "Sending is switched off (JOBBOT_SEND_ENABLED). The draft is still in Gmail.")
        }
        val sentToday = store.countSince(SEND, Outcomes.DONE, clock.millis() - Duration.ofDays(1).toMillis())
        if (sentToday >= maxSendsPerDay) {
            return TapResponse(Outcomes.REFUSED, toast = "Daily send limit ($maxSendsPerDay) reached. The draft is still in Gmail.")
        }
        val details = JSON.parseToJsonElement(approval.details ?: "{}").jsonObject
        val draftId = details["draft_id"]!!.jsonPrimitive.content
        val threadId = details["thread_id"]!!.jsonPrimitive.content
        val ref = JobRef.format(approval.seq)

        val current = try {
            Mime.view(Mime.parse(gmail.draftRaw(draftId)))
        } catch (e: GmailClient.GmailException) {
            if (e.status == 404) {
                store.update(approval.id, CANCELLED)
                return TapResponse(Outcomes.REFUSED, toast = "That draft no longer exists in Gmail.", actionRow = emptyList())
            }
            throw e
        }
        if (current.fingerprint() != details["fingerprint"]!!.jsonPrimitive.content) {
            store.update(approval.id, SUPERSEDED)
            return TapResponse(
                Outcomes.REFUSED,
                toast = "The draft changed since this preview — nothing sent. Ask JobBot to show it again.",
                actionRow = emptyList(),
            )
        }
        val participants = participants(threadId)
        val strangers = (current.to + current.cc + current.bcc).filter { it !in participants }
        if (current.to.isEmpty() || strangers.isNotEmpty()) {
            return TapResponse(
                Outcomes.REFUSED,
                toast = "Not sent: ${strangers.joinToString().ifEmpty { "no recipient" }} isn't in this email thread.",
            )
        }
        repliedAlready(threadId)?.let { date ->
            store.update(approval.id, SUPERSEDED)
            return TapResponse(Outcomes.REFUSED, toast = "You already replied in this thread ($date) — nothing sent.", actionRow = emptyList())
        }

        store.update(approval.id, SENDING)
        val sentId = try {
            gmail.sendDraft(draftId)
        } catch (e: Exception) {
            store.update(approval.id, Outcomes.AWAITING_APPROVAL)
            throw e
        }
        store.update(approval.id, Outcomes.DONE, buildJsonObject {
            details.forEach { (k, v) -> put(k, v) }
            put("sent_message_id", sentId)
        }.toString())
        recordTrackEvent(approval.seq, "email_sent", "Replied to ${current.to.joinToString()} from Telegram")
        log.info("sent reply draft {} for {} to {}", draftId, ref, current.to)
        return TapResponse(Outcomes.DONE, toast = "Sent.", reply = "Sent to ${current.to.joinToString()}.", actionRow = emptyList())
    }

    /**
     * An approval left SENDING (the process stopped mid-send). Recent: genuinely in progress.
     * Older: proof of sending is our own message in the thread after the approval — not a missing
     * draft, which could also mean someone deleted it. Sent ⇒ record it; draft still there ⇒ the
     * tap may proceed; neither ⇒ nothing was sent and the approval is cancelled.
     */
    private fun recoverInterruptedSend(approval: ActionStore.Action): TapResponse? {
        if (Duration.ofMillis(clock.millis() - approval.updatedAt) < SEND_STALL) {
            return TapResponse(Outcomes.REFUSED, toast = "This send is already in progress.")
        }
        val draftId = JSON.parseToJsonElement(approval.details ?: "{}").jsonObject["draft_id"]!!.jsonPrimitive.content
        val stillThere = try {
            gmail.draftRaw(draftId); true
        } catch (e: GmailClient.GmailException) {
            if (e.status == 404) false else throw e
        }
        if (!stillThere) {
            val threadId = JSON.parseToJsonElement(approval.details ?: "{}").jsonObject["thread_id"]!!.jsonPrimitive.content
            val self = gmail.selfAddress()
            val sentSince = gmail.thread(threadId).any { m ->
                addresses(m.from.orEmpty()).any { it.equals(self, ignoreCase = true) } && (m.internalDate ?: 0) >= approval.createdAt - 60_000
            }
            if (sentSince) {
                store.update(approval.id, Outcomes.DONE)
                recordTrackEvent(approval.seq, "email_sent", "Reply sent from Telegram (recovered after an interrupted send)")
                return TapResponse(Outcomes.ALREADY, toast = "Already sent.", actionRow = emptyList())
            }
            store.update(approval.id, CANCELLED)
            return TapResponse(Outcomes.REFUSED, toast = "The draft is gone and nothing was sent. Tap Reply to start again.", actionRow = emptyList())
        }
        store.update(approval.id, Outcomes.AWAITING_APPROVAL)
        return null
    }

    /** ✖ Cancel under a preview: the draft stays in Gmail. */
    override fun cancel(id: Long, req: TapRequest): TapResponse {
        val approval = store.byId(id)?.takeIf { it.verb == SEND }
            ?: return TapResponse(Outcomes.REFUSED, toast = "Unknown approval.", actionRow = emptyList())
        if (approval.status == Outcomes.DONE) return TapResponse(Outcomes.ALREADY, toast = "Already sent.", actionRow = emptyList())
        store.update(approval.id, CANCELLED)
        return TapResponse(Outcomes.DONE, toast = "Cancelled. The draft stays in Gmail.", actionRow = emptyList())
    }

    // ── model-facing helpers (MCP tools) ──────────────────────────────────────

    /** The current draft for a job's thread, if any. */
    fun currentDraft(seq: Long): Draft? {
        val threadId = threadOf(seq) ?: return null
        return findDraft(threadId)
    }

    /** Replace the draft's text (or create the draft) — never sends. */
    fun writeDraft(seq: Long, body: String): Draft = synchronized(draftLock) { writeDraftLocked(seq, body) }

    private fun writeDraftLocked(seq: Long, body: String): Draft {
        val event = lookup.event(seq) ?: error("No JFAA job ${JobRef.format(seq)}.")
        val sourceId = event.str("message_id") ?: error("${JobRef.format(seq)} did not come from an email.")
        val threadId = gmail.message(sourceId).threadId ?: error("source email has no thread")
        val existing = findDraft(threadId)
        val id = if (existing != null) {
            val updated = Mime.withBody(Mime.parse(gmail.draftRaw(existing.id)), body)
            gmail.updateDraft(existing.id, Mime.encode(updated), threadId)
        } else {
            gmail.createDraft(Mime.encode(newReply(event, sourceId, body)), threadId)
        }
        recordTrackEvent(seq, "reply_drafted", "Reply draft written in Telegram")
        return Draft(id, threadId, Mime.view(Mime.parse(gmail.draftRaw(id))))
    }

    /** Record a pending approval for the draft as it is now; the plugin posts its Send/Cancel buttons. */
    fun requestApproval(seq: Long, jobKey: String, draft: Draft, chatId: String?): ActionStore.Action {
        val details = buildJsonObject {
            put("draft_id", draft.id)
            put("thread_id", draft.threadId)
            put("fingerprint", draft.view.fingerprint())
            put("preview", approvalText(JobRef.format(seq), draft.view))
        }.toString()
        return store.insert(SEND, jobKey, seq, Outcomes.AWAITING_APPROVAL, chatId, null, details)
    }

    /** The preview message for a pending approval, as the plugin should post it (null if not pending). */
    fun approvalPrompt(id: Long): TapResponse? {
        val a = store.byId(id)?.takeIf { it.verb == SEND && it.status == Outcomes.AWAITING_APPROVAL } ?: return null
        val preview = JSON.parseToJsonElement(a.details ?: "{}").jsonObject["preview"]?.jsonPrimitive?.content ?: return null
        return TapResponse(Outcomes.AWAITING_APPROVAL, reply = preview, replyRow = approvalButtons(a.id))
    }

    fun approvalText(ref: String, view: Mime.View) =
        "Reply draft for $ref — tap ✅ Send to send exactly this, or reply with changes.\n\n${view.preview()}"

    fun approvalButtons(id: Long) = listOf(InlineButton("✅ Send", "send:$id"), InlineButton("✖ Cancel", "cancel:$id"))

    // ── internals ──────────────────────────────────────────────────────────────

    private fun threadOf(seq: Long): String? {
        val sourceId = lookup.event(seq)?.str("message_id") ?: return null
        return gmail.message(sourceId).threadId
    }

    private fun findDraft(threadId: String): Draft? {
        val id = gmail.drafts().firstOrNull { it.second == threadId }?.first ?: return null
        return Draft(id, threadId, Mime.view(Mime.parse(gmail.draftRaw(id))))
    }

    /** The poller drafts recruiter replies itself; if that draft is gone, rebuild one from JFAA's text. */
    private fun draftFromJfaaText(seq: Long): Draft? {
        val event = lookup.event(seq) ?: return null
        val text = event.str("draft_text") ?: return null
        return writeDraft(seq, text)
    }

    private fun newReply(event: kotlinx.serialization.json.JsonObject, sourceId: String, body: String): jakarta.mail.internet.MimeMessage {
        val h = gmail.headers(sourceId)
        val to = (h["reply-to"] ?: h["from"]) ?: error("source email has no sender")
        val attachments = event.str("job_id")?.let { jobId ->
            runCatching { bridge.resumePdf(jobId) }.getOrNull()?.let { listOf(Triple(RESUME_NAME, "application/pdf", it)) }
        }.orEmpty()
        return Mime.reply(to, Mime.reSubject(h["subject"]), h["message-id"], h["references"], body, attachments)
    }

    /** Every address that has written or been written to in the thread (minus our own). */
    private fun participants(threadId: String): Set<String> {
        val self = gmail.selfAddress()
        return gmail.thread(threadId).flatMap { m -> listOfNotNull(m.from, m.to, m.cc).flatMap { addresses(it) } }
            .map { it.lowercase() }.filter { it != self }.toSet()
    }

    /** The date of our own message in the thread that came after the recruiter's latest, if any. */
    private fun repliedAlready(threadId: String): String? {
        val self = gmail.selfAddress()
        val messages = gmail.thread(threadId).filter { "DRAFT" !in it.labels }
        val lastInbound = messages.indexOfLast { m -> addresses(m.from.orEmpty()).none { it.equals(self, ignoreCase = true) } }
        return messages.drop(lastInbound + 1)
            .lastOrNull { m -> addresses(m.from.orEmpty()).any { it.equals(self, ignoreCase = true) } }
            ?.let { it.date ?: "date unknown" }
    }

    private fun recordTrackEvent(seq: Long, kind: String, summary: String) {
        val writer = tracks ?: return
        try {
            val found = lookup.find(seq) ?: return
            if (found.trackMatch !in ArchiveSupport.RELIABLE_MATCHES) return
            val id = found.track?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: return
            writer.addEvent(id, kind, summary, buildJsonObject { put("ref", JobRef.format(seq)) })
        } catch (e: Exception) {
            store.recordError("track event $kind ${JobRef.format(seq)}", e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val SEND = "send"
        const val SENDING = "sending"
        const val CANCELLED = "cancelled"
        const val SUPERSEDED = "superseded"
        const val RESUME_NAME = "RichardHatcherResume.pdf"

        /** A SENDING row older than this was interrupted, not in flight. */
        val SEND_STALL: Duration = Duration.ofMinutes(2)

        /** How long a Send preview stays valid. */
        val APPROVAL_TTL: Duration = Duration.ofHours(24)
        private val JSON = Json { ignoreUnknownKeys = true }
        private val EMAIL = Regex("""[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

        fun addresses(header: String): List<String> = EMAIL.findAll(header).map { it.value.lowercase() }.toList()
    }
}
