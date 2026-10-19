package com.jd.pipeline.pipeline

import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest
import java.net.URI

/**
 * Terminal outcomes from an attempted job-page scrape.
 *
 * These are deliberately narrower than arbitrary ingestion errors.  A transient LLM or browser
 * service outage remains a [EmailDisposition.Error] (and therefore `JD_Error`), while an outcome
 * here means retrying the same job URL is not expected to produce a usable JD.
 */
enum class ScrapeTerminalReason(val terminalLabel: String) {
    MALFORMED_URL(TerminalLabel.JD_SCRAPE_FAILED),
    BLOCKED(TerminalLabel.JD_SCRAPE_FAILED),
    TIMEOUT(TerminalLabel.JD_SCRAPE_FAILED),
    INSUFFICIENT_JD_TEXT(TerminalLabel.JD_SCRAPE_FAILED),
}

object ScrapeOutcome {
    private const val SCRAPE_PREFIX = "scrape_jd:"
    private const val BRIDGE_MIN_JD_TEXT_LENGTH = 150

    /** Returns null unless [state] has an unrecoverable job-board extraction outcome. */
    fun classify(state: JDState): ScrapeTerminalReason? {
        val error = state.error
        val scrapeError = error.startsWith(SCRAPE_PREFIX, ignoreCase = true)
        val normalizedError = error.lowercase()

        // `blocked` describes the scrape transport's final path, not the cause. It is also used
        // when a fallback sees a transient HTTP/service failure, so only an explicit durable
        // access denial can become a terminal verdict. All other scrape errors stay JD_Error.
        if (scrapeError && isDurableAccessBlock(normalizedError)) {
            return ScrapeTerminalReason.BLOCKED
        }

        // Do not turn a Steel/LLM timeout into a permanent scrape verdict. Navigation failures are
        // identified by the browser operation, not by the broad word "timeout".
        if (scrapeError && normalizedError.contains("timeout") &&
            (normalizedError.contains("page.goto") || normalizedError.contains("navigation"))) {
            return ScrapeTerminalReason.TIMEOUT
        }

        if (state.jobUrl.isNotBlank() && !isHttpUrl(state.jobUrl)) {
            return ScrapeTerminalReason.MALFORMED_URL
        }

        // A digest parent's text is the summary of its children, not a scraped page: a one-job
        // digest copies the child's URL up with a one-line summary, which is no scrape verdict.
        if (state.isJobPosting && state.jobUrl.isNotBlank() && error.isBlank() && !state.isDigest && !state.isInlineDigest &&
            state.jdText.isNotBlank() && state.jdText.length < BRIDGE_MIN_JD_TEXT_LENGTH) {
            return ScrapeTerminalReason.INSUFFICIENT_JD_TEXT
        }

        return null
    }

    /** Durable board-side access denials; never infer one from the generic scrape path.
     *  Transient HTTP failures (429, 5xx) remain retryable and should NOT be classified
     *  as terminal scrape failures — they surface as JD_Error and get retried. */
    private fun isDurableAccessBlock(error: String): Boolean =
        error.contains("http 403") ||
            error.contains("forbidden") ||
            error.contains("captcha") ||
            error.contains("bot-block") ||
            error.contains("cloudflare browser challenge")

    internal fun isHttpUrl(url: String): Boolean = runCatching {
        if (url.any(Char::isWhitespace)) return false
        val uri = URI(url)
        uri.host != null && uri.scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)
}
