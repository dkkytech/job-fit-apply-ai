package com.jd.poller.gmail

import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Draft
import com.google.api.services.gmail.model.Label
import com.google.api.services.gmail.model.ListLabelsResponse
import com.google.api.services.gmail.model.ListMessagesResponse
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.MessagePartBody
import com.google.api.services.gmail.model.MessagePartHeader
import com.google.api.services.gmail.model.Thread
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Answers
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GmailClient wraps the generated Gmail API surface. The [Gmail] service is a plain (non-final)
 * class from google-api-client, so it — and its nested request-builder classes — can be mocked
 * directly with Mockito's inline mock maker (already the mockito-core 5.x default) without any
 * live network call. This exercises the request-building / response-mapping logic that was
 * previously untested.
 */
@DisplayName("GmailClientTest")
class GmailClientTest {

    /** Wires up the users()/messages()/labels()/threads()/drafts() fluent root once per test. */
    private class Rig {
        val gmail: Gmail = mock()
        val users: Gmail.Users = mock()
        val messages: Gmail.Users.Messages = mock()
        val labels: Gmail.Users.Labels = mock()
        val threads: Gmail.Users.Threads = mock()
        val drafts: Gmail.Users.Drafts = mock()

        init {
            whenever(gmail.users()).thenReturn(users)
            whenever(users.messages()).thenReturn(messages)
            whenever(users.labels()).thenReturn(labels)
            whenever(users.threads()).thenReturn(threads)
            whenever(users.drafts()).thenReturn(drafts)
        }

        fun client() = GmailClient(gmail)
    }

    private fun header(name: String, value: String) = MessagePartHeader().setName(name).setValue(value)

    private fun b64(s: String): String = Base64.getUrlEncoder().encodeToString(s.toByteArray())

    // ── findLabelId ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findLabelId returns the matching label's id")
    fun findLabelIdFound() {
        val rig = Rig()
        val listReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(listReq)
        whenever(listReq.execute()).thenReturn(
            ListLabelsResponse().setLabels(listOf(Label().setId("lbl-1").setName("JD_Processed")))
        )

        assertEquals("lbl-1", rig.client().findLabelId("JD_Processed"))
    }

    @Test
    @DisplayName("findLabelId returns null when no label matches")
    fun findLabelIdNotFound() {
        val rig = Rig()
        val listReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(listReq)
        whenever(listReq.execute()).thenReturn(
            ListLabelsResponse().setLabels(listOf(Label().setId("lbl-1").setName("Other")))
        )

        assertNull(rig.client().findLabelId("JD_Processed"))
    }

    @Test
    @DisplayName("findLabelId is null-safe when the API returns no labels list")
    fun findLabelIdNullLabelsList() {
        val rig = Rig()
        val listReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(listReq)
        whenever(listReq.execute()).thenReturn(ListLabelsResponse())

        assertNull(rig.client().findLabelId("JD_Processed"))
    }

    // ── getOrCreateLabel ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrCreateLabel returns the existing label id without creating one")
    fun getOrCreateLabelExisting() {
        val rig = Rig()
        val listReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(listReq)
        whenever(listReq.execute()).thenReturn(
            ListLabelsResponse().setLabels(listOf(Label().setId("lbl-9").setName("Recruiter_Response_Required")))
        )

        assertEquals("lbl-9", rig.client().getOrCreateLabel("Recruiter_Response_Required"))
        verify(rig.labels, never()).create(any(), any())
    }

    @Test
    @DisplayName("getOrCreateLabel creates a new visible label when none matches")
    fun getOrCreateLabelCreatesNew() {
        val rig = Rig()
        val listReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(listReq)
        whenever(listReq.execute()).thenReturn(ListLabelsResponse().setLabels(emptyList()))

        val createReq = mock<Gmail.Users.Labels.Create>()
        whenever(rig.labels.create(eq("me"), any())).thenReturn(createReq)
        whenever(createReq.execute()).thenReturn(Label().setId("lbl-new"))

        assertEquals("lbl-new", rig.client().getOrCreateLabel("Brand_New_Label"))
        verify(rig.labels).create(
            eq("me"),
            argThat { name == "Brand_New_Label" && labelListVisibility == "labelShow" && messageListVisibility == "show" },
        )
    }

    @Test
    @DisplayName("getOrCreateLabel creates when the API returns no labels list at all")
    fun getOrCreateLabelCreatesWhenListNull() {
        val rig = Rig()
        val listReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(listReq)
        whenever(listReq.execute()).thenReturn(ListLabelsResponse())

        val createReq = mock<Gmail.Users.Labels.Create>()
        whenever(rig.labels.create(eq("me"), any())).thenReturn(createReq)
        whenever(createReq.execute()).thenReturn(Label().setId("lbl-x"))

        assertEquals("lbl-x", rig.client().getOrCreateLabel("Whatever"))
    }

