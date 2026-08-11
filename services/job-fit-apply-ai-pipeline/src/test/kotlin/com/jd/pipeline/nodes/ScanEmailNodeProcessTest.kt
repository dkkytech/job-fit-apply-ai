package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral tests for [ScanEmailNode.process] — the recruiter LLM path and the job-board
 * URL-extraction fallback. The LLM is injected via [LlmCaller] so the tests exercise the real
 * classification/parsing logic without a live backend.
 */
@DisplayName("ScanEmailNode.process")
class ScanEmailNodeProcessTest {

    /** Records the prompt handed to the LLM and returns a canned response. */
    private class RecordingLlm(val response: String) : LlmCaller {
        var lastPrompt: String? = null
        override fun call(prompt: String): String {
            lastPrompt = prompt
            return response
        }
    }

    private fun recruiterEmail(
        subject: String = "Senior QA Engineer role at Acme",
        rawBody: String = "We have an exciting engineer position. Requirements: 5 yoe.",
        htmlBody: String = "",
        from: String = "jane@acme-recruiting.com",
    ) = JDState(
        intake = IntakeContext.Email(
            emailId = "r-1", subject = subject, from = from,
            rawBody = rawBody, htmlBody = htmlBody,
            isRecruiter = false, isDigest = false, isInlineDigest = false,
        )
    )

    private fun boardEmail(from: String, rawBody: String) = JDState(
        intake = IntakeContext.Email(
            emailId = "b-1", subject = "Jobs for you", from = from,
            rawBody = rawBody, htmlBody = "",
            isRecruiter = false, isDigest = false, isInlineDigest = false,
        )
    )

    @Nested
    @DisplayName("recruiter LLM path")
    inner class RecruiterPath {

        @Test
        @DisplayName("parses a well-formed JSON job posting into all fields")
        fun parsesJobPosting() {
            val json = """
                {"is_job_posting": true, "job_url": "https://acme.com/jobs/qa-42",
                 "jd_text": "Test everything.", "company": "Acme", "role_title": "Senior QA Engineer",
                 "location": "Remote", "remote_policy": "remote", "yoe_required": 5,
                 "tech_stack": ["Kotlin", "Selenium"]}
            """.trimIndent()
            val result = ScanEmailNode(llm = RecordingLlm(json)).process(recruiterEmail())

            assertTrue(result.isJobPosting)
            assertEquals("https://acme.com/jobs/qa-42", result.jobUrl)
            assertEquals("Test everything.", result.jdText)
            assertEquals("Acme", result.company)
            assertEquals("Senior QA Engineer", result.roleTitle)
            assertEquals("Remote", result.location)
            assertEquals("remote", result.remotePolicy)
            assertEquals(5, result.yoeRequired)
            assertEquals(listOf("Kotlin", "Selenium"), result.techStack)
        }

        @Test
        @DisplayName("strips markdown code fences before parsing")
        fun stripsMarkdownFences() {
            val json = "```json\n{\"is_job_posting\": true, \"company\": \"Beta\"}\n```"
            val result = ScanEmailNode(llm = RecordingLlm(json)).process(recruiterEmail())

            assertTrue(result.isJobPosting)
            assertEquals("Beta", result.company)
        }

        @Test
        @DisplayName("applies defaults for missing fields")
        fun appliesDefaultsForMissingFields() {
            val result = ScanEmailNode(llm = RecordingLlm("""{"is_job_posting": true}""")).process(recruiterEmail())

            assertEquals("Unknown", result.company)
            assertEquals("Unknown", result.roleTitle)
            assertEquals("Unknown", result.location)
            assertEquals("unknown", result.remotePolicy)
            assertNull(result.yoeRequired)
            assertTrue(result.techStack.isEmpty())
        }

        @Test
        @DisplayName("treats the literal string \"null\" job_url as blank and keeps the existing url")
        fun literalNullJobUrlFallsBackToInput() {
            val input = recruiterEmail().copy(jobUrl = "https://existing.example/job/1")
            val result = ScanEmailNode(llm = RecordingLlm("""{"is_job_posting": true, "job_url": "null"}""")).process(input)

            assertEquals("https://existing.example/job/1", result.jobUrl)
        }

        @Test
        @DisplayName("is_job_posting false yields a skipped, non-posting state")
        fun notAJobPosting() {
            val result = ScanEmailNode(llm = RecordingLlm("""{"is_job_posting": false}""")).process(recruiterEmail())

            assertFalse(result.isJobPosting)
            assertEquals("Not a job posting", result.skippedReason)
        }

        @Test
        @DisplayName("invalid JSON from the LLM is captured as a parse error, not a crash")
        fun invalidJsonBecomesError() {
            val result = ScanEmailNode(llm = RecordingLlm("this is not json")).process(recruiterEmail())

            assertFalse(result.isJobPosting)
            assertTrue(result.error.contains("JSON parse failed"), "error was: ${result.error}")
        }

        @Test
        @DisplayName("an LLM exception is caught and recorded on the state")
        fun llmExceptionIsCaught() {
            val throwing = LlmCaller { error("backend down") }
            val result = ScanEmailNode(llm = throwing).process(recruiterEmail())

            assertFalse(result.isJobPosting)
            assertTrue(result.error.contains("backend down"), "error was: ${result.error}")
        }

        @Test
        @DisplayName("skips the LLM entirely when no job-signal keywords are present")
        fun skipsLlmWithoutJobSignals() {
            val throwing = LlmCaller { error("LLM must not be called") }
            val input = recruiterEmail(subject = "Lunch tomorrow?", rawBody = "See you at noon.")
            val result = ScanEmailNode(llm = throwing).process(input)

            assertFalse(result.isJobPosting)
            assertEquals("Not a job posting", result.skippedReason)
        }

        @Test
        @DisplayName("passes hidden JSON-LD script content to the LLM alongside the visible body")
        fun forwardsHiddenContent() {
            val html = """
                <html><body>
                  <p>Apply now for this engineer role.</p>
                  <script type="application/ld+json">{"title":"Hidden SDET","hiring":"secret payload"}</script>
                </body></html>
            """.trimIndent()
            val llm = RecordingLlm("""{"is_job_posting": true}""")
            ScanEmailNode(llm = llm).process(recruiterEmail(htmlBody = html))

            val prompt = llm.lastPrompt!!
            assertTrue(prompt.contains("HIDDEN_OR_NONVISIBLE_EMAIL_CONTENT"), "prompt: $prompt")
            assertTrue(prompt.contains("secret payload"))
        }
    }

