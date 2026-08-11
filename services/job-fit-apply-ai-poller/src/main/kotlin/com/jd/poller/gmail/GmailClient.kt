package com.jd.poller.gmail

import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Draft
import com.google.api.services.gmail.model.Label
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.ModifyMessageRequest
import com.jd.poller.config.PollerConfig
import com.jd.poller.model.RawEmail
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Gmail API wrapper for the Poller — fetch intake emails, apply labels, create draft replies.
 * The [service] is injectable so tests can supply a stub; production builds it from [GmailAuth].
 */
class GmailClient(
    private val service: Gmail = buildService(),
) {
    private val parser = EmailParser

    data class MessageMeta(val from: String, val subject: String)

    // ── Intake ──────────────────────────────────────────────────────────────────

    /**
     * Fetch unprocessed JD emails as [RawEmail]s (subject/body/html) to submit to the bridge.
     * Skips reply messages in already-processed threads.
     */
    fun fetchIntakeEmails(maxResults: Int = PollerConfig.GMAIL_MAX_EMAILS): List<RawEmail> {
        val emails = mutableListOf<RawEmail>()
        val processedLabelIds = getJdProcessedLabelIds()

        val result = service.users().messages()
            .list("me")
            .setQ(PollerConfig.GMAIL_SEARCH_QUERY)
            .setMaxResults(maxResults.toLong())
            .execute()

        val messages = result.messages ?: return emails

        for (msgRef in messages) {
            val msg = service.users().messages()
                .get("me", msgRef.id)
                .setFormat("full")
                .execute()

            val headers = extractHeaders(msg.payload)
            val subject = headers["Subject"] ?: "(no subject)"
            val from = headers["From"] ?: ""

            if (headers["In-Reply-To"] != null && msg.threadId != null) {
                if (isThreadAlreadyProcessed(msg.threadId, processedLabelIds)) {
                    println("[gmail] Skipping reply in already-processed thread: $subject")
                    continue
                }
            }

            val parsed = parser.parse(msg)
            val body = parsed.plainText
            if (body.isBlank()) continue

            emails.add(
                RawEmail(
                    messageId = msgRef.id,
                    subject   = subject,
                    from      = from,
                    body      = body,
                    htmlBody  = parsed.htmlBodies.firstOrNull().orEmpty(),
                    // The Processor's scan node determines recruiter status; no hint from intake.
                    isRecruiterHint = false,
                )
            )
        }
        return emails
    }

    private fun getJdProcessedLabelIds(): Set<String> {
        val processedLabelNames = setOf(
            "JD_Processed",
            "JD_Processed_Digest",
            "JD_Not_Found",
            "Recruiter_Response_Required"
        )
        val labelsResponse = service.users().labels().list("me").execute()
        return labelsResponse.labels
            ?.filter { it.name in processedLabelNames }
            ?.map { it.id }
            ?.toSet()
            ?: emptySet()
    }

    private fun isThreadAlreadyProcessed(threadId: String, processedLabelIds: Set<String>): Boolean {
        if (processedLabelIds.isEmpty()) return false
        val thread = service.users().threads()
            .get("me", threadId)
            .setFormat("metadata")
            .execute()
        return thread.messages?.any { msg ->
            msg.labelIds?.any { it in processedLabelIds } == true
        } ?: false
    }

    // ── Write-back: labels ────────────────────────────────────────────────────────

    fun findLabelId(name: String): String? {
        val labelsResponse = service.users().labels().list("me").execute()
        return labelsResponse.labels?.find { it.name == name }?.id
    }

    fun getOrCreateLabel(labelName: String): String {
        val labelsResponse = service.users().labels().list("me").execute()
        labelsResponse.labels?.forEach { if (it.name == labelName) return it.id }

        val label = Label()
            .setName(labelName)
            .setLabelListVisibility("labelShow")
            .setMessageListVisibility("show")
        return service.users().labels().create("me", label).execute().id
    }

    fun applyLabels(messageId: String, addLabels: List<String>, removeLabels: List<String>) {
        val request = ModifyMessageRequest()
            .setAddLabelIds(addLabels)
            .setRemoveLabelIds(removeLabels)
        service.users().messages().modify("me", messageId, request).execute()
    }

    fun labelEmail(emailId: String, labelId: String) {
        if (labelId.isEmpty()) return
        val request = ModifyMessageRequest().setAddLabelIds(listOf(labelId))
        service.users().messages().modify("me", emailId, request).execute()
    }

    fun archiveEmail(emailId: String) {
        val request = ModifyMessageRequest().setRemoveLabelIds(listOf("INBOX"))
        service.users().messages().modify("me", emailId, request).execute()
    }

    fun starEmail(emailId: String) {
        val request = ModifyMessageRequest().setAddLabelIds(listOf("STARRED"))
        service.users().messages().modify("me", emailId, request).execute()
    }

    fun markUnread(emailId: String) {
        val request = ModifyMessageRequest().setAddLabelIds(listOf("UNREAD"))
        service.users().messages().modify("me", emailId, request).execute()
    }

    // ── Write-back: draft reply ────────────────────────────────────────────────────

    fun createDraftReply(
        originalEmailId: String,
        toAddress: String,
        subject: String,
        body: String,
        attachmentPaths: List<String> = emptyList()
    ): String {
        val original = service.users().messages()
            .get("me", originalEmailId)
            .setFormat("metadata")
            .setMetadataHeaders(listOf("Message-ID", "References"))
            .execute()

        val origHeaders = extractHeaders(original.payload)
        val messageId = origHeaders["Message-ID"] ?: ""
        val references = origHeaders["References"] ?: ""
        val refsHeader = if (references.isNotEmpty()) "$references $messageId".trim() else messageId
        val threadId = original.threadId ?: ""

        val props = java.util.Properties()
        val session = Session.getDefaultInstance(props, null)
        val mime = MimeMessage(session)
        mime.setRecipients(javax.mail.Message.RecipientType.TO, InternetAddress.parse(toAddress))
        mime.subject = subject
        if (messageId.isNotEmpty()) {
            mime.setHeader("In-Reply-To", messageId)
            mime.setHeader("References", refsHeader)
        }

        val validAttachments = attachmentPaths.filter { File(it).exists() }
        if (validAttachments.isEmpty()) {
            mime.setText(body, "UTF-8")
        } else {
            val multipart = MimeMultipart()
            val textPart = MimeBodyPart()
            textPart.setText(body, "UTF-8")
            multipart.addBodyPart(textPart)
            for (path in validAttachments) {
                val attachPart = MimeBodyPart()
                attachPart.attachFile(File(path))
                // Override the temp-file name ("poller-artifact-*.pdf") with a clean,
                // professional name the recruiter will see in their inbox.
                val file = File(path)
                if (file.extension.equals("pdf", ignoreCase = true)) {
                    attachPart.fileName = "RichardHatcherResume.pdf"
                }
                multipart.addBodyPart(attachPart)
            }
            mime.setContent(multipart)
        }

        val buffer = ByteArrayOutputStream()
        mime.writeTo(buffer)
        val encodedEmail = Base64.getUrlEncoder().encodeToString(buffer.toByteArray())

        val message = Message().setRaw(encodedEmail)
        if (threadId.isNotEmpty()) message.threadId = threadId

        val draft = Draft().setMessage(message)
        return service.users().drafts().create("me", draft).execute().id ?: ""
    }

    /** The original message's From/Subject headers — used to address a recruiter draft reply. */
    fun getMessageMeta(messageId: String): MessageMeta {
        val msg = service.users().messages()
            .get("me", messageId)
            .setFormat("metadata")
            .setMetadataHeaders(listOf("From", "Subject"))
            .execute()
        val h = extractHeaders(msg.payload)
        return MessageMeta(from = h["From"] ?: "", subject = h["Subject"] ?: "")
    }

    fun checkTokenStatus(): GmailAuth.TokenCheckResult = GmailAuth.checkTokenStatus()

    private fun extractHeaders(payload: MessagePart?): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        payload?.headers?.forEach { headers[it.name] = it.value }
        return headers
    }

    companion object {
        private fun buildService(): Gmail {
            val credential = GmailAuth.getCredentials()
            return Gmail.Builder(
                com.google.api.client.http.javanet.NetHttpTransport(),
                com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("JD Poller").build()
        }
    }
}
