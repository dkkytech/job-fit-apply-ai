package com.jd.pipeline.client

import com.microsoft.playwright.Page

/**
 * A browser the scraper drives over the Chrome DevTools Protocol. Two implementations:
 *  - [ChromeCdpBrowser] attaches to a raw, user-launched host Chrome (`connectOverCDP`);
 *  - [SteelBrowser] drives a self-hosted Steel Browser session (REST-created, CDP-connected),
 *    adding persisted auth (sessionContext) and an interactive [debugUrl] for phone re-auth.
 *
 * [BrowserFactory] picks the implementation from config. Callers program to this surface only,
 * so the two paths stay swappable behind a single flag.
 */
interface CdpBrowser {
    /** True when a live browser connection is established (lazily connects on first call). */
    fun isAvailable(): Boolean

    /** A healthy, foregrounded tab dedicated to [host], reused across jobs. Call only when available. */
    fun pageForDomain(host: String): Page

    /**
     * Run [block] against a tab for [host]. Implementations whose backing session can die mid-scrape
     * ([SteelBrowser]) recover by rebuilding the session and re-running [block] on a fresh tab.
     *
     * Scraping a page is a *sequence* — acquire tab, navigate, wait, extract — and a session that
     * dies under it usually does so during the navigate/extract phase (tens of seconds), not during
     * the millisecond-long tab acquisition. So recovery has to wrap the whole sequence: use this in
     * preference to [pageForDomain] for anything that then drives the page. [block] must therefore be
     * safe to run more than once — for scraping (a GET of a job page) it is.
     *
     * The default is a plain pass-through for backends with no session to lose.
     */
    fun <T> withPageForDomain(host: String, block: (Page) -> T): T = block(pageForDomain(host))

    /** Dispose tabs and disconnect. Does NOT close the user's host Chrome; releases the Steel session. */
    fun close()

    /**
     * Interactive live-view/debug URL for the current session (for phone re-auth), or null when the
     * implementation has none (raw host Chrome). Steel returns its session viewer URL.
     */
    fun debugUrl(): String? = null
}
