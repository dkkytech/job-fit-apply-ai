package com.jd.jobbot.gmail

import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

/**
 * RFC 822 helpers for reply drafts. A draft is read from Gmail's `raw` form, shown as a [View],
 * and edited by swapping only its text body — headers and attachments stay byte-exact.
 */
object Mime {
    private val session = Session.getInstance(Properties())

    data class View(
        val to: List<String>,
        val cc: List<String>,
        val subject: String,
        /** Hidden recipients still receive the mail: they are approved and checked like the rest. */
        val bcc: List<String> = emptyList(),
        val body: String,
        val attachments: List<String>,
    ) {
        /** What the user approves. Any change to recipients, subject, text or attachments changes it. */
        fun fingerprint(): String {
            val canonical = listOf(to.sorted().joinToString(","), cc.sorted().joinToString(","), bcc.sorted().joinToString(","), subject, body, attachments.joinToString(","))
                .joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun preview(maxBody: Int = 3000): String = buildString {
            appendLine("To: ${to.joinToString().ifEmpty { "-" }}")
            if (cc.isNotEmpty()) appendLine("Cc: ${cc.joinToString()}")
            if (bcc.isNotEmpty()) appendLine("Bcc: ${bcc.joinToString()}")
            appendLine("Subject: $subject")
            appendLine("Attachments: ${attachments.joinToString().ifEmpty { "none" }}")
            appendLine("———")
            append(body.take(maxBody))
            if (body.length > maxBody) append("\n[… ${body.length - maxBody} more characters]")
        }
    }

    fun parse(rawBase64Url: String): MimeMessage =
        MimeMessage(session, GmailClient.decodeBase64Url(rawBase64Url).inputStream())

    fun encode(msg: MimeMessage): String {
        msg.saveChanges()
        val out = ByteArrayOutputStream()
        msg.writeTo(out)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun view(msg: MimeMessage): View = View(
        to = addresses(msg, Message.RecipientType.TO),
        cc = addresses(msg, Message.RecipientType.CC),
        bcc = addresses(msg, Message.RecipientType.BCC),
        subject = msg.subject.orEmpty(),
        body = textPart(msg)?.let { it.content as? String }.orEmpty().trimEnd(),
        attachments = attachmentNames(msg),
    )

    /** A copy of [msg] with its text body replaced; everything else is kept. */
    fun withBody(msg: MimeMessage, body: String): MimeMessage {
        val copy = MimeMessage(msg)
        val text = textPart(copy)
        val content = runCatching { copy.content }.getOrNull()
        when {
            text != null && text !== copy -> (text as MimeBodyPart).setText(body, "UTF-8")
            // A multipart with no plain-text part (HTML-only + attachments): add the text as the first
            // part; replacing the content would drop the attachments.
            text == null && content is MimeMultipart -> {
                content.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") }, 0)
                copy.setContent(content)
            }
            else -> copy.setText(body, "UTF-8")
        }
        copy.saveChanges()
        return copy
    }

    /** A new reply: To, Re: subject, threading headers, text, and optional attachments. */
    fun reply(
        to: String,
        subject: String,
        inReplyTo: String?,
        references: String?,
        body: String,
        attachments: List<Triple<String, String, ByteArray>> = emptyList(),
    ): MimeMessage {
        val msg = MimeMessage(session)
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        msg.setSubject(subject, "UTF-8")
        inReplyTo?.takeIf { it.isNotBlank() }?.let {
            msg.setHeader("In-Reply-To", it)
            msg.setHeader("References", listOfNotNull(references?.takeIf { r -> r.isNotBlank() }, it).joinToString(" "))
        }
        if (attachments.isEmpty()) {
            msg.setText(body, "UTF-8")
        } else {
            val mixed = MimeMultipart()
            mixed.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
            attachments.forEach { (name, type, bytes) ->
                mixed.addBodyPart(MimeBodyPart().apply {
                    dataHandler = jakarta.activation.DataHandler(ByteArrayDataSource(bytes, type))
                    fileName = name
                    disposition = Part.ATTACHMENT
                })
            }
            msg.setContent(mixed)
        }
        msg.saveChanges()
        return msg
    }

    fun reSubject(subject: String?): String {
        val s = subject.orEmpty().trim()
        return if (s.lowercase().startsWith("re:")) s else "Re: $s"
    }

    private fun addresses(msg: MimeMessage, type: Message.RecipientType): List<String> =
        msg.getRecipients(type)?.mapNotNull { (it as? InternetAddress)?.address?.lowercase() }.orEmpty()

    /** The first inline text/plain part (the message itself when it is a single part). */
    private fun textPart(part: Part): Part? {
        if (part.isMimeType("text/plain") && !Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return part
        val content = runCatching { part.content }.getOrNull() as? Multipart ?: return null
        for (i in 0 until content.count) textPart(content.getBodyPart(i))?.let { return it }
        return null
    }

    private fun attachmentNames(part: Part): List<String> {
        val content = runCatching { part.content }.getOrNull() as? Multipart ?: return emptyList()
        return (0 until content.count).flatMap { i ->
            val p = content.getBodyPart(i)
            val name = p.fileName
            if (name != null && (Part.ATTACHMENT.equals(p.disposition, ignoreCase = true) || !p.isMimeType("text/*"))) listOf(name)
            else attachmentNames(p)
        }
    }
}