    // ── applyLabels / labelEmail / archive / star / markUnread ───────────────────

    @Test
    @DisplayName("applyLabels sends the add and remove label ids and executes")
    fun applyLabelsSendsBoth() {
        val rig = Rig()
        val modifyReq = mock<Gmail.Users.Messages.Modify>()
        whenever(rig.messages.modify(eq("me"), eq("m1"), any())).thenReturn(modifyReq)

        rig.client().applyLabels("m1", addLabels = listOf("A"), removeLabels = listOf("B"))

        verify(rig.messages).modify(
            eq("me"), eq("m1"),
            argThat { addLabelIds == listOf("A") && removeLabelIds == listOf("B") },
        )
        verify(modifyReq).execute()
    }

    @Test
    @DisplayName("labelEmail with a blank labelId never calls the Gmail API")
    fun labelEmailBlankIdIsNoOp() {
        val rig = Rig()
        rig.client().labelEmail("m1", "")
        verifyNoInteractions(rig.gmail)
    }

    @Test
    @DisplayName("labelEmail with a non-blank labelId modifies with addLabelIds")
    fun labelEmailAppliesLabel() {
        val rig = Rig()
        val modifyReq = mock<Gmail.Users.Messages.Modify>()
        whenever(rig.messages.modify(eq("me"), eq("m1"), any())).thenReturn(modifyReq)

        rig.client().labelEmail("m1", "lbl-1")

        verify(rig.messages).modify(eq("me"), eq("m1"), argThat { addLabelIds == listOf("lbl-1") })
        verify(modifyReq).execute()
    }

    @Test
    @DisplayName("archiveEmail removes INBOX")
    fun archiveEmailRemovesInbox() {
        val rig = Rig()
        val modifyReq = mock<Gmail.Users.Messages.Modify>()
        whenever(rig.messages.modify(eq("me"), eq("m1"), any())).thenReturn(modifyReq)

        rig.client().archiveEmail("m1")

        verify(rig.messages).modify(eq("me"), eq("m1"), argThat { removeLabelIds == listOf("INBOX") })
        verify(modifyReq).execute()
    }

    @Test
    @DisplayName("starEmail adds STARRED")
    fun starEmailAddsStarred() {
        val rig = Rig()
        val modifyReq = mock<Gmail.Users.Messages.Modify>()
        whenever(rig.messages.modify(eq("me"), eq("m1"), any())).thenReturn(modifyReq)

        rig.client().starEmail("m1")

        verify(rig.messages).modify(eq("me"), eq("m1"), argThat { addLabelIds == listOf("STARRED") })
    }

    @Test
    @DisplayName("markUnread adds UNREAD")
    fun markUnreadAddsUnread() {
        val rig = Rig()
        val modifyReq = mock<Gmail.Users.Messages.Modify>()
        whenever(rig.messages.modify(eq("me"), eq("m1"), any())).thenReturn(modifyReq)

        rig.client().markUnread("m1")

        verify(rig.messages).modify(eq("me"), eq("m1"), argThat { addLabelIds == listOf("UNREAD") })
    }

