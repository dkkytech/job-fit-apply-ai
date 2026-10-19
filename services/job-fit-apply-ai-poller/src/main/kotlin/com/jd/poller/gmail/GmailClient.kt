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
    private val maxScanPerPass: Int = PollerConfig.INTAKE_MAX_SCAN,
) {
    private val parser = EmailParser

    data class MessageMeta(val from: String, val subject: String)

    // ── Intake ──────────────────────────────────────────────────────────────────

    /**
     * Message ids already known to be skipped by intake (replies in processed threads, blank
     * bodies). Both verdicts are stable for a given message, and the skipped message is never
     * labeled — it keeps matching the intake query — so remembering it avoids a full-format
     * re-fetch every pass. In-memory only and bounded; after a restart the ids are re-learned.
     */
    private val knownSkipped: MutableSet<String> = java.util.Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
                size > SKIP_CACHE_MAX
        },
    )

    /**
     * Fetch up to [batchSize] unprocessed JD emails as [RawEmail]s (subject/body/html) to submit
     * to the bridge. Skips reply messages in already-processed threads and blank-body messages.
     *
     * Skipped messages are never labeled, so they keep matching the query and would crowd older
     * unprocessed mail out of a single [batchSize]-sized listing. Instead, page through the
     * newest-first results until [batchSize] processable messages are found, fetching at most
     * [maxScanPerPass] messages and listing at most [MAX_LIST_PAGES] pages per pass.
     */
    fun fetchIntakeEmails(batchSize: Int = PollerConfig.INTAKE_BATCH_SIZE): List<RawEmail> {
        val emails = mutableListOf<RawEmail>()
        val processedLabelIds = getJdProcessedLabelIds()
        var fetched = 0
        var pageToken: String? = null
        var pages = 0

        pages@ while (emails.size < batchSize && fetched < maxScanPerPass && pages < MAX_LIST_PAGES) {
            val result = service.users().messages()
                .list("me")
                .setQ(PollerConfig.GMAIL_SEARCH_QUERY)
                .setMaxResults(LIST_PAGE_SIZE.toLong())
                .setPageToken(pageToken)
                .execute()
            pages++

            for (msgRef in result.messages.orEmpty()) {
                if (emails.size >= batchSize || fetched >= maxScanPerPass) break@pages
                if (msgRef.id in knownSkipped) continue
                fetched++
                val email = fetchProcessable(msgRef.id, processedLabelIds)
                if (email == null) knownSkipped.add(msgRef.id) else emails.add(email)
            }
            pageToken = result.nextPageToken ?: break
        }
        return emails
    }

    /** Fetch one message; null when intake skips it (processed-thread reply or blank body). */
    private fun fetchProcessable(messageId: String, processedLabelIds: Set<String>): RawEmail? {
        val msg = service.users().messages()
            .get("me", messageId)
            .setFormat("full")
            .execute()

        val headers = extractHeaders(msg.payload)
        val subject = headers["Subject"] ?: "(no subject)"
        val from = headers["From"] ?: ""

        if (headers["In-Reply-To"] != null && msg.threadId != null) {
            if (isThreadAlreadyProcessed(msg.threadId, processedLabelIds)) {
                println("[gmail] Skipping reply in already-processed thread: $subject")
                return null
            }
        }

        val parsed = parser.parse(msg)
        val body = parsed.plainText
        if (body.isBlank()) return null

        return RawEmail(
            messageId = messageId,
            subject   = subject,
            from      = from,
            body      = body,
            htmlBody  = parsed.htmlBodies.firstOrNull().orEmpty(),
            // The Processor's scan node determines recruiter status; no hint from intake.
            isRecruiterHint = false,
        )
    }

    private fun getJdProcessedLabelIds(): Set<String> {
        val processedLabelNames = setOf(
            "JD_Processed",
            "JD_Processed_Digest",
            "JD_Not_Found",
            "JD_Scrape_Failed",
            "Recruiter_Response_Required",
            "JD_Application_Update",
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
        /** Ids per `messages.list` page (Gmail allows up to 500; ids only, so pages are cheap). */
        internal const val LIST_PAGE_SIZE = 100
        /** Hard bound on list calls per intake pass, independent of how many ids are cached as skipped. */
        internal const val MAX_LIST_PAGES = 10
        /** Bound on remembered skipped ids (a week of skipped inbox mail fits comfortably). */
        internal const val SKIP_CACHE_MAX = 5_000

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
