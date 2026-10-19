package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.Node
import com.jd.pipeline.nodes.ScrapeJdNode
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.times
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("IngestionPipelineTest")
class IngestionPipelineTest {

    // ── reflection helpers ────────────────────────────────────────────────────

    private fun injectScanNode(pipeline: IngestionPipeline, node: Node<JDState>) {
        val f = IngestionPipeline::class.java.getDeclaredField("scanNode")
        f.isAccessible = true
        f.set(pipeline, node)
    }

    private fun injectSaveNode(pipeline: IngestionPipeline, node: Node<JDState>) {
        val f = IngestionPipeline::class.java.getDeclaredField("saveNode")
        f.isAccessible = true
        f.set(pipeline, node)
    }

    // ── toJdRecord ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toJdRecord maps state fields correctly")
    fun toJdRecordMapsFields() {
        val pipeline = IngestionPipeline()
        val state = JDState(
            jdText    = "full jd text",
            company   = "Acme Corp",
            roleTitle = "Staff SDET",
            location  = "Seattle, WA",
            jobUrl    = "https://example.com/job/1",
        )

        val record = pipeline.toJdRecord(state, idempotencyKey = "email-abc")

        assertEquals("full jd text",              record.jdText)
        assertEquals("Acme Corp",                 record.company)
        assertEquals("Staff SDET",                record.roleTitle)
        assertEquals("Seattle, WA",               record.location)
        assertEquals("https://example.com/job/1", record.jobUrl)
        assertEquals("email-abc",                 record.idempotencyKey)
        assertEquals(IngestionSource.EMAIL,        record.source)
    }

    @Test
    @DisplayName("toJdRecord carries scrape-extracted fields across the queue boundary")
    fun toJdRecordCarriesScrapeFields() {
        val pipeline = IngestionPipeline()
        val state = JDState(
            jdText         = "jd with salary",
            salaryRange    = "CA\$110K/yr - CA\$140K/yr",
            remotePolicy   = "remote",
            employmentType = "Full-time",
            seniorityLevel = "Senior",
            yoeRequired    = 5,
            techStack      = listOf("Kotlin", "Selenium"),
        )

        val record = pipeline.toJdRecord(state)

        assertEquals("CA\$110K/yr - CA\$140K/yr", record.salaryRange)
        assertEquals("remote",                    record.remotePolicy)
        assertEquals("Full-time",                 record.employmentType)
        assertEquals("Senior",                    record.seniorityLevel)
        assertEquals(5,                           record.yoeRequired)
        assertEquals(listOf("Kotlin", "Selenium"), record.techStack)
    }

    @Test
    @DisplayName("toJdRecord carries scrapePath, so browser-scraping health reaches the run_log")
    fun toJdRecordCarriesScrapePath() {
        // Regression: scraping happens in ingestion, but the run_log is written from the *processing*
        // result — and this mapping dropped scrapePath, so ProcessingPipeline re-read the "" default.
        // Every one of 11,999 logged jobs recorded an empty scrapePath, making the browser backend
        // invisible to the analyzer exactly when we needed to judge whether Steel was costing scrapes.
        val pipeline = IngestionPipeline()

        val record = pipeline.toJdRecord(JDState(jdText = "jd", scrapePath = "cdp_forced"))

        assertEquals("cdp_forced", record.scrapePath)
    }

    @Test
    @DisplayName("toJdRecord maps blank/empty scrape fields to null")
    fun toJdRecordBlankScrapeFieldsToNull() {
        val pipeline = IngestionPipeline()
        val state = JDState(jdText = "jd", salaryRange = "", employmentType = "", techStack = emptyList())

        val record = pipeline.toJdRecord(state)

        assertNull(record.salaryRange)
        assertNull(record.employmentType)
        assertNull(record.techStack)
    }

    @Test
    @DisplayName("toJdRecord maps blank string fields to null")
    fun toJdRecordBlanksToNull() {
        val pipeline = IngestionPipeline()
        val state = JDState(
            jdText    = "some jd",
            company   = "",
            roleTitle = "   ",
            location  = "",
            jobUrl    = "",
        )

        val record = pipeline.toJdRecord(state)

        assertNull(record.company)
        assertNull(record.roleTitle)
        assertNull(record.location)
        assertNull(record.jobUrl)
        assertNull(record.idempotencyKey)
    }

