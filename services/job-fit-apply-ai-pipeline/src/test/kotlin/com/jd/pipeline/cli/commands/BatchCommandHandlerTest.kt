package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.EmailLabelingServiceImpl
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.JobStatusDto
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@DisplayName("BatchCommandHandler")
class BatchCommandHandlerTest {

    private lateinit var gmail: GmailTransport
    private lateinit var ingestion: IngestionPipeline
    private lateinit var bridge: BridgeClient
    private lateinit var labeling: EmailLabelingServiceImpl

    private val cmd = Command.Batch(maxEmails = 10, debug = false)

    @BeforeEach
    fun setUp() {
        gmail    = mock()
        ingestion = mock()
        bridge   = mock()
        labeling  = mock()
        // applyLabeling returns a non-null LabelingResult — set up a safe default
        whenever(labeling.applyLabeling(any(), any())).doReturn(
            com.jd.pipeline.cli.LabelingResult(
                labelApplied = "JD_Processed",
                wasArchived = true, wasStarred = false,
                wasMarkedUnread = false, wasKeptInInbox = false,
            )
        )
        // batchBlockedDomains and batchLinkedInSessionExpired have safe defaults
        whenever(ingestion.batchLinkedInSessionExpired()).doReturn(false)
        whenever(ingestion.batchBlockedDomains()).doReturn(emptySet())
    }

    private fun run() = BatchCommandHandler.run(cmd, gmail, ingestion, bridge, labeling)

    private fun emailState(
        emailId: String = "email-001",
        isJobPosting: Boolean = true,
        isDigest: Boolean = false,
    ) = JDState(
        intake = IntakeContext.Email(
            emailId = emailId, from = "hr@acme.com", subject = "Staff SDET at Acme",
            rawBody = "We are hiring.", htmlBody = "",
            isRecruiter = false, isDigest = isDigest, isInlineDigest = false,
        ),
        isJobPosting = isJobPosting,
        company = "Acme", roleTitle = "Staff SDET", jdText = "We are hiring.",
    )

    private fun minRecord(company: String = "Acme") = JdRecord(
        jdText = "jd text", company = company, roleTitle = "Engineer",
        location = null, jobUrl = null,
        source = IngestionSource.EMAIL,
    )

    private fun doneStatus(action: String = "TAILOR", score: Int = 85) = JobStatusDto(
        job_id = "job-001", status = "done", pipeline_action = action, fit_score = score,
    )

    // ── Empty inbox ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("empty inbox")
    inner class EmptyInbox {

        @Test
        @DisplayName("returns early when no emails found — no ingestion or bridge calls")
        fun noEmailsNoWork() {
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(emptyList())
            run()
            verify(ingestion, never()).invoke(any())
            verify(bridge, never()).submit(any())
        }
    }

    // ── Non-job-posting email ─────────────────────────────────────────────────

    @Nested
    @DisplayName("non-job-posting email")
    inner class NonJobPosting {

        @Test
        @DisplayName("labels immediately without submitting to bridge")
        fun nonJobPostingLabeled() {
            val email = emailState(isJobPosting = false)
            val ingested = email.copy(isJobPosting = false)
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            run()
            verify(bridge, never()).submit(any())
            verify(labeling).applyLabeling(any(), any())
        }
    }

    // ── Single job-posting email ──────────────────────────────────────────────

    @Nested
    @DisplayName("single job-posting email")
    inner class SingleJobPosting {

        @Test
        @DisplayName("ingests, submits to bridge, applies processing label, then polls")
        fun jobPostingFullFlow() {
            val email = emailState()
            val ingested = email.copy(isJobPosting = true, jdText = "full jd text")
            val record = minRecord()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            whenever(ingestion.toJdRecord(any(), any())).doReturn(record)
            whenever(bridge.submit(record)).doReturn("job-001")
            whenever(bridge.pollUntilTerminal(any(), any(), any())).doReturn(doneStatus("TAILOR"))

            run()

            verify(bridge).submit(record)
            verify(labeling).applyProcessing(any(), any())
            verify(bridge).pollUntilTerminal(org.mockito.kotlin.eq("job-001"), any(), any())
            verify(labeling).applyLabeling(any(), any())
        }

        @Test
        @DisplayName("SKIP result increments skipped count (no exception)")
        fun skipResultHandledCleanly() {
            val email = emailState()
            val ingested = email.copy(isJobPosting = true)
            val record = minRecord()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            whenever(ingestion.toJdRecord(any(), any())).doReturn(record)
            whenever(bridge.submit(record)).doReturn("job-002")
            whenever(bridge.pollUntilTerminal(any(), any(), any())).doReturn(
                JobStatusDto(job_id = "job-002", status = "done", pipeline_action = "SKIP", fit_score = 25)
            )
            run() // must not throw
            verify(bridge).pollUntilTerminal(org.mockito.kotlin.eq("job-002"), any(), any())
        }

        @Test
        @DisplayName("bridge submit exception is caught — email is not labeled as processing")
        fun bridgeSubmitExceptionCaught() {
            val email = emailState()
            val ingested = email.copy(isJobPosting = true)
            val record = minRecord()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            whenever(ingestion.toJdRecord(any(), any())).doReturn(record)
            whenever(bridge.submit(any())).thenThrow(RuntimeException("bridge unreachable"))
            run() // must not propagate exception
            verify(labeling, never()).applyProcessing(any(), any())
        }
    }

    // ── Ingestion error ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("ingestion error")
    inner class IngestionError {

        @Test
        @DisplayName("ingestion exception labels email with error and continues to next email")
        fun ingestionExceptionHandled() {
            val email = emailState()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).thenThrow(RuntimeException("scan failed"))
            run() // must not throw
            // labeling is applied even on ingestion failure
            verify(labeling).applyLabeling(any(), any())
            verify(bridge, never()).submit(any())
        }
    }

    // ── Poll timeout / error ──────────────────────────────────────────────────

    @Nested
    @DisplayName("poll error or timeout")
    inner class PollError {

        @Test
        @DisplayName("poll exception is caught — terminal label is still applied")
        fun pollExceptionCaught() {
            val email = emailState()
            val ingested = email.copy(isJobPosting = true)
            val record = minRecord()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            whenever(ingestion.toJdRecord(any(), any())).doReturn(record)
            whenever(bridge.submit(record)).doReturn("job-timeout")
            whenever(bridge.pollUntilTerminal(any(), any(), any())).thenThrow(RuntimeException("poll timeout"))

            run() // must not throw

            // Terminal label is applied even after poll failure
            verify(labeling).applyLabeling(any(), any())
        }
    }

    // ── LinkedIn / blocked-domain warnings ───────────────────────────────────

    @Nested
    @DisplayName("post-batch warnings")
    inner class PostBatchWarnings {

        @Test
        @DisplayName("batchLinkedInSessionExpired warning does not throw")
        fun linkedInWarningDoesNotThrow() {
            val email = emailState(isJobPosting = false)
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(any())).doReturn(email.copy(isJobPosting = false))
            whenever(ingestion.batchLinkedInSessionExpired()).doReturn(true)
            whenever(ingestion.batchBlockedDomains()).doReturn(setOf("linkedin.com"))
            run() // must not throw
        }
    }
}
