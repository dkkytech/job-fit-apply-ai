package com.jd.pipeline.client

import com.jd.pipeline.config.Config
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.WaitUntilState
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths

/**
 * A human-paced Steel sign-in session: create a long-lived session, park it on the site's login
 * page, then **continuously** export-and-merge its cookies while the user signs in from a phone.
 *
 * Two things distinguish this from the scrape path ([SteelBrowser]):
 *
 *  - **Lifetime.** A scrape session lives [Config.STEEL_SESSION_TIMEOUT_MS] (10 min) and is released
 *    at batch close, so the interactive debug URL in a re-auth alert is usually dead by the time the
 *    user taps it — the alert links to a session that no longer exists. This session is created *at
 *    sign-in time* and holds open for [windowMs] instead.
 *  - **Capture.** Cookies are merged on every poll, not once at the end, so a sign-in is persisted
 *    even if the user never confirms, closes the tab, or the window expires. There is no step the
 *    user can forget.
 *
 * Detection is deliberately conservative — see [awaitCapture]. Because capture is continuous,
 * detection only decides when to stop *early*; getting it wrong costs a wait, never a lost sign-in.
 *
 * Not thread-safe: one session per instance, driven by one caller.
 */
class SteelSigninSession(
    private val baseUrl: String = Config.STEEL_BASE_URL,
    private val uiBaseUrl: String = Config.STEEL_UI_URL,
    private val windowMs: Long = Config.STEEL_SIGNIN_WINDOW_MS,
    private val pollIntervalMs: Long = Config.STEEL_SIGNIN_POLL_MS,
    private val client: SteelClient = SteelClient(baseUrl),
    private val store: SteelStorageStore = SteelStorageStore(Paths.get(Config.STEEL_STORAGE_STATE_PATH)),
    // Monotonic clock seam (nanos); overridable so the window/deadline is unit-testable without waiting.
    private val nanoTime: () -> Long = System::nanoTime,
    // Poll-sleep seam; overridable so the capture loop is unit-testable without actually sleeping.
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    // Connect seam, mirroring [SteelBrowser]: builds the Playwright + CDP browser for a live session.
    // Closes the Playwright if the CDP connect throws, so a partial connect doesn't leak.
    private val connect: (SteelClient.SteelSession) -> Pair<Playwright, Browser> = { s ->
        val pw = Playwright.create()
        runCatching {
            pw.chromium().connectOverCDP(
                SteelBrowser.hostToIp(SteelBrowser.resolveWsEndpoint(baseUrl, s.websocketUrl))
            )
        }.fold({ pw to it }, { runCatching { pw.close() }; throw it })
    },
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(SteelSigninSession::class.java)

    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var page: Page? = null
    private var session: SteelClient.SteelSession? = null

    /** A live sign-in session the user can be pointed at. */
    data class Opened(
        val sessionId: String,
        /** Interactive live-view URL to open on a phone (over the Tailscale UI base), if available. */
        val debugUrl: String?,
        /** The URL the session was parked on, or null if it could not be navigated. */
        val startUrl: String?,
        /** How long the session stays open. */
        val windowMs: Long,
    )

    /** Outcome of [awaitCapture]. Cookies are already persisted regardless of [signedIn]. */
    data class Captured(
        val cookies: Int,
        /** True when the login wall cleared (or was never there) — a best-effort heuristic. */
        val signedIn: Boolean,
        /** True when the window elapsed without the wall clearing. Cookies were still merged. */
        val timedOut: Boolean,
        /** How many successful export+merge cycles ran. Zero means nothing was captured. */
        val merges: Int,
    )

    /**
     * Create the session, connect over CDP, and park a tab on [loginUrl] so the live view opens on
     * the sign-in form rather than whatever page happened to be loaded.
     *
     * Navigation is best-effort: a slow or failed load still yields a usable session — the user can
     * navigate themselves in the live view — so it must not abort the sign-in. Session creation and
     * the CDP connect are not best-effort and throw.
     */
    fun open(loginUrl: String?): Opened {
        require(baseUrl.isNotBlank()) { "Steel is disabled — set STEEL_BASE_URL first." }

        val s = client.createSession(store.load(), windowMs)
        // Record the session BEFORE connecting. A CDP connect failure still leaves a live session on
        // the backend, and Steel holds it against its single Chrome until the (30-minute) window
        // expires — so [close] has to be able to release it even when open() throws.
        session = s
        val (pw, b) = connect(s)
        playwright = pw
        browser = b

        val parked = loginUrl?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                val ctx = b.contexts().firstOrNull() ?: b.newContext()
                val p = ctx.newPage()
                page = p
                p.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000.0))
                runCatching { p.bringToFront() }
                url
            }.onFailure { log.warn("Could not park the sign-in tab on {}: {}", url, it.message) }.getOrNull()
        }

        return Opened(
            sessionId = s.id,
            debugUrl = s.debugUrl?.let { SteelBrowser.interactiveDebugUrl(uiBaseUrl.ifBlank { baseUrl }, it) },
            startUrl = parked,
            windowMs = windowMs,
        )
    }

    /**
     * Poll until the sign-in lands, [stopRequested] returns true, or the window elapses — merging the
     * session's cookies into the store on **every** poll so nothing depends on a clean finish.
     *
     * Detection, in order:
     *  - If the *first* poll sees no login wall, the stored cookies were still good: done, signed in.
     *  - Otherwise wait for the wall to clear. Requiring that we saw a wall first means a failed
     *    navigation (which leaves a URL with no login marker) can't be mistaken for success.
     *  - On deadline, return `timedOut` — with whatever was merged along the way.
     *
     * [onPoll] reports each cycle so a caller can log progress; it must not throw.
     */
    fun awaitCapture(
        stopRequested: () -> Boolean = { false },
        onPoll: (elapsedMs: Long, cookies: Int) -> Unit = { _, _ -> },
    ): Captured {
        val s = session ?: error("open() must succeed before awaitCapture()")
        val startNanos = nanoTime()
        val deadlineNanos = startNanos + windowMs * 1_000_000

        var merges = 0
        var lastCookies = 0
        var sawLoginWall = false
        var firstPoll = true

        while (true) {
            val expired = nanoTime() - deadlineNanos >= 0
            val stopped = runCatching { stopRequested() }.getOrDefault(false)

            val ctx = runCatching { client.exportContext(s.id) }.getOrNull()
            if (ctx != null) {
                store.merge(ctx)
                merges++
                lastCookies = ctx.get("cookies")?.size() ?: 0
            }
            val elapsedMs = (nanoTime() - startNanos) / 1_000_000
            runCatching { onPoll(elapsedMs, lastCookies) }

            val onWall = looksLikeLoginWall(currentUrl())
            if (firstPoll && !onWall) {
                // Nothing to sign into — the injected cookies still carry the session.
                return Captured(lastCookies, signedIn = true, timedOut = false, merges = merges)
            }
            if (onWall) sawLoginWall = true
            if (sawLoginWall && !onWall) {
                return Captured(lastCookies, signedIn = true, timedOut = false, merges = merges)
            }
            firstPoll = false

            if (stopped) return Captured(lastCookies, signedIn = !onWall, timedOut = false, merges = merges)
            if (expired) return Captured(lastCookies, signedIn = false, timedOut = true, merges = merges)

            runCatching { sleep(pollIntervalMs) }
        }
    }

    /** The live tab's URL, or empty when there is no tab / it can't be read. */
    private fun currentUrl(): String {
        val p = page ?: browser?.contexts()?.firstOrNull()?.pages()?.firstOrNull()
        return runCatching { p?.url().orEmpty() }.getOrDefault("")
    }

    /**
     * Export one final time, then release the session. Ordering matches [SteelBrowser.close]:
     * persist before release so the last moments of the sign-in are captured. Best-effort throughout —
     * close must never throw over an otherwise-successful sign-in.
     */
    override fun close() {
        session?.let { s ->
            runCatching { client.exportContext(s.id)?.let { store.merge(it) } }
            runCatching { client.releaseSession(s.id) }
        }
        runCatching { page?.close() }
        runCatching { browser?.close() }
        runCatching { playwright?.close() }
        page = null
        browser = null
        playwright = null
        session = null
    }

    companion object {
        /**
         * URL fragments that mean a tab is parked on an auth/challenge page. Mirrors
         * [SteelBrowser]'s `stuckMarkers` — the scraper treats these as "not usable", and here they
         * mean "the user still has work to do".
         */
        private val LOGIN_MARKERS = listOf(
            "/login", "/signin", "/sign-in", "/authwall", "/checkpoint",
            "/challenge", "captcha", "security-verification", "accounts.google.com",
        )

        /** Whether [url] looks like a login / challenge wall. Empty (unknown) counts as a wall. */
        fun looksLikeLoginWall(url: String): Boolean {
            if (url.isBlank() || url == "about:blank") return true
            val u = url.lowercase()
            return LOGIN_MARKERS.any { u.contains(it) }
        }

        /**
         * Best-effort sign-in URL for a site label ([com.jd.pipeline.nodes.ScrapeJdNode]'s `siteLabel`)
         * or a bare host. Known boards get their real login page; anything else falls back to the
         * host's root, which reliably redirects to that site's own sign-in when a session is missing.
         */
        fun loginUrlFor(siteOrHost: String?): String? {
            val key = siteOrHost?.trim()?.removePrefix("www.")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                key.contains("linkedin") -> "https://www.linkedin.com/login"
                key.contains("glassdoor") -> "https://www.glassdoor.com/profile/login_input.htm"
                key.contains("jobright") -> "https://jobright.ai/?login=true"
                key.contains("indeed") -> "https://secure.indeed.com/auth"
                // A bare host we don't have a login page for: its root will redirect to sign-in.
                key.contains('.') -> runCatching { URI("https://$key/").toString() }.getOrNull()
                else -> null
            }
        }
    }
}
