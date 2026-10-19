package com.jd.poller.intake

import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.gmail.GmailClient
import com.jd.poller.gmail.TerminalLabels
import com.jd.poller.model.RawEmail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.jd.poller.testutil.GmailInboxStub
import com.jd.poller.testutil.GmailInboxStub.Companion.posting
import com.jd.poller.testutil.GmailInboxStub.Companion.reply
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("IntakeLoopTest")
class IntakeLoopTest {

    private fun email(id: String) = RawEmail(id, "Role $id", "rec@x.com", "body", "", false)

    @Test
    @DisplayName("submits every fetched email and marks each Processing")
    fun submitsAndLabels() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails(any()) } doReturn listOf(email("a"), email("b"))
            on { getOrCreateLabel(TerminalLabels.PROCESSING) } doReturn "proc-id"
        }
        val bridge = mock<PollerBridgeClient> { on { submitEmail(any()) } doReturn "job-x" }

        val submitted = IntakeLoop(gmail, bridge, interSubmitDelayMs = 0).pollOnce()

        assertEquals(2, submitted)
        verify(bridge, times(2)).submitEmail(any())
        verify(gmail).labelEmail(eq("a"), eq("proc-id"))
        verify(gmail).labelEmail(eq("b"), eq("proc-id"))
    }

    @Test
    @DisplayName("limits each intake pass to the configured batch size")
    fun limitsEachPassToConfiguredBatchSize() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails(2) } doReturn listOf(email("a"), email("b"))
            on { getOrCreateLabel(TerminalLabels.PROCESSING) } doReturn "proc-id"
        }
        val bridge = mock<PollerBridgeClient> { on { submitEmail(any()) } doReturn "job-x" }

        val submitted = IntakeLoop(gmail, bridge, batchSize = 2).pollOnce()

        assertEquals(2, submitted)
        verify(gmail).fetchIntakeEmails(2)
    }

    @Test
    @DisplayName("paces successful submissions by the configured delay")
    fun pacesSuccessfulSubmissions() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails(2) } doReturn listOf(email("a"), email("b"))
            on { getOrCreateLabel(TerminalLabels.PROCESSING) } doReturn "proc-id"
        }
        val bridge = mock<PollerBridgeClient> { on { submitEmail(any()) } doReturn "job-x" }
        val delays = mutableListOf<Long>()

        IntakeLoop(gmail, bridge, batchSize = 2, interSubmitDelayMs = 1_000, sleep = { delays.add(it) }).pollOnce()

        assertEquals(listOf(1_000L), delays)
    }

    @Test
    @DisplayName("an interrupted pacing wait restores interrupt and stops the pass")
    fun interruptedPacingWaitStopsPass() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails(3) } doReturn listOf(email("a"), email("b"), email("c"))
            on { getOrCreateLabel(TerminalLabels.PROCESSING) } doReturn "proc-id"
        }
        val bridge = mock<PollerBridgeClient> { on { submitEmail(any()) } doReturn "job-x" }

        try {
            val submitted = IntakeLoop(
                gmail,
                bridge,
                batchSize = 3,
                interSubmitDelayMs = 1_000,
                sleep = { throw InterruptedException("shutdown") },
            ).pollOnce()

            assertEquals(1, submitted)
            assertTrue(Thread.currentThread().isInterrupted)
            verify(bridge).submitEmail(eq(email("a")))
            verify(bridge, never()).submitEmail(eq(email("b")))
            verify(bridge, never()).submitEmail(eq(email("c")))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    @DisplayName("a failed submit does not block the other emails and is not counted")
    fun submitFailureIsIsolated() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails(any()) } doReturn listOf(email("bad"), email("good"))
            on { getOrCreateLabel(any()) } doReturn "proc-id"
        }
        val bridge = mock<PollerBridgeClient>()
        whenever(bridge.submitEmail(any())).thenThrow(RuntimeException("bridge down")).thenReturn("job-ok")

        val submitted = IntakeLoop(gmail, bridge, interSubmitDelayMs = 0).pollOnce()

        assertEquals(1, submitted)
        // The failed email is never labeled in-flight, so it is re-fetched next pass.
        verify(gmail, never()).labelEmail(eq("bad"), any())
        verify(gmail).labelEmail(eq("good"), eq("proc-id"))
    }

    @Test
    @DisplayName("empty inbox submits nothing")
    fun emptyInbox() {
        val gmail = mock<GmailClient> { on { fetchIntakeEmails(any()) } doReturn emptyList() }
        val bridge = mock<PollerBridgeClient>()
        assertEquals(0, IntakeLoop(gmail, bridge).pollOnce())
        verify(bridge, never()).submitEmail(any())
    }

    @Test
    @DisplayName("submits an unprocessed posting sitting behind 3+ skipped replies, paced and without touching the replies")
    fun submitsPostingBehindSkippedReplies() {
        val stub = GmailInboxStub(
            inbox = listOf(reply("r1", "t-a"), reply("r2", "t-a"), reply("r3", "t-b"), posting("jd1"), posting("jd2")),
            processedThreads = setOf("t-a", "t-b"),
        )
        val gmail = spy(GmailClient(stub.gmail))
        doReturn("proc-id").whenever(gmail).getOrCreateLabel(TerminalLabels.PROCESSING)
        doNothing().whenever(gmail).labelEmail(any(), any())
        val bridge = mock<PollerBridgeClient> { on { submitEmail(any()) } doReturn "job-x" }
        val delays = mutableListOf<Long>()

        val submitted = IntakeLoop(gmail, bridge, batchSize = 3, interSubmitDelayMs = 15_000, sleep = { delays.add(it) }).pollOnce()

        assertEquals(2, submitted)
        verify(bridge).submitEmail(argThat { messageId == "jd1" })
        verify(bridge).submitEmail(argThat { messageId == "jd2" })
        verify(gmail).labelEmail(eq("jd1"), eq("proc-id"))
        verify(gmail).labelEmail(eq("jd2"), eq("proc-id"))
        // Skipped replies are left exactly as they were: no label, no read-state change.
        verify(gmail, never()).labelEmail(argThat { startsWith("r") }, any())
        verify(stub.messages, never()).modify(any(), any(), any())
        assertEquals(listOf(15_000L), delays)
    }

    @Test
    @DisplayName("a labeling failure still counts the submit (bridge already has the work item)")
    fun labelFailureStillCountsSubmit() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails(any()) } doReturn listOf(email("a"))
            on { getOrCreateLabel(any()) } doReturn "proc-id"
        }
        doThrow(RuntimeException("gmail 500")).whenever(gmail).labelEmail(any(), any())
        val bridge = mock<PollerBridgeClient> { on { submitEmail(any()) } doReturn "job-a" }

        assertEquals(1, IntakeLoop(gmail, bridge).pollOnce())
    }
}
