package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.ClaimDto
import com.jd.pipeline.client.SubmitOutcome
import com.jd.pipeline.client.ClaimedEmail
import com.jd.pipeline.client.WorkItemType
import com.jd.pipeline.nodes.ScrapeJdNode
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.pipeline.TerminalLabel
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.state.JDState
import com.jd.pipeline.utils.RunReport
import java.nio.file.Files
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("ProcessorCommandHandlerTest")
class ProcessorCommandHandlerTest {

    private fun fakeRecord() = JdRecord(
        jdText    = "x".repeat(200),
        company   = "Acme Corp",
        roleTitle = "Staff SDET",
        location  = "Seattle, WA",
        jobUrl    = "https://boards.example.com/acme/job/123",
        source    = IngestionSource.EMAIL,
    )

    private fun successResult() = ProcessingResult(
        pipelineAction = "TAILOR",
        fitScore       = 82,
        strengths      = listOf("Kotlin", "CI/CD"),
        isDuplicate    = false,
        outputPath     = null,
        hasCoverLetter = false,
        error          = null,
        artifactUrl    = "https://artifacts.example.com/acme",
    )

    @Test
    @DisplayName("processor processes a claimed job and posts the result")
    fun processorProcessesClaimAndPostsResult() {
        val record = fakeRecord()
        val result = successResult()
        // Carries a fence: the handler must hand it back on postResult, or a displaced worker
        // could overwrite the attempt that replaced it (bridge refuses a mismatch).
        val claim  = ClaimDto(jobId = "job-1", jdRecord = record, claimToken = "token-1")

        val resultPosted = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline) }

        // First claim: return the job. Second claim: interrupt to stop the loop.
        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                processorThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record, null)).doReturn(result)
        doAnswer { resultPosted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

        processorThread.isDaemon = true
        processorThread.start()

        assertTrue(resultPosted.await(5, TimeUnit.SECONDS), "processor should post result within 5s")

        verify(pipeline).invoke(record)
        verify(bridge).postResult("job-1", result, "token-1")
    }

    @Test
    @DisplayName("processor posts a SKIP error result when pipeline throws")
    fun processorPostsErrorResultOnPipelineException() {
        val record = fakeRecord()
        val claim  = ClaimDto(jobId = "job-err", jdRecord = record)

        val resultPosted = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline) }

        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                processorThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record, null)).thenThrow(RuntimeException("pipeline exploded"))
        doAnswer { resultPosted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

        processorThread.isDaemon = true
        processorThread.start()

        assertTrue(resultPosted.await(5, TimeUnit.SECONDS), "processor should post error result within 5s")

        verify(bridge).postResult(
            org.mockito.kotlin.eq("job-err"),
            org.mockito.kotlin.argThat {
                error != null && error!!.contains("pipeline exploded") &&
                    terminalLabel == TerminalLabel.JD_ERROR
            },
            org.mockito.kotlin.anyOrNull(),
        )
    }

    @Test
    @DisplayName("processor marks an Ollama 429 as retryable and does not assign JD_Error")
    fun processorRequeuesTransientProviderFailure() {
        val record = fakeRecord()
        val claim = ClaimDto(jobId = "job-retry", jdRecord = record, claimToken = "retry-token")
        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()
        val posted = CountDownLatch(1)
        val thread = Thread { ProcessorCommandHandler.run(bridge, pipeline) }
        whenever(bridge.claim()).doReturn(claim).doAnswer { thread.interrupt(); null }
        whenever(pipeline.invoke(record, null)).thenThrow(RuntimeException("LLM HTTP 429 too many concurrent requests"))
        doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())
        thread.isDaemon = true
        thread.start()

        assertTrue(posted.await(5, TimeUnit.SECONDS))
        verify(bridge).postResult(eq("job-retry"), argThat {
            retryable && terminalLabel == null && error!!.contains("429")
        }, eq("retry-token"))
    }

    // (Per-job Discord/Telegram messaging moved to the Notifier service — see NotifierTest there.)

    // ── EMAIL_RAW claims (scan/scrape happen in the Processor) ──────────────────

    @Nested
    @DisplayName("EMAIL_RAW claims")
    inner class EmailRawClaims {

        private fun emailClaim() = ClaimDto(
            jobId = "job-email",
            type  = WorkItemType.EMAIL_RAW,
            email = ClaimedEmail(
                messageId = "m1", subject = "Staff SDET role", body = "hi",
                htmlBody = null, from = "rec@firm.com", isRecruiterHint = false,
            ),
        )

        private fun ingested(isJobPosting: Boolean, isDigest: Boolean = false, children: List<JDState> = emptyList()) =
            JDState(
                isJobPosting = isJobPosting,
                intake = IntakeContext.Email(
                    emailId = "m1", from = "rec@firm.com", subject = "Staff SDET role",
                    rawBody = "hi", htmlBody = "",
                    isRecruiter = false, isDigest = isDigest, isInlineDigest = false,
                ),
                digestJobs = children,
            )

        @Test
        @DisplayName("scans a single-posting email and processes the resolved record")
        fun scansAndProcessesSinglePosting() {
            val record = fakeRecord()
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingested(isJobPosting = true))
            whenever(ingestion.toJdRecord(any(), anyOrNull())).doReturn(record)
            whenever(pipeline.invoke(record, null)).doReturn(successResult())
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post result within 5s")
            verify(pipeline).invoke(record)
            verify(bridge).postResult(eq("job-email"), any(), anyOrNull())
        }

        @Test
        @DisplayName("re-enqueues digest children and completes the parent without processing it")
        fun reEnqueuesDigestChildren() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val children = listOf(ingested(isJobPosting = true), ingested(isJobPosting = true))
            val childRecord = fakeRecord()

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingested(isJobPosting = false, isDigest = true, children = children))
            whenever(ingestion.toJdRecord(any(), anyOrNull())).doReturn(childRecord)
            whenever(bridge.submitDetailed(any())).doReturn(SubmitOutcome("child-1", deduped = false))
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should complete the parent within 5s")
            verify(bridge, times(2)).submitDetailed(childRecord)      // one per job-posting child
            // Each child carries a stable key derived from the parent message, so a retried digest
            // re-submits only what is missing instead of duplicating what already landed.
            verify(ingestion).toJdRecord(any(), eq("m1|#0"))
            verify(ingestion).toJdRecord(any(), eq("m1|#1"))
            // parent digest completed → JD_Processed_Digest so the Poller archives it (not a null
            // label that would loop the parent through JD_Processing forever)
            verify(bridge).postResult(eq("job-email"), argThat { terminalLabel == TerminalLabel.JD_PROCESSED_DIGEST }, anyOrNull())
            verify(pipeline, never()).invoke(any(), anyOrNull())           // the digest parent itself is never processed
        }

        @Test
        @DisplayName("employer-alert children go to /api/urls under their posting key, career-site window")
        fun linkOnlyChildrenAreSubmittedAsUrls() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val alertChild = ingested(isJobPosting = true).copy(
                jobUrl = "https://apply.careers.microsoft.com/careers/job/1970393556874530",
                submitAsUrl = true, postingKey = "watchlist:eightfold:apply.careers.microsoft.com:1970393556874530",
                company = "Microsoft", roleTitle = "Senior SDET",
            )
            val scrapedChild = ingested(isJobPosting = true)
            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingested(isJobPosting = false, isDigest = true, children = listOf(alertChild, scrapedChild)))
            whenever(ingestion.toJdRecord(any(), anyOrNull())).doReturn(fakeRecord())
            whenever(bridge.submitDetailed(any())).doReturn(SubmitOutcome("child-2", deduped = false))
            whenever(bridge.submitUrl(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).doReturn(SubmitOutcome("watch-1", deduped = true))
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should complete the parent within 5s")
            verify(bridge).submitUrl(
                eq("https://apply.careers.microsoft.com/careers/job/1970393556874530"),
                eq("watchlist:eightfold:apply.careers.microsoft.com:1970393556874530"),
                eq("CAREER_SITE"), eq("Microsoft"), eq("Senior SDET"), anyOrNull(),
            )
            verify(ingestion, times(1)).toJdRecord(any(), eq("m1|#1"))   // only the scraped child takes the JD_SCRAPED path
            verify(bridge, times(1)).submitDetailed(any())
            // A posting the bridge already had (deduped) still counts as landed: the parent is a processed digest.
            verify(bridge).postResult(eq("job-email"), argThat { terminalLabel == TerminalLabel.JD_PROCESSED_DIGEST }, anyOrNull())
        }

        @Test
        @DisplayName("a child that fails to enqueue makes the parent an error, not a processed digest")
        fun lostChildFailsTheParent() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val children = listOf(ingested(isJobPosting = true), ingested(isJobPosting = true))
            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingested(isJobPosting = false, isDigest = true, children = children))
            whenever(ingestion.toJdRecord(any(), anyOrNull())).doReturn(fakeRecord())
            whenever(bridge.submitDetailed(any()))
                .doReturn(SubmitOutcome("child-1", deduped = false))
                .doThrow(RuntimeException("bridge down"))
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should complete the parent within 5s")
            // Archiving the parent here is how siblings get silently lost: the failed child exists
            // nowhere and there is nothing left to retry from.
            verify(bridge).postResult(
                eq("job-email"),
                argThat {
                    terminalLabel == TerminalLabel.JD_ERROR &&
                        error != null && error!!.contains("1 of 2 children")
                },
                anyOrNull(),
            )
        }

        @Test
        @DisplayName("skips a non-job email and never processes it")
        fun skipsNonJobEmail() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingested(isJobPosting = false))
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should complete the non-job within 5s")
            // JD_Not_Found so the Poller excludes it from the intake query (no null-label loop)
            verify(bridge).postResult(eq("job-email"), argThat { terminalLabel == TerminalLabel.JD_NOT_FOUND }, anyOrNull())
            verify(pipeline, never()).invoke(any(), anyOrNull())
            verify(bridge, never()).submit(any())
        }

        @Test
        @DisplayName("completes a terminal scrape outcome with the scrape-failed label")
        fun terminalScrapeOutcomeUsesScrapeFailedLabel() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()
            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)
            val blocked = ingested(isJobPosting = true).copy(
                jobUrl = "https://jobs.example.com/123",
                error = "scrape_jd: HTTP 403 blocked",
                scrapePath = "blocked",
            )

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(blocked)
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should complete the scrape failure within 5s")
            verify(bridge).postResult(eq("job-email"), argThat {
                terminalLabel == TerminalLabel.JD_SCRAPE_FAILED &&
                    error?.contains("BLOCKED") == true &&
                    scrapePath == "blocked"
            }, anyOrNull())
            verify(pipeline, never()).invoke(any(), anyOrNull())
        }

        /** Run one EMAIL_RAW claim whose ingestion returns [ingestedState]; returns the bridge mock. */
        private fun runIngestionError(ingestedState: JDState): BridgeClient {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()
            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingestedState)
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should complete the ingestion error within 5s")
            verify(pipeline, never()).invoke(any(), anyOrNull())
            return bridge
        }

        @Test
        @DisplayName("a browser outage recorded by the scrape is retried, not labelled JD_Error")
        fun browserOutageIsRetryable() {
            val bridge = runIngestionError(ingested(isJobPosting = true).copy(
                jobUrl = "https://jobs.example.com/123",
                error = "scrape_jd: Error {\n  message='Target page, context or browser has been closed",
                scrapePath = "cdp_fallback",
            ))

            verify(bridge).postResult(eq("job-email"), argThat {
                retryable && terminalLabel == null && scrapePath == "cdp_fallback"
            }, anyOrNull())
        }

        @Test
        @DisplayName("a non-transient ingestion error is still labelled JD_Error")
        fun nonTransientIngestionErrorIsJdError() {
            val bridge = runIngestionError(ingested(isJobPosting = false).copy(
                error = "scan_email: JSON parse failed — \": true,",
            ))

            verify(bridge).postResult(eq("job-email"), argThat {
                !retryable && terminalLabel == TerminalLabel.JD_ERROR
            }, anyOrNull())
        }

        @Test
        @DisplayName("posts a skip when an EMAIL_RAW claim has no email payload")
        fun skipsWhenEmailPayloadMissing() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val badClaim = ClaimDto(jobId = "job-bad", type = WorkItemType.EMAIL_RAW, email = null)
            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(badClaim).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a skip within 5s")
            verify(bridge).postResult(eq("job-bad"), argThat {
                error != null && error!!.contains("missing email payload") && terminalLabel == TerminalLabel.JD_ERROR
            }, anyOrNull())
            verify(ingestion, never()).invoke(any())
            verify(pipeline, never()).invoke(any(), anyOrNull())
        }
    }

    // ── JD_PAGE_RAW claims (browser-extension captures — LLM-extracted here) ────

    @Nested
    @DisplayName("JD_PAGE_RAW claims")
    inner class PageCaptureClaims {

        private fun pageClaim() = ClaimDto(
            jobId       = "job-page",
            type        = WorkItemType.JD_PAGE_RAW,
            pageCapture = com.jd.pipeline.client.ClaimedPageCapture(
                url = "https://linkedin.com/jobs/view/123", text = "raw page text".repeat(50), title = "Staff SDET",
            ),
        )

        /** A JDState as the dual-mode ScrapeJdNode would return after extraction. */
        private fun extracted(jdText: String, error: String = "") = JDState(
            jobUrl = "https://linkedin.com/jobs/view/123",
            company = "Acme", roleTitle = "Staff SDET", location = "Remote",
            jdText = jdText, isJobPosting = jdText.isNotBlank(), error = error,
        )

        @Test
        @DisplayName("extracts a captured page and processes it as an EXTENSION-sourced JdRecord")
        fun extractsAndProcesses() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()
            val scrapeNode = mock<ScrapeJdNode>()

            whenever(ingestion.scrapeNode).doReturn(scrapeNode)
            whenever(scrapeNode.process(any())).doReturn(extracted("x".repeat(300)))

            val recordCaptor = argumentCaptor<JdRecord>()
            whenever(pipeline.invoke(recordCaptor.capture(), anyOrNull())).doReturn(successResult())

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)
            whenever(bridge.claim()).doReturn(pageClaim()).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()
            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post result within 5s")

            verify(bridge).postResult(eq("job-page"), any(), anyOrNull())
            val record = recordCaptor.firstValue
            assertEquals(IngestionSource.EXTENSION, record.source)
            assertEquals("https://linkedin.com/jobs/view/123", record.idempotencyKey)
            assertEquals("Acme", record.company)
        }

        @Test
        @DisplayName("skips (no processing) when extraction yields no usable JD")
        fun skipsWhenNoJdExtracted() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()
            val scrapeNode = mock<ScrapeJdNode>()

            whenever(ingestion.scrapeNode).doReturn(scrapeNode)
            whenever(scrapeNode.process(any())).doReturn(extracted(jdText = "", error = "scrape_jd: empty"))

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)
            whenever(bridge.claim()).doReturn(pageClaim()).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()
            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a skip within 5s")

            verify(bridge).postResult(eq("job-page"), argThat { error != null && error!!.contains("job posting") }, anyOrNull())
            verify(pipeline, never()).invoke(any(), anyOrNull())
        }

        @Test
        @DisplayName("posts a skip when a JD_PAGE_RAW claim has no page payload")
        fun skipsWhenPayloadMissing() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val badClaim = ClaimDto(jobId = "job-bad-page", type = WorkItemType.JD_PAGE_RAW, pageCapture = null)
            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)
            whenever(bridge.claim()).doReturn(badClaim).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

            processorThread.isDaemon = true
            processorThread.start()
            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a skip within 5s")

            verify(bridge).postResult(eq("job-bad-page"), argThat { error != null && error!!.contains("missing page payload") }, anyOrNull())
            verify(pipeline, never()).invoke(any(), anyOrNull())
        }
    }

    // ── JD_URL claims (POST /api/urls — a posting known only by its link) ──────

    @Nested
    @DisplayName("TAILOR_REQUEST claims")
    inner class TailorRequestClaims {

        private val record = JdRecord(
            jdText = "x".repeat(300), company = "Acme", roleTitle = "Staff SDET", location = "Seattle, WA",
            jobUrl = "https://jobs.acme.example/qa/7", source = IngestionSource.EMAIL,
        )

        /** Run one claim through the processor loop; returns the posted result and what the pipeline got. */
        private fun runOne(claim: ClaimDto): Triple<ProcessingResult, JdRecord?, com.jd.pipeline.source.TailorRequest?> {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val recordCaptor = argumentCaptor<JdRecord>()
            val tailorCaptor = argumentCaptor<com.jd.pipeline.source.TailorRequest>()
            whenever(pipeline.invoke(recordCaptor.capture(), tailorCaptor.capture())).doReturn(successResult())

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, mock<IngestionPipeline>()) }
            val posted = CountDownLatch(1)
            val resultCaptor = argumentCaptor<ProcessingResult>()
            whenever(bridge.claim()).doReturn(claim).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), resultCaptor.capture(), anyOrNull())
            processorThread.isDaemon = true
            processorThread.start()
            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a result within 5s")
            return Triple(resultCaptor.firstValue, recordCaptor.allValues.firstOrNull(), tailorCaptor.allValues.firstOrNull())
        }

        @Test
        @DisplayName("hands the stored record to the pipeline as a tailor request, with the offer's track and board")
        fun tailorsTheStoredRecord() {
            val claim = ClaimDto(
                jobId = "job-tailor", type = WorkItemType.TAILOR_REQUEST,
                tailor = com.jd.pipeline.client.ClaimedTailorRequest(record = record, parentJobId = "job-offer", trackId = 7, jobBoard = "jobright.ai"),
            )
            val (result, processed, tailor) = runOne(claim)
            assertEquals(record, processed)
            assertEquals(com.jd.pipeline.source.TailorRequest(parentJobId = "job-offer", trackId = 7, jobBoard = "jobright.ai"), tailor)
            assertNull(result.error)
        }

        @Test
        @DisplayName("a claim without its payload is failed, not spun on")
        fun missingPayloadIsFailed() {
            val (result, processed, _) = runOne(ClaimDto(jobId = "job-tailor", type = WorkItemType.TAILOR_REQUEST))
            assertNull(processed, "nothing to tailor")
            assertEquals("TAILOR_REQUEST claim missing its record", result.error)
        }
    }

    @Nested
    @DisplayName("JD_URL claims")
    inner class UrlClaims {

        private fun urlClaim(source: String? = "EMAIL", key: String? = "alert:acme:7") = ClaimDto(
            jobId = "job-url",
            type  = WorkItemType.JD_URL,
            url   = com.jd.pipeline.client.ClaimedUrl(
                url = "https://jobs.acme.example/qa/7", source = source,
                company = "Acme (hint)", roleTitle = "SDET (hint)", location = "Seattle, WA", idempotencyKey = key,
            ),
        )

        private fun scraped(jdText: String, error: String = "", company: String = "", roleTitle: String = "Staff SDET") = JDState(
            jobUrl = "https://jobs.acme.example/qa/7", company = company, roleTitle = roleTitle, location = "",
            jdText = jdText, isJobPosting = jdText.isNotBlank(), error = error, scrapePath = "steel",
        )

        /** Run one claim through the processor loop; returns the posted result and any processed record. */
        private fun runOne(claim: ClaimDto, scrape: (JDState) -> JDState): Pair<ProcessingResult, JdRecord?> {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()
            val scrapeNode = mock<ScrapeJdNode>()
            val seen = mutableListOf<JDState>()
            whenever(ingestion.scrapeNode).doReturn(scrapeNode)
            whenever(scrapeNode.process(any())).doAnswer { inv -> (inv.arguments[0] as JDState).also { seen += it }.let(scrape) }
            val recordCaptor = argumentCaptor<JdRecord>()
            whenever(pipeline.invoke(recordCaptor.capture(), anyOrNull())).doReturn(successResult())

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)
            val resultCaptor = argumentCaptor<ProcessingResult>()
            whenever(bridge.claim()).doReturn(claim).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), resultCaptor.capture(), anyOrNull())
            processorThread.isDaemon = true
            processorThread.start()
            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a result within 5s")

            claim.url?.let { link ->
                val input = seen.single()
                assertEquals(link.url, input.jobUrl, "the scraper is pointed at the submitted URL")
                assertTrue(input.intake is com.jd.pipeline.source.IntakeContext.Link)
            }
            return resultCaptor.firstValue to recordCaptor.allValues.firstOrNull()
        }

        @Test
        @DisplayName("scrapes the URL and processes it, with submitter hints as fallbacks")
        fun scrapesAndProcesses() {
            val (_, record) = runOne(urlClaim()) { scraped("x".repeat(300)) }
            assertNotNull(record)
            assertEquals(IngestionSource.EMAIL, record.source)
            assertEquals("alert:acme:7", record.idempotencyKey)
            assertEquals("Acme (hint)", record.company, "scrape found no company → hint")
            assertEquals("Staff SDET", record.roleTitle, "scrape's title wins over the hint")
            assertEquals("Seattle, WA", record.location)
            assertEquals("steel", record.scrapePath)
        }

        @Test
        @DisplayName("a transient scrape error is retryable; a permanent one is a plain skip")
        fun scrapeErrors() {
            val (transient, r1) = runOne(urlClaim()) { scraped("", error = "scrape_jd: HTTP 503 from steel") }
            assertNull(r1)
            assertTrue(transient.retryable && transient.terminalLabel == null && transient.error!!.contains("503"), transient.toString())

            val (permanent, r2) = runOne(urlClaim()) { scraped("", error = "scrape_jd: sign-in wall") }
            assertNull(r2)
            assertFalse(permanent.retryable)
            assertNull(permanent.terminalLabel, "no Gmail message → no label")
        }

        @Test
        @DisplayName("a page with too little text is skipped, not scored")
        fun skipsShortPages() {
            val (result, record) = runOne(urlClaim()) { scraped("Apply now") }
            assertNull(record)
            assertTrue(result.error!!.contains("No job description could be extracted"), result.toString())
        }

        @Test
        @DisplayName("missing payload is skipped; unknown source tags count as MANUAL and the key defaults to the url")
        fun payloadAndSourceEdges() {
            val (missing, _) = runOne(ClaimDto(jobId = "job-url", type = WorkItemType.JD_URL, url = null)) { error("must not scrape") }
            assertTrue(missing.error!!.contains("missing url payload"))

            val (_, record) = runOne(urlClaim(source = "SOMETHING_NEW", key = null)) { scraped("x".repeat(300), company = "Acme") }
            assertEquals(IngestionSource.MANUAL, record!!.source)
            assertEquals("https://jobs.acme.example/qa/7", record.idempotencyKey)
            assertEquals(IngestionSource.CAREER_SITE, ProcessorCommandHandler.ingestionSourceOf(" career_site "))
        }
    }

    // ── Run-log coverage for terminal-at-resolve branches ──────────────────────

    /**
     * The analyzer's spine is the bridge completed-feed; a job that goes terminal without a
     * run_log line shows up as `run_log_missing` and its metrics silently vanish from the window.
     * Every branch that posts a result must therefore also append a line — including the
     * scan/scrape short-circuits that never reach [ProcessingPipeline].
     */
    @Nested
    @DisplayName("run_log is written for every terminal outcome")
    inner class RunLogCoverage {

        private fun runLogLinesFor(jobId: String, claim: ClaimDto, ingestion: IngestionPipeline): List<String> {
            val logFile = Files.createTempFile("run_log", ".jsonl")
            RunReport.pathOverride = logFile
            try {
                val bridge = mock<BridgeClient>()
                val pipeline = mock<ProcessingPipeline>()
                val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
                val posted = CountDownLatch(1)

                whenever(bridge.claim()).doReturn(claim).doAnswer { processorThread.interrupt(); null }
                doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

                processorThread.isDaemon = true
                processorThread.start()
                assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a result within 5s")
                processorThread.join(2_000)

                return Files.readAllLines(logFile).filter { it.contains("\"jobId\":\"$jobId\"") }
            } finally {
                RunReport.pathOverride = null
            }
        }

        @Test
        @DisplayName("an EMAIL_RAW claim with no payload still gets a run_log line")
        fun missingEmailPayloadIsLogged() {
            val claim = ClaimDto(jobId = "job-log-1", type = WorkItemType.EMAIL_RAW, email = null)
            val lines = runLogLinesFor("job-log-1", claim, mock())

            assertEquals(1, lines.size, "exactly one run_log line expected, got: $lines")
            assertTrue(lines[0].contains("\"action\":\"SKIP\""), lines[0])
        }

        @Test
        @DisplayName("a claim with no jd_record is failed and logged, not silently re-queued forever")
        fun missingJdRecordIsFailedAndLogged() {
            // Regression: this branch used to `continue` without posting a result, so the bridge row
            // stayed CLAIMED, the stale-claim sweep re-queued it, and the processor span on it.
            val claim = ClaimDto(jobId = "job-log-2", type = WorkItemType.JD_SCRAPED, jdRecord = null)
            val lines = runLogLinesFor("job-log-2", claim, mock())

            assertEquals(1, lines.size, "exactly one run_log line expected, got: $lines")
            assertTrue(lines[0].contains("no jd_record"), lines[0])
        }

        @Test
        @DisplayName("the run_log line carries the terminal label")
        fun runLogCarriesTerminalLabel() {
            val claim = ClaimDto(jobId = "job-log-3", type = WorkItemType.EMAIL_RAW, email = null)
            val lines = runLogLinesFor("job-log-3", claim, mock())

            assertTrue(lines[0].contains("\"terminalLabel\":\"${TerminalLabel.JD_ERROR}\""), lines[0])
        }

        @Test
        @DisplayName("a terminal-at-resolve job records pipelineRan=false — the digest parent/child signal")
        fun terminalAtResolveRecordsPipelineDidNotRun() {
            // This is the ONLY field separating a digest PARENT from a digest CHILD in the run log.
            // The label cannot: a child inherits the parent's isDigest intake and
            // TerminalLabel.forState checks isDigest first, so both read JD_Processed_Digest. Nor
            // can action/score (both SKIP at 0) or hasJobUrl (a single-job digest copies the child's
            // URL onto the parent). If this regresses, the analyzer's thin-digest detector either
            // goes silent or fires on every digest email received.
            val claim = ClaimDto(jobId = "job-log-4", type = WorkItemType.EMAIL_RAW, email = null)
            val lines = runLogLinesFor("job-log-4", claim, mock())

            assertTrue(lines[0].contains("\"pipelineRan\":false"), lines[0])
        }
    }

    @Test
    @DisplayName("processor sleeps and retries when queue is empty")
    fun processorSleepsOnEmptyQueue() {
        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline) }

        // Null claim (empty queue) — interrupt after first idle
        var callCount = 0
        whenever(bridge.claim()).doAnswer {
            callCount++
            if (callCount >= 2) processorThread.interrupt()
            null
        }

        processorThread.isDaemon = true
        processorThread.start()
        processorThread.join(5_000)

        assertTrue(callCount >= 1, "processor should poll at least once")
        verify(pipeline, org.mockito.kotlin.never()).invoke(any(), anyOrNull())
    }
}