    @Nested
    @DisplayName("job-board URL-extraction fallback")
    inner class BoardUrlFallback {

        // lever.co routes to the "ats" group whose strategy returns no structured jobs,
        // so the node falls through to raw URL extraction.
        private val throwing = LlmCaller { error("LLM must not be called for a job-board email") }

        @Test
        @DisplayName("extracts eligible job URLs from an ATS board email and flags it as a digest")
        fun extractsBoardUrls() {
            val body = """
                <a href="https://jobs.lever.co/acme/123/apply">Apply</a>
                <a href="https://jobs.lever.co/acme/unsubscribe">Unsubscribe</a>
                Plain link: https://jobs.lever.co/beta/careers/456
            """.trimIndent()
            val result = ScanEmailNode(llm = throwing).process(boardEmail("careers@lever.co", body))

            assertTrue(result.isDigest)
            assertTrue(result.isJobPosting)
            val urls = result.digestJobs.map { it.jobUrl }.toSet()
            assertTrue(urls.contains("https://jobs.lever.co/acme/123/apply"), "urls: $urls")
            assertTrue(urls.contains("https://jobs.lever.co/beta/careers/456"), "urls: $urls")
            assertFalse(urls.any { it.contains("unsubscribe") }, "unsubscribe link should be filtered: $urls")
        }

        @Test
        @DisplayName("de-duplicates a URL that appears in both an href and as plain text")
        fun deduplicatesUrls() {
            val url = "https://jobs.lever.co/acme/789/apply"
            val body = """<a href="$url">Apply</a> and again: $url"""
            val result = ScanEmailNode(llm = throwing).process(boardEmail("careers@lever.co", body))

            assertEquals(1, result.digestJobs.count { it.jobUrl == url })
        }

        @Test
        @DisplayName("board email with no eligible URLs is marked non-posting with a reason")
        fun noUrlsFound() {
            val result = ScanEmailNode(llm = throwing).process(
                boardEmail("careers@lever.co", "Thanks for subscribing. Manage your preferences.")
            )

            assertFalse(result.isJobPosting)
            assertTrue(result.isDigest)
            assertTrue(result.skippedReason.contains("no job URLs"), "reason: ${result.skippedReason}")
        }
    }
}
