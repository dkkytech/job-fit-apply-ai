package com.jd.jobbot.apply

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialsTest {
    @TempDir lateinit var dir: Path
    private val file get() = dir.resolve("site-credentials.json")
    private fun creds() = Credentials(file, "dkkytech@gmail.com")

    @Test
    fun `site keys are the exact host for multi-tenant ATSs and the registrable domain otherwise`() {
        assertEquals("acme.wd5.myworkdayjobs.com", Credentials.siteKey("https://acme.wd5.myworkdayjobs.com/en-US/careers/job/1"))
        assertEquals("careers-acme.icims.com", Credentials.siteKey("https://careers-acme.icims.com/jobs/1/login"))
        assertEquals("greenhouse.io", Credentials.siteKey("https://boards.greenhouse.io/acme/jobs/1"))
        assertEquals("acme.co.uk", Credentials.siteKey("https://jobs.acme.co.uk/apply"))
        assertEquals("snap.com", Credentials.siteKey("https://www.snap.com/en-US/jobs"))
        assertNull(Credentials.siteKey("not a url"))
    }

    @Test
    fun `generated passwords are long, mixed and unique`() {
        val c = creds()
        val pws = (1..50).map { c.generate(Credentials.PasswordRules()) }
        assertEquals(50, pws.toSet().size)
        pws.forEach { pw ->
            assertEquals(20, pw.length)
            assertTrue(pw.any(Char::isUpperCase) && pw.any(Char::isLowerCase) && pw.any(Char::isDigit) && pw.any { it in Credentials.DEFAULT_SYMBOLS }, pw)
        }
    }

    @Test
    fun `password rules are honoured`() {
        val pw = creds().generate(Credentials.PasswordRules(minLength = 30, maxLength = 16, symbols = "!"))
        assertEquals(16, pw.length)
        assertTrue(pw.filterNot(Char::isLetterOrDigit).all { it == '!' }, pw)
        assertFalse(creds().generate(Credentials.PasswordRules(symbols = "")).any { !it.isLetterOrDigit() })
    }

    @Test
    fun `a new password is saved pending before use, then activated`() {
        val c = creds()
        val pw = c.createPending("acme.co", "https://acme.co/signup", "#J1")
        assertEquals(Credentials.PENDING, c.get("acme.co")!!.status)
        assertEquals(pw, Credentials(file, "x").passwordFor("acme.co"), "persisted, not just in memory")
        c.activate("acme.co")
        assertEquals(Credentials.ACTIVE, c.get("acme.co")!!.status)
        assertEquals("#J1", c.get("acme.co")!!.created_for)
    }

    @Test
    fun `each site gets its own password`() {
        val c = creds()
        assertNotEquals(c.createPending("a.com", null, null), c.createPending("b.com", null, null))
    }

    @Test
    fun `google sign-ins are recorded without a password`() {
        val c = creds()
        c.recordGoogle("greenhouse.io", "https://boards.greenhouse.io/x", "#J2")
        val e = c.get("greenhouse.io")!!
        assertEquals("google", e.method)
        assertNull(e.password)
    }

    @Test
    fun `the public view never carries the password`() {
        val c = creds()
        c.createPending("acme.co", null, null)
        val pub = c.get("acme.co")!!.public("acme.co")
        assertFalse("password" in pub.keys)
        assertFalse(pub.values.any { it == c.passwordFor("acme.co") })
    }

    @Test
    fun `the file is mode 600 and the previous version is kept`() {
        val c = creds()
        c.createPending("a.com", null, null)
        c.createPending("b.com", null, null)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.resolveSibling("site-credentials.json.prev"))), "the backup holds passwords too")
        assertTrue(Files.readString(file.resolveSibling("site-credentials.json.prev")).contains("a.com"))
        assertFalse(Files.readString(file.resolveSibling("site-credentials.json.prev")).contains("b.com"))
    }
}