    // ── getMessageMeta ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMessageMeta reads From/Subject headers")
    fun getMessageMetaReadsHeaders() {
        val rig = Rig()
        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "m1")).thenReturn(getReq)
        whenever(getReq.execute()).thenReturn(
            Message().setPayload(
                MessagePart().setHeaders(
                    listOf(header("From", "Jane <jane@firm.com>"), header("Subject", "Staff SDET"))
                )
            )
        )

        val meta = rig.client().getMessageMeta("m1")
        assertEquals("Jane <jane@firm.com>", meta.from)
        assertEquals("Staff SDET", meta.subject)
        verify(getReq).setFormat("metadata")
        verify(getReq).setMetadataHeaders(listOf("From", "Subject"))
    }

    @Test
    @DisplayName("getMessageMeta defaults to blank strings when headers are absent")
    fun getMessageMetaMissingHeaders() {
        val rig = Rig()
        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "m1")).thenReturn(getReq)
        whenever(getReq.execute()).thenReturn(Message())

        val meta = rig.client().getMessageMeta("m1")
        assertEquals("", meta.from)
        assertEquals("", meta.subject)
    }

    // ── fetchIntakeEmails ──────────────────────────────────────────────────────

    private fun stubNoProcessedLabels(rig: Rig) {
        val labelsListReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(labelsListReq)
        whenever(labelsListReq.execute()).thenReturn(ListLabelsResponse())
    }

    private fun stubMessageList(rig: Rig, ids: List<String>): Gmail.Users.Messages.List {
        val listReq = mock<Gmail.Users.Messages.List>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.list("me")).thenReturn(listReq)
        whenever(listReq.execute()).thenReturn(
            ListMessagesResponse().setMessages(ids.map { Message().setId(it) })
        )
        return listReq
    }

    @Test
    @DisplayName("fetchIntakeEmails returns empty when the search yields no messages")
    fun fetchIntakeEmailsEmptyResult() {
        val rig = Rig()
        stubNoProcessedLabels(rig)
        val listReq = mock<Gmail.Users.Messages.List>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.list("me")).thenReturn(listReq)
        whenever(listReq.execute()).thenReturn(ListMessagesResponse())   // messages == null

        assertTrue(rig.client().fetchIntakeEmails().isEmpty())
    }

    @Test
    @DisplayName("fetchIntakeEmails decodes a plain-text message into a RawEmail")
    fun fetchIntakeEmailsHappyPath() {
        val rig = Rig()
        stubNoProcessedLabels(rig)
        val listReq = stubMessageList(rig, listOf("m1"))

        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "m1")).thenReturn(getReq)
        whenever(getReq.execute()).thenReturn(
            Message().setId("m1").setPayload(
                MessagePart()
                    .setMimeType("text/plain")
                    .setHeaders(listOf(header("Subject", "Great role"), header("From", "rec@firm.com")))
                    .setBody(MessagePartBody().setData(b64("We have a role for you.")))
            )
        )

        val result = rig.client().fetchIntakeEmails(maxResults = 5)
        assertEquals(1, result.size)
        assertEquals("m1", result[0].messageId)
        assertEquals("Great role", result[0].subject)
        assertEquals("rec@firm.com", result[0].from)
        assertEquals("We have a role for you.", result[0].body)
        verify(listReq).setMaxResults(5L)
    }

    @Test
    @DisplayName("fetchIntakeEmails skips a message that decodes to a blank body")
    fun fetchIntakeEmailsSkipsBlankBody() {
        val rig = Rig()
        stubNoProcessedLabels(rig)
        stubMessageList(rig, listOf("m1"))

        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "m1")).thenReturn(getReq)
        // Unsupported mime type with no matching decode branch -> decodeBody() returns "".
        whenever(getReq.execute()).thenReturn(
            Message().setId("m1").setPayload(MessagePart().setMimeType("application/octet-stream"))
        )

        assertTrue(rig.client().fetchIntakeEmails().isEmpty())
    }

    @Test
    @DisplayName("fetchIntakeEmails skips a reply in an already-processed thread")
    fun fetchIntakeEmailsSkipsProcessedThreadReply() {
        val rig = Rig()
        val labelsListReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(labelsListReq)
        whenever(labelsListReq.execute()).thenReturn(
            ListLabelsResponse().setLabels(listOf(Label().setId("proc-id").setName("JD_Processed")))
        )
        stubMessageList(rig, listOf("m1"))

        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "m1")).thenReturn(getReq)
        whenever(getReq.execute()).thenReturn(
            Message().setId("m1").setThreadId("t1").setPayload(
                MessagePart()
                    .setMimeType("text/plain")
                    .setHeaders(listOf(header("In-Reply-To", "<orig@x.com>"), header("Subject", "Re: role")))
                    .setBody(MessagePartBody().setData(b64("thanks")))
            )
        )

        val threadsGetReq = mock<Gmail.Users.Threads.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.threads.get("me", "t1")).thenReturn(threadsGetReq)
        whenever(threadsGetReq.execute()).thenReturn(
            Thread().setMessages(listOf(Message().setLabelIds(listOf("proc-id"))))
        )

        assertTrue(rig.client().fetchIntakeEmails().isEmpty())
    }

    @Test
    @DisplayName("fetchIntakeEmails includes a reply whose thread is NOT already processed")
    fun fetchIntakeEmailsIncludesUnprocessedThreadReply() {
        val rig = Rig()
        val labelsListReq = mock<Gmail.Users.Labels.List>()
        whenever(rig.labels.list("me")).thenReturn(labelsListReq)
        whenever(labelsListReq.execute()).thenReturn(
            ListLabelsResponse().setLabels(listOf(Label().setId("proc-id").setName("JD_Processed")))
        )
        stubMessageList(rig, listOf("m1"))

        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "m1")).thenReturn(getReq)
        whenever(getReq.execute()).thenReturn(
            Message().setId("m1").setThreadId("t1").setPayload(
                MessagePart()
                    .setMimeType("text/plain")
                    .setHeaders(listOf(header("In-Reply-To", "<orig@x.com>"), header("Subject", "Re: role")))
                    .setBody(MessagePartBody().setData(b64("thanks")))
            )
        )

        val threadsGetReq = mock<Gmail.Users.Threads.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.threads.get("me", "t1")).thenReturn(threadsGetReq)
        whenever(threadsGetReq.execute()).thenReturn(
            Thread().setMessages(listOf(Message().setLabelIds(listOf("some-other-label"))))
        )

        val result = rig.client().fetchIntakeEmails()
        assertEquals(1, result.size)
        assertEquals("thanks", result[0].body)
    }

    // ── createDraftReply ───────────────────────────────────────────────────────

    @Test
    @DisplayName("createDraftReply sets In-Reply-To/References when the original has a Message-ID, and returns the new draft id")
    fun createDraftReplySetsThreadingHeaders() {
        val rig = Rig()
        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "orig1")).thenReturn(getReq)
        whenever(getReq.execute()).thenReturn(
            Message().setThreadId("t1").setPayload(
                MessagePart().setHeaders(listOf(header("Message-ID", "<abc@firm.com>")))
            )
        )

        val createReq = mock<Gmail.Users.Drafts.Create>()
        whenever(rig.drafts.create(eq("me"), any())).thenReturn(createReq)
        whenever(createReq.execute()).thenReturn(Draft().setId("draft-1"))

        val id = rig.client().createDraftReply("orig1", "to@x.com", "Re: role", "Thanks!")
        assertEquals("draft-1", id)

        val mime = capturedMimeMessage(rig)
        assertEquals("<abc@firm.com>", mime.getHeader("In-Reply-To", null))
        assertEquals("<abc@firm.com>", mime.getHeader("References", null))
        assertEquals("t1", capturedDraftMessage(rig).threadId)
    }

    @Test
    @DisplayName("createDraftReply omits threading headers when the original has no Message-ID")
    fun createDraftReplyNoMessageId() {
        val rig = Rig()
        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "orig1")).thenReturn(getReq)
        whenever(getReq.execute()).thenReturn(Message().setPayload(MessagePart()))

        val createReq = mock<Gmail.Users.Drafts.Create>()
        whenever(rig.drafts.create(eq("me"), any())).thenReturn(createReq)
        whenever(createReq.execute()).thenReturn(Draft().setId("draft-2"))

        rig.client().createDraftReply("orig1", "to@x.com", "Re: role", "Thanks!")

        val mime = capturedMimeMessage(rig)
        assertNull(mime.getHeader("In-Reply-To", null))
    }

    @Test
    @DisplayName("createDraftReply attaches only paths that exist on disk")
    fun createDraftReplyAttachesExistingFiles(@TempDir dir: Path) {
        val rig = Rig()
        val getReq = mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(rig.messages.get("me", "orig1")).thenReturn(getReq)
        whenever(getReq.execute()).thenReturn(Message().setPayload(MessagePart()))

        val createReq = mock<Gmail.Users.Drafts.Create>()
        whenever(rig.drafts.create(eq("me"), any())).thenReturn(createReq)
        whenever(createReq.execute()).thenReturn(Draft().setId("draft-3"))

        val realFile = dir.resolve("resume.pdf").toFile().apply { writeText("PDF") }
        val id = rig.client().createDraftReply(
            "orig1", "to@x.com", "Re: role", "Thanks!",
            attachmentPaths = listOf(realFile.absolutePath, "/no/such/file.pdf"),
        )

        assertEquals("draft-3", id)
        val mime = capturedMimeMessage(rig)
        assertTrue(mime.content is javax.mail.Multipart, "attachment present -> multipart content")
        val mp = mime.content as javax.mail.Multipart
        assertEquals(2, mp.count)   // text part + the one real attachment

        // The PDF attachment must be renamed to RichardHatcherResume.pdf — the temp file
        // "poller-artifact-*.pdf" name must never leak into the recruiter's inbox.
        val attachPart = mp.getBodyPart(1) as javax.mail.internet.MimeBodyPart
        assertEquals("RichardHatcherResume.pdf", attachPart.fileName, "PDF attachment should be named RichardHatcherResume.pdf")
    }

    private fun capturedDraftMessage(rig: Rig): Message {
        val captor = org.mockito.kotlin.argumentCaptor<Draft>()
        verify(rig.drafts).create(eq("me"), captor.capture())
        return captor.firstValue.message
    }

    private fun capturedMimeMessage(rig: Rig): javax.mail.internet.MimeMessage {
        val raw = capturedDraftMessage(rig).raw
        val bytes = Base64.getUrlDecoder().decode(raw)
        val session = javax.mail.Session.getDefaultInstance(java.util.Properties(), null)
        return javax.mail.internet.MimeMessage(session, bytes.inputStream())
    }

    // ── checkTokenStatus delegate ──────────────────────────────────────────────

    @Test
    @DisplayName("checkTokenStatus delegates to GmailAuth (no stored token in this environment -> MISSING)")
    fun checkTokenStatusDelegatesToGmailAuth() {
        val rig = Rig()
        val result = rig.client().checkTokenStatus()
        assertEquals(GmailAuth.TokenStatus.MISSING, result.status)
    }
}
