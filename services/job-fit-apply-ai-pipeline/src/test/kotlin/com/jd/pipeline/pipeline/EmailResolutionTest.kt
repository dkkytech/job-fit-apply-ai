package com.jd.pipeline.pipeline

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The post-ingestion routing decision for a raw email: digest fans out, non-job is skipped,
 * a single posting is processed. Pure — no bridge / Gmail. Mirrors [TerminalLabelTest] in style.
 */
class EmailResolutionTest {

    private fun email(isDigest: Boolean = false, isInlineDigest: Boolean = false) =
        IntakeContext.Email(
            emailId = "e1", subject = "s", from = "f@x.com", rawBody = "b", htmlBody = "",
            isRecruiter = false, isDigest = isDigest, isInlineDigest = isInlineDigest,
        )

    @Test
    fun `single job posting is processed`() {
        val s = JDState(isJobPosting = true, intake = email())
        assertEquals(EmailDisposition.Process, EmailResolution.classify(s))
    }

    @Test
    fun `non-job email is skipped`() {
        val s = JDState(isJobPosting = false, intake = email())
        assertEquals(EmailDisposition.SkipNotJob, EmailResolution.classify(s))
    }

    @Test
    fun `an application update is skipped with its own disposition, not as a non-job`() {
        val s = JDState(isJobPosting = false, isApplicationUpdate = true, intake = email())
        assertEquals(EmailDisposition.SkipApplicationUpdate, EmailResolution.classify(s))
    }

    @Test
    fun `a failed scan is an Error, not a clean non-job skip`() {
        // ScanEmailNode catches a transient LLM error (e.g. oMLX 507) and returns
        // isJobPosting=false WITH error set. That must NOT be labeled JD_Not_Found.
        val s = JDState(isJobPosting = false, intake = email(), error = "scan_email: LLM HTTP 507")
        val disposition = EmailResolution.classify(s)
        assertTrue(disposition is EmailDisposition.Error, "errored scan must classify as Error, got $disposition")
        assertEquals("scan_email: LLM HTTP 507", (disposition as EmailDisposition.Error).message)
    }

    @Test
    fun `terminal scrape failure is distinct from a transient ingestion error`() {
        val state = JDState(
            isJobPosting = true,
            intake = email(),
            jobUrl = "https://jobs.example.com/123",
            error = "scrape_jd: HTTP 403 blocked",
            scrapePath = "blocked",
        )

        assertEquals(
            EmailDisposition.ScrapeTerminal(ScrapeTerminalReason.BLOCKED, state.error),
            EmailResolution.classify(state),
        )
    }

    @Test
    fun `a blocked-path rate limit remains a retryable ingestion error`() {
        val state = JDState(
            isJobPosting = true,
            intake = email(),
            error = "scrape_jd: HTTP 429 — rate-limited",
            scrapePath = "blocked",
        )

        assertEquals(EmailDisposition.Error(state.error), EmailResolution.classify(state))
    }

    @Test
    fun `terminal scrape outcome takes precedence over digest fan-out`() {
        val state = JDState(
            isJobPosting = false,
            intake = email(isDigest = true),
            error = "scrape_jd: CAPTCHA widget detected",
            scrapePath = "cdp_fallback",
            digestJobs = listOf(JDState(isJobPosting = true, company = "must not fan out")),
        )

        assertEquals(
            EmailDisposition.ScrapeTerminal(ScrapeTerminalReason.BLOCKED, state.error),
            EmailResolution.classify(state),
        )
    }

    @Test
    fun `error takes precedence over a digest classification`() {
        // A digest whose scan errored should surface the error, not fan out stale children.
        val s = JDState(
            isJobPosting = false, intake = email(isDigest = true),
            digestJobs = listOf(JDState(isJobPosting = true, company = "A")),
            error = "scan_email: timeout",
        )
        assertTrue(EmailResolution.classify(s) is EmailDisposition.Error)
    }

    @Test
    fun `digest re-enqueues only its job-posting children`() {
        val children = listOf(
            JDState(isJobPosting = true, company = "A"),
            JDState(isJobPosting = false, company = "B"),   // filtered out
            JDState(isJobPosting = true, company = "C"),
        )
        val s = JDState(isJobPosting = false, intake = email(isDigest = true), digestJobs = children)

        val disposition = EmailResolution.classify(s)
        assertTrue(disposition is EmailDisposition.ReEnqueueChildren)
        val names = (disposition as EmailDisposition.ReEnqueueChildren).children.map { it.company }
        assertEquals(listOf("A", "C"), names)
    }

    @Test
    fun `inline digest is treated as a digest fan-out`() {
        val s = JDState(
            isJobPosting = true,   // inline digests can look like a posting; digest check wins
            intake = email(isInlineDigest = true),
            digestJobs = listOf(JDState(isJobPosting = true, company = "A")),
        )
        assertTrue(EmailResolution.classify(s) is EmailDisposition.ReEnqueueChildren)
    }

    @Test
    fun `digest with no job-posting children yields an empty re-enqueue`() {
        val s = JDState(isJobPosting = false, intake = email(isDigest = true), digestJobs = emptyList())
        val disposition = EmailResolution.classify(s)
        assertTrue(disposition is EmailDisposition.ReEnqueueChildren)
        assertTrue((disposition as EmailDisposition.ReEnqueueChildren).children.isEmpty())
    }
}
