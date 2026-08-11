package com.jd.pipeline.client

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SteelBrowser] that need no live Steel: the URL-rewriting helpers (critical for
 * container networking + phone re-auth links) and the config gating.
 */
@DisplayName("SteelBrowser")
class SteelBrowserTest {

    @Test
    @DisplayName("resolveWsEndpoint rehosts Steel's localhost websocketUrl onto the reachable base")
    fun wsRehosted() {
        assertEquals(
            "ws://steel:3000/",
            SteelBrowser.resolveWsEndpoint("http://steel:3000", "ws://localhost:3000/"),
        )
    }

    @Test
    @DisplayName("resolveWsEndpoint preserves the ws path/query and upgrades https base to wss")
    fun wsPreservesPathAndScheme() {
        assertEquals(
            "ws://steel:3000/devtools/browser/abc",
            SteelBrowser.resolveWsEndpoint("http://steel:3000", "ws://localhost:3000/devtools/browser/abc"),
        )
        assertEquals(
            "wss://steel.example.com/",
            SteelBrowser.resolveWsEndpoint("https://steel.example.com", "ws://localhost:3000/"),
        )
    }

    @Test
    @DisplayName("interactiveDebugUrl rehosts the debug path onto the UI base with interactive params")
    fun debugUrlBuilt() {
        assertEquals(
            "http://mac.tailnet.ts.net:3000/v1/sessions/debug?interactive=true&showControls=true",
            SteelBrowser.interactiveDebugUrl("http://mac.tailnet.ts.net:3000", "http://localhost:3000/v1/sessions/debug"),
        )
    }

    @Test
    @DisplayName("hostToIp leaves localhost and literal IPs untouched")
    fun hostToIpPassthrough() {
        assertEquals("ws://localhost:3000/", SteelBrowser.hostToIp("ws://localhost:3000/"))
        assertEquals("ws://172.20.0.9:3000/devtools/x", SteelBrowser.hostToIp("ws://172.20.0.9:3000/devtools/x"))
    }

    @Test
    @DisplayName("hostToIp returns the endpoint unchanged when the host can't be resolved")
    fun hostToIpUnresolvableIsSafe() {
        val ep = "ws://steel.nonexistent.invalid:3000/"
        assertEquals(ep, SteelBrowser.hostToIp(ep))
    }

    @Test
    @DisplayName("hostToIp resolves a real hostname to an IP (localhost's canonical name)")
    fun hostToIpResolves() {
        // ip6-localhost / the machine's own resolvable name → an IP literal, not the hostname.
        val out = SteelBrowser.hostToIp("ws://ip6-localhost:3000/")
        // Either it resolved to an IP, or (if that alias is absent) it safely passed through.
        assertTrue(out == "ws://ip6-localhost:3000/" || out.matches(Regex("""ws://[0-9a-f.:]+:3000/""")), "got $out")
    }

    @Test
    @DisplayName("isAvailable is false when no base URL is configured, without touching the network")
    fun unavailableWhenBaseBlank() {
        assertFalse(SteelBrowser(baseUrl = "").isAvailable())
    }

    @Test
    @DisplayName("isAvailable stands down while an interactive sign-in holds the browser")
    fun defersToInteractiveSignin() {
        // Steel keeps one Chrome and a session reusing it refreshes the primary page, so scraping
        // through a live sign-in would keep closing the login page the user is working in. The
        // backend must not even be probed while the gate is held.
        val client = mock<SteelClient>()
        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            client = client,
            store = mock(),
            nanoTime = { 0L },
            sleep = {},
        )

        SigninGate.holding {
            assertFalse(browser.isAvailable(), "scraping must defer to a human signing in")
        }
        verify(client, never()).createSession(anyOrNull(), any())

