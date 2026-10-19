package com.jd.pipeline.pipeline

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The Gmail terminal-label decision the Processor emits (and the Poller applies).
 * Order/precedence matters: error > recruiter > digest > not-a-job > processed.
 */
class TerminalLabelTest {

    private fun email(isDigest: Boolean = false, isInlineDigest: Boolean = false) =
        IntakeContext.Email(
            emailId = "e1", subject = "s", from = "f@x.com",
            rawBody = "b", htmlBody = "",
            isRecruiter = false, isDigest = isDigest, isInlineDigest = isInlineDigest,
        )

    @Test
    fun `error wins over everything`() {
        val s = JDState(isJobPosting = true, isRecruiterResponseRequired = true, error = "boom", intake = email(isDigest = true))
        assertEquals(TerminalLabel.JD_ERROR, TerminalLabel.forState(s))
    }

    @Test
    fun `recruiter response required`() {
        val s = JDState(isJobPosting = true, isRecruiterResponseRequired = true)
        assertEquals(TerminalLabel.RECRUITER, TerminalLabel.forState(s))
    }

    @Test
    fun `application update wins over recruiter response`() {
        val s = JDState(isApplicationUpdate = true, isRecruiterResponseRequired = true)
        assertEquals(TerminalLabel.APPLICATION_UPDATE, TerminalLabel.forState(s))
    }

    @Test
    fun `digest email (or inline digest)`() {
        assertEquals(TerminalLabel.JD_PROCESSED_DIGEST, TerminalLabel.forState(JDState(isJobPosting = true, intake = email(isDigest = true))))
        assertEquals(TerminalLabel.JD_PROCESSED_DIGEST, TerminalLabel.forState(JDState(isJobPosting = true, intake = email(isInlineDigest = true))))
    }

    @Test
    fun `not a job posting`() {
        assertEquals(TerminalLabel.JD_NOT_FOUND, TerminalLabel.forState(JDState(isJobPosting = false)))
    }

    @Test
    fun `normal processed job`() {
        assertEquals(TerminalLabel.JD_PROCESSED, TerminalLabel.forState(JDState(isJobPosting = true)))
    }
}
