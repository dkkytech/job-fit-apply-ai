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
        whenever(pipeline.invoke(record)).doReturn(result)
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
        whenever(pipeline.invoke(record)).thenThrow(RuntimeException("pipeline exploded"))
        doAnswer { resultPosted.countDown() }.whenever(bridge).postResult(any(), any(), anyOrNull())

        processorThread.isDaemon = true
        processorThread.start()

        assertTrue(resultPosted.await(5, TimeUnit.SECONDS), "processor should post error result within 5s")

        verify(bridge).postResult(
            org.mockito.kotlin.eq("job-err"),
            org.mockito.kotlin.argThat { error != null && error!!.contains("pipeline exploded") },
            org.mockito.kotlin.anyOrNull(),
        )
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
            whenever(pipeline.invoke(record)).doReturn(successResult())
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
            verify(pipeline, never()).invoke(any())           // the digest parent itself is never processed
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
            verify(pipeline, never()).invoke(any())
            verify(bridge, never()).submit(any())
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
            verify(pipeline, never()).invoke(any())
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
            whenever(pipeline.invoke(recordCaptor.capture())).doReturn(successResult())

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
            verify(pipeline, never()).invoke(any())
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
            verify(pipeline, never()).invoke(any())
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
        verify(pipeline, org.mockito.kotlin.never()).invoke(any())
    }
}