        // Gate released → normal behaviour resumes (this connect fails; the point is that it tried).
        whenever(client.createSession(anyOrNull(), any())).thenThrow(RuntimeException("Steel down"))
        assertFalse(browser.isAvailable())
        verify(client, times(1)).createSession(anyOrNull(), any())
    }

    @Test
    @DisplayName("close is a safe no-op when never connected")
    fun closeIsSafeWithoutConnect() {
        val browser = SteelBrowser(baseUrl = "")
        browser.close() // must not throw
        assertFalse(browser.isAvailable())
    }

    @Test
    @DisplayName("a failed connect is retried after the cooldown, not latched off (nor hammered) for the batch")
    fun reconnectCooldownGatesRetries() {
        // Regression: a never-connected Steel used to latch off after its first failed connect for
        // the whole batch, so a restarted backend was never picked up without a pipeline restart.
        // Now a failed connect only backs off for reconnectCooldownMs and re-probes past it.
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any())).thenThrow(RuntimeException("Steel down"))
        val store = mock<SteelStorageStore>()
        var now = 0L

        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            reconnectCooldownMs = 1000,
            connectMaxAttempts = 1,  // isolate the cooldown gating from the in-scrape retry loop
            client = client,
            store = store,
            nanoTime = { now },
            sleep = {},
        )

        assertFalse(browser.isAvailable())  // first attempt fails and arms the cooldown
        assertFalse(browser.isAvailable())  // still within cooldown — must NOT re-probe the backend
        verify(client, times(1)).createSession(anyOrNull(), any())

        now = 2_000L * 1_000_000  // advance past the 1000ms cooldown
        assertFalse(browser.isAvailable())  // cooldown elapsed — probes again (auto-recovers when up)
        verify(client, times(2)).createSession(anyOrNull(), any())
    }

    @Test
    @DisplayName("a transient createSession 5xx is retried in-scrape with exponential backoff before giving up")
    fun connectRetriesWithBackoff() {
        // A transient createSession 500 (SingletonLock race) should be retried within the same scrape
        // so the job isn't dropped to thin email-only JD text — not deferred to the next cooldown.
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any()))
            .thenThrow(SteelHttpException(500, "HTTP 500 SingletonLock"))
        val store = mock<SteelStorageStore>()
        val backoffs = mutableListOf<Long>()

        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            connectMaxAttempts = 3,
            connectBackoffBaseMs = 500,
            client = client,
            store = store,
            nanoTime = { 0L },
            sleep = { backoffs.add(it) },
        )

        assertFalse(browser.isAvailable())  // one isAvailable() → three in-scrape attempts
        verify(client, times(3)).createSession(anyOrNull(), any())
        // Backoff only *between* attempts (2 gaps for 3 attempts), doubling each time.
        assertEquals(listOf(500L, 1000L), backoffs)
    }

    @Test
    @DisplayName("a createSession timeout is NOT retried in-scrape — it gives up after one attempt")
    fun connectTimeoutIsNotRetried() {
        // Bound the amplification: createSession has a 90s HTTP timeout, so retrying a *hung* backend
        // 3× would block the single pipeline thread for ~4.5 min. Only a fast 5xx is retriable; a
        // timeout gives up immediately and defers to the cooldown re-probe.
        val client = mock<SteelClient>()
        // thenAnswer (not thenThrow): HttpTimeoutException is a checked type the Kotlin signature
        // doesn't declare, so Mockito rejects it as a stubbed throwable unless thrown from an answer.
        whenever(client.createSession(anyOrNull(), any()))
            .thenAnswer { throw java.net.http.HttpTimeoutException("request timed out") }
        val store = mock<SteelStorageStore>()
        val backoffs = mutableListOf<Long>()

        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            connectMaxAttempts = 3,  // would allow 3 attempts — the timeout must still stop at 1
            connectBackoffBaseMs = 500,
            client = client,
            store = store,
            nanoTime = { 0L },
            sleep = { backoffs.add(it) },
        )

        assertFalse(browser.isAvailable())
        verify(client, times(1)).createSession(anyOrNull(), any())  // no in-scrape retry on a timeout
        assertTrue(backoffs.isEmpty())                              // and therefore no backoff sleep
    }

    @Test
    @DisplayName("the circuit breaker widens the cooldown after N consecutive failed connect cycles")
    fun circuitBreakerWidensCooldown() {
        // After the breaker opens, a wedged backend must NOT be re-probed on the normal cooldown —
        // the pipeline backs off for the longer circuit-open window while the watchdog restarts Steel.
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any())).thenThrow(RuntimeException("Steel wedged"))
        val store = mock<SteelStorageStore>()
        var now = 0L

        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            reconnectCooldownMs = 1_000,
            circuitOpenCooldownMs = 60_000,
            circuitBreakerThreshold = 2,
            connectMaxAttempts = 1,  // one attempt per cycle keeps the createSession count exact
            client = client,
            store = store,
            nanoTime = { now },
            sleep = {},
        )

        assertFalse(browser.isAvailable())          // cycle 1: fail, arms the 1s (normal) cooldown
        now = 1_500L * 1_000_000                     // past the 1s cooldown
        assertFalse(browser.isAvailable())          // cycle 2: fail → breaker opens, arms the 60s cooldown
        verify(client, times(2)).createSession(anyOrNull(), any())

        now = 3_000L * 1_000_000                     // past the *normal* cooldown, but within the wide one
        assertFalse(browser.isAvailable())          // breaker open — must NOT re-probe yet
        verify(client, times(2)).createSession(anyOrNull(), any())

        now = 62_000L * 1_000_000                    // past the circuit-open cooldown
        assertFalse(browser.isAvailable())           // re-probes once the wide window elapses
        verify(client, times(3)).createSession(anyOrNull(), any())
    }

    @Test
    @DisplayName("a genuine drop of a live session reconnects immediately, bypassing the cooldown")
    fun genuineDropReconnectsImmediately() {
        // Regression guard for ensureLive's branch: a *live* handle that dropped (isConnected==false
        // but non-null) is a genuine mid-batch session drop — it must reset and reconnect NOW, not sit
        // out a cooldown. (A failed *connect* leaves a null handle and honours the cooldown — covered
        // by reconnectCooldownGatesRetries above.) The clock never advances here, so if the reconnect
        // were gated on the cooldown it could never fire; a second createSession proves it isn't.
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any()))
            .thenReturn(SteelClient.SteelSession(id = "s1", websocketUrl = "ws://localhost:3000/"))
        val store = mock<SteelStorageStore>()
        val pw = mock<Playwright>()

        val first = mock<Browser>()
        whenever(first.isConnected).thenReturn(true, false)  // healthy on connect, then drops
        val second = mock<Browser>()
        whenever(second.isConnected).thenReturn(true)        // the reconnect is healthy
        val handles = ArrayDeque(listOf(first, second))

        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            client = client,
            store = store,
            nanoTime = { 0L },                               // time never advances → no cooldown can elapse
            sleep = {},
            connect = { pw to handles.removeFirst() },
        )

        assertTrue(browser.isAvailable())                    // first connect succeeds
        verify(client, times(1)).createSession(anyOrNull(), any())

        // The live session has now dropped (first.isConnected → false). Despite the frozen clock, the
        // next call must reconnect immediately — a genuine drop clears the cooldown rather than gating.
        assertTrue(browser.isAvailable())
        verify(client, times(2)).createSession(anyOrNull(), any())
    }

    /**
     * Wire a [SteelBrowser] whose warm-tab creation throws a TargetClosedError for the first
     * [failFirst] attempts (a Steel session that died under a still-"connected" Browser handle),
     * then succeeds. Returns the browser, the page it eventually hands out, and a call counter.
     */
    private class TargetClosedFixture(failFirst: Int, error: PlaywrightException) {
        val client = mock<SteelClient>()
        val page = mock<Page>()
        var newPageCalls = 0
        val browser: SteelBrowser

        init {
            whenever(client.createSession(anyOrNull(), any()))
                .thenReturn(SteelClient.SteelSession(id = "s1", websocketUrl = "ws://localhost:3000/"))
            val handle = mock<Browser>()
            whenever(handle.isConnected).thenReturn(true)
            val context = mock<BrowserContext>()
            whenever(handle.contexts()).thenReturn(listOf(context))
            whenever(context.newPage()).thenAnswer {
                newPageCalls++
                if (newPageCalls <= failFirst) throw error else page
            }
            browser = SteelBrowser(
                baseUrl = "http://steel:3000",
                client = client,
                store = mock(),
                nanoTime = { 0L },
                sleep = {},
                connect = { mock<Playwright>() to handle },
            )
        }
    }

    @Test
    @DisplayName("pageForDomain retries a TargetClosedError with a fresh session, then succeeds")
    fun targetClosedRetriesWithNewSession() {
        // Regression: a Steel session idle-released under a Browser handle that still reports connected
        // makes tab creation throw TargetClosedError. That used to propagate and drop the job to thin
        // email-only JD text; now it recovers by reconnecting to a brand-new session and retrying.
        val fx = TargetClosedFixture(failFirst = 2, error = PlaywrightException("Target closed"))

        assertEquals(fx.page, fx.browser.pageForDomain("jobleads.com"))
        assertEquals(3, fx.newPageCalls)                             // 2 failures + 1 success
        verify(fx.client, times(3)).createSession(anyOrNull(), any())  // a fresh session per attempt
    }

    @Test
    @DisplayName("pageForDomain propagates a TargetClosedError only after exhausting the 2 retries")
    fun targetClosedExhaustsAndPropagates() {
        // A persistently dead backend must still surface the error (so the caller falls back to the
        // email JD body) — but only after 1 initial attempt + 2 retries, each on a new session.
        val fx = TargetClosedFixture(failFirst = 99, error = PlaywrightException("Target closed"))

        val thrown = assertFailsWith<PlaywrightException> { fx.browser.pageForDomain("jobleads.com") }
        assertTrue(thrown.message!!.contains("Target closed"))
        assertEquals(3, fx.newPageCalls)                             // 1 + 2 retries, then give up
        verify(fx.client, times(3)).createSession(anyOrNull(), any())
    }

    @Test
    @DisplayName("pageForDomain does NOT retry a non-TargetClosed Playwright error — it propagates at once")
    fun nonTargetClosedIsNotRetried() {
        // Only TargetClosedError means a dead session worth a fresh-session retry; any other Playwright
        // error (e.g. a genuine navigation failure) must surface immediately without reconnect churn.
        val fx = TargetClosedFixture(failFirst = 99, error = PlaywrightException("some other failure"))

        assertFailsWith<PlaywrightException> { fx.browser.pageForDomain("jobleads.com") }
        assertEquals(1, fx.newPageCalls)                             // no retry
        verify(fx.client, times(1)).createSession(anyOrNull(), any())
    }

    @Test
    @DisplayName("isTargetClosed matches by CLASS NAME even when the message says nothing")
    fun targetClosedMatchedByClassName() {
        // The message-substring branch is the fallback; in production Playwright throws its own
        // TargetClosedError subclass, whose message varies by version. Simulate that shape (a
        // subclass literally named TargetClosedError, with a non-matching message) so the
        // class-name branch — the one that actually fires in production — is covered.
        class TargetClosedError(message: String) : PlaywrightException(message)
        val fx = TargetClosedFixture(failFirst = 1, error = TargetClosedError("no useful text here"))

        assertEquals(fx.page, fx.browser.pageForDomain("jobleads.com"))
        assertEquals(2, fx.newPageCalls)                             // recognised → retried once
    }

    @Test
    @DisplayName("isTargetClosed unwraps a TargetClosedError nested as a cause")
    fun targetClosedMatchedAsCause() {
        // Playwright often wraps the underlying target-closed failure; the walk up the cause chain
        // must still recognise it rather than surfacing the job to an email-only JD fallback.
        val wrapped = PlaywrightException("navigation failed", PlaywrightException("Target closed"))
        val fx = TargetClosedFixture(failFirst = 1, error = wrapped)

        assertEquals(fx.page, fx.browser.pageForDomain("jobleads.com"))
        assertEquals(2, fx.newPageCalls)
    }

    @Test
    @DisplayName("withPageForDomain retries the WHOLE scrape when the session dies mid-navigation")
    fun withPageForDomainRetriesMidScrapeFailure() {
        // The failure this guards is a session dying during navigate/extract — tens of seconds —
        // not during the millisecond-long tab acquisition. Here the tab is handed out fine and the
        // caller's block throws, which a retry around acquisition alone would not have caught.
        val fx = TargetClosedFixture(failFirst = 0, error = PlaywrightException("unused"))
        var blockCalls = 0

        val result = fx.browser.withPageForDomain("jobleads.com") { page ->
            blockCalls++
            if (blockCalls == 1) throw PlaywrightException("Target closed") else "scraped:${page === fx.page}"
        }

        assertEquals("scraped:true", result)
        assertEquals(2, blockCalls)                                    // block re-run on a fresh tab
        verify(fx.client, times(2)).createSession(anyOrNull(), any())  // on a brand-new session
    }

    @Test
    @DisplayName("withPageForDomain does not retry a non-TargetClosed failure from the block")
    fun withPageForDomainDoesNotRetryRealFailures() {
        // A genuine scrape failure (a real navigation error, an auth wall) must surface on the first
        // attempt — retrying it would burn two more Steel sessions for nothing.
        val fx = TargetClosedFixture(failFirst = 0, error = PlaywrightException("unused"))
        var blockCalls = 0

        assertFailsWith<PlaywrightException> {
            fx.browser.withPageForDomain("jobleads.com") { blockCalls++; throw PlaywrightException("nav failed") }
        }
        assertEquals(1, blockCalls)
    }

    @Test
    @DisplayName("a dead Steel session is RELEASED, not abandoned, before a fresh one is created")
    fun deadSessionIsReleased() {
        // Steel keeps one shared Chrome and holds an unreleased session until its 10min idle timeout,
        // so abandoning one per retry would starve the backend a few failing jobs in.
        val fx = TargetClosedFixture(failFirst = 2, error = PlaywrightException("Target closed"))

        fx.browser.pageForDomain("jobleads.com")

        verify(fx.client, times(2)).releaseSession("s1")   // one per dead session, not for the live one
    }
}
