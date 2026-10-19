package com.jd.pipeline.pipeline

import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("ScrapeOutcome")
class ScrapeOutcomeTest {

    @Test
    @DisplayName("classifies malformed job URLs as a terminal scrape failure")
    fun malformedUrlIsTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(jobUrl = "https://jobs.example.com/a bad-url"))

        assertEquals(ScrapeTerminalReason.MALFORMED_URL, outcome)
    }

    @Test
    @DisplayName("classifies a blocked page after fetch fallback as a terminal scrape failure")
    fun blockedPageIsTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(error = "scrape_jd: HTTP 403 blocked", scrapePath = "blocked"))

        assertEquals(ScrapeTerminalReason.BLOCKED, outcome)
    }

    @Test
    @DisplayName("classifies navigation timeouts as a terminal scrape failure")
    fun navigationTimeoutIsTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(error = "scrape_jd: page.goto: Timeout 30000ms exceeded"))

        assertEquals(ScrapeTerminalReason.TIMEOUT, outcome)
    }

    @Test
    @DisplayName("classifies extracted JD text below the bridge minimum as a terminal scrape failure")
    fun insufficientJdTextIsTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(jobUrl = "https://jobs.example.com/123", jdText = "x".repeat(149), isJobPosting = true))

        assertEquals(ScrapeTerminalReason.INSUFFICIENT_JD_TEXT, outcome)
    }

    @Test
    @DisplayName("recognizes durable CAPTCHA and Cloudflare access blocks regardless of scrape path")
    fun durableAccessBlocksAreTerminal() {
        listOf(
            "scrape_jd: CAPTCHA widget detected" to "cdp_fallback",
            "scrape_jd: Cloudflare browser challenge" to "blocked",
            "scrape_jd: forbidden" to "http",
        ).forEach { (error, path) ->
            assertEquals(
                ScrapeTerminalReason.BLOCKED,
                ScrapeOutcome.classify(JDState(error = error, scrapePath = path)),
                "expected durable access block for $error",
            )
        }
    }

    @Test
    @DisplayName("requires a scrape error prefix before terminalizing generic access text")
    fun nonScrapeAccessTextIsNotTerminal() {
        assertNull(ScrapeOutcome.classify(JDState(error = "HTTP 403 forbidden", scrapePath = "http")))
    }

    @Test
    @DisplayName("leaves transient service faults as JD_Error candidates")
    fun transientServiceFaultIsNotScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(error = "scrape_jd: LLM service returned 503"))

        assertNull(outcome)
    }

    @Test
    @DisplayName("does not make an HTTP 429 rate limit terminal when the scrape path is blocked")
    fun http429IsNotTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(
            JDState(error = "scrape_jd: HTTP 429 — rate-limited", scrapePath = "blocked"),
        )

        assertNull(outcome)
    }

    @Test
    @DisplayName("does not make an HTTP 500 service error terminal when the scrape path is blocked")
    fun http500IsNotTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(
            JDState(error = "scrape_jd: HTTP 500 — internal server error", scrapePath = "blocked"),
        )

        assertNull(outcome)
    }

    @Test
    @DisplayName("does not make an HTTP 502 gateway error terminal when the scrape path is blocked")
    fun http502IsNotTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(
            JDState(error = "scrape_jd: HTTP 502 — bad gateway", scrapePath = "blocked"),
        )

        assertNull(outcome)
    }

    @Test
    @DisplayName("maps a terminal scrape failure to the dedicated Gmail label")
    fun terminalScrapeFailureUsesDedicatedGmailLabel() {
        assertEquals(TerminalLabel.JD_SCRAPE_FAILED, ScrapeTerminalReason.BLOCKED.terminalLabel)
    }

    @Test
    @DisplayName("a one-job digest's short summary is not a scrape verdict")
    fun digestSummaryIsNotInsufficientText() {
        val parent = JDState(
            intake = com.jd.pipeline.source.IntakeContext.Email("m", "alerts@noreply9.jobs2web.com", "New jobs", "", "", false, true, false),
            isJobPosting = true, jobUrl = "https://careers.example.com/job/SDET/12345", jdText = "1. SDET @ Acme | https://careers.example.com/job/SDET/12345",
        )
        assertEquals(null, ScrapeOutcome.classify(parent))
    }
}

