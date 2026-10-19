package com.jd.poller

import com.jd.poller.bridge.ArtifactUrls
import com.jd.poller.bridge.CompletedJob
import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.gmail.GmailClient
import com.jd.poller.gmail.TerminalLabels
import com.jd.poller.intake.IntakeLoop
import com.jd.poller.model.RawEmail
import com.jd.poller.testutil.FakeBridge
import com.jd.poller.writeback.WritebackLoop
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end: the Poller's intake + write-back loops driving the real HTTP client against an
 * in-process [FakeBridge], with Gmail mocked. Exercises the whole contract — submit an email,
 * simulate the Processor completing it, then drain it back to Gmail — with no production impact.
 */
@DisplayName("PollerE2ETest")
class PollerE2ETest {

    private lateinit var bridge: FakeBridge
    private lateinit var client: PollerBridgeClient

    @BeforeEach
    fun setup() {
        bridge = FakeBridge().start()
        client = PollerBridgeClient(bridge.baseUrl)
    }

    @AfterEach
    fun tearDown() = bridge.stop()

    private fun labelStubGmail() = mock<GmailClient> {
        on { getOrCreateLabel(any()) } doReturn "lbl"
        on { findLabelId(any()) } doAnswer { (it.arguments[0] as String) + "-id" }
    }

    @Test
    @DisplayName("intake → processor-completes → write-back, for a processed (non-recruiter) email")
    fun processedEmailRoundTrip() {
        val gmail = labelStubGmail().apply {
            whenever(fetchIntakeEmails(any())).doReturn(listOf(RawEmail("m1", "Staff SDET", "jobs@board.com", "JD text", "", false)))
        }

        // 1. Intake: fetch from Gmail, submit to the bridge.
        val submitted = IntakeLoop(gmail, client).pollOnce()
        assertEquals(1, submitted)
        assertEquals(1, bridge.submitted.size)
        assertEquals("m1", bridge.submitted.first()["message_id"])
        verify(gmail).labelEmail(eq("m1"), eq("lbl"))   // Processing applied

        // 2. Simulate the Processor completing the job (posts a terminal result into the feed).
        bridge.complete(CompletedJob(jobId = "job-m1", completedSeq = 1, status = "done", messageId = "m1", terminalLabel = TerminalLabels.JD_PROCESSED, isRecruiter = false))

        // 3. Write-back: drain the feed and apply the outcome to Gmail.
        val drained = WritebackLoop(gmail, client).drainOnce()
        assertEquals(1, drained)
        verify(gmail).getOrCreateLabel(TerminalLabels.JD_PROCESSED)
        verify(gmail).archiveEmail("m1")
        assertTrue(bridge.wasWrittenBack("job-m1"))
        assertTrue(client.fetchCompleted(since = 0).isEmpty(), "job should leave the feed after write-back")
    }

    @Test
    @DisplayName("recruiter email round-trips into a Gmail draft with downloaded attachments")
    fun recruiterEmailRoundTrip() {
        val gmail = labelStubGmail().apply {
            whenever(fetchIntakeEmails(any())).doReturn(listOf(RawEmail("m2", "Great role for you", "Jane <jane@firm.com>", "hi", "", false)))
            whenever(getMessageMeta("m2")).doReturn(GmailClient.MessageMeta("Jane <jane@firm.com>", "Great role for you"))
            whenever(createDraftReply(any(), any(), any(), any(), any())).doReturn("draft-1")
        }
        IntakeLoop(gmail, client).pollOnce()

        // Processor completes it as a recruiter job with a draft + artifacts.
        bridge.artifacts["/api/jobs/job-m2/resume.pdf"] = "PDF".toByteArray()
        bridge.artifacts["/api/jobs/job-m2/cover_letter.txt"] = "COVER".toByteArray()
        bridge.complete(
            CompletedJob(
                jobId = "job-m2", completedSeq = 1, status = "done", messageId = "m2",
                terminalLabel = TerminalLabels.RECRUITER, isRecruiter = true, draftText = "Thanks!",
                artifacts = ArtifactUrls("/api/jobs/job-m2/resume.pdf", "/api/jobs/job-m2/cover_letter.txt"),
            )
        )

        val drained = WritebackLoop(gmail, client).drainOnce()
        assertEquals(1, drained)
        verify(gmail).starEmail("m2")
        // Draft addressed from the recruiter, with both artifacts downloaded from the bridge attached.
        verify(gmail).createDraftReply(
            eq("m2"), eq("jane@firm.com"), eq("Re: Great role for you"), eq("Thanks!"),
            org.mockito.kotlin.argThat { size == 2 && all { it.endsWith(".pdf") || it.endsWith(".txt") } },
        )
        assertTrue(bridge.wasWrittenBack("job-m2"))
    }
}
