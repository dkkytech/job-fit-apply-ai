package com.jd.pipeline.client.gmail

import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Draft
import com.google.api.services.gmail.model.Label
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.ModifyMessageRequest
import com.jd.pipeline.config.Config
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class GmailTransport {
    private val service: Gmail = buildService()
    private val parser = EmailParser

    data class EmailInvestigationBundle(
        val state: JDState,
        val plainTextBody: String,
        val htmlBodies: List<String>,
        val inlineScripts: List<String>,
        val scriptUrls: List<String>,
        val partSummary: String
    )

    private fun buildService(): Gmail {
        val credential = GmailAuth.getCredentials()
        return Gmail.Builder(
            com.google.api.client.http.javanet.NetHttpTransport(),
            com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("JD Pipeline").build()
    }

    fun fetchJdEmails(maxResults: Int, debug: Boolean): List<JDState> {
        val emails = mutableListOf<JDState>()
        val processedLabelIds = getJdProcessedLabelIds()

        val result = service.users().messages()
            .list("me")
            .setQ(Config.GMAIL_SEARCH_QUERY)
            .setMaxResults(maxResults.toLong())
            .execute()

        val messages = result.messages
        if (messages == null || messages.isEmpty()) {
            return emails
        }

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
                    println("[fetch] Skipping reply in already-processed thread: $subject")
                    continue
                }
            }

            val parsed = parser.parse(msg)
            val body = parsed.plainText
            val htmlBody = parsed.htmlBodies.firstOrNull().orEmpty()

            if (body.isNotBlank()) {
                if (debug) {
                    debugLog(subject, body)
                }
                val state = JDState(
                    intake = IntakeContext.Email(
                        emailId = msgRef.id,
                        subject = subject,
                        from = from,
                        rawBody = body,
                        htmlBody = htmlBody,
                        isRecruiter = false,
                        isDigest = false,
                        isInlineDigest = false,
                    ),
                    candidateProfile = JDState.loadCandidateProfile(),
                )
                emails.add(state)
            }
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

    fun findLabelId(name: String): String? {
        val labelsResponse = service.users().labels().list("me").execute()
        return labelsResponse.labels?.find { it.name == name }?.id
    }

    fun fetchEmailBySubject(subject: String, debug: Boolean): JDState? {
        return fetchEmailInvestigationBySubject(subject, debug)?.state
    }

    fun fetchEmailInvestigationBySubject(subject: String, debug: Boolean): EmailInvestigationBundle? {
        val msg = fetchFullMessageBySubject(subject) ?: return null
        return buildInvestigationBundle(msg, debug)
    }

    fun applyLabels(messageId: String, addLabels: List<String>, removeLabels: List<String>) {
        val request = ModifyMessageRequest()
            .setAddLabelIds(addLabels)
            .setRemoveLabelIds(removeLabels)
        service.users().messages()
            .modify("me", messageId, request)
            .execute()
    }

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
                multipart.addBodyPart(attachPart)
            }
            mime.setContent(multipart)
        }

        val buffer = ByteArrayOutputStream()
        mime.writeTo(buffer)
        val encodedEmail = Base64.getUrlEncoder().encodeToString(buffer.toByteArray())

        val message = Message().setRaw(encodedEmail)
        if (threadId.isNotEmpty()) {
            message.threadId = threadId
        }

        val draft = Draft().setMessage(message)
        val created = service.users().drafts().create("me", draft).execute()
        return created.id ?: ""
    }

    fun checkTokenStatus(): GmailAuth.TokenCheckResult {
        return GmailAuth.checkTokenStatus()
    }

    private fun fetchFullMessageBySubject(subject: String): Message? {
        val result = service.users().messages()
            .list("me")
            .setQ("subject:$subject")
            .setMaxResults(1)
            .execute()

        val messages = result.messages
        if (messages.isNullOrEmpty()) {
            return null
        }

        return service.users().messages()
            .get("me", messages[0].id)
            .setFormat("full")
            .execute()
    }

    private fun buildInvestigationBundle(msg: Message, debug: Boolean): EmailInvestigationBundle {
        val headers = extractHeaders(msg.payload)
        val parsed = parser.parse(msg)

        val subjectLine = headers["Subject"] ?: "(no subject)"
        val from = headers["From"] ?: ""

        if (debug) {
            debugLog(subjectLine, parsed.plainText)
        }

        val state = JDState(
            intake = IntakeContext.Email(
                emailId = msg.id ?: "",
                subject = subjectLine,
                from = from,
                rawBody = parsed.plainText,
                htmlBody = parsed.htmlBodies.firstOrNull().orEmpty(),
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false,
            ),
            candidateProfile = JDState.loadCandidateProfile(),
        )

        return EmailInvestigationBundle(
            state = state,
            plainTextBody = parsed.plainText,
            htmlBodies = parsed.htmlBodies,
            inlineScripts = parsed.inlineScripts,
            scriptUrls = parsed.scriptUrls,
            partSummary = parsed.partSummary
        )
    }

    private fun extractHeaders(payload: MessagePart?): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (payload?.headers != null) {
            for (header in payload.headers) {
                headers[header.name] = header.value
            }
        }
        return headers
    }

    fun getOrCreateLabel(labelName: String): String {
        val labelsResponse = service.users().labels()
            .list("me")
            .execute()

        val labels = labelsResponse.labels
        if (labels != null) {
            for (label in labels) {
                if (label.name == labelName) {
                    return label.id
                }
            }
        }

        val label = Label()
            .setName(labelName)
            .setLabelListVisibility("labelShow")
            .setMessageListVisibility("show")

        val created = service.users().labels()
            .create("me", label)
            .execute()

        return created.id
    }

    fun labelEmail(emailId: String, labelId: String) {
        if (labelId.isEmpty()) return

        val request = ModifyMessageRequest()
            .setAddLabelIds(listOf(labelId))

        service.users().messages()
            .modify("me", emailId, request)
            .execute()
    }

    fun archiveEmail(emailId: String) {
        val request = ModifyMessageRequest()
            .setRemoveLabelIds(listOf("INBOX"))

        service.users().messages()
            .modify("me", emailId, request)
            .execute()
    }

    fun starEmail(emailId: String) {
        val request = ModifyMessageRequest()
            .setAddLabelIds(listOf("STARRED"))

        service.users().messages()
            .modify("me", emailId, request)
            .execute()
    }

    fun markUnread(emailId: String) {
        val request = ModifyMessageRequest()
            .setAddLabelIds(listOf("UNREAD"))

        service.users().messages()
            .modify("me", emailId, request)
            .execute()
    }

    private fun debugLog(subject: String, body: String) {
        val entry = "=== SUBJECT: $subject ===\n$body\n\n"
        print(entry)
        val debugLog = Path.of("debug.log")
        try {
            Files.writeString(debugLog, entry, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)
        } catch (e: Exception) {
            System.err.println("[gmail_transport] Failed to write debug log: ${e.message}")
        }
    }
}
