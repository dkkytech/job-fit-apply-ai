package com.jd.pipeline.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.AlertService
import com.jd.pipeline.client.BrowserFactory
import com.jd.pipeline.client.CdpBrowser
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.client.SigninServer
import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import com.jd.pipeline.utils.ExpiringHostSet
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
import com.jd.pipeline.utils.UrlSafety
import org.jsoup.Jsoup
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Node: scrape_jd
 *
 * Stage 2: Fetch job URL and extract additional JD information.
 * Uses LlmClient (temperature=0 — structured extraction, deterministic).
 * Jackson replaces all regex JSON parsing.
 */
class ScrapeJdNode(
    private val llm: LlmCaller = LlmClient.forIngest(Config.SCRAPE_MODEL, jsonMode = true, temperature = 0.0, nodeKey = "scrape_jd"),
    // Long-lived Chrome connected over CDP (one tab per domain, warm session). The ONLY browser
    // path — when the endpoint is unreachable, browser-needing scrapes fail cleanly with an alert
    // (plain-HTTP scraping is unaffected). No in-process Chromium is ever launched.
    private val cdpBrowser: CdpBrowser = BrowserFactory.create(),
    private val alerts: AlertService = AlertService(),
    domainSkipTtlMs: Long = Config.SCRAPE_DOMAIN_SKIP_TTL_MS,
    // Monotonic clock seam (nanos) for the skip lists' expiry.
    nanoTime: () -> Long = System::nanoTime,
    // SSRF guard (see UrlSafety). Seams so tests need neither DNS nor a real private host.
    private val allowPrivateHosts: Boolean = Config.SCRAPE_ALLOW_PRIVATE_HOSTS,
    private val isPrivateTarget: (String) -> Boolean = { UrlSafety.isPrivateTarget(it) },
) : Node<JDState> {

    /** Set to false to suppress verbose progress logging (e.g. during tuner runs). */
    var verbose: Boolean = true

    private fun log(msg: String) { if (verbose) println(msg) }

    // ── Batch-level state ────────────────────────────────────────────────────
    // Sites that blocked us as a whole (403 / 429 / bot check), so we don't hammer them. One instance
    // lives for the Processor's whole run and nothing resets it between emails, so entries lapse after
    // [domainSkipTtlMs] — before that, one block skipped the site until the next restart.
    val batchBlockedDomains: MutableSet<String> = ExpiringHostSet(domainSkipTtlMs, nanoTime)
    // Hosts that hit an auth wall (login/challenge) via a CDP scrape — skipped until the entry lapses,
    // and each fires a phone re-auth alert (repeat-limited by AlertService). Generalizes the old
    // LinkedIn-only flag so every authenticated CDP-scraped board (Glassdoor, Jobright, …) is covered.
    val batchAuthExpiredDomains: MutableSet<String> = ExpiringHostSet(domainSkipTtlMs, nanoTime)

    /** Back-compat accessor for the LinkedIn-specific console warning (see CliOutput). */
    val batchLinkedInSessionExpired: Boolean
        get() = batchAuthExpiredDomains.any { it.contains("linkedin") }

    fun resetBatch() {
        batchBlockedDomains.clear()
        batchAuthExpiredDomains.clear()
    }

    /** True if [host] already hit an auth wall this batch (exact or parent-domain match). */
    private fun isAuthExpired(host: String): Boolean =
        host.isNotEmpty() && batchAuthExpiredDomains.any { host == it || host.endsWith(".$it") }

    /** Release the shared CDP browser connection (disconnects only — does not close the user's Chrome). */
    fun close() = cdpBrowser.close()

    private val mapper = ObjectMapper()

    // Page-fetching HttpClient — separate from LlmClient. Redirects are followed by hand
    // (sendFollowingRedirects) so every hop passes the private-network check *before* it is requested.
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    data class PageContent(val rawHtml: String, val cleanedText: String, val blockReason: String = "", val isCaptchaBlock: Boolean = false, val isBotBlock: Boolean = false, val scrapePath: String = "", val isRateLimited: Boolean = false) {
        /**
         * Whether the block is about the whole site (bot wall, CAPTCHA, rate limit) rather than this
         * one URL (404 for an expired posting, a redirect to search results). Only a site-wide block
         * skips the site's other jobs — one dead Dice link used to take every Dice job down with it.
         */
        val blocksDomain: Boolean get() = isBotBlock || isCaptchaBlock || isRateLimited
    }

    companion object {
        internal const val PRIVATE_TARGET_REASON = "private network address — not fetched"
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        // Site-agnostic auth-wall markers (see detectAuthWall). URL redirects to these paths are the
        // strongest signal a CDP-scraped page bounced us to login/verification instead of the JD.
        private val LOGIN_URL_MARKERS = listOf(
            "/login", "/signin", "/sign-in", "/authwall", "/uas/login",
            "/users/sign_in", "/account/login", "/auth/login", "/session/new"
        )
        private val CHALLENGE_URL_MARKERS = listOf(
            "/checkpoint", "/challenge", "/challengesv2", "/security-verification", "captcha"
        )
        // A login/verification page is content-thin; a real JD is far longer. Guards the DOM/text backstops.
        private const val AUTH_WALL_MAX_BODY_CHARS = 2500

        private val DEFAULT_SCRAPE_SKILL_PROMPT = """
            Extract structured job details from the following job page content.
            The content may include raw JSON from the page (PAGE_JSON_DATA) and/or visible page text.

            Return ONLY valid JSON, no markdown fences, no preamble:
            {
              "role_title": "string",
              "company": "string",
              "location": "string (city, state or Remote)",
              "remote_policy": "remote | hybrid | onsite | unknown",
              "salary_range": "string or null",
              "employment_type": "Full-time | Part-time | Contract | Internship | null",
              "seniority_level": "Entry-level | Mid-level | Senior | Lead | Director | null",
              "yoe_required": number or null,
              "tech_stack": ["string", ...],
              "benefits": ["string", ...],
              "company_description": "string or null",
              "jd_text": "string (full job description text, cleaned)"
            }

            If a field cannot be determined, use null or "unknown".
            jd_text should include all requirements, responsibilities, and qualifications.
            benefits should list individual benefit items (health, 401k, PTO, etc.).
            company_description should be a brief overview of the company (mission, size, industry).
        """.trimIndent()
    }

    override fun process(input: JDState): JDState {
        val jobUrl = input.jobUrl

        if (jobUrl.isNullOrEmpty()) {
            return input
        }

        // Browser-extension path: the page was already rendered in the user's authenticated
        // session, so skip fetching/auth-gating entirely and extract from the captured text.
        if (input.capturedText.isNotBlank()) {
            log("[scrape_jd] Using captured page text (${input.capturedText.length} chars) for $jobUrl — no fetch")
            return parseJobPage(input, jobUrl, input.capturedText).copy(scrapePath = "captured")
        }

        val host = extractHost(jobUrl)

        // ── Batch-skip checks (one attempt per domain per batch) ─────────────
        if (isAuthExpired(host)) {
            log("[scrape_jd] Skipping $host — auth wall hit earlier this batch (re-auth pending)")
            return input.copy(
                isChromeSessionExpired = true,
                error = "scrape_jd: $host needs re-authentication — skipped this batch"
            )
        }
        if (host.isNotEmpty() && batchBlockedDomains.contains(host)) {
            log("[scrape_jd] Skipping $host — blocked earlier this batch")
            return input.copy(error = "scrape_jd: $host blocked earlier in this batch — skipped")
        }

        log("[scrape_jd] Fetching job URL: $jobUrl")

        return try {
            val page = fetchPage(jobUrl)

            if (page.blockReason.isNotEmpty()) {
                log("[scrape_jd] Blocked for $jobUrl: ${page.blockReason}")
                if (page.blocksDomain) batchBlockedDomains.add(host)
                return input.copy(error = "scrape_jd: ${page.blockReason}", scrapePath = "blocked")
            }

            if (page.cleanedText.isEmpty()) {
                log("[scrape_jd] Empty page content for $jobUrl")
                return input.copy(error = "scrape_jd: empty page content", scrapePath = "empty")
            }

            parseJobPage(input, jobUrl, page.cleanedText, page.rawHtml)
                .copy(rawPageContent = page.rawHtml, scrapePath = page.scrapePath)
        } catch (e: Exception) {
            log("[scrape_jd] Error fetching $jobUrl: ${e.message}")
            when (e) {
                is AuthRequiredException -> {
                    // Any authenticated CDP site (LinkedIn, Glassdoor, Jobright, …) that hit a login
                    // or challenge wall: skip it for the batch and send ONE phone re-auth alert.
                    batchAuthExpiredDomains.add(host)
                    alerts.reauthRequired(e.site, detail = e.message, linkUrl = reauthLink(e.site))
                    input.copy(
                        isChromeSessionExpired = true,
                        error = "scrape_jd: ${e.message}"
                    )
                }
                else -> input.copy(error = "scrape_jd: ${e.message}")
            }
        }
    }

    private fun fetchPage(url: String): PageContent {
        if (!allowPrivateHosts && isPrivateTarget(url)) {
            log("[scrape_jd] Refusing private-network target: $url")
            return PageContent(rawHtml = "", cleanedText = "", blockReason = PRIVATE_TARGET_REASON, scrapePath = "blocked")
        }
        val host = extractHost(url)
        if (isAuthExpired(host)) {
            log("[scrape_jd] Auth wall pending for $host — skipping browser fetch this batch")
            return PageContent(rawHtml = "", cleanedText = "", blockReason = "auth expired — re-auth pending", scrapePath = "blocked")
        }
        if (isLinkedInHost(host)) {
            return fetchLinkedInPageWithPlaywright(url)
        }

        // Proactive CDP for known soft-blocking / challenge-prone domains: skip HTTP and use the
        // warm browser directly (fetchPageWithPlaywright alerts + fails the scrape if the debug
        // Chrome is unreachable — all browser scraping goes through the host CDP Chrome).
        if (isForceCdpHost(host)) {
            log("[scrape_jd] Force-CDP domain: $host — routing to browser")
            return fetchPageWithPlaywright(url).copy(scrapePath = "cdp_forced")
        }

        val httpResult = fetchPageOverHttp(url)
        if ((httpResult.isCaptchaBlock || httpResult.isBotBlock) && Config.PLAYWRIGHT_FALLBACK_ON_CAPTCHA) {
            log("[scrape_jd] HTTP blocked ($host: ${httpResult.blockReason}) — retrying with CDP browser")
            return fetchPageWithPlaywright(url).copy(scrapePath = "cdp_fallback")
        }
        if (httpResult.blockReason.isEmpty() &&
            httpResult.cleanedText.length < Config.PLAYWRIGHT_FALLBACK_MIN_CONTENT_LENGTH &&
            Config.PLAYWRIGHT_FALLBACK_ON_THIN_CONTENT) {
            log("[scrape_jd] Thin content ($host: ${httpResult.cleanedText.length} chars) — retrying with CDP browser")
            return fetchPageWithPlaywright(url).copy(scrapePath = "cdp_fallback")
        }
        return httpResult.copy(scrapePath = "http")
    }

    private fun pageRequest(uri: URI): HttpRequest = HttpRequest.newBuilder()
        .uri(uri)
        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9")
        .timeout(java.time.Duration.ofSeconds(20))
        .GET()
        .build()

    /**
     * GET [start], following up to [MAX_REDIRECTS] redirects the way Redirect.NORMAL would (never
     * HTTPS → HTTP), but checking each `Location` against the private-network guard *before*
     * requesting it — so an open redirect on a public site can't make us send a request inward.
     * Null when a hop is refused. The returned response's uri() is the final URL, as before.
     */
    private fun sendFollowingRedirects(start: URI): HttpResponse<String>? {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val response = httpClient.send(pageRequest(current), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in REDIRECT_CODES) return response
            val location = response.headers().firstValue("Location").orElse(null) ?: return response
            val next = current.resolve(location.trim())
            if (current.scheme.equals("https", true) && next.scheme.equals("http", true)) return response
            if (!allowPrivateHosts && isPrivateTarget(next.toString())) {
                log("[scrape_jd] Redirect to a private-network target refused: $current → $next")
                return null
            }
            current = next
        }
        throw java.io.IOException("too many redirects (> $MAX_REDIRECTS) from $start")
    }

    private fun fetchPageOverHttp(url: String): PageContent {
        try {
            val response = sendFollowingRedirects(URI.create(url))
                ?: return PageContent("", "", PRIVATE_TARGET_REASON)
            val status = response.statusCode()

            // Track the final URL after redirect chain
            val finalUrl = response.uri().toString()
            val finalHost = extractHost(finalUrl)
            log("[scrape_jd] Final URL after redirects: $finalUrl")

            when (status) {
                403 -> {
                    log("[scrape_jd] HTTP 403 (bot-blocked) for $url")
                    return PageContent("", "", "HTTP 403 — bot-blocked or auth required", isBotBlock = true)
                }
                429 -> {
                    log("[scrape_jd] HTTP 429 (rate-limited) for $url")
                    return PageContent("", "", "HTTP 429 — rate-limited", isRateLimited = true)
                }
                503 -> {
                    val body = response.body()
                    val reason = detectCaptchaInHtml(body)?.let { "HTTP 503 — $it" } ?: "HTTP 503 — service unavailable"
                    log("[scrape_jd] $reason for $url")
                    return PageContent(body, "", reason, isCaptchaBlock = true)
                }
                200 -> { /* fall through */ }
                else -> {
                    log("[scrape_jd] HTTP $status for $url")
                    return PageContent("", "", "HTTP $status")
                }
            }

            val rawHtml = response.body()

            // Detect auth-gated / SPA-shell redirect destinations before CAPTCHA/content checks.
            // True auth-gated blocks (jobleads, monster search) are NOT retried with Playwright.
            // SPA shell blocks (WttJ/Otta) ARE retried with Playwright so JS can render the job content.
            val authGatedReason = detectAuthGatedPage(finalUrl, finalHost, rawHtml)
            if (authGatedReason != null) {
                val isSpaShell = authGatedReason.contains("SPA shell")
                log("[scrape_jd] ${if (isSpaShell) "SPA shell" else "Auth-gated"} redirect detected for $url → $finalUrl: $authGatedReason")
                return PageContent(rawHtml, "", authGatedReason, isCaptchaBlock = isSpaShell)
            }

            val captchaReason = detectCaptchaInHtml(rawHtml)
            if (captchaReason != null) {
                log("[scrape_jd] CAPTCHA/bot-check detected for $url: $captchaReason")
                return PageContent(rawHtml, "", captchaReason, isCaptchaBlock = true)
            }

            return buildPageContent(rawHtml)
        } catch (e: Exception) {
            log("[scrape_jd] Failed to fetch $url: ${e.message}")
            return PageContent("", "")
        }
    }

    /**
     * Detects when an HTTP redirect has landed on an auth-gated or non-job page.
     * Returns a human-readable block reason, or null if the page appears to be a real job page.
     */
    private fun detectAuthGatedPage(finalUrl: String, finalHost: String, html: String): String? {
        val finalLower = finalUrl.lowercase()

        // JobLeads auth-gated redirect — kuuid links redirect to search/login/homepage (requires login to see actual jobs)
        if (finalHost.contains("jobleads.com")) {
            if (finalLower.contains("/search/") || finalLower.contains("/search?") || finalLower.contains("/login")) {
                return "auth-gated redirect — jobleads.com search/login page (original link requires authentication)"
            }
            // Catch homepage redirect: title "Enhance Your Job Search Now | JobLeads" has no " at " between job title and company
            val jobLeadsTitle = Jsoup.parse(html).title().lowercase()
            if (jobLeadsTitle.isBlank() || jobLeadsTitle.contains("job search") ||
                (jobLeadsTitle.contains("jobleads") && !jobLeadsTitle.contains(" at "))) {
                return "auth-gated redirect — jobleads.com homepage (original link requires authentication)"
            }
        }

        // Monster tracking links — click.monster.com resolves to search results when a specific job has expired
        if (finalHost.contains("monster.com") &&
            (finalLower.contains("/jobs/search") || finalLower.contains("/jobs/search?"))) {
            return "tracking link resolved to Monster search results page (job link expired or redirected to search)"
        }

        // WelcomeToTheJungle / Otta — React SPA, no server-side job content in HTML
        if ((finalHost.contains("welcometothejungle.com") || finalHost.contains("app.otta.com")) &&
            html.contains("static.otta.com")) {
            // Confirm it's a SPA shell with no meaningful job content
            val doc = Jsoup.parse(html)
            doc.select("script,style,noscript").remove()
            val visibleText = doc.text().trim()
            if (visibleText.length < 300) {
                return "auth-gated redirect — welcometothejungle/otta SPA shell page (no server-rendered job content)"
            }
        }

        return null
    }

    /**
     * Returns a human-readable block reason if the HTML looks like a CAPTCHA or bot-check page,
     * or null if the page appears normal.
     */
    internal fun detectCaptchaInHtml(html: String): String? {
        if (html.isBlank()) return null
        val lower = html.lowercase()

        // Cloudflare challenge markers
        if (lower.contains("cf-browser-verification") ||
            lower.contains("cf-challenge-running") ||
            lower.contains("checking your browser") ||
            lower.contains("please wait while we check your browser")) {
            return "Cloudflare browser challenge"
        }

        // DataDome challenge (Monster). Match the CAPTCHA page only: normal Monster pages also load
        // DataDome's tag script and an auto-passing `/interstitial/` frame from the same domain.
        if ((lower.contains("geo.captcha-delivery.com/captcha") || lower.contains("title=\"datadome captcha\"")) &&
            Jsoup.parse(html).text().length < AUTH_WALL_MAX_BODY_CHARS) {
            return "DataDome CAPTCHA"
        }

        // Generic CAPTCHA widget markers
        if (lower.contains("data-sitekey") &&
            (lower.contains("recaptcha") || lower.contains("hcaptcha"))) {
            return "CAPTCHA widget detected (reCAPTCHA/hCaptcha)"
        }

        // Title-based detection (short page with challenge title)
        val titleMatch = Regex("<title[^>]*>([^<]{1,120})</title>", RegexOption.IGNORE_CASE).find(html)
        val title = titleMatch?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
        if (title.contains("captcha") ||
            title.contains("robot") ||
            title.contains("security check") ||
            title.contains("attention required") ||
            title.contains("just a moment") ||
            title.contains("access denied") ||
            title.contains("verify")) {
            return "Bot-check page (title: '${titleMatch?.groupValues?.getOrNull(1)?.trim()}')"
        }

        return null
    }

    /**
     * Fetch a LinkedIn job page using the logged-in CDP Chrome (warm session, reused
     * per-domain tab). All browser scraping goes through the host CDP Chrome — when the
     * debug Chrome is unreachable the scrape fails cleanly (alerting once per batch)
     * instead of falling back to an in-process launch.
     */
    private fun fetchLinkedInPageWithPlaywright(url: String): PageContent {
        if (!cdpBrowser.isAvailable()) {
            alertCdpUnavailable()
            throw CdpUnavailableException(cdpUnavailableMessage())
        }
        log("[scrape_jd] Using CDP Chrome for LinkedIn: $url")
        // See fetchPageWithPlaywright: recovery must wrap the navigate/extract, not just the tab.
        // AuthRequiredException from requireAuthenticated is not a PlaywrightException, so it still
        // propagates on the first attempt rather than burning retries on a genuine auth wall.
        return cdpBrowser.withPageForDomain(extractHost(url)) { page ->
            scrapeLinkedInPage(page, url).copy(scrapePath = "cdp_profile")
        }
    }

    /**
     * Drive a LinkedIn job page that is already open in [page]: navigate, wait, detect
     * login/CAPTCHA (throwing the matching exception), dismiss popups, expand the description,
     * and extract content. Does NOT close [page] — the caller owns its lifecycle (a CDP tab is
     * reused; a profile-copy context is closed by its `finally`).
     */
    private fun scrapeLinkedInPage(page: Page, url: String): PageContent {
        page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(25000.0))
        waitForLinkedInPage(page)
        log("[scrape_jd] LinkedIn final URL: ${page.url()}")
        log("[scrape_jd] LinkedIn title: ${runCatching { page.title() }.getOrDefault("")}")

        requireAuthenticated(page, extractHost(url))

        dismissLinkedInPopups(page)
        expandLinkedInJobDescription(page)

        val rawHtml = page.content()
        detectCaptchaInHtml(rawHtml)?.let {
            val site = siteLabel(extractHost(url))
            throw AuthRequiredException(site, "$site blocked — $it at ${page.url()}")
        }

        val visibleText = extractLinkedInVisibleText(page)
        log("[scrape_jd] LinkedIn text preview: ${visibleText.take(300)}")
        return buildPageContent(rawHtml, visibleText)
    }

    /** The active browser-backend endpoint (Steel takes precedence over the host Chrome CDP port). */
    private fun activeBackendEndpoint(): String =
        Config.STEEL_BASE_URL.ifBlank { Config.CHROME_CDP_ENDPOINT }

    /**
     * Interactive re-auth link for the sign-in alert, best first:
     *
     *  1. The tap-to-sign-in endpoint ([SigninServer]), which creates a session *when tapped* and
     *     parks it on [site]'s login page. Preferred because it is the only option that still works
     *     after this batch ends — which is the normal case, since a human reads the alert minutes to
     *     hours later.
     *  2. The live Steel session's debug URL — valid only until this batch closes the session
     *     (~10 min), so it is a fallback for when the endpoint isn't advertised.
     *  3. The bare Steel UI / base URL, which has no session behind it at all.
     */
    private fun reauthLink(site: String): String? =
        SigninServer.signinUrl(site)
            ?: cdpBrowser.debugUrl()
            ?: Config.STEEL_UI_URL.ifBlank { Config.STEEL_BASE_URL }.takeIf { it.isNotBlank() }

    /** Fire the "browser backend unreachable" alert once per batch, only when a backend is configured. */
    private fun alertCdpUnavailable() {
        val endpoint = activeBackendEndpoint()
        // AlertService repeat-limits this (dedup key "cdp-down"), so no per-run flag is needed.
        if (endpoint.isNotBlank()) {
            alerts.chromeDebugUnavailable(endpoint)
        }
    }

    private fun cdpUnavailableMessage(): String {
        val endpoint = activeBackendEndpoint()
        return if (endpoint.isBlank())
            "Browser backend not configured (set STEEL_BASE_URL or CHROME_CDP_ENDPOINT) — browser scraping unavailable"
        else
            "Browser backend unreachable at $endpoint — browser scraping unavailable"
    }

    private fun waitForLinkedInPage(page: Page) {
        // Use LOAD rather than NETWORKIDLE — NETWORKIDLE can hang indefinitely
        // on pages with continuous analytics/chat traffic. Cap at 15s.
        try {
            page.waitForLoadState(LoadState.LOAD, Page.WaitForLoadStateOptions().setTimeout(15000.0))
        } catch (_: Exception) {
            // Continue with partial load — content selectors below will time out individually
        }

        val selectors = listOf(
            ".show-more-less-html__markup",
            ".jobs-description__content",
            ".jobs-box__html-content",
            ".description__text",
            "main"
        )

        for (selector in selectors) {
            try {
                page.locator(selector).first().waitFor(
                    com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(4000.0)
                )
                return
            } catch (_: Exception) {
            }
        }
    }

    /** Kinds of auth wall a CDP-scraped page can hit. Both fire a phone re-auth alert. */
    private enum class AuthWall { LOGIN, CHALLENGE }

    /**
     * Throw [AuthRequiredException] (→ phone re-auth alert) if [page] is an auth/challenge wall.
     * Site-agnostic — applies to every CDP-scraped page (LinkedIn, forced-CDP, and CDP fallback), so
     * any authenticated board that drops its session prompts a phone sign-in, not just LinkedIn.
     */
    private fun requireAuthenticated(page: Page, host: String) {
        detectAuthWall(page)?.let { wall ->
            val site = siteLabel(host)
            val what = if (wall == AuthWall.LOGIN) "needs sign-in" else "hit a security challenge"
            throw AuthRequiredException(site, "$site $what at ${page.url()}")
        }
    }

    /**
     * Detect whether a CDP-rendered [page] is a login wall or account challenge, for any site.
     * URL-redirect markers are the primary, low-false-positive signal (the site bounced us to a
     * login/checkpoint path); a visible password field on a content-thin page is the DOM backstop;
     * a few challenge phrases catch verification pages that keep the URL. Returns null for a real JD.
     */
    private fun detectAuthWall(page: Page): AuthWall? {
        val url = runCatching { page.url().lowercase() }.getOrDefault("")
        if (CHALLENGE_URL_MARKERS.any { url.contains(it) }) return AuthWall.CHALLENGE
        if (LOGIN_URL_MARKERS.any { url.contains(it) }) return AuthWall.LOGIN

        val hasVisiblePassword = runCatching {
            val loc = page.locator("input[type='password']")
            loc.count() > 0 && loc.first().isVisible
        }.getOrDefault(false)
        if (hasVisiblePassword) {
            val bodyLen = runCatching { page.locator("body").innerText().length }.getOrDefault(Int.MAX_VALUE)
            if (bodyLen < AUTH_WALL_MAX_BODY_CHARS) return AuthWall.LOGIN
        }

        val body = runCatching { page.locator("body").innerText().lowercase() }.getOrDefault("")
        if (body.contains("verify you're a human") || body.contains("please verify you are a human") ||
            body.contains("complete the security check") ||
            (body.contains("security verification") && body.length < AUTH_WALL_MAX_BODY_CHARS)) {
            return AuthWall.CHALLENGE
        }
        return null
    }

    /** Human-friendly site name for the re-auth alert, from the host. */
    private fun siteLabel(host: String): String {
        val h = host.removePrefix("www.").lowercase()
        return when {
            h.contains("linkedin") -> "LinkedIn"
            h.contains("glassdoor") -> "Glassdoor"
            h.contains("jobright") -> "Jobright"
            h.contains("indeed") -> "Indeed"
            else -> h.split('.').let { p ->
                if (p.size >= 2) p[p.size - 2].replaceFirstChar { it.uppercase() } else h.ifBlank { "the site" }
            }
        }
    }

    private fun extractLinkedInVisibleText(page: Page): String {
        val selectors = listOf(
            ".show-more-less-html__markup",
            ".jobs-description__content",
            ".jobs-box__html-content",
            ".description__text",
            "main",
            "body"
        )

        for (selector in selectors) {
            val text = runCatching { page.locator(selector).first().innerText().trim() }.getOrDefault("")
            if (text.length > 200) {
                // The description selectors are the pre-2026 DOM; the current one uses hashed class
                // names, so we usually land on `main`. That also holds the top card (work model,
                // employment type), which we want, and "More jobs", which lists other postings'
                // salaries and work models the LLM would otherwise attribute to this one.
                return if (selector == "main" || selector == "body") trimLinkedInRecommendations(text) else text
            }
        }

        return ""
    }

    /** Cut LinkedIn page text at the first "other jobs" section heading, if any. */
    internal fun trimLinkedInRecommendations(text: String): String {
        val markers = setOf("more jobs", "similar jobs", "people also viewed", "jobs you may be interested in")
        val lines = text.lines()
        val cut = lines.indexOfFirst { it.trim().lowercase() in markers }
        return if (cut > 0) lines.take(cut).joinToString("\n").trim() else text
    }

    private fun buildPageContent(rawHtml: String, preferredVisibleText: String? = null): PageContent {
        var nextDataJson = ""
        val nextDataMatcher = Pattern.compile(
            "<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>([^<]+)</script>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        ).matcher(rawHtml)
        if (nextDataMatcher.find()) {
            nextDataJson = nextDataMatcher.group(1).trim()
        }

        val document = Jsoup.parse(rawHtml)
        // Extract schema.org JobPosting JSON-LD before stripping <script> tags. Most
        // job boards/ATS (Indeed, Monster, ZipRecruiter, Lever, Greenhouse, Workday…)
        // embed the full, clean JD here — far better than scraping visible text, and
        // it's the difference between a real JD and a thin digest summary for scoring.
        val jsonLdJob = extractJobPostingJsonLd(document)
        document.select("script,style,noscript").remove()

        var text = preferredVisibleText?.takeIf { it.isNotBlank() } ?: document.text()
        text = text.replace(Regex("\\s+"), " ").trim()

        val prefixes = buildList {
            if (nextDataJson.isNotEmpty()) add("PAGE_JSON_DATA:\n$nextDataJson")
            if (jsonLdJob != null) add(jsonLdJob)
        }
        if (prefixes.isNotEmpty()) {
            text = prefixes.joinToString("\n\n") + "\n\n" + text
        }

        return PageContent(rawHtml, text)
    }

    /**
     * Report fields read straight from a schema.org `JobPosting`. Deterministic, so they win over
     * the LLM's reading of the same page (see [keepStructuredFields]). Empty string / null = absent.
     */
    data class JobPostingFacts(
        val title: String = "",
        val company: String = "",
        val location: String = "",
        val remotePolicy: String = "",
        val salaryRange: String = "",
        val employmentType: String = "",
        val yoeRequired: Int? = null,
        val skills: List<String> = emptyList(),
        val description: String = "",
    )

    /**
     * Extract a schema.org `JobPosting` from `application/ld+json` script blocks and
     * render it as a clean, labeled text block (title, company, location, salary,
     * employment type, full description). Returns null when no usable JobPosting with
     * a substantive description is found. Handles single objects, arrays, and `@graph`.
     */
    internal fun extractJobPostingJsonLd(html: String): String? = extractJobPostingJsonLd(Jsoup.parse(html))

    internal fun extractJobPostingJsonLd(document: org.jsoup.nodes.Document): String? {
        val facts = parseJobPostingFacts(document) ?: return null
        return buildString {
            appendLine("STRUCTURED_JOB_DATA (authoritative — prefer over visible text):")
            if (facts.title.isNotBlank()) appendLine("Title: ${facts.title}")
            if (facts.company.isNotBlank()) appendLine("Company: ${facts.company}")
            if (facts.location.isNotBlank()) appendLine("Location: ${facts.location}")
            if (facts.remotePolicy.isNotBlank()) appendLine("Work model: ${facts.remotePolicy}")
            if (facts.salaryRange.isNotBlank()) appendLine("Salary: ${facts.salaryRange}")
            if (facts.employmentType.isNotBlank()) appendLine("Employment type: ${facts.employmentType}")
            facts.yoeRequired?.let { appendLine("Years of experience required: $it") }
            if (facts.skills.isNotEmpty()) appendLine("Skills: ${facts.skills.joinToString(", ")}")
            appendLine("Description: ${facts.description}")
        }.trim()
    }

    internal fun parseJobPostingFacts(html: String): JobPostingFacts? =
        if (html.isBlank()) null else parseJobPostingFacts(Jsoup.parse(html))

    /** The first JobPosting with a substantive (>= 100 char) description, or null. */
    internal fun parseJobPostingFacts(document: org.jsoup.nodes.Document): JobPostingFacts? {
        for (script in document.select("script[type=application/ld+json]")) {
            val json = script.data().trim()
            if (json.isBlank()) continue
            val root = try { mapper.readTree(json) } catch (_: Exception) { null }
            if (root == null) continue
            val posting = findJobPostingNode(root) ?: continue

            val descHtml = posting.path("description").asText("")
            if (descHtml.isBlank()) continue
            val descText = Jsoup.parse(descHtml).text().replace(Regex("\\s+"), " ").trim()
            if (descText.length < 100) continue  // too thin to be a real JD body

            val loc = posting.path("jobLocation").let { jl ->
                val addr = (if (jl.isArray) jl.firstOrNull() else jl)?.path("address")
                listOfNotNull(
                    addr?.path("addressLocality")?.asText("")?.takeIf { it.isNotBlank() },
                    addr?.path("addressRegion")?.asText("")?.takeIf { it.isNotBlank() },
                ).joinToString(", ")
            }
            val employmentType = posting.path("employmentType").let {
                if (it.isArray) it.firstOrNull()?.asText("").orEmpty() else it.asText("")
            }.trim()
            // schema.org's only remote signal. Hybrid/onsite aren't expressible, so absence means "unknown".
            val remote = posting.path("jobLocationType").let {
                if (it.isArray) it.map { e -> e.asText() } else listOf(it.asText(""))
            }.any { it.equals("TELECOMMUTE", ignoreCase = true) }

            return JobPostingFacts(
                title = posting.path("title").asText("").trim(),
                company = posting.path("hiringOrganization").path("name").asText("").trim(),
                location = loc,
                remotePolicy = if (remote) "remote" else "",
                salaryRange = formatJsonLdSalary(posting.path("baseSalary")),
                employmentType = if (employmentType.isNotBlank()) normalizeEmploymentType(employmentType) else "",
                yoeRequired = jsonLdYearsOfExperience(posting.path("experienceRequirements")),
                skills = jsonLdSkills(posting.path("skills")),
                description = descText,
            )
        }
        return null
    }

    /**
     * `baseSalary` comes in two shapes: the spec's `{value: {minValue, maxValue, unitText}}` (Built In,
     * Indeed, Greenhouse) and a flattened `{minValue, maxValue}` (Dice). `value` may also be a bare
     * number. Renders "$121K - $219K/yr" or "$55 - $65/hr"; with no unit, sub-1000 amounts are hourly.
     */
    internal fun formatJsonLdSalary(baseSalary: com.fasterxml.jackson.databind.JsonNode): String {
        if (baseSalary.isMissingNode || baseSalary.isNull) return ""
        val value = baseSalary.path("value")
        val holder = if (value.isObject) value else baseSalary
        fun num(n: com.fasterxml.jackson.databind.JsonNode): Double? =
            (if (n.isNumber) n.asDouble() else n.asText("").replace(",", "").toDoubleOrNull())?.takeIf { it > 0 }
        val min = num(holder.path("minValue")) ?: if (value.isValueNode) num(value) else num(holder.path("value"))
        val max = num(holder.path("maxValue"))
        if (min == null && max == null) return ""
        val unit = holder.path("unitText").asText("").ifBlank { baseSalary.path("unitText").asText("") }.uppercase()
        val suffix = when (unit) {
            "YEAR" -> "/yr"
            "HOUR" -> "/hr"
            "MONTH" -> "/mo"
            "WEEK" -> "/wk"
            "DAY" -> "/day"
            else -> if ((max ?: min!!) < 1000) "/hr" else "/yr"
        }
        val currency = baseSalary.path("currency").asText("").ifBlank { holder.path("currency").asText("") }
        val symbol = if (currency.isBlank() || currency.equals("USD", ignoreCase = true)) "\$" else "$currency "
        fun money(v: Double) =
            if (v >= 1000) "$symbol${Math.round(v / 1000)}K" else "$symbol${"%.2f".format(v).removeSuffix(".00")}"
        return when {
            min != null && max != null && min != max -> "${money(min)} - ${money(max)}$suffix"
            else -> "${money(min ?: max!!)}$suffix"
        }
    }

    /** `experienceRequirements` is either `{monthsOfExperience: 60}` or free text ("5+ years ..."). */
    private fun jsonLdYearsOfExperience(node: com.fasterxml.jackson.databind.JsonNode): Int? {
        val months = node.path("monthsOfExperience").asInt(0)
        if (months > 0) return (months + 11) / 12
        val text = if (node.isTextual) node.asText() else node.path("description").asText("")
        return Regex("(\\d{1,2})\\+?\\s*(?:-\\s*\\d{1,2}\\s*)?years?", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..40 }
    }

    /** `skills` is a comma-separated string, an array of strings, or an array of DefinedTerms. */
    private fun jsonLdSkills(node: com.fasterxml.jackson.databind.JsonNode): List<String> = when {
        node.isArray -> node.mapNotNull { e ->
            (if (e.isTextual) e.asText() else e.path("name").asText("")).trim().takeIf { it.isNotBlank() }
        }
        node.isTextual -> node.asText().split(",").map { it.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }

    /**
     * Apply [facts] to [state]. The page's own structured data describes this exact posting, so it
     * replaces email-digest guesses for the metadata fields unless [fillOnly]; title/company/location
     * always only fill blanks.
     */
    internal fun applyJobPostingFacts(state: JDState, facts: JobPostingFacts, fillOnly: Boolean = false): JDState {
        fun blank(v: String) = v.isBlank() || v == "unknown"
        var s = state
        if (blank(s.roleTitle) && facts.title.isNotBlank()) s = s.copy(roleTitle = facts.title)
        if (blank(s.company) && facts.company.isNotBlank()) s = s.copy(company = facts.company)
        if (blank(s.location) && facts.location.isNotBlank()) s = s.copy(location = facts.location)
        if (facts.remotePolicy.isNotBlank() && (!fillOnly || blank(s.remotePolicy))) s = s.copy(remotePolicy = facts.remotePolicy)
        if (facts.salaryRange.isNotBlank() && (!fillOnly || s.salaryRange.isBlank())) s = s.copy(salaryRange = facts.salaryRange)
        if (facts.employmentType.isNotBlank() && (!fillOnly || s.employmentType.isBlank())) s = s.copy(employmentType = facts.employmentType)
        if (facts.yoeRequired != null && (!fillOnly || s.yoeRequired == null)) s = s.copy(yoeRequired = facts.yoeRequired)
        if (facts.skills.isNotEmpty() && (!fillOnly || s.techStack.isEmpty())) s = s.copy(techStack = facts.skills)
        return s
    }

    private fun findJobPostingNode(node: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode? {
        when {
            node.isObject -> {
                val type = node.path("@type")
                val isJob = (type.isTextual && type.asText() == "JobPosting") ||
                    (type.isArray && type.any { it.asText() == "JobPosting" })
                if (isJob) return node
                if (node.has("@graph")) findJobPostingNode(node.path("@graph"))?.let { return it }
            }
            node.isArray -> for (el in node) findJobPostingNode(el)?.let { return it }
        }
        return null
    }

    private fun extractHost(url: String): String {
        return try {
            URI.create(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun isLinkedInHost(host: String): Boolean {
        return host == "linkedin.com" || host.endsWith(".linkedin.com")
    }

    // Domains configured to always scrape via the CDP browser (parsed once from CDP_FORCE_DOMAINS).
    private val forceCdpDomains: List<String> by lazy {
        Config.CDP_FORCE_DOMAINS.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    // jobright.ai is force-CDP by configuration (CDP_FORCE_DOMAINS in .env / .env.example) — its full
    // JD only renders in the logged-in session. Kept in config rather than hardcoded here so the
    // list stays in one place; when that routing does not help, ScoreFitNode refuses to score the
    // digest stub that survives.
    private fun isForceCdpHost(host: String): Boolean = matchesDomainSuffix(host, forceCdpDomains)

    /** True when [host] equals, or is a subdomain of, any entry in [domains]. */
    internal fun matchesDomainSuffix(host: String, domains: List<String>): Boolean =
        domains.any { host == it || host.endsWith(".$it") }

    // ── LLM extraction ────────────────────────────────────────────────────────

    internal fun parseJobPage(input: JDState, url: String, content: String, rawHtml: String = ""): JDState {
        val emailJdText = input.jdText   // preserve before clearing — restored as last-resort fallback
        val emailCompany = input.company
        val emailRoleTitle = input.roleTitle
        val emailLocation = input.location
        val emailRemotePolicy = input.remotePolicy
        val emailSalaryRange = input.salaryRange
        val emailYoe = input.yoeRequired
        val emailTechStack = input.techStack

        val truncated = content.take(if (isJobrightUrl(url)) 16000 else 8000)
        val skillPrompt = loadSkillPrompt()
        val prompt = """
            $skillPrompt

            JOB PAGE URL: $url

            CONTENT:
            $truncated
        """.trimIndent()

        val base = input.copy(scrapedContent = content, jdText = "")
        var state = base

        // Jobright: extract all structured fields directly from __NEXT_DATA__ JSON.
        // The SSR'd visible HTML is often incomplete (e.g. only Responsibilities), while
        // __NEXT_DATA__ always contains the full job object including About and Qualifications.
        if (isJobrightUrl(url) && content.startsWith("PAGE_JSON_DATA:")) {
            val jsonEnd = content.indexOf("\n\n")
            if (jsonEnd > 0) {
                val nextDataJson = content.substring("PAGE_JSON_DATA:\n".length, jsonEnd)
                val extracted = applyJobrightStructuredData(state, nextDataJson)
                if (extracted !== state) log("[scrape_jd] Jobright structured extraction applied")
                else log("[scrape_jd] Jobright __NEXT_DATA__ present but no fields extracted (schema mismatch?)")
                state = extracted
            }
        }

        // Jobright also exposes report metadata in client-data scripts and JSON-LD outside
        // __NEXT_DATA__. Salesforce's page keeps salary, work model, and seniority there.
        // Read it before the LLM so optional facts it omits cannot turn into report placeholders.
        // Needs the raw HTML: the cleaned content has had its <script> blocks stripped.
        if (isJobrightUrl(url)) {
            state = applyJobrightRawPageMetadata(state, rawHtml.ifBlank { content })
        }

        // schema.org JobPosting (Dice, Built In, Indeed, ZipRecruiter, Greenhouse, Lever, Workday…):
        // remote / salary / employment type / experience / skills, straight from the page.
        // Jobright's own job object (read above) is richer than its JSON-LD, so there it only fills gaps.
        parseJobPostingFacts(rawHtml)?.let { facts ->
            state = applyJobPostingFacts(state, facts, fillOnly = isJobrightUrl(url))
            log("[scrape_jd] JSON-LD JobPosting applied (remote=${facts.remotePolicy.ifBlank { "-" }}, " +
                "salary=${facts.salaryRange.ifBlank { "-" }}, type=${facts.employmentType.ifBlank { "-" }})")
        }
        val structured = state

        val llmResponse = try {
            // One retry on an unparseable reply: glm occasionally drops the opening `{"role`.
            callLlmForJson(prompt) ?: callLlmForJson(prompt)
        } catch (e: Exception) {
            log("[scrape_jd] LLM extraction failed: ${e.message}")
            null
        }
        if (llmResponse != null) {
            state = keepStructuredFields(applyLlmFields(state, llmResponse), structured, base)
        } else {
            // Regex-based salary/remote fallback when the LLM is unavailable or unparseable
            val salary = extractSalary(content)
            val remotePolicy = extractRemotePolicy(content)
            state = state.copy(
                salaryRange = state.salaryRange.ifBlank { salary },
                remotePolicy = if (state.remotePolicy.isBlank() || state.remotePolicy == "unknown") remotePolicy else state.remotePolicy,
            )
        }

        state = applyScanFallbacks(
            state = state,
            emailCompany = emailCompany,
            emailRoleTitle = emailRoleTitle,
            emailLocation = emailLocation,
            emailRemotePolicy = emailRemotePolicy,
            emailSalaryRange = emailSalaryRange,
            emailYoe = emailYoe,
            emailTechStack = emailTechStack
        )

        val finalJdText = when {
            state.jdText.isNotBlank() -> state.jdText
            content.isNotBlank() -> buildFallbackJdTextFromPage(content)
            emailJdText.isNotBlank() -> {
                log("[scrape_jd] Scrape returned no jd_text — keeping email-scanned text")
                emailJdText
            }
            else -> ""
        }

        return state.copy(
            jdText = finalJdText,
            isJobPosting = finalJdText.isNotBlank()
        )
    }

    /**
     * Call the extraction LLM and return its reply as a JSON object, or null when the reply has no
     * parseable object. Tolerates fences and chatter around the object ("json\n{...}").
     */
    private fun callLlmForJson(prompt: String): com.fasterxml.jackson.databind.JsonNode? {
        val reply = llm.call(prompt)
        return parseLlmJson(reply).also {
            if (it == null) log("[scrape_jd] LLM response parse failed: ${reply.take(120).replace("\n", " ")}")
        }
    }

    internal fun parseLlmJson(reply: String): com.fasterxml.jackson.databind.JsonNode? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { mapper.readTree(reply.substring(start, end + 1)) }.getOrNull()?.takeIf { it.isObject }
    }

    /**
     * Fields the structured pass ([structured]) changed from [base] came from data the page embeds
     * for this exact posting, so they beat the LLM's reading of the same page in [llm].
     */
    private fun keepStructuredFields(llm: JDState, structured: JDState, base: JDState): JDState {
        fun <T> pick(s: T, b: T, l: T): T = if (s != b) s else l
        return llm.copy(
            roleTitle = pick(structured.roleTitle, base.roleTitle, llm.roleTitle),
            company = pick(structured.company, base.company, llm.company),
            location = pick(structured.location, base.location, llm.location),
            remotePolicy = pick(structured.remotePolicy, base.remotePolicy, llm.remotePolicy),
            salaryRange = pick(structured.salaryRange, base.salaryRange, llm.salaryRange),
            employmentType = pick(structured.employmentType, base.employmentType, llm.employmentType),
            seniorityLevel = pick(structured.seniorityLevel, base.seniorityLevel, llm.seniorityLevel),
            yoeRequired = pick(structured.yoeRequired, base.yoeRequired, llm.yoeRequired),
            techStack = pick(structured.techStack, base.techStack, llm.techStack),
            benefits = pick(structured.benefits, base.benefits, llm.benefits),
            companyDescription = pick(structured.companyDescription, base.companyDescription, llm.companyDescription),
            // Jobright's __NEXT_DATA__ sections are the full JD; the LLM's jd_text is a re-typing of it.
            jdText = pick(structured.jdText, base.jdText, llm.jdText),
        )
    }

    /**
     * Apply the LLM's JSON fields to [state]. Fields absent, null, or "unknown" in the response
     * fall back to the current state values.
     */
    private fun applyLlmFields(state: JDState, node: com.fasterxml.jackson.databind.JsonNode): JDState {
        fun text(key: String): String? = node.path(key).asText("").trim()
            .takeIf { it.isNotBlank() && it.lowercase() !in setOf("null", "unknown", "n/a", "none") }

        val roleTitle = text("role_title")
        val company = text("company")
        val location = text("location")
        val remotePolicy = text("remote_policy")
        val salaryRange = text("salary_range")
        val employmentType = text("employment_type")
        val seniorityLevel = text("seniority_level")
        val yoeRequired = node.path("yoe_required").asInt(0).takeIf { it > 0 }
        val techStack = node.path("tech_stack").map { it.asText() }.filter { it.isNotBlank() }
        val benefits = node.path("benefits").map { it.asText() }.filter { it.isNotBlank() }
        val companyDescription = text("company_description")
        val jdText = text("jd_text")

        return state.copy(
            roleTitle = roleTitle ?: state.roleTitle,
            company = company ?: state.company,
            location = location ?: state.location,
            remotePolicy = remotePolicy ?: state.remotePolicy,
            salaryRange = salaryRange ?: state.salaryRange,
            employmentType = employmentType ?: state.employmentType,
            seniorityLevel = seniorityLevel ?: state.seniorityLevel,
            yoeRequired = yoeRequired ?: state.yoeRequired,
            techStack = if (techStack.isNotEmpty()) techStack else state.techStack,
            benefits = if (benefits.isNotEmpty()) benefits else state.benefits,
            companyDescription = companyDescription ?: state.companyDescription,
            jdText = jdText ?: state.jdText,
            isJobPosting = jdText != null
        )
    }

    /**
     * Fill blank / "unknown" fields from the email-scanned values when the scrape didn't
     * produce better data. Returns a new JDState — no mutation.
     */
    private fun applyScanFallbacks(
        state: JDState,
        emailCompany: String,
        emailRoleTitle: String,
        emailLocation: String,
        emailRemotePolicy: String,
        emailSalaryRange: String,
        emailYoe: Int?,
        emailTechStack: List<String>
    ): JDState {
        var s = state
        if ((s.company.isBlank() || s.company == "unknown") && emailCompany.isNotBlank()) {
            s = s.copy(company = emailCompany)
            log("[scrape_jd] Using email company: $emailCompany")
        }
        if ((s.roleTitle.isBlank() || s.roleTitle == "unknown") && emailRoleTitle.isNotBlank()) {
            s = s.copy(roleTitle = emailRoleTitle)
            log("[scrape_jd] Using email role title: $emailRoleTitle")
        }
        if ((s.location.isBlank() || s.location == "unknown") && emailLocation.isNotBlank()) {
            s = s.copy(location = emailLocation)
        }
        if ((s.remotePolicy.isBlank() || s.remotePolicy == "unknown") && emailRemotePolicy.isNotBlank()) {
            s = s.copy(remotePolicy = emailRemotePolicy)
        }
        if (s.salaryRange.isBlank() && emailSalaryRange.isNotBlank()) {
            s = s.copy(salaryRange = emailSalaryRange)
        }
        if (s.yoeRequired == null && emailYoe != null) {
            s = s.copy(yoeRequired = emailYoe)
        }
        if (s.techStack.isEmpty() && emailTechStack.isNotEmpty()) {
            s = s.copy(techStack = emailTechStack)
        }
        return s
    }

    private fun buildFallbackJdTextFromPage(content: String): String {
        return content
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(12000)
    }

    private fun loadSkillPrompt(): String = try {
        if (Files.exists(Config.SCRAPE_SKILL)) {
            Files.readString(Config.SCRAPE_SKILL)
        } else {
            DEFAULT_SCRAPE_SKILL_PROMPT
        }
    } catch (e: Exception) {
        log("[scrape_jd] Failed to load scrape skill file: ${e.message}")
        DEFAULT_SCRAPE_SKILL_PROMPT
    }

    private fun isJobrightUrl(url: String): Boolean = isJobrightHost(extractHost(url))

    private fun isJobrightHost(host: String): Boolean =
        host == "jobright.ai" || host.endsWith(".jobright.ai")

    /**
     * Parses Jobright's __NEXT_DATA__ JSON to extract fields not reliably present in visible text.
     * Jobright is a Next.js app; all structured job data lives in the server-rendered JSON blob.
     * Only populates fields that are blank in the current state (LLM runs after this and can override).
     */
    internal fun applyJobrightStructuredData(state: JDState, nextDataJson: String): JDState {
        return try {
            val root = mapper.readTree(nextDataJson)
            val pageProps = root.path("props").path("pageProps")
            val dataSource = pageProps.path("dataSource")

            // Current Jobright schema nests the job under pageProps.dataSource.jobResult (company data
            // is a sibling at dataSource.companyResult). Older layouts put it directly on pageProps —
            // kept as fallbacks so a schema revert doesn't silently break extraction again.
            val job = sequenceOf(
                dataSource.path("jobResult"),
                pageProps.path("job"),
                pageProps.path("jobData"),
                pageProps.path("jobDetails"),
                pageProps.path("data"),
            ).firstOrNull { !it.isMissingNode && !it.isNull }
                ?: return state
            val companyNode = sequenceOf(dataSource.path("companyResult"), job.path("company"))
                .firstOrNull { !it.isMissingNode && !it.isNull }

            var s = state

            // Employment type
            if (s.employmentType.isBlank()) {
                val raw = firstText(job, "employmentType", "jobType", "employment_type", "type")
                if (raw.isNotBlank()) s = s.copy(employmentType = normalizeEmploymentType(raw))
            }

            // Seniority level
            if (s.seniorityLevel.isBlank()) {
                val raw = firstText(job, "jobSeniority", "seniorityLevel", "seniority_level", "level", "experienceLevel", "seniority")
                if (raw.isNotBlank()) s = s.copy(seniorityLevel = raw)
            }

            // Years of experience required
            if (s.yoeRequired == null) {
                val yoe = sequenceOf("minYearsOfExperience", "yearsOfExperience", "yoe", "minExperience")
                    .map { job.path(it).asInt(0) }.firstOrNull { it > 0 }
                if (yoe != null) s = s.copy(yoeRequired = yoe)
            }

            // Remote policy (JDState defaults to "unknown", not blank)
            if (s.remotePolicy.isBlank() || s.remotePolicy == "unknown") {
                val workModel = firstText(job, "workModel", "remotePolicy", "remote_policy")
                val remote = when {
                    workModel.isNotBlank() -> workModel
                    job.path("isRemote").asBoolean(false) -> "Remote"
                    else -> ""
                }
                if (remote.isNotBlank()) s = s.copy(remotePolicy = remote)
            }

            // Location
            if (s.location.isBlank() || s.location == "unknown") {
                val loc = firstText(job, "jobLocation", "location")
                    .ifBlank { job.path("jobLocations").firstOrNull()?.asText("") ?: "" }
                if (loc.isNotBlank()) s = s.copy(location = loc)
            }

            // Salary
            if (s.salaryRange.isBlank()) {
                val salaryStr = firstText(job, "salaryDesc", "salaryString", "salary_string")
                val min = sequenceOf("minSalary", "salaryMin", "salary_min").map { job.path(it).asLong(0) }.firstOrNull { it > 0 }
                val max = sequenceOf("maxSalary", "salaryMax", "salary_max").map { job.path(it).asLong(0) }.firstOrNull { it > 0 }
                val salary = when {
                    salaryStr.isNotBlank() -> salaryStr
                    min != null && max != null -> "\$${min / 1000}K – \$${max / 1000}K"
                    min != null -> "\$${min / 1000}K+"
                    else -> ""
                }
                if (salary.isNotBlank()) s = s.copy(salaryRange = salary)
            }

            // Benefits
            if (s.benefits.isEmpty()) {
                val list = firstList(job, "benefitsSummaries", "benefits", "perks")
                if (list.isNotEmpty()) s = s.copy(benefits = list)
            }

            // Company description
            if (s.companyDescription.isBlank() && companyNode != null) {
                val desc = firstText(companyNode, "companyDesc", "companyDescription", "company_description", "description", "about", "overview")
                if (desc.isNotBlank()) s = s.copy(companyDescription = desc)
            }

            // Tech stack / skills — jdCoreSkills holds {skill, score, type} objects on the current schema.
            if (s.techStack.isEmpty()) {
                val skills = firstList(job, "jdCoreSkills", "skills", "requiredSkills", "required_skills", "techStack", "tech_stack")
                if (skills.isNotEmpty()) s = s.copy(techStack = skills)
            }

            // Full job description text — assemble from all available sections.
            // The SSR HTML often only surfaces one section; the JSON has everything.
            if (s.jdText.isBlank()) {
                val sections = mutableListOf<String>()

                // About / company overview
                val aboutText = firstSection(job, "about", "aboutCompany", "about_company", "companyDescription", "company_description")
                    .ifBlank { s.companyDescription }
                if (aboutText.isNotBlank()) sections.add("About the Company\n$aboutText")

                // Main job description
                val descText = firstSection(job, "jobSummary", "description", "jobDescription", "job_description", "summary")
                if (descText.isNotBlank()) sections.add(descText)

                // Responsibilities
                val respText = firstSection(job, "coreResponsibilities", "responsibilities", "jobResponsibilities", "job_responsibilities", "duties")
                if (respText.isNotBlank()) sections.add("Responsibilities\n$respText")

                // Qualifications / requirements
                val qualText = firstSection(job, "skillSummaries", "qualifications", "requirements", "jobQualifications", "job_qualifications")
                if (qualText.isNotBlank()) sections.add("Qualifications\n$qualText")

                if (sections.isNotEmpty()) s = s.copy(jdText = sections.joinToString("\n\n"))
            }

            s
        } catch (e: Exception) {
            log("[scrape_jd] Jobright __NEXT_DATA__ parse failed: ${e.message}")
            state
        }
    }

    /**
     * Fills metadata Jobright keeps in raw client-data scripts or JSON-LD rather than its
     * __NEXT_DATA__ job object. Existing values remain authoritative.
     */
    internal fun applyJobrightRawPageMetadata(state: JDState, rawHtml: String): JDState {
        fun rawString(key: String): String {
            val pattern = Regex("[\\\"']${Regex.escape(key)}[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']")
            return pattern.find(rawHtml)?.groupValues?.get(1)?.trim().orEmpty()
        }

        var s = state
        val rawSalary = rawString("salaryDesc").ifBlank { rawString("salaryString") }
        if (s.salaryRange.isBlank() && rawSalary.isNotBlank()) s = s.copy(salaryRange = rawSalary)
        if (s.remotePolicy.isBlank() || s.remotePolicy == "unknown") {
            rawString("workModel").takeIf { it.isNotBlank() }?.let { s = s.copy(remotePolicy = it) }
        }
        if (s.seniorityLevel.isBlank()) {
            rawString("jobSeniority").takeIf { it.isNotBlank() }?.let { s = s.copy(seniorityLevel = it) }
        }
        if (s.employmentType.isBlank()) {
            rawString("employmentType").takeIf { it.isNotBlank() }?.let {
                s = s.copy(employmentType = normalizeEmploymentType(it))
            }
        }
        if (s.location.isBlank() || s.location == "unknown") {
            rawString("jobLocation").takeIf { it.isNotBlank() }?.let { s = s.copy(location = it) }
        }

        val posting = Jsoup.parse(rawHtml).select("script[type=application/ld+json]")
            .asSequence()
            .mapNotNull { script -> runCatching { mapper.readTree(script.data().trim()) }.getOrNull() }
            .mapNotNull(::findJobPostingNode)
            .firstOrNull()
            ?: return s

        if (s.location.isBlank() || s.location == "unknown") {
            val address = posting.path("jobLocation").let { locations ->
                (if (locations.isArray) locations.firstOrNull() else locations)?.path("address")
            }
            val location = listOfNotNull(
                address?.path("addressLocality")?.asText()?.takeIf { it.isNotBlank() },
                address?.path("addressRegion")?.asText()?.takeIf { it.isNotBlank() },
            ).joinToString(", ")
            if (location.isNotBlank()) s = s.copy(location = location)
        }
        if (s.employmentType.isBlank()) {
            val employmentType = posting.path("employmentType").let {
                if (it.isArray) it.firstOrNull()?.asText().orEmpty() else it.asText("")
            }
            if (employmentType.isNotBlank()) s = s.copy(employmentType = normalizeEmploymentType(employmentType))
        }
        if (s.salaryRange.isBlank()) {
            val value = posting.path("baseSalary").path("value")
            val min = value.path("minValue").asLong(0).takeIf { it > 0 }
            val max = value.path("maxValue").asLong(0).takeIf { it > 0 }
            val salary = when {
                min != null && max != null -> "\$${min / 1000}K – \$${max / 1000}K"
                min != null -> "\$${min / 1000}K+"
                else -> ""
            }
            if (salary.isNotBlank()) s = s.copy(salaryRange = salary)
        }
        return s
    }

    /** First non-blank string value among [keys] on [node] (ignores literal "null"). */
    private fun firstText(node: com.fasterxml.jackson.databind.JsonNode, vararg keys: String): String =
        keys.asSequence().map { node.path(it).asText("").trim() }
            .firstOrNull { it.isNotEmpty() && it != "null" } ?: ""

    /**
     * Flattens the first array field among [keys] into a list of strings. Array elements may be plain
     * strings or objects — for objects the "skill" then "name" property is used (covers jdCoreSkills).
     */
    private fun firstList(node: com.fasterxml.jackson.databind.JsonNode, vararg keys: String): List<String> {
        val arr = keys.asSequence().map { node.path(it) }.firstOrNull { it.isArray } ?: return emptyList()
        return arr.mapNotNull { el ->
            when {
                el.isTextual -> el.asText("").takeIf { it.isNotBlank() }
                else -> firstText(el, "skill", "name").takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * Renders the first present section among [keys] as text: a string field verbatim, or an array
     * field as newline-joined "- " bullets (Jobright stores responsibilities/qualifications as arrays).
     */
    private fun firstSection(node: com.fasterxml.jackson.databind.JsonNode, vararg keys: String): String {
        for (k in keys) {
            val v = node.path(k)
            if (v.isTextual && v.asText().isNotBlank()) return v.asText().trim()
            if (v.isArray) {
                val items = v.mapNotNull { el -> el.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotBlank() } }
                if (items.isNotEmpty()) return items.joinToString("\n") { "- $it" }
            }
        }
        return ""
    }

    private fun normalizeEmploymentType(raw: String): String = when (raw.uppercase().replace("-", "_").replace(" ", "_")) {
        "FULL_TIME", "FULLTIME" -> "Full-time"
        "PART_TIME", "PARTTIME" -> "Part-time"
        "CONTRACT", "CONTRACTOR" -> "Contract"
        "INTERNSHIP" -> "Internship"
        "TEMPORARY" -> "Temporary"
        else -> raw
    }

    // ── Regex fallbacks (used only when LLM fails entirely) ───────────────────

    private fun extractSalary(text: String): String {
        val patterns = listOf(
            "\\$[0-9]{2,3}(?:K|k)\\s*[–-]?\\s*\\$[0-9]{2,3}(?:K|k)",
            "\\$[0-9]{2,3},?000\\s*[–-]?\\s*\\$[0-9]{2,3},?000",
            "\\$[0-9]{5,7}\\s*[–-]?\\s*\\$[0-9]{5,7}"
        )
        for (pattern in patterns) {
            val m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text)
            if (m.find()) return m.group()
        }
        return ""
    }

    private fun extractRemotePolicy(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("fully remote") || lower.contains("100% remote") ||
            lower.contains("remote work") || lower.contains("work from home") -> "remote"
            lower.contains("hybrid") || lower.contains("flexible") -> "hybrid"
            lower.contains("onsite") || lower.contains("on-site") ||
            lower.contains("in-office") || lower.contains("in office") -> "onsite"
            else -> "unknown"
        }
    }

    /**
     * Attempts to dismiss common LinkedIn popup overlays that can obscure the job description:
     * sign-in prompts, cookie consent banners, and modal close buttons.
     * Each attempt uses a short timeout so failures do not add meaningful delay.
     */
    private fun dismissLinkedInPopups(page: Page) {
        val dismissSelectors = listOf(
            // Generic modal close / dismiss
            "button.artdeco-modal__dismiss",
            "button[aria-label='Dismiss']",
            "button[aria-label='Close']",
            // Cookie consent
            "button[action-type='ACCEPT_COOKIE']",
            "button[data-control-name='accept_cookies']",
            // "Sign in to view more" overlay dismiss / see more without signing in
            "button[data-tracking-control-name='public_jobs_contextual-sign-in-modal_modal_dismiss']",
            // Messaging bubble close
            ".msg-overlay-bubble-header__control--close-btn"
        )
        for (selector in dismissSelectors) {
            try {
                val btn = page.locator(selector).first()
                btn.waitFor(com.microsoft.playwright.Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(1500.0))
                btn.click()
                log("[scrape_jd] Dismissed popup: $selector")
            } catch (_: Exception) {
                // Not present — continue
            }
        }
    }

    /**
     * Clicks "Show more" / expand buttons within the LinkedIn job description to ensure the full
     * description text is visible before extraction. LinkedIn collapses long descriptions by default.
     */
    private fun expandLinkedInJobDescription(page: Page) {
        val expandSelectors = listOf(
            ".show-more-less-html__button--more",       // primary "Show more" in JD body
            "button[aria-label*='Show more']",          // aria-label variant
            ".jobs-description__footer-button"          // footer expand button (some views)
        )
        for (selector in expandSelectors) {
            try {
                val btn = page.locator(selector).first()
                btn.waitFor(
                    com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(2000.0)
                )
                btn.click()
                page.waitForTimeout(500.0)
                log("[scrape_jd] Expanded LinkedIn section: $selector")
            } catch (_: Exception) {
                // Button not present — continue
            }
        }
    }

    /**
     * Fetches a JS-rendered page when the plain HTTP fetch is blocked (Cloudflare challenge, 403)
     * or returns thin SPA content, via the warm CDP Chrome — a real, logged-in browser clears bot
     * checks far better than a cold headless launch. All browser scraping goes through the host
     * CDP Chrome; when it is unreachable the scrape fails cleanly (alerting once per batch), and a
     * CDP fetch error propagates rather than retrying with an in-process launch.
     */
    private fun fetchPageWithPlaywright(url: String): PageContent {
        if (!cdpBrowser.isAvailable()) {
            alertCdpUnavailable()
            throw CdpUnavailableException(cdpUnavailableMessage())
        }
        log("[scrape_jd] Using CDP Chrome for $url")
        // withPageForDomain (not pageForDomain): a Steel session that dies mid-scrape surfaces during
        // the navigate/extract below, not during tab acquisition, so recovery has to wrap both.
        return cdpBrowser.withPageForDomain(extractHost(url)) { page -> extractDynamicPage(page, url) }
    }

    /**
     * Navigate [page] to [url], wait for load + SPA hydration, and extract content. Shared by the
     * CDP and clean-launch fallback paths. Does not close [page]; the caller owns its lifecycle.
     */
    private fun extractDynamicPage(page: Page, url: String): PageContent {
        page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000.0))
        runCatching {
            page.waitForLoadState(LoadState.LOAD, Page.WaitForLoadStateOptions().setTimeout(15000.0))
        }
        // Wait for SPA content hydration (WttJ, Jobright, other React/Next.js sites)
        runCatching {
            page.waitForSelector(
                "[data-testid='job-description'], article, .job-description, main",
                Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(8000.0)
            )
        }

        // Generalized auth/challenge detection for ANY CDP-scraped site (not just LinkedIn) — a
        // login redirect or account challenge on Glassdoor/Jobright/etc. now prompts phone re-auth.
        requireAuthenticated(page, extractHost(url))

        val rawHtml = page.content()
        val captchaReason = detectCaptchaInHtml(rawHtml)
        if (captchaReason != null) {
            val host = extractHost(url)
            // On a gated site (LinkedIn / forced-CDP), a bot-wall is solvable via the phone viewer,
            // so treat it as re-auth. On a generic fallback site it's just a block.
            if (isLinkedInHost(host) || isForceCdpHost(host)) {
                val site = siteLabel(host)
                throw AuthRequiredException(site, "$site blocked — $captchaReason at ${page.url()}")
            }
            log("[scrape_jd] Playwright fetch still blocked: $captchaReason")
            return PageContent(rawHtml, "", "Playwright: $captchaReason", isCaptchaBlock = true)
        }

        // Prefer innerText for SPA-rendered content (cleaner than Jsoup on a hydrated DOM), but
        // still run buildPageContent so embedded __NEXT_DATA__ / schema.org JSON-LD is extracted
        // like the HTTP path does — otherwise force-CDP sites (e.g. Jobright) lose their structured
        // extraction. buildPageContent keeps the visible text as the primary body when supplied.
        val visibleText = runCatching { page.innerText("body") }.getOrElse { "" }
        log("[scrape_jd] Playwright fetch succeeded for $url")
        return buildPageContent(rawHtml, visibleText.takeIf { it.length > 200 })
    }

    /** A CDP-scraped, authenticated site hit a login/challenge wall — fires a phone re-auth alert. */
    private class AuthRequiredException(val site: String, message: String) : RuntimeException(message)

    /** The browser backend is unconfigured/unreachable — no browser scraping is possible. */
    private class CdpUnavailableException(message: String) : RuntimeException(message)
}
