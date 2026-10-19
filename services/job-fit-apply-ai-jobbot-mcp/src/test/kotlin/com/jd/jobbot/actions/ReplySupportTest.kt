package com.jd.jobbot.actions

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.gmail.GmailAuth
import com.jd.jobbot.gmail.GmailClient
import com.jd.jobbot.gmail.Mime
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.FakeBridge
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplySupportTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = Moving(now)

    private class Moving(var at: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = at
    }
    private val user = "8679792351"
    private val self = "dkkytech@gmail.com"

    /** One recruiter thread, its drafts, and a send log — all in memory. */
    private inner class FakeGmail : GmailClient(object : GmailAuth(Paths.get("/x"), Paths.get("/y")) { override fun token(forceRefresh: Boolean) = "t" }) {
        val thread = mutableListOf(msg("m1", "Rec <rec@agency.com>", self, "Staff SDET role"))
        val drafts = linkedMapOf<String, String>()
        val sent = mutableListOf<String>()
        var nextId = 1

        var onUpdate: () -> Unit = {}

        fun msg(id: String, from: String, to: String, subject: String = "Re: Staff SDET role", labels: List<String> = listOf("INBOX")) =
            Message(id, "t1", labels, from, to, null, subject, "Wed, 1 Oct 2026", null, "body", internalDate = clock.at.toEpochMilli())

        override fun message(messageId: String) = thread.first { it.id == messageId }
        override fun thread(threadId: String) = thread.toList()
        override fun selfAddress() = self
        override fun drafts() = drafts.keys.map { it to "t1" }
        override fun draftRaw(draftId: String) = drafts[draftId] ?: throw GmailException(404, "gone")
        override fun createDraft(raw: String, threadId: String?) = "d${nextId++}".also { drafts[it] = raw }
        override fun updateDraft(draftId: String, raw: String, threadId: String?) = draftId.also { onUpdate(); drafts[it] = raw }
        override fun headers(messageId: String) = mapOf("from" to "Rec <rec@agency.com>", "subject" to "Staff SDET role", "message-id" to "<m1@agency.com>")
        override fun sendDraft(draftId: String): String {
            val raw = drafts.remove(draftId)!!
            sent += Mime.view(Mime.parse(raw)).body
            thread += msg("s${sent.size}", "Richard <$self>", "rec@agency.com", labels = listOf("SENT"))
            return "s${sent.size}"
        }
    }

    private class Lookup(val event: JsonObject) : JobLookup(BridgeReadClient("http://unused")) {
        override fun event(seq: Long) = event.takeIf { seq == 9L }
        override fun find(seq: Long) = event(seq)?.let { Found(it, FakeBridge.track(41, "Acme", "Staff SDET"), "track_id") }
    }

    private class Bridge : BridgeReadClient("http://unused") {
        override fun resumePdf(jobId: String) = "%PDF fake".toByteArray()
    }

    private class Tracks : TrackWriter("http://unused") {
        val kinds = mutableListOf<String>()
        override fun addEvent(trackId: Long, kind: String, summary: String?, details: JsonElement?) { kinds += kind }
    }

    private fun event(draftText: String? = "Thanks — happy to talk.") = buildJsonObject {
        FakeBridge.event(9, messageId = "m1", recruiter = true, terminalLabel = "Recruiter_Response_Required").forEach { (k, v) -> put(k, v) }
        put("draft_text", draftText)
    }

    private inner class Rig(draftText: String? = "Thanks — happy to talk.", sendEnabled: Boolean = true, cap: Int = 10) {
        val gmail = FakeGmail()
        val store = ActionStore(":memory:", clock)
        val tracks = Tracks()
        val replies = ReplySupport(gmail, Lookup(event(draftText)), Bridge(), tracks, store, sendEnabled, cap, clock)
        val taps = TapService(Lookup(event(draftText)), store, mapOf("reply" to replies.reply), setOf(user), setOf("reply"), false,
            Duration.ofDays(7), clock, approvals = replies)

        fun tap(verb: String, n: Long = 9) = taps.tap(TapRequest(verb, n, user, user, 5, clock.at.epochSecond))
        fun approvalId(r: TapResponse) = r.replyRow!!.first().callbackData.substringAfter(':').toLong()
    }

    @Test
    fun `Reply builds a draft from JFAA's text and shows it with Send and Cancel`() {
        val rig = Rig()
        val r = rig.tap("reply")
        assertEquals(Outcomes.AWAITING_APPROVAL, r.outcome)
        assertTrue(r.reply!!.contains("To: rec@agency.com") && r.reply!!.contains("Thanks — happy to talk."), r.reply)
        assertTrue(r.reply!!.contains("Attachments: RichardHatcherResume.pdf"), r.reply)
        assertEquals(listOf("✅ Send", "✖ Cancel"), r.replyRow!!.map { it.text })
        assertTrue(rig.gmail.sent.isEmpty(), "Reply must never send")
    }

    @Test
    fun `Reply reuses the poller's existing draft`() {
        val rig = Rig()
        rig.gmail.drafts["poller"] = Mime.encode(Mime.reply("rec@agency.com", "Re: Staff SDET role", "<m1@agency.com>", null, "Poller draft"))
        assertTrue(rig.tap("reply").reply!!.contains("Poller draft"))
        assertEquals(setOf("poller"), rig.gmail.drafts.keys)
    }

    @Test
    fun `Send sends exactly the previewed draft once`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        val r = rig.tap("send", id)
        assertEquals(Outcomes.DONE, r.outcome)
        assertEquals(emptyList(), r.actionRow, "buttons come off the preview")
        assertEquals(listOf("Thanks — happy to talk."), rig.gmail.sent)
        assertEquals(Outcomes.ALREADY, rig.tap("send", id).outcome)
        assertEquals(1, rig.gmail.sent.size)
        assertTrue("email_sent" in rig.tracks.kinds)
    }

    @Test
    fun `a draft edited after the preview is not sent`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        rig.replies.writeDraft(9, "Something else")
        val r = rig.tap("send", id)
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertTrue(r.toast!!.contains("changed"), r.toast)
        assertTrue(rig.gmail.sent.isEmpty())
    }

    @Test
    fun `the kill switch and the daily cap stop sends`() {
        val off = Rig(sendEnabled = false)
        assertEquals(Outcomes.REFUSED, off.tap("send", off.approvalId(off.tap("reply"))).outcome)
        assertTrue(off.gmail.sent.isEmpty())

        val capped = Rig(cap = 0)
        assertTrue(capped.tap("send", capped.approvalId(capped.tap("reply"))).toast!!.contains("limit"))
        assertTrue(capped.gmail.sent.isEmpty())
    }

    @Test
    fun `recipients outside the thread are refused`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        val draftId = rig.gmail.drafts.keys.single()
        // Re-point the draft *and* its approval fingerprint at a stranger, as a compromised edit would.
        val stranger = Mime.encode(Mime.reply("evil@else.com", "Re: Staff SDET role", null, null, "Thanks — happy to talk."))
        rig.gmail.drafts[draftId] = stranger
        val a = rig.store.byId(id)!!
        rig.store.update(id, a.status, a.details!!.replace(Regex("\"fingerprint\":\"[0-9a-f]+\""),
            "\"fingerprint\":\"${Mime.view(Mime.parse(stranger)).fingerprint()}\""))
        val r = rig.tap("send", id)
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertTrue(r.toast!!.contains("evil@else.com"), r.toast)
        assertTrue(rig.gmail.sent.isEmpty())
    }

    @Test
    fun `if Richard already replied (e g via Muse) nothing is sent`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        rig.gmail.thread += rig.gmail.msg("x", "Richard <$self>", "rec@agency.com", labels = listOf("SENT"))
        assertEquals(Outcomes.REFUSED, rig.tap("send", id).outcome)
        assertTrue(rig.gmail.sent.isEmpty())
        assertEquals(Outcomes.ALREADY, rig.tap("reply").outcome)
    }

    @Test
    fun `Cancel keeps the draft and disables the preview`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        assertEquals(Outcomes.DONE, rig.tap("cancel", id).outcome)
        assertEquals(1, rig.gmail.drafts.size)
        assertEquals(Outcomes.REFUSED, rig.tap("send", id).outcome)
    }

    @Test
    fun `with no draft and no JFAA text, the agent is asked to write one`() {
        val r = Rig(draftText = null).tap("reply")
        assertEquals(Outcomes.AGENT, r.outcome)
        assertTrue(r.agentPrompt!!.contains("write_reply_draft") && r.agentPrompt!!.contains("request_send_approval"))
        assertNull(r.replyRow)
    }

    @Test
    fun `an approval the model requested renders the same preview`() {
        val rig = Rig()
        rig.replies.writeDraft(9, "Model-written reply")
        val draft = rig.replies.currentDraft(9)!!
        val a = rig.replies.requestApproval(9, "url:acme.co/jobs/9", draft, null)
        val prompt = rig.replies.approvalPrompt(a.id)!!
        assertTrue(prompt.reply!!.contains("Model-written reply"))
        assertEquals("send:${a.id}", prompt.replyRow!!.first().callbackData)
        assertEquals(Outcomes.DONE, rig.tap("send", a.id).outcome)
        assertNull(rig.replies.approvalPrompt(a.id), "a sent approval is no longer pending")
    }

    @Test
    fun `send and cancel taps still need the allowlist`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        assertEquals(Outcomes.REFUSED, rig.taps.tap(TapRequest("send", id, "123", user, 5, now.epochSecond)).outcome)
        assertTrue(rig.gmail.sent.isEmpty())
    }

    @Test
    fun `a stranger hidden in Bcc is caught by both the fingerprint and the recipient check`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        val draftId = rig.gmail.drafts.keys.single()
        val withBcc = Mime.parse(rig.gmail.drafts[draftId]!!).also {
            it.addRecipients(jakarta.mail.Message.RecipientType.BCC, jakarta.mail.internet.InternetAddress.parse("spy@else.com"))
        }
        rig.gmail.drafts[draftId] = Mime.encode(withBcc)
        assertEquals(Outcomes.REFUSED, rig.tap("send", id).outcome, "fingerprint differs")
        assertTrue(rig.gmail.sent.isEmpty())
        // Even a preview that showed the Bcc is refused: spy@else.com is not in the thread.
        val fresh = rig.replies.requestApproval(9, "k", rig.replies.currentDraft(9)!!, null)
        assertTrue(rig.replies.approvalPrompt(fresh.id)!!.reply!!.contains("Bcc: spy@else.com"))
        val r = rig.tap("send", fresh.id)
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertTrue(r.toast!!.contains("spy@else.com"), r.toast)
        assertTrue(rig.gmail.sent.isEmpty())
    }

    @Test
    fun `a preview older than a day no longer sends`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        clock.at = clock.at.plus(Duration.ofHours(25))
        val r = rig.tap("send", id)
        assertEquals(Outcomes.EXPIRED, r.outcome)
        assertTrue(rig.gmail.sent.isEmpty())
    }

    @Test
    fun `an interrupted send is recovered, never stuck`() {
        // Process died after Gmail sent: our message is in the thread → recorded as sent, nothing re-sent.
        val sent = Rig()
        val a = sent.approvalId(sent.tap("reply"))
        sent.store.update(a, ReplySupport.SENDING)
        sent.gmail.drafts.clear()
        sent.gmail.thread += sent.gmail.msg("s1", "Richard <$self>", "rec@agency.com", labels = listOf("SENT"))
        clock.at = clock.at.plus(Duration.ofMinutes(5))
        assertEquals(Outcomes.ALREADY, sent.tap("send", a).outcome)
        assertEquals(Outcomes.DONE, sent.store.byId(a)!!.status)

        // The draft was deleted by hand and nothing was sent → cancelled, never "Already sent".
        val deleted = Rig()
        val c = deleted.approvalId(deleted.tap("reply"))
        deleted.store.update(c, ReplySupport.SENDING)
        deleted.gmail.drafts.clear()
        clock.at = clock.at.plus(Duration.ofMinutes(5))
        val r = deleted.tap("send", c)
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertTrue(r.toast!!.contains("nothing was sent"), r.toast)
        assertEquals(ReplySupport.CANCELLED, deleted.store.byId(c)!!.status)

        // Process died before Gmail sent: the draft is still there → the tap sends it.
        val unsent = Rig()
        val b = unsent.approvalId(unsent.tap("reply"))
        unsent.store.update(b, ReplySupport.SENDING)
        clock.at = clock.at.plus(Duration.ofMinutes(5))
        assertEquals(Outcomes.DONE, unsent.tap("send", b).outcome)
        assertEquals(1, unsent.gmail.sent.size)
    }

    @Test
    fun `a send genuinely in flight is not doubled`() {
        val rig = Rig()
        val a = rig.approvalId(rig.tap("reply"))
        rig.store.update(a, ReplySupport.SENDING)
        assertEquals(Outcomes.REFUSED, rig.tap("send", a).outcome)
        assertTrue(rig.gmail.sent.isEmpty())
    }

    @Test
    fun `a draft edit racing a Send tap can never be sent unseen`() {
        val rig = Rig()
        val id = rig.approvalId(rig.tap("reply"))
        val editing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        rig.gmail.onUpdate = { editing.countDown(); release.await() }
        val writer = Thread { rig.replies.writeDraft(9, "Edited by the model") }.apply { start() }
        editing.await()
        var result: TapResponse? = null
        val tapper = Thread { result = rig.tap("send", id) }.apply { start() }
        Thread.sleep(200)
        assertTrue(rig.gmail.sent.isEmpty(), "Send must wait for the edit in progress")
        release.countDown()
        writer.join(); tapper.join()
        assertEquals(Outcomes.REFUSED, result!!.outcome, "the edited draft no longer matches the preview")
        assertTrue(rig.gmail.sent.isEmpty())
    }
}
