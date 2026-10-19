package com.jd.jobbot.gmail

import com.jd.jobbot.support.FakeHttp
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GmailAuthTest {
    @TempDir lateinit var dir: Path
    private lateinit var google: FakeHttp
    private val issued = AtomicInteger()
    private lateinit var tokenFile: Path
    private lateinit var creds: Path

    @BeforeTest
    fun setUp() {
        google = FakeHttp()
        google.on("POST /token") { FakeHttp.Resp(200, """{"access_token":"at-${issued.incrementAndGet()}","expires_in":3599}""") }
        tokenFile = dir.resolve("gmail_token.json")
        Files.writeString(tokenFile, """{"access_token":"stale","refresh_token":"rt-1","expires_in":3599}""")
        creds = dir.resolve("credentials.json")
        Files.writeString(creds, """{"installed":{"client_id":"cid","client_secret":"sec","token_uri":"${google.url}/token"}}""")
    }

    @AfterTest fun stop() = google.close()

    private fun auth() = GmailAuth(tokenFile, creds)

    @Test
    fun `refreshes once and caches the access token in memory`() {
        val a = auth()
        assertEquals("at-1", a.token())
        assertEquals("at-1", a.token())
        assertEquals(1, google.requests.size)
        val form = google.requests.single().body
        assertTrue(form.contains("grant_type=refresh_token") && form.contains("refresh_token=rt-1") && form.contains("client_id=cid"), form)
        assertIs<GmailAuth.State.Ok>(a.state)
    }

    @Test
    fun `never writes the poller's token file`() {
        val before = Files.readString(tokenFile)
        val stamp = Files.getLastModifiedTime(tokenFile)
        auth().token()
        assertEquals(before, Files.readString(tokenFile))
        assertEquals(stamp, Files.getLastModifiedTime(tokenFile))
    }

    @Test
    fun `a changed token file (poller re-auth) is picked up without a restart`() {
        val a = auth()
        a.token()
        Files.writeString(tokenFile, """{"refresh_token":"rt-2"}""")
        Files.setLastModifiedTime(tokenFile, FileTime.from(Instant.now().plusSeconds(5)))
        assertEquals("at-2", a.token())
        assertTrue(google.requests.last().body.contains("refresh_token=rt-2"))
    }

    @Test
    fun `invalid_grant means re-auth through the poller, and nothing is deleted`() {
        google.on("POST /token") { FakeHttp.Resp(400, """{"error":"invalid_grant"}""") }
        val a = auth()
        val e = assertFailsWith<GmailAuth.GmailAuthException> { a.token() }
        assertTrue(e.message!!.contains("poller --reauth"), e.message)
        assertIs<GmailAuth.State.NeedsReauth>(a.state)
        assertTrue(Files.exists(tokenFile))
    }

    @Test
    fun `server errors are transient, not dead`() {
        google.on("POST /token") { FakeHttp.Resp(503, """{"error":"backend"}""") }
        val a = auth()
        assertFailsWith<GmailAuth.GmailAuthException> { a.token() }
        assertIs<GmailAuth.State.Unavailable>(a.state)
    }

    @Test
    fun `a missing token file needs re-auth`() {
        Files.delete(tokenFile)
        val a = auth()
        assertFailsWith<GmailAuth.GmailAuthException> { a.token() }
        assertIs<GmailAuth.State.NeedsReauth>(a.state)
    }

    @Test
    fun `a rotated refresh token is tolerated (logged) and the access token still works`() {
        google.on("POST /token") { FakeHttp.Resp(200, """{"access_token":"at-x","refresh_token":"rt-NEW","expires_in":3599}""") }
        assertEquals("at-x", auth().token())
        assertTrue(Files.readString(tokenFile).contains("rt-1"), "jobbot-mcp must not persist it")
    }
}
