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
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
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
    private val llm: LlmCaller = LlmClient.fromModelString(Config.SCRAPE_MODEL, jsonMode = true, temperature = 0.0, nodeKey = "scrape_jd"),
    // Long-lived Chrome connected over CDP (one tab per domain, warm session). The ONLY browser
    // path — when the endpoint is unreachable, browser-needing scrapes fail cleanly with an alert
    // (plain-HTTP scraping is unaffected). No in-process Chromium is ever launched.
    private val cdpBrowser: CdpBrowser = BrowserFactory.create(),
    private val alerts: AlertService = AlertService(),
) : Node<JDState> {

    /** Set to false to suppress verbose progress logging (e.g. during tuner runs). */
    var verbose: Boolean = true

    private fun log(msg: String) { if (verbose) println(msg) }

    // ── Batch-level state ────────────────────────────────────────────────────
    // Tracks domains that blocked this batch so we don't hammer them repeatedly.
    // The same ScrapeJdNode instance is reused across all jobs in a single pipeline
    // invocation — one attempt per domain, then skip with a clear reason.
    val batchBlockedDomains: MutableSet<String> = mutableSetOf()
    // Hosts that hit an auth wall (login/challenge) via a CDP scrape this batch — skipped on
    // re-attempt, and each fires one phone re-auth alert. Generalizes the old LinkedIn-only flag so
    // every authenticated CDP-scraped board (Glassdoor, Jobright, …) gets the same treatment.
    val batchAuthExpiredDomains: MutableSet<String> = mutableSetOf()
    // Fire the "debug Chrome unreachable" alert at most once per batch.
    var batchCdpUnavailableAlerted: Boolean = false

    /** Back-compat accessor for the LinkedIn-specific console warning (see CliOutput). */
    val batchLinkedInSessionExpired: Boolean
        get() = batchAuthExpiredDomains.any { it.contains("linkedin") }

    fun resetBatch() {
        batchBlockedDomains.clear()
        batchAuthExpiredDomains.clear()
        batchCdpUnavailableAlerted = false
    }

    /** True if [host] already hit an auth wall this batch (exact or parent-domain match). */
    private fun isAuthExpired(host: String): Boolean =
        host.isNotEmpty() && batchAuthExpiredDomains.any { host == it || host.endsWith(".$it") }

    /** Release the shared CDP browser connection (disconnects only — does not close the user's Chrome). */
    fun close() = cdpBrowser.close()

    private val mapper = ObjectMapper()

    // Page-fetching HttpClient — separate from LlmClient; needs followRedirects.
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    data class PageContent(val rawHtml: String, val cleanedText: String, val blockReason: String = "", val isCaptchaBlock: Boolean = false, val isBotBlock: Boolean = false, val scrapePath: String = "")

    companion object {
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
                batchBlockedDomains.add(host)
                return input.copy(error = "scrape_jd: ${page.blockReason}", scrapePath = "blocked")
            }

            if (page.cleanedText.isEmpty()) {
                log("[scrape_jd] Empty page content for $jobUrl")
                return input.copy(error = "scrape_jd: empty page content", scrapePath = "empty")
            }

            parseJobPage(input, jobUrl, page.cleanedText)
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

    private fun fetchPageOverHttp(url: String): PageContent {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(java.time.Duration.ofSeconds(20))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
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
                    return PageContent("", "", "HTTP 429 — rate-limited")
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
    private fun detectCaptchaInHtml(html: String): String? {
        if (html.isBlank()) return null
        val lower = html.lowercase()

        // Cloudflare challenge markers
        if (lower.contains("cf-browser-verification") ||
            lower.contains("cf-challenge-running") ||
            lower.contains("checking your browser") ||
            lower.contains("please wait while we check your browser")) {
            return "Cloudflare browser challenge"
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
        if (endpoint.isNotBlank() && !batchCdpUnavailableAlerted) {
            batchCdpUnavailableAlerted = true
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
                return text
            }
        }

        return ""
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
     * Extract a schema.org `JobPosting` from `application/ld+json` script blocks and
     * render it as a clean, labeled text block (title, company, location, salary,
     * employment type, full description). Returns null when no usable JobPosting with
     * a substantive description is found. Handles single objects, arrays, and `@graph`.
     */
    internal fun extractJobPostingJsonLd(html: String): String? = extractJobPostingJsonLd(Jsoup.parse(html))

    internal fun extractJobPostingJsonLd(document: org.jsoup.nodes.Document): String? {
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

            val title = posting.path("title").asText("").trim()
            val company = posting.path("hiringOrganization").path("name").asText("").trim()
            val loc = posting.path("jobLocation").let { jl ->
                val addr = (if (jl.isArray) jl.firstOrNull() else jl)?.path("address")
                listOfNotNull(
                    addr?.path("addressLocality")?.asText("")?.takeIf { it.isNotBlank() },
                    addr?.path("addressRegion")?.asText("")?.takeIf { it.isNotBlank() },
                ).joinToString(", ")
            }
            val salary = posting.path("baseSalary").path("value").let { v ->
                val min = v.path("minValue").asText(""); val max = v.path("maxValue").asText("")
                val unit = v.path("unitText").asText("")
                when {
                    min.isNotBlank() && max.isNotBlank() -> "$min - $max ${unit}".trim()
                    min.isNotBlank() -> "$min ${unit}".trim()
                    else -> ""
                }
            }
            val employmentType = posting.path("employmentType").let {
                if (it.isArray) it.joinToString(", ") { e -> e.asText() } else it.asText("")
            }.trim()

            return buildString {
                appendLine("STRUCTURED_JOB_DATA (authoritative — prefer over visible text):")
                if (title.isNotBlank()) appendLine("Title: $title")
                if (company.isNotBlank()) appendLine("Company: $company")
                if (loc.isNotBlank()) appendLine("Location: $loc")
                if (salary.isNotBlank()) appendLine("Salary: $salary")
                if (employmentType.isNotBlank()) appendLine("Employment type: $employmentType")
                appendLine("Description: $descText")
            }.trim()
        }
        return null
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

    private fun parseJobPage(input: JDState, url: String, content: String): JDState {
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

        var state = input.copy(scrapedContent = content, jdText = "")

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

        try {
            val llmResponse = llm.call(prompt)
            // LLM overrides only blank Jobright fields — structured extraction takes precedence for jd_text
            val llmState = applyLlmFields(state, llmResponse)
            state = if (isJobrightUrl(url) && state.jdText.isNotBlank()) {
                // Keep Jobright-extracted jd_text; take other LLM fields where Jobright had nothing
                llmState.copy(jdText = state.jdText)
            } else {
                llmState
            }
        } catch (e: Exception) {
            log("[scrape_jd] LLM extraction failed: ${e.message}")
            // Regex-based salary/remote fallback when LLM is unavailable
            val salary = extractSalary(content)
            val remotePolicy = extractRemotePolicy(content)
            state = state.copy(
                salaryRange = if (salary.isNotEmpty()) salary else state.salaryRange,
                remotePolicy = remotePolicy
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
     * Parse the LLM JSON response with Jackson and return an updated JDState.
     * Fields absent or null in the response fall back to the current state values.
     */
    private fun applyLlmFields(state: JDState, responseText: String): JDState {
        val cleaned = responseText.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

        return try {
            val node = mapper.readTree(cleaned)

            val roleTitle = node.path("role_title").asText("").takeIf { it.isNotBlank() && it != "null" }
            val company = node.path("company").asText("").takeIf { it.isNotBlank() && it != "null" }
            val location = node.path("location").asText("").takeIf { it.isNotBlank() && it != "null" }
            val remotePolicy = node.path("remote_policy").asText("").takeIf { it.isNotBlank() && it != "null" }
            val salaryRange = node.path("salary_range").asText("").takeIf { it.isNotBlank() && it != "null" }
            val employmentType = node.path("employment_type").asText("").takeIf { it.isNotBlank() && it != "null" }
            val seniorityLevel = node.path("seniority_level").asText("").takeIf { it.isNotBlank() && it != "null" }
            val yoeRequired = node.path("yoe_required").asInt(0).takeIf { it > 0 }
            val techStack = node.path("tech_stack").map { it.asText() }.filter { it.isNotBlank() }
            val benefits = node.path("benefits").map { it.asText() }.filter { it.isNotBlank() }
            val companyDescription = node.path("company_description").asText("").takeIf { it.isNotBlank() && it != "null" }
            val jdText = node.path("jd_text").asText("").takeIf { it.isNotBlank() && it != "null" }

            state.copy(
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
        } catch (e: Exception) {
            log("[scrape_jd] LLM response parse failed: ${e.message}")
            state
        }
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
        "CONTRACT" -> "Contract"
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
            return PageContent(rawHtml, "", "Playwright: $captchaReason")
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
