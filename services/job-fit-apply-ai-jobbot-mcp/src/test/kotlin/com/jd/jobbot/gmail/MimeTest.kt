package com.jd.jobbot.gmail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MimeTest {
    private val pdf = Triple("RichardHatcherResume.pdf", "application/pdf", "%PDF-1.4 fake".toByteArray())

    private fun draft() = Mime.reply("Rec <Rec@X.com>", "Re: Staff SDET", "<m1@x.com>", "<m0@x.com>", "Hi,\nI'm interested.", listOf(pdf))

    @Test
    fun `a reply round-trips through Gmail's raw encoding`() {
        val v = Mime.view(Mime.parse(Mime.encode(draft())))
        assertEquals(listOf("rec@x.com"), v.to)
        assertEquals("Re: Staff SDET", v.subject)
        assertEquals("Hi,\nI'm interested.", v.body)
        assertEquals(listOf("RichardHatcherResume.pdf"), v.attachments)
    }

    @Test
    fun `threading headers are set`() {
        val m = draft()
        assertEquals("<m1@x.com>", m.getHeader("In-Reply-To").single())
        assertEquals("<m0@x.com> <m1@x.com>", m.getHeader("References").single())
    }

    @Test
    fun `swapping the body keeps recipients, subject and the attachment`() {
        val edited = Mime.withBody(Mime.parse(Mime.encode(draft())), "New text")
        val v = Mime.view(Mime.parse(Mime.encode(edited)))
        assertEquals("New text", v.body)
        assertEquals(listOf("rec@x.com"), v.to)
        assertEquals(listOf("RichardHatcherResume.pdf"), v.attachments)
        assertEquals("<m1@x.com>", edited.getHeader("In-Reply-To").single())
    }

    @Test
    fun `a single-part draft can be edited too`() {
        val plain = Mime.reply("r@x.com", "Re: A", null, null, "old")
        assertEquals("new", Mime.view(Mime.parse(Mime.encode(Mime.withBody(plain, "new")))).body)
    }

    @Test
    fun `the fingerprint changes with any approved detail`() {
        val v = Mime.view(draft())
        assertEquals(v.fingerprint(), Mime.view(Mime.parse(Mime.encode(draft()))).fingerprint())
        assertNotEquals(v.fingerprint(), v.copy(body = v.body + "!").fingerprint())
        assertNotEquals(v.fingerprint(), v.copy(to = listOf("other@x.com")).fingerprint())
        assertNotEquals(v.fingerprint(), v.copy(attachments = emptyList()).fingerprint())
    }

    @Test
    fun `non-ASCII subjects and bodies survive`() {
        val v = Mime.view(Mime.parse(Mime.encode(Mime.reply("r@x.com", "Re: Café — rôle", null, null, "Merci — à bientôt"))))
        assertEquals("Re: Café — rôle", v.subject)
        assertEquals("Merci — à bientôt", v.body)
    }

    @Test
    fun `the preview shows exactly what will be sent`() {
        val p = Mime.view(draft()).preview()
        assertTrue(p.startsWith("To: rec@x.com\nSubject: Re: Staff SDET\nAttachments: RichardHatcherResume.pdf"), p)
        assertTrue(p.endsWith("Hi,\nI'm interested."), p)
    }

    @Test
    fun `reSubject adds Re once`() {
        assertEquals("Re: Role", Mime.reSubject("Role"))
        assertEquals("RE: Role", Mime.reSubject("RE: Role"))
    }

    @Test
    fun `editing an HTML-only multipart draft keeps its attachment`() {
        val html = jakarta.mail.internet.MimeMultipart().apply {
            addBodyPart(jakarta.mail.internet.MimeBodyPart().apply { setContent("<p>Hi</p>", "text/html; charset=UTF-8") })
            addBodyPart(jakarta.mail.internet.MimeBodyPart().apply {
                dataHandler = jakarta.activation.DataHandler(jakarta.mail.util.ByteArrayDataSource("%PDF".toByteArray(), "application/pdf"))
                fileName = "RichardHatcherResume.pdf"
                disposition = jakarta.mail.Part.ATTACHMENT
            })
        }
        val msg = jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties())).apply {
            setRecipients(jakarta.mail.Message.RecipientType.TO, "r@x.com"); subject = "Re: A"; setContent(html); saveChanges()
        }
        val v = Mime.view(Mime.parse(Mime.encode(Mime.withBody(Mime.parse(Mime.encode(msg)), "Plain now"))))
        assertEquals("Plain now", v.body)
        assertEquals(listOf("RichardHatcherResume.pdf"), v.attachments)
    }
}
