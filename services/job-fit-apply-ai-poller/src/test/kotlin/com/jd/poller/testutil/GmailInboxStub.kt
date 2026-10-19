package com.jd.poller.testutil

import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Label
import com.google.api.services.gmail.model.ListLabelsResponse
import com.google.api.services.gmail.model.ListMessagesResponse
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.MessagePartBody
import com.google.api.services.gmail.model.MessagePartHeader
import com.google.api.services.gmail.model.Thread
import org.mockito.Answers
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Base64

/**
 * A mocked [Gmail] serving [inbox] as the newest-first intake listing. Like Gmail, each
 * `messages.list` call returns at most the requested `maxResults` ids (further capped at
 * [serverPageSize], to force paging) plus a `nextPageToken` while more remain. Threads in
 * [processedThreads] carry a JD_Processed message, so replies in them are skipped by intake.
 * Fetches are recorded in [fetched] / [threadLookups] so tests can assert what was touched.
 */
class GmailInboxStub(
    inbox: List<Message>,
    processedThreads: Set<String> = emptySet(),
    serverPageSize: Int = Int.MAX_VALUE,
) {
    val gmail: Gmail = mock()
    val messages: Gmail.Users.Messages = mock()
    val listReq: Gmail.Users.Messages.List = mock(defaultAnswer = Answers.RETURNS_SELF)
    val fetched = mutableListOf<String>()
    val threadLookups = mutableListOf<String>()

    init {
        val users = mock<Gmail.Users>()
        val labels = mock<Gmail.Users.Labels>()
        val threads = mock<Gmail.Users.Threads>()
        whenever(gmail.users()).thenReturn(users)
        whenever(users.messages()).thenReturn(messages)
        whenever(users.labels()).thenReturn(labels)
        whenever(users.threads()).thenReturn(threads)

        val labelsListReq = mock<Gmail.Users.Labels.List>()
        whenever(labels.list("me")).thenReturn(labelsListReq)
        whenever(labelsListReq.execute()).thenReturn(
            ListLabelsResponse().setLabels(listOf(Label().setId(PROCESSED_LABEL_ID).setName("JD_Processed")))
        )

        var requestedMax = 100L   // Gmail's default page size
        var offset = 0
        whenever(messages.list("me")).thenReturn(listReq)
        whenever(listReq.setMaxResults(any())).thenAnswer { requestedMax = it.getArgument(0); listReq }
        whenever(listReq.setPageToken(anyOrNull())).thenAnswer {
            offset = it.getArgument<String?>(0)?.removePrefix("offset-")?.toInt() ?: 0
            listReq
        }
        whenever(listReq.execute()).thenAnswer {
            val end = minOf(inbox.size, offset + minOf(requestedMax, serverPageSize.toLong()).toInt())
            ListMessagesResponse()
                .setMessages(inbox.subList(offset, end).map { Message().setId(it.id) }.ifEmpty { null })
                .setNextPageToken(if (end < inbox.size) "offset-$end" else null)
        }

        val byId = inbox.associateBy { it.id }
        whenever(messages.get(eq("me"), any())).thenAnswer { inv ->
            val id = inv.getArgument<String>(1)
            fetched += id
            mock<Gmail.Users.Messages.Get>(defaultAnswer = Answers.RETURNS_SELF).also {
                whenever(it.execute()).thenReturn(byId.getValue(id))
            }
        }

        whenever(threads.get(eq("me"), any())).thenAnswer { inv ->
            val threadId = inv.getArgument<String>(1)
            threadLookups += threadId
            val threadLabels = if (threadId in processedThreads) listOf(PROCESSED_LABEL_ID) else listOf("INBOX")
            mock<Gmail.Users.Threads.Get>(defaultAnswer = Answers.RETURNS_SELF).also {
                whenever(it.execute()).thenReturn(Thread().setMessages(listOf(Message().setLabelIds(threadLabels))))
            }
        }
    }

    companion object {
        const val PROCESSED_LABEL_ID = "proc-id"

        private fun header(name: String, value: String) = MessagePartHeader().setName(name).setValue(value)
        private fun b64(s: String): String = Base64.getUrlEncoder().encodeToString(s.toByteArray())

        /** A fresh (non-reply) plain-text posting. */
        fun posting(id: String, body: String = "JD for $id") = Message().setId(id).setThreadId("t-$id").setPayload(
            MessagePart()
                .setMimeType("text/plain")
                .setHeaders(listOf(header("Subject", "Role $id"), header("From", "jobs@board.com")))
                .setBody(MessagePartBody().setData(b64(body)))
        )

        /** A reply in [threadId]; skipped by intake when that thread is processed. */
        fun reply(id: String, threadId: String) = Message().setId(id).setThreadId(threadId).setPayload(
            MessagePart()
                .setMimeType("text/plain")
                .setHeaders(listOf(header("Subject", "Re: role"), header("In-Reply-To", "<orig-$threadId@x.com>")))
                .setBody(MessagePartBody().setData(b64("Thanks, let's talk")))
        )

        /** A message whose body decodes blank (no decodable part), which intake skips. */
        fun blankBody(id: String) = Message().setId(id).setThreadId("t-$id").setPayload(
            MessagePart().setMimeType("application/octet-stream").setHeaders(listOf(header("Subject", "(blank)")))
        )
    }
}
