package com.jd.pipeline.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
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
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern

/**
 * Node: scrape_jd
 *
 * Stage 2: Fetch job URL and extract additional JD information.
 * Uses LlmClient (temperature=0 — structured extraction, deterministic).
 * Jackson replaces all regex JSON parsing.
 */
class ScrapeJdNode(
    private val llm: LlmCaller = LlmClient.fromModelString(Config.SCRAPE_MODEL, jsonMode = true, temperature = 0.0, nodeKey = "scrape_jd")
) : Node<JDState> {

    /** Set to false to suppress verbose progress logging (e.g. during tuner runs). */
    var verbose: Boolean = true

    private fun log(msg: String) { if (verbose) println(msg) }

    // ── Batch-level state ────────────────────────────────────────────────────
    // Tracks domains that blocked this batch so we don't hammer them repeatedly.
    // The same ScrapeJdNode instance is reused across all jobs in a single pipeline
    // invocation — one attempt per domain, then skip with a clear reason.
    val batchBlockedDomains: MutableSet<String> = mutableSetOf()
    var batchLinkedInSessionExpired: Boolean = false

    fun resetBatch() {
        batchBlockedDomains.clear()
        batchLinkedInSessionExpired = false
    }

    private val mapper = ObjectMapper()

    // Page-fetching HttpClient — separate from LlmClient; needs followRedirects.
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    data class PageContent(val rawHtml: String, val cleanedText: String, val blockReason: String = "", val isCaptchaBlock: Boolean = false)

    companion object {
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

        val host = extractHost(jobUrl)

        // ── Batch-skip checks (one attempt per domain per batch) ─────────────
        // Pre-check: if Chrome profile doesn't exist, skip LinkedIn entirely (no auth possible)
        if (isLinkedInHost(host)) {
            val chromeProfilePath = Paths.get(Config.CHROME_USER_DATA_DIR).resolve(Config.CHROME_PROFILE_DIRECTORY)
            if (!Files.isDirectory(chromeProfilePath)) {
                log("[scrape_jd] No Chrome profile for LinkedIn — skipping $host")
                batchLinkedInSessionExpired = true
                return input.copy(
                    isChromeSessionExpired = true,
                    error = "scrape_jd: LinkedIn auth unavailable — Chrome profile not found at $chromeProfilePath"
                )
            }
        }
        if (isLinkedInHost(host) && batchLinkedInSessionExpired) {
            log("[scrape_jd] Skipping $host — LinkedIn session expired this batch")
            return input.copy(
                isChromeSessionExpired = true,
                error = "scrape_jd: LinkedIn session expired — skipped (re-authenticate Chrome profile)"
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
                return input.copy(error = "scrape_jd: ${page.blockReason}")
            }

            if (page.cleanedText.isEmpty()) {
                log("[scrape_jd] Empty page content for $jobUrl")
                return input.copy(error = "scrape_jd: empty page content")
            }

            parseJobPage(input, jobUrl, page.cleanedText)
                .copy(rawPageContent = page.rawHtml)
        } catch (e: Exception) {
            log("[scrape_jd] Error fetching $jobUrl: ${e.message}")
            when (e) {
                is LinkedInAuthenticationException -> {
                    batchLinkedInSessionExpired = true
                    input.copy(
                        isChromeSessionExpired = true,
                        error = "scrape_jd: ${e.message}"
                    )
                }
                is CaptchaBlockedException -> {
                    batchBlockedDomains.add(host)
                    input.copy(error = "scrape_jd: captcha — ${e.message}")
                }
                else -> input.copy(error = "scrape_jd: ${e.message}")
            }
        }
    }

    private fun fetchPage(url: String): PageContent {
        val host = extractHost(url)
        if (isLinkedInHost(host)) {
            if (batchLinkedInSessionExpired) {
                log("[scrape_jd] LinkedIn session expired — skipping $host")
                return PageContent(
                    rawHtml = "",
                    cleanedText = "",
                    blockReason = "LinkedIn auth expired"
                )
            }
            return fetchLinkedInPageWithPlaywright(url)
        }

        val httpResult = fetchPageOverHttp(url)
        if (httpResult.isCaptchaBlock && Config.PLAYWRIGHT_FALLBACK_ON_CAPTCHA) {
            log("[scrape_jd] HTTP CAPTCHA-blocked ($host: ${httpResult.blockReason}) — retrying with Playwright")
            return fetchPageWithPlaywright(url)
        }
        return httpResult
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
                    return PageContent("", "", "HTTP 403 — bot-blocked or auth required")
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

    private fun fetchLinkedInPageWithPlaywright(url: String): PageContent {
        val sourceUserDataDir = Paths.get(Config.CHROME_USER_DATA_DIR)
        val profileDirectory = Config.CHROME_PROFILE_DIRECTORY

        if (!Files.isDirectory(sourceUserDataDir)) {
            throw RuntimeException("Chrome user data dir not found: $sourceUserDataDir")
        }

        val profilePath = sourceUserDataDir.resolve(profileDirectory)
        if (!Files.isDirectory(profilePath)) {
            throw RuntimeException("Chrome profile directory not found: $profilePath")
        }

        log("[scrape_jd] Using Playwright for LinkedIn with profile: $profilePath")

        val isolatedUserDataDir = createIsolatedChromeUserDataDir(sourceUserDataDir, profileDirectory)
        var playwright: Playwright? = null
        var launchedContext: com.microsoft.playwright.BrowserContext? = null
        try {
            playwright = Playwright.create()
            val launchOptions = BrowserType.LaunchPersistentContextOptions()
                .setExecutablePath(Paths.get(Config.CHROME_EXECUTABLE_PATH))
                .setHeadless(Config.PLAYWRIGHT_HEADLESS)
                .setArgs(listOf("--profile-directory=$profileDirectory"))

            launchedContext = playwright.chromium().launchPersistentContext(isolatedUserDataDir, launchOptions)
            launchedContext.setDefaultTimeout(Config.PLAYWRIGHT_TIMEOUT_MS)
            launchedContext.setDefaultNavigationTimeout(Config.PLAYWRIGHT_TIMEOUT_MS)

            val page = launchedContext.pages().firstOrNull() ?: launchedContext.newPage()
            page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(25000.0))
            waitForLinkedInPage(page)
            log("[scrape_jd] LinkedIn final URL: ${page.url()}")
            log("[scrape_jd] LinkedIn title: ${runCatching { page.title() }.getOrDefault("")}")

            if (isLinkedInLoginPage(page)) {
                throw LinkedInAuthenticationException(
                    "LinkedIn session is not authenticated for Chrome profile $profileDirectory"
                )
            }

            if (isLinkedInCaptchaPage(page)) {
                throw CaptchaBlockedException("LinkedIn CAPTCHA/security challenge at ${page.url()}")
            }

            dismissLinkedInPopups(page)
            expandLinkedInJobDescription(page)

            val rawHtml = page.content()
            val captchaReason = detectCaptchaInHtml(rawHtml)
            if (captchaReason != null) {
                throw CaptchaBlockedException("LinkedIn page blocked — $captchaReason")
            }

            val visibleText = extractLinkedInVisibleText(page)
            log("[scrape_jd] LinkedIn text preview: ${visibleText.take(300)}")
            return buildPageContent(rawHtml, visibleText)
        } finally {
            runCatching { launchedContext?.close() }
            runCatching { playwright?.close() }
            deleteDirectoryRecursively(isolatedUserDataDir)
        }
    }

    private fun createIsolatedChromeUserDataDir(sourceUserDataDir: Path, profileDirectory: String): Path {
        val tempUserDataDir = Files.createTempDirectory("jd-linkedin-chrome-")
        val sourceProfileDir = sourceUserDataDir.resolve(profileDirectory)
        val targetProfileDir = tempUserDataDir.resolve(profileDirectory)

        val localState = sourceUserDataDir.resolve("Local State")
        if (Files.exists(localState)) {
            Files.copy(localState, tempUserDataDir.resolve("Local State"), StandardCopyOption.REPLACE_EXISTING)
        }

        copyDirectoryRecursively(sourceProfileDir, targetProfileDir)
        return tempUserDataDir
    }

    private fun copyDirectoryRecursively(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.createDirectories(target.resolve(source.relativize(dir)))
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.copy(
                    file,
                    target.resolve(source.relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteDirectoryRecursively(path: Path) {
        if (!Files.exists(path)) return

        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                Files.deleteIfExists(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
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

    private fun isLinkedInLoginPage(page: Page): Boolean {
        val currentUrl = page.url().lowercase()
        if (currentUrl.contains("/login") || currentUrl.contains("/checkpoint")) {
            return true
        }

        val bodyText = runCatching { page.locator("body").innerText().lowercase() }.getOrDefault("")
        return bodyText.contains("sign in") && bodyText.contains("linkedin")
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
        document.select("script,style,noscript").remove()

        var text = preferredVisibleText?.takeIf { it.isNotBlank() } ?: document.text()
        text = text.replace(Regex("\\s+"), " ").trim()

        if (nextDataJson.isNotEmpty()) {
            text = "PAGE_JSON_DATA:\n$nextDataJson\n\n$text"
        }

        return PageContent(rawHtml, text)
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
                state = applyJobrightStructuredData(state, nextDataJson)
                log("[scrape_jd] Jobright structured extraction applied")
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

    private fun isJobrightUrl(url: String): Boolean {
        val host = extractHost(url)
        return host == "jobright.ai" || host.endsWith(".jobright.ai")
    }

    /**
     * Parses Jobright's __NEXT_DATA__ JSON to extract fields not reliably present in visible text.
     * Jobright is a Next.js app; all structured job data lives in the server-rendered JSON blob.
     * Only populates fields that are blank in the current state (LLM runs after this and can override).
     */
    private fun applyJobrightStructuredData(state: JDState, nextDataJson: String): JDState {
        return try {
            val root = mapper.readTree(nextDataJson)
            val pageProps = root.path("props").path("pageProps")

            // Jobright stores job data under pageProps.job or pageProps.jobData
            val job = sequenceOf("job", "jobData", "jobDetails", "data")
                .map { pageProps.path(it) }
                .firstOrNull { !it.isMissingNode && !it.isNull }
                ?: return state

            var s = state

            // Employment type
            if (s.employmentType.isBlank()) {
                val raw = sequenceOf("jobType", "employment_type", "employmentType", "type")
                    .map { job.path(it).asText("") }.firstOrNull { it.isNotBlank() }
                if (raw != null) {
                    s = s.copy(employmentType = normalizeEmploymentType(raw))
                }
            }

            // Seniority level
            if (s.seniorityLevel.isBlank()) {
                val raw = sequenceOf("seniorityLevel", "seniority_level", "level", "experienceLevel", "seniority")
                    .map { job.path(it).asText("") }.firstOrNull { it.isNotBlank() }
                if (raw != null) s = s.copy(seniorityLevel = raw)
            }

            // Salary
            if (s.salaryRange.isBlank()) {
                val min = job.path("salaryMin").asLong(0).takeIf { it > 0 }
                    ?: job.path("salary_min").asLong(0).takeIf { it > 0 }
                val max = job.path("salaryMax").asLong(0).takeIf { it > 0 }
                    ?: job.path("salary_max").asLong(0).takeIf { it > 0 }
                val salaryStr = job.path("salaryString").asText("")
                    .ifBlank { job.path("salary_string").asText("") }
                s = s.copy(salaryRange = when {
                    salaryStr.isNotBlank() -> salaryStr
                    min != null && max != null -> "\$${min / 1000}K – \$${max / 1000}K"
                    min != null -> "\$${min / 1000}K+"
                    else -> s.salaryRange
                })
            }

            // Benefits
            if (s.benefits.isEmpty()) {
                val benefitsNode = job.path("benefits").takeIf { it.isArray }
                    ?: job.path("perks").takeIf { it.isArray }
                if (benefitsNode != null) {
                    val list = benefitsNode.mapNotNull { node ->
                        node.asText("").takeIf { it.isNotBlank() }
                            ?: node.path("name").asText("").takeIf { it.isNotBlank() }
                    }
                    if (list.isNotEmpty()) s = s.copy(benefits = list)
                }
            }

            // Company description
            if (s.companyDescription.isBlank()) {
                val companyNode = job.path("company").takeIf { !it.isMissingNode && !it.isNull }
                val desc = sequenceOf("companyDescription", "company_description", "description", "about", "overview")
                    .map { companyNode?.path(it)?.asText("") ?: job.path(it).asText("") }
                    .firstOrNull { it.isNotBlank() }
                if (desc != null) s = s.copy(companyDescription = desc)
            }

            // Tech stack / skills
            if (s.techStack.isEmpty()) {
                val skillsNode = sequenceOf("skills", "requiredSkills", "required_skills", "techStack", "tech_stack")
                    .map { job.path(it) }.firstOrNull { it.isArray }
                if (skillsNode != null) {
                    val skills = skillsNode.mapNotNull { node ->
                        node.asText("").takeIf { it.isNotBlank() }
                            ?: node.path("name").asText("").takeIf { it.isNotBlank() }
                    }
                    if (skills.isNotEmpty()) s = s.copy(techStack = skills)
                }
            }

            // Full job description text — assemble from all available sections.
            // The SSR HTML often only surfaces one section; the JSON has everything.
            if (s.jdText.isBlank()) {
                val sections = mutableListOf<String>()

                // About / company overview
                val aboutText = sequenceOf("about", "aboutCompany", "about_company", "companyDescription", "company_description")
                    .map { job.path(it).asText("") }.firstOrNull { it.isNotBlank() }
                    ?: s.companyDescription.takeIf { it.isNotBlank() }
                if (aboutText != null) sections.add("About the Company\n$aboutText")

                // Main job description
                val descText = sequenceOf("description", "jobDescription", "job_description", "jobSummary", "summary")
                    .map { job.path(it).asText("") }.firstOrNull { it.isNotBlank() }
                if (descText != null) sections.add(descText)

                // Qualifications / requirements
                val qualText = sequenceOf("qualifications", "requirements", "jobQualifications", "job_qualifications")
                    .map { job.path(it).asText("") }.firstOrNull { it.isNotBlank() }
                if (qualText != null) sections.add("Qualifications\n$qualText")

                // Responsibilities
                val respText = sequenceOf("responsibilities", "jobResponsibilities", "job_responsibilities", "duties")
                    .map { job.path(it).asText("") }.firstOrNull { it.isNotBlank() }
                if (respText != null) sections.add("Responsibilities\n$respText")

                if (sections.isNotEmpty()) s = s.copy(jdText = sections.joinToString("\n\n"))
            }

            s
        } catch (e: Exception) {
            log("[scrape_jd] Jobright __NEXT_DATA__ parse failed: ${e.message}")
            state
        }
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

    private fun isLinkedInCaptchaPage(page: Page): Boolean {
        val currentUrl = page.url().lowercase()
        if (currentUrl.contains("/checkpoint/challenge") ||
            currentUrl.contains("/challengesv2") ||
            currentUrl.contains("/security-verification") ||
            currentUrl.contains("captcha")) {
            return true
        }
        val bodyText = runCatching { page.locator("body").innerText().lowercase() }.getOrDefault("")
        return (bodyText.contains("security verification") && bodyText.contains("linkedin")) ||
            bodyText.contains("verify you're a human") ||
            bodyText.contains("please verify you are a human") ||
            bodyText.contains("complete the security check")
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
     * Fetches a page using a clean Playwright browser session (no Chrome profile, no cookies).
     * Used as a fallback when the plain HTTP fetch is blocked by a Cloudflare JS challenge or
     * other bot-detection that a real browser can solve by executing JavaScript.
     */
    private fun fetchPageWithPlaywright(url: String): PageContent {
        var playwright: Playwright? = null
        try {
            playwright = Playwright.create()
            val browser = playwright.chromium().launch(
                BrowserType.LaunchOptions()
                    .setExecutablePath(Paths.get(Config.CHROME_EXECUTABLE_PATH))
                    .setHeadless(Config.PLAYWRIGHT_HEADLESS)
                    .setArgs(listOf(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--disable-setuid-sandbox"
                    ))
            )
            val context = browser.newContext(
                Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            )
            // Hide the webdriver flag so bot-detection JS sees a regular browser
            context.addInitScript("Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")

            val page = context.newPage()
            page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000.0))
            runCatching {
                page.waitForLoadState(LoadState.LOAD, Page.WaitForLoadStateOptions().setTimeout(15000.0))
            }

            val rawHtml = page.content()
            val captchaReason = detectCaptchaInHtml(rawHtml)
            if (captchaReason != null) {
                log("[scrape_jd] Playwright fetch still blocked: $captchaReason")
                return PageContent(rawHtml, "", "Playwright: $captchaReason")
            }

            log("[scrape_jd] Playwright fetch succeeded for $url")
            return buildPageContent(rawHtml)
        } catch (e: Exception) {
            log("[scrape_jd] Playwright fallback failed for $url: ${e.message}")
            return PageContent("", "", "Playwright error: ${e.message}")
        } finally {
            runCatching { playwright?.close() }
        }
    }

    private class LinkedInAuthenticationException(message: String) : RuntimeException(message)
    private class CaptchaBlockedException(message: String) : RuntimeException(message)
}
