package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the digest-split scrape path so a posting is never scored on the one-line summary that
 * seeds each child's jdText — the "jobright.ai digests scored on thin JDs" report.
 *
 * The guard lives in score_fit rather than in the scraper on purpose. Blanking a thin jdText
 * upstream looks tidier but destroys the job: the bridge rejects a submit under 150 characters
 * (`Routes.kt`, `POST /api/jobs`), and `ProcessorCommandHandler` swallows that failure with a
 * `runCatching`, so a blanked digest child is dropped with no bridge job, no terminal label, no
 * run-log line and no retry. Refusing to SCORE it instead keeps the whole terminal path intact,
 * which is what makes the underlying scrape gap visible to the run-analyzer.
 */
@DisplayName("ScoreFitNode — digest stub-JD guard")
class ScoreFitDigestStubGuardTest {

    // Exactly what createParsedDigestJob seeds each child's jdText with. Kept faithful to a real
    // one (~211 chars, most of it the tracking-laden URL) — that length is the whole reason these
    // stubs clear the bridge's 150-char floor and reach score_fit, which is what this guard is for.
    private val digestSummary =
        "Test Automation Lead @ Qualitest | Remote | \$122K/yr - \$126K/yr | " +
            "https://jobright.ai/jobs/info/abc123def456?utm_source=digest&utm_medium=email" +
            "&utm_campaign=daily_jobs&utm_content=card_1&recommend=true"

    private fun digestState(jdText: String) = JDState(
        intake = IntakeContext.Email(
            emailId = "m1", from = "alerts@jobright.ai", subject = "5 new jobs",
            rawBody = "", htmlBody = "", isRecruiter = false,
            isDigest = true, isInlineDigest = false,
        ),
        isJobPosting = true,
        roleTitle = "Test Automation Lead",
        company = "Qualitest",
        jobUrl = "https://jobright.ai/jobs/info/abc123",
        jdText = jdText,
    )

    private fun strictNode() = ScoreFitNode(llm = LlmCaller { error("score_fit must not call the LLM here") })

    @Test
    @DisplayName("a digest child is not scored on the digest summary")
    fun digestStubIsNotScored() {
        val result = strictNode().process(digestState(digestSummary))

        assertEquals(PipelineAction.SKIP, result.pipelineAction)
        assertEquals(0f, result.fitScore)
        assertTrue(result.skippedReason.contains("digest summary"), "got: '${result.skippedReason}'")
    }

    @Test
    @DisplayName("the skipped child keeps its JD text so it stays above the bridge's 150-char floor")
    fun skippedChildKeepsItsText() {
        // Regression: an earlier fix blanked jdText in the scraper instead. The bridge rejects a
        // submit under 150 chars with a 422, and the digest-child submit swallows failures — so the
        // posting vanished entirely. The guard must not shorten the text, only decline to score it.
        val input = digestState(digestSummary)
        val result = strictNode().process(input)

        assertEquals(input.jdText, result.jdText, "score_fit must not mutate the JD text")
        assertTrue(result.jdText.length >= 150, "must stay submittable to the bridge")
        assertTrue(result.isJobPosting, "must stay a job posting so it is re-enqueued and labelled")
    }

    @Test
    @DisplayName("a digest child with a real scraped JD is scored normally")
    fun realDigestJdIsScored() {
        val fullJd = "Responsibilities: " + "Own the automation strategy. ".repeat(40)  // > 400 chars
        var called = false
        val node = ScoreFitNode(llm = LlmCaller {
            called = true
            """{"fit_score":78,"strengths":["Kotlin"],"gaps":[],"verdict":"good"}"""
        })

        val result = node.process(digestState(fullJd))

        assertTrue(called, "a substantive digest JD must still reach the LLM")
        assertEquals(fullJd, result.jdText)
    }

    @Test
    @DisplayName("a NON-digest job with a short JD is still scored — the guard is digest-scoped")
    fun shortNonDigestJdIsStillScored() {
        // The stub problem is specific to the digest fan-out seeding jdText with a summary. A short
        // JD arriving any other way is a real (if terse) posting and must not be silently skipped.
        var called = false
        val node = ScoreFitNode(llm = LlmCaller {
            called = true
            """{"fit_score":55,"strengths":[],"gaps":[],"verdict":"ok"}"""
        })
        val nonDigest = JDState(
            intake = IntakeContext.Email(
                emailId = "m2", from = "jobs@example.com", subject = "Role",
                rawBody = "", htmlBody = "", isRecruiter = false,
                isDigest = false, isInlineDigest = false,
            ),
            isJobPosting = true,
            jobUrl = "https://example.com/jobs/1",
            jdText = "Short but real posting.",
        )

        node.process(nonDigest)

        assertTrue(called, "a non-digest short JD must still be scored")
    }

    @Test
    @DisplayName("a blank JD still skips with the pre-existing reason")
    fun blankJdStillSkips() {
        val result = strictNode().process(digestState(""))

        assertEquals(PipelineAction.SKIP, result.pipelineAction)
        assertEquals("No JD text to score", result.skippedReason)
    }
}