    @Test
    @DisplayName("toJdRecord with null idempotencyKey leaves it null")
    fun toJdRecordNullKeyIsNull() {
        val pipeline = IngestionPipeline()
        val state = JDState(jdText = "jd", company = "Acme")
        val record = pipeline.toJdRecord(state, idempotencyKey = null)
        assertNull(record.idempotencyKey)
    }

    // ── invoke routing ────────────────────────────────────────────────────────

    @Test
    @DisplayName("invoke routes non-job-posting through saveNode and never calls scrape")
    fun invokeNonJobPostingSkipsScrape() {
        val pipeline = IngestionPipeline()

        injectScanNode(pipeline, Node { state -> state.copy(isJobPosting = false) })

        val mockScrape = mock<ScrapeJdNode>()
        pipeline.scrapeNode = mockScrape

        var saveCalled = false
        injectSaveNode(pipeline, Node { state -> saveCalled = true; state })

        val result = pipeline.invoke(JDState(isJobPosting = true))

        assertFalse(result.isJobPosting)
        assertTrue(saveCalled,  "saveNode should be called for non-job-postings")
        verify(mockScrape, never()).process(any())
    }

    @Test
    @DisplayName("invoke routes job posting through scrape and save")
    fun invokeSingleJobPostingRunsScrapeAndSave() {
        val pipeline = IngestionPipeline()

        injectScanNode(pipeline, Node { state ->
            state.copy(isJobPosting = true, jdText = "original jd")
        })

        val mockScrape = mock<ScrapeJdNode>()
        whenever(mockScrape.process(any())).doAnswer { inv ->
            (inv.arguments[0] as JDState).copy(jdText = "scraped content")
        }
        whenever(mockScrape.batchBlockedDomains).thenReturn(mutableSetOf())
        whenever(mockScrape.batchLinkedInSessionExpired).thenReturn(false)
        pipeline.scrapeNode = mockScrape

        var saveCalled = false
        injectSaveNode(pipeline, Node { state -> saveCalled = true; state })

        val result = pipeline.invoke(JDState(isJobPosting = false))

        assertTrue(result.isJobPosting)
        assertTrue(saveCalled, "saveNode should be called for job postings")
        verify(mockScrape).process(any())
        assertEquals("scraped content", result.jdText)
    }

    /** A pipeline whose scan yields a job posting with [emailJd] and whose scrape fails with [scrapeError]. */
    private fun failingScrapePipeline(emailJd: String, scrapeError: String): IngestionPipeline {
        val pipeline = IngestionPipeline()
        injectScanNode(pipeline, Node { state ->
            state.copy(isJobPosting = true, jdText = emailJd, jobUrl = "https://www2.joboppforyou.com/respond.jsp?d=1")
        })
        val mockScrape = mock<ScrapeJdNode>()
        whenever(mockScrape.process(any())).doAnswer { inv ->
            (inv.arguments[0] as JDState).copy(error = scrapeError, scrapePath = "cdp_fallback")
        }
        pipeline.scrapeNode = mockScrape
        injectSaveNode(pipeline, Node { state -> state })
        return pipeline
    }

    @Test
    @DisplayName("a failed scrape falls back to a full JD already in the recruiter email")
    fun failedScrapeFallsBackToEmailJd() {
        // .Net RUST Developer (2026-10-06): an 834-char JD in the email, a mail-merge link the
        // scan took for the job URL, and Steel down — the whole email went to JD_Error.
        val emailJd = "Role: .Net RUST Developer. ".repeat(30)
        val pipeline = failingScrapePipeline(emailJd, "scrape_jd: Error { message='Target page, context or browser has been closed")

        val result = pipeline.invoke(JDState(isJobPosting = false))

        assertEquals("", result.error)
        assertEquals(emailJd, result.jdText)
        assertEquals("cdp_fallback", result.scrapePath, "the analyzer still sees the browser was involved")
        assertEquals(EmailDisposition.Process, EmailResolution.classify(result))
    }

