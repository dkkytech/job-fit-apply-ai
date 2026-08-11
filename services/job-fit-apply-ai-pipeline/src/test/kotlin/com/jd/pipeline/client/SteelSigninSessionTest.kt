package com.jd.pipeline.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SteelSigninSession] — the human-paced sign-in path. The guarantee under test is
 * that cookies land *continuously*, so a sign-in survives a detached terminal, a closed tab, or an
 * expired window; detection only decides when to stop early.
 */
@DisplayName("SteelSigninSession")
class SteelSigninSessionTest {

    private val mapper = ObjectMapper()

    private fun context(cookies: Int): JsonNode {
        val root = mapper.createObjectNode()
        val arr = root.putArray("cookies")
        repeat(cookies) { i ->
            arr.addObject().put("name", "c$i").put("domain", ".linkedin.com").put("path", "/")
        }
        return root
    }

    /** Wire a session whose live tab reports [urls] in order (the last repeats). */
    private fun fixture(
        vararg urls: String,
        windowMs: Long = 3_000,
        pollIntervalMs: Long = 1_000,
        exportedCookies: Int? = 2,
        navigateThrows: Boolean = false,
    ): Triple<SteelSigninSession, SteelClient, SteelStorageStore> {
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any()))
            .thenReturn(
                SteelClient.SteelSession(
                    id = "sess-1",
                    websocketUrl = "ws://localhost:3000/",
                    debugUrl = "http://localhost:3000/v1/sessions/debug",
                )
            )
        whenever(client.exportContext(any()))
            .thenReturn(exportedCookies?.let { context(it) })

        val store = mock<SteelStorageStore>()
        val page = mock<Page>()
        if (navigateThrows) {
            whenever(page.navigate(any(), any<Page.NavigateOptions>()))
                .thenThrow(PlaywrightException("net::ERR_CONNECTION_REFUSED"))
        }
        if (urls.isNotEmpty()) {
            val first = urls.first()
            val rest = urls.drop(1).toTypedArray()
            whenever(page.url()).thenReturn(first, *rest)
        }
        val ctx = mock<BrowserContext>()
        whenever(ctx.newPage()).thenReturn(page)
        val browser = mock<Browser>()
        whenever(browser.contexts()).thenReturn(listOf(ctx))
        val pw = mock<Playwright>()

        var now = 0L
        val session = SteelSigninSession(
            baseUrl = "http://steel:3000",
            uiBaseUrl = "http://mac.tailnet.ts.net:3000",
            windowMs = windowMs,
            pollIntervalMs = pollIntervalMs,
            client = client,
            store = store,
            nanoTime = { now },
            sleep = { now += it * 1_000_000 },
            connect = { pw to browser },
        )
        return Triple(session, client, store)
    }

    @Test
    @DisplayName("open() parks the tab on the login URL so the live view lands on the sign-in form")
    fun opensOnLoginPage() {
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any()))
            .thenReturn(
                SteelClient.SteelSession(
                    id = "sess-1",
                    websocketUrl = "ws://localhost:3000/",
                    debugUrl = "http://localhost:3000/v1/sessions/debug",
                )
            )
        val page = mock<Page>()
        val ctx = mock<BrowserContext>()
        whenever(ctx.newPage()).thenReturn(page)
        val browser = mock<Browser>()
        whenever(browser.contexts()).thenReturn(listOf(ctx))

        val session = SteelSigninSession(
            baseUrl = "http://steel:3000",
            uiBaseUrl = "http://mac.tailnet.ts.net:3000",
            client = client,
            store = mock(),
            connect = { mock<Playwright>() to browser },
        )

        val opened = session.open("https://www.linkedin.com/login")

        assertEquals("sess-1", opened.sessionId)
        assertEquals("https://www.linkedin.com/login", opened.startUrl)
        // The link handed to the user must be the tailnet UI base, not Steel's internal localhost.
        assertEquals(
            "http://mac.tailnet.ts.net:3000/v1/sessions/debug?interactive=true&showControls=true",
            opened.debugUrl,
        )
        verify(page).navigate(org.mockito.kotlin.eq("https://www.linkedin.com/login"), any<Page.NavigateOptions>())
    }

    @Test
    @DisplayName("a failed navigation still yields a usable session — the user can navigate themselves")
    fun navigationFailureDoesNotAbortSignin() {
        val (session, _, _) = fixture("https://www.linkedin.com/login", navigateThrows = true)
        val opened = session.open("https://www.linkedin.com/login")
        assertEquals("sess-1", opened.sessionId)
        assertNull(opened.startUrl)          // parking failed…
        assertTrue(opened.debugUrl != null)  // …but there is still a live view to sign in on
    }

    @Test
    @DisplayName("a failed CDP connect still releases the session it created")
    fun connectFailureReleasesTheSession() {
        // Regression: the session field was assigned *after* connect(), so a connect failure left a
        // live session on the backend with no handle to release it — holding Steel's single Chrome
        // for the whole 30-minute window while every scrape fell back to email-only JD text.
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any()))
            .thenReturn(SteelClient.SteelSession(id = "sess-1", websocketUrl = "ws://localhost:3000/"))
        val session = SteelSigninSession(
            baseUrl = "http://steel:3000",
            client = client,
            store = mock(),
            connect = { throw RuntimeException("ECONNRESET") },
        )

        assertFailsWith<RuntimeException> { session.open("https://www.linkedin.com/login") }
        session.close()

        verify(client).releaseSession("sess-1")
    }

    @Test
    @DisplayName("cookies are merged on EVERY poll, so an expired window still persists the sign-in")
    fun mergesContinuouslyThenTimesOut() {
        // The core guarantee. The old flow captured once, on ENTER — so a detached terminal or a
        // forgotten confirmation lost the sign-in entirely.
        val (session, _, store) = fixture(
            "https://www.linkedin.com/login",  // never clears
            windowMs = 3_000,
            pollIntervalMs = 1_000,
        )
        session.open("https://www.linkedin.com/login")

        val result = session.awaitCapture()

        assertTrue(result.timedOut)
        assertFalse(result.signedIn)
        assertEquals(2, result.cookies)
        assertEquals(4, result.merges)               // t=0,1s,2s,3s
        verify(store, times(4)).merge(any())         // …every one of them persisted
    }

    @Test
    @DisplayName("stops as soon as the login wall clears")
    fun stopsWhenWallClears() {
        val (session, _, store) = fixture(
            "https://www.linkedin.com/login",
            "https://www.linkedin.com/login",
            "https://www.linkedin.com/feed/",
        )
        session.open("https://www.linkedin.com/login")

        val result = session.awaitCapture()

        assertTrue(result.signedIn)
        assertFalse(result.timedOut)
        assertEquals(3, result.merges)
        verify(store, times(3)).merge(any())
    }

    @Test
    @DisplayName("an already-authenticated session finishes on the first poll instead of waiting out the window")
    fun alreadySignedInReturnsImmediately() {
        val (session, _, store) = fixture("https://www.linkedin.com/feed/")
        session.open("https://www.linkedin.com/login")

        val result = session.awaitCapture()

        assertTrue(result.signedIn)
        assertFalse(result.timedOut)
        assertEquals(1, result.merges)
        verify(store, times(1)).merge(any())
    }

    @Test
    @DisplayName("a blank/unknown tab URL counts as a wall — it must not be mistaken for success")
    fun unknownUrlIsTreatedAsAWall() {
        // Guards the failed-navigation case: about:blank has no login marker, but concluding
        // \"signed in\" there would silently skip the sign-in the user was asked for.
        val (session, _, _) = fixture("about:blank", windowMs = 1_000, pollIntervalMs = 1_000)
        session.open("https://www.linkedin.com/login")

        val result = session.awaitCapture()

        assertTrue(result.timedOut)
        assertFalse(result.signedIn)
    }

    @Test
    @DisplayName("stopRequested (ENTER) ends the wait early without discarding what was captured")
    fun earlyExitStillPersists() {
        val (session, _, store) = fixture("https://www.linkedin.com/login")
        session.open("https://www.linkedin.com/login")

        val result = session.awaitCapture(stopRequested = { true })

        assertFalse(result.timedOut)
        assertEquals(1, result.merges)
        verify(store, times(1)).merge(any())
    }

    @Test
    @DisplayName("an unreadable session context never throws — it reports nothing captured")
    fun exportFailureIsReportedNotThrown() {
        val (session, _, store) = fixture(
            "https://www.linkedin.com/login",
            windowMs = 1_000,
            pollIntervalMs = 1_000,
            exportedCookies = null,
        )
        session.open("https://www.linkedin.com/login")

        val result = session.awaitCapture()

        assertEquals(0, result.merges)
        verify(store, never()).merge(any())
    }

    @Test
    @DisplayName("close() exports once more before releasing, so the tail of the sign-in is captured")
    fun closeExportsThenReleases() {
        val (session, client, store) = fixture("https://www.linkedin.com/feed/")
        session.open("https://www.linkedin.com/login")
        session.awaitCapture()

        session.close()

        verify(client).releaseSession("sess-1")
        // One export per poll plus the final one on close.
        verify(client, atLeastOnce()).exportContext("sess-1")
        verify(store, times(2)).merge(any())
    }

    // ── pure helpers ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("looksLikeLoginWall matches auth/challenge URLs and treats unknown as a wall")
    fun wallDetection() {
        assertTrue(SteelSigninSession.looksLikeLoginWall("https://www.linkedin.com/login"))
        assertTrue(SteelSigninSession.looksLikeLoginWall("https://www.linkedin.com/checkpoint/challenge"))
        assertTrue(SteelSigninSession.looksLikeLoginWall("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(SteelSigninSession.looksLikeLoginWall(""))
        assertTrue(SteelSigninSession.looksLikeLoginWall("about:blank"))
        assertFalse(SteelSigninSession.looksLikeLoginWall("https://www.linkedin.com/feed/"))
        assertFalse(SteelSigninSession.looksLikeLoginWall("https://jobright.ai/jobs/123"))
    }

    @Test
    @DisplayName("loginUrlFor resolves site labels and bare hosts, and gives up on nonsense")
    fun loginUrlResolution() {
        assertEquals("https://www.linkedin.com/login", SteelSigninSession.loginUrlFor("LinkedIn"))
        assertEquals("https://www.linkedin.com/login", SteelSigninSession.loginUrlFor("www.linkedin.com"))
        assertEquals("https://jobright.ai/?login=true", SteelSigninSession.loginUrlFor("jobright"))
        // An unknown host falls back to its root, which redirects to that site's own sign-in.
        assertEquals("https://boards.greenhouse.io/", SteelSigninSession.loginUrlFor("boards.greenhouse.io"))
        assertNull(SteelSigninSession.loginUrlFor("nonsense"))
        assertNull(SteelSigninSession.loginUrlFor(null))
        assertNull(SteelSigninSession.loginUrlFor("  "))
    }
}
