package com.jd.pipeline.client

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SigninServer] — the tap-to-sign-in endpoint. The behaviour that matters is that a
 * tap creates a session *at tap time* (the whole point: the scrape session the alert used to link to
 * is long gone), redirects into it, and never lets two taps fight over Steel's single Chrome.
 */
@DisplayName("SigninServer")
class SigninServerTest {

    private var server: SigninServer? = null

    /**
     * Stop the server and wait for any capture thread to drop [SigninGate]. The gate is process-wide,
     * so a thread outliving its test would make an unrelated [SteelBrowser] test see the backend as
     * unavailable — the tests share a JVM.
     */
    @AfterEach
    fun stop() {
        server?.close()
        server = null
        val deadline = System.currentTimeMillis() + 5_000
        while (SigninGate.isActive() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        check(!SigninGate.isActive()) { "sign-in gate still held after the test — it would leak into the next one" }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** A session mock that opens successfully and blocks in awaitCapture until released. */
    private fun sessionMock(
        debugUrl: String? = "http://tailnet:3000/v1/sessions/debug?interactive=true&showControls=true",
        capture: () -> SteelSigninSession.Captured = {
            SteelSigninSession.Captured(cookies = 7, signedIn = true, timedOut = false, merges = 3)
        },
    ): SteelSigninSession {
        val s = mock<SteelSigninSession>()
        whenever(s.open(any())).thenReturn(
            SteelSigninSession.Opened(
                sessionId = "sess-1",
                debugUrl = debugUrl,
                startUrl = "https://www.linkedin.com/login",
                windowMs = 1_800_000,
            )
        )
        whenever(s.awaitCapture(any(), any())).thenAnswer { capture() }
        return s
    }

    /** GET without following redirects, so the 302 itself is observable. */
    private fun get(url: String): Pair<Int, String?> {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        return try {
            conn.responseCode to conn.getHeaderField("Location")
        } finally {
            conn.disconnect()
        }
    }

    @Test
    @DisplayName("a tap opens a session parked on the site's login page and redirects into its live view")
    fun tapOpensAndRedirects() {
        val session = sessionMock()
        val port = freePort()
        server = SigninServer(port = port, bindAddr = "127.0.0.1", token = "", alerts = mock()) { session }
            .also { it.start() }

        val (status, location) = get("http://127.0.0.1:$port/signin?site=LinkedIn")

        assertEquals(302, status)
        assertEquals("http://tailnet:3000/v1/sessions/debug?interactive=true&showControls=true", location)
        // The session is created at tap time — and parked on LinkedIn's real login page.
        verify(session).open("https://www.linkedin.com/login")
    }

    @Test
    @DisplayName("a second tap reuses the in-flight session instead of opening a rival one")
    fun secondTapReusesInFlightSession() {
        // Steel keeps one Chrome: a second session would close the login page the user is typing in.
        val released = java.util.concurrent.CountDownLatch(1)
        val session = sessionMock(capture = {
            released.await()
            SteelSigninSession.Captured(cookies = 1, signedIn = true, timedOut = false, merges = 1)
        })
        var built = 0
        val port = freePort()
        server = SigninServer(port = port, bindAddr = "127.0.0.1", token = "", alerts = mock()) {
            built++; session
        }.also { it.start() }

        val first = get("http://127.0.0.1:$port/signin?site=LinkedIn")
        val second = get("http://127.0.0.1:$port/signin?site=LinkedIn")

        assertEquals(302, first.first)
        assertEquals(302, second.first)
        assertEquals(first.second, second.second)  // same live view
        assertEquals(1, built)                     // only one session was ever created
        verify(session, times(1)).open(any())
        released.countDown()
    }

    @Test
    @DisplayName("two taps racing during a slow open() still produce only one session")
    fun concurrentTapsOpenOneSession() {
        // Regression: the in-flight check and the open() were not atomic. open() takes seconds
        // (createSession + CDP connect) — well inside human double-tap range — so a second tap
        // arriving mid-open saw an empty slot and built a rival session, which on Steel's single
        // Chrome closes the first one's login page. The sequential reuse test could not catch this.
        val opening = java.util.concurrent.CountDownLatch(1)
        val built = java.util.concurrent.atomic.AtomicInteger(0)
        val release = java.util.concurrent.CountDownLatch(1)
        val session = sessionMock(capture = {
            release.await()
            SteelSigninSession.Captured(cookies = 1, signedIn = true, timedOut = false, merges = 1)
        })
        // A slow open(), so both requests are in flight at the same time.
        whenever(session.open(any())).thenAnswer {
            opening.countDown()
            Thread.sleep(300)
            SteelSigninSession.Opened("sess-1", "http://tailnet:3000/live", "https://www.linkedin.com/login", 1_800_000)
        }

        val port = freePort()
        server = SigninServer(port = port, bindAddr = "127.0.0.1", token = "", alerts = mock()) {
            built.incrementAndGet(); session
        }.also { it.start() }

        val url = "http://127.0.0.1:$port/signin?site=LinkedIn"
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, String?>>())
        val t1 = Thread { results.add(get(url)) }
        val t2 = Thread {
            opening.await()          // only tap once the first request is inside open()
            results.add(get(url))
        }
        t1.start(); t2.start(); t1.join(10_000); t2.join(10_000)

        assertEquals(1, built.get(), "a racing second tap must not build a rival session")
        verify(session, times(1)).open(any())
        assertEquals(listOf(302, 302), results.map { it.first }, "both taps should still reach a live view")
        release.countDown()
    }

    @Test
    @DisplayName("a completed sign-in pings the user and always releases the session")
    fun capturePingsAndReleases() {
        val session = sessionMock()
        val alerts = mock<AlertService>()
        val port = freePort()
        server = SigninServer(port = port, bindAddr = "127.0.0.1", token = "", alerts = alerts) { session }
            .also { it.start() }

        get("http://127.0.0.1:$port/signin?site=LinkedIn")

        // The capture runs on a background thread so the redirect isn't held for the whole window.
        verify(alerts, timeout(5_000)).reauthCaptured(eq("LinkedIn"), eq(7))
        verify(session, timeout(5_000)).close()
    }

    @Test
    @DisplayName("a sign-in that times out still releases the session and does not claim success")
    fun timeoutStillReleases() {
        val session = sessionMock(capture = {
            SteelSigninSession.Captured(cookies = 2, signedIn = false, timedOut = true, merges = 5)
        })
        val alerts = mock<AlertService>()
        val port = freePort()
        server = SigninServer(port = port, bindAddr = "127.0.0.1", token = "", alerts = alerts) { session }
            .also { it.start() }

        get("http://127.0.0.1:$port/signin?site=LinkedIn")

        verify(session, timeout(5_000)).close()
        verify(alerts, never()).reauthCaptured(any(), any())
    }

    @Test
    @DisplayName("a failed session open reports 503 rather than redirecting to nothing")
    fun failedOpenIsReported() {
        val session = mock<SteelSigninSession>()
        whenever(session.open(any())).thenThrow(RuntimeException("Steel down"))
        val port = freePort()
        server = SigninServer(port = port, bindAddr = "127.0.0.1", token = "", alerts = mock()) { session }
            .also { it.start() }

        val (status, _) = get("http://127.0.0.1:$port/signin?site=LinkedIn")

        assertEquals(503, status)
        verify(session).close()  // no half-open session left holding Steel's Chrome
    }

    @Test
    @DisplayName("the token, when configured, is required")
    fun tokenIsEnforced() {
        val session = sessionMock()
        val port = freePort()
        server = SigninServer(port = port, bindAddr = "127.0.0.1", token = "s3cret", alerts = mock()) { session }
            .also { it.start() }

        assertEquals(403, get("http://127.0.0.1:$port/signin?site=LinkedIn").first)
        assertEquals(403, get("http://127.0.0.1:$port/signin?site=LinkedIn&token=wrong").first)
        verify(session, never()).open(any())

        assertEquals(302, get("http://127.0.0.1:$port/signin?site=LinkedIn&token=s3cret").first)
    }

    @Test
    @DisplayName("signinUrl builds the alert link, and is null when the endpoint isn't advertised")
    fun signinUrlBuilt() {
        assertEquals(
            "http://mac.tailnet.ts.net:3100/signin?site=LinkedIn",
            SigninServer.signinUrl("LinkedIn", publicUrl = "http://mac.tailnet.ts.net:3100", token = ""),
        )
        // Trailing slash tolerated; site is encoded; token carried through so the link works as-is.
        assertEquals(
            "http://mac.tailnet.ts.net:3100/signin?site=the+site&token=s3+cret",
            SigninServer.signinUrl("the site", publicUrl = "http://mac.tailnet.ts.net:3100/", token = "s3 cret"),
        )
        // Not advertised → caller falls back to the old short-lived debug link.
        assertNull(SigninServer.signinUrl("LinkedIn", publicUrl = "", token = ""))
        assertNull(SigninServer.signinUrl("LinkedIn", publicUrl = "   ", token = ""))
    }

    @Test
    @DisplayName("the gate is held for the duration of a sign-in so the scraper stands down")
    fun gateHeldDuringSignin() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val session = sessionMock(capture = {
            entered.countDown()
            release.await()
            SteelSigninSession.Captured(cookies = 1, signedIn = true, timedOut = false, merges = 1)
        })
        val port = freePort()
        server = SigninServer(port = port, bindAddr = "127.0.0.1", token = "", alerts = mock()) { session }
            .also { it.start() }

        assertTrue(!SigninGate.isActive())
        get("http://127.0.0.1:$port/signin?site=LinkedIn")

        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(SigninGate.isActive(), "the scraper must defer while a human is signing in")

        release.countDown()
        // Gate opens again once the capture thread finishes.
        val deadline = System.currentTimeMillis() + 5_000
        while (SigninGate.isActive() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(!SigninGate.isActive())
    }
}