    @Test
    @DisplayName("the fallback drops a job_url that is not a URL, so the email is not MALFORMED_URL")
    fun fallbackDropsNonHttpJobUrl() {
        val pipeline = IngestionPipeline()
        injectScanNode(pipeline, Node { state ->
            state.copy(isJobPosting = true, jdText = "Senior SDET. ".repeat(60), jobUrl = "see link below")
        })
        val mockScrape = mock<ScrapeJdNode>()
        whenever(mockScrape.process(any())).doAnswer { inv ->
            (inv.arguments[0] as JDState).copy(error = "scrape_jd: Illegal character in path")
        }
        pipeline.scrapeNode = mockScrape
        injectSaveNode(pipeline, Node { state -> state })

        val result = pipeline.invoke(JDState(isJobPosting = false))

        assertEquals("", result.jobUrl)
        assertEquals(EmailDisposition.Process, EmailResolution.classify(result))
    }

    @Test
    @DisplayName("a failed scrape keeps its error when the email holds only a blurb")
    fun failedScrapeWithThinEmailJdStillErrors() {
        val pipeline = failingScrapePipeline("SDET role, see link", "scrape_jd: Steel browser not available")

        val result = pipeline.invoke(JDState(isJobPosting = false))

        assertEquals("scrape_jd: Steel browser not available", result.error)
    }

    @Test
    @DisplayName("invoke processes only isJobPosting children of a digest email")
    fun invokeDigestProcessesJobPostingChildrenOnly() {
        val pipeline = IngestionPipeline()

        val jobChild    = JDState(isJobPosting = true,  jdText = "child jd")
        val nonJobChild = JDState(isJobPosting = false, jdText = "not a job")

        injectScanNode(pipeline, Node { state ->
            state.copy(isJobPosting = true, digestJobs = listOf(jobChild, nonJobChild))
        })

        val mockScrape = mock<ScrapeJdNode>()
        whenever(mockScrape.process(any())).doAnswer { inv -> inv.arguments[0] as JDState }
        whenever(mockScrape.batchBlockedDomains).thenReturn(mutableSetOf())
        whenever(mockScrape.batchLinkedInSessionExpired).thenReturn(false)
        pipeline.scrapeNode = mockScrape
        injectSaveNode(pipeline, Node { state -> state })

        val input = JDState(
            intake = IntakeContext.Email(
                emailId        = "digest-001",
                subject        = "Daily Digest",
                from           = "noreply@jobright.ai",
                rawBody        = "",
                htmlBody       = "",
                isRecruiter    = false,
                isDigest       = true,
                isInlineDigest = false,
            ),
        )
        val result = pipeline.invoke(input)

        assertEquals(1, result.digestJobs.size, "only the isJobPosting child should be processed")
        assertTrue(result.digestJobs.first().isJobPosting)
    }

    @Test
    @DisplayName("a link-only digest child (employer alert) is not scraped in the email's own claim")
    fun linkOnlyChildrenAreNotScraped() {
        val pipeline = IngestionPipeline()
        val linkOnly = JDState(isJobPosting = true, jobUrl = "https://jobs.lever.co/acme/x", submitAsUrl = true, postingKey = "watchlist:lever:acme:x")
        val scraped = JDState(isJobPosting = true, jobUrl = "https://board.example.com/jobs/1")
        injectScanNode(pipeline, Node { state ->
            state.copy(
                intake = com.jd.pipeline.source.IntakeContext.Email("e", "a@b.com", "s", "", "", false, true, false),
                digestJobs = listOf(linkOnly, scraped),
            )
        })
        val mockScrape = mock<ScrapeJdNode>()
        whenever(mockScrape.process(any())).thenAnswer { (it.arguments[0] as JDState).copy(jdText = "scraped") }
        pipeline.scrapeNode = mockScrape
        injectSaveNode(pipeline, Node { it })

        val result = pipeline.invoke(JDState())

        verify(mockScrape, times(1)).process(any())
        assertEquals(listOf("", "scraped"), result.digestJobs.map { it.jdText })
        assertTrue(result.digestJobs.first().submitAsUrl)
    }
}
