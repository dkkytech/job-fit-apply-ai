package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isRecruiterEmail
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
        @DisplayName("an interview confirmation with the JD attached is an application update, not a posting")
        fun interviewConfirmationIsApplicationUpdate() {
            // Shape of the Costco Travel emails that were re-tailored each interview round.
            val json = """
                {"is_job_posting": true, "is_application_update": true, "company": "Costco Travel",
                 "role_title": "Quality Engineer - Engineering Productivity", "jd_text": "Position Summary ..."}
            """.trimIndent()
            val result = ScanEmailNode(llm = RecordingLlm(json)).process(
                recruiterEmail(subject = "Costco Travel - (Round 2 Panel Interview) Quality Engineer - MSFT Teams Interview",
                    rawBody = "You are confirmed for a MSFT Teams video interview. The job description is attached below.")
            )

            assertTrue(result.isApplicationUpdate)
            assertTrue(!result.isJobPosting, "an application update must not be processed as a posting")
            assertTrue(!result.isRecruiterEmail, "must not take the recruiter tailor-and-reply path")
            assertTrue(result.skippedReason.startsWith("Application update"))
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
        @DisplayName("an unparseable reply is retried once and the retry's JSON is used")
        fun unparseableReplyIsRetriedOnce() {
            // glm-5.2 occasionally drops the opening `{"` of its JSON (Randstad, Lead .NET —
            // 2026-10-05/06). Re-asking the same prompt parses; a JD_Error does not.
            val replies = ArrayDeque(listOf(
                "is_job_posting\": true, \"role_title\": \"Lead .NET Developer\"}",
                """{"is_job_posting": true, "role_title": "Lead .NET Developer"}""",
            ))
            var calls = 0
            val llm = LlmCaller { calls++; replies.removeFirst() }

            val result = ScanEmailNode(llm = llm).process(recruiterEmail())

            assertEquals(2, calls)
            assertEquals("", result.error)
            assertTrue(result.isJobPosting)
            assertEquals("Lead .NET Developer", result.roleTitle)
        }

        @Test
        @DisplayName("a reply that lost its opening brace is a parse error, not a non-posting")
        fun headlessJsonIsAParseError() {
            // Single-line, so Jackson reads `": true, "` as a bare string instead of failing.
            val result = ScanEmailNode(llm = RecordingLlm(""": true, "role_title": "SDET"}""")).process(recruiterEmail())

            assertTrue(result.error.contains("JSON parse failed"), "error was: ${result.error}")
        }

        @Test
        @DisplayName("a parseable reply is not retried")
        fun parseableReplyIsNotRetried() {
            var calls = 0
            val llm = LlmCaller { calls++; """{"is_job_posting": false}""" }

            ScanEmailNode(llm = llm).process(recruiterEmail())

            assertEquals(1, calls)
        }

        @Test
        @DisplayName("a Dice Private Email relay goes through recruiter extraction, not the Dice digest")
        fun diceRelayIsARecruiterEmail() {
            // `<alias>@user.dice.com` is one recruiter's message with the JD in the body. Read as a
            // Dice digest it became a stub child the bridge rejected (422) — JD_Error.
            val llm = RecordingLlm("""{"is_job_posting": true, "role_title": "SDET", "jd_text": "Design automation frameworks"}""")

            val result = ScanEmailNode(llm = llm).process(recruiterEmail(
                subject = "Immediate opening for SDET @ Plano, TX",
                rawBody = "This is a trusted Dice Private Email. Job Title: SDET. Job Type: Contract role.",
                from = "Anuj Verma <pap-hi0-sah@user.dice.com>",
            ))

            assertTrue(llm.lastPrompt != null, "the recruiter LLM path must run")
            assertFalse(result.isDigest)
            assertTrue(result.isJobPosting)
            assertTrue(result.isRecruiterEmail)
            assertEquals("SDET", result.roleTitle)
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
        @DisplayName("a Workday candidate-account activation link is not a job")
        fun workdayActivationLinkIsNotAJob() {
            // "Verify your candidate account" (2026-10-05): the activation link passed the workday
            // URL rule, was scraped as a job, and hit the sign-in wall.
            val body = "Activate your account: https://premera.wd5.myworkdayjobs.com/premera/activate/" +
                "mlbauv8q7b5lfp4ts3y/?redirect=%2Fen-US%2Fpremera%2Fjob%2FTelecommuter%2FSoftware-Development-Engineer-IV_R29090%2Fapply"

            val result = ScanEmailNode(llm = throwing).process(boardEmail("premera@otp.workday.com", body))

            assertFalse(result.isJobPosting)
            assertTrue(result.digestJobs.isEmpty(), "jobs: ${result.digestJobs.map { it.jobUrl }}")
        }

        @Test
        @DisplayName("a posting whose slug starts with \"verify\" is still a job")
        fun verifySlugIsStillAJob() {
            val url = "https://jobs.lever.co/acme/verify-test-engineer-123/apply"
            val result = ScanEmailNode(llm = throwing).process(boardEmail("careers@lever.co", "Apply: $url"))

            assertEquals(listOf(url), result.digestJobs.map { it.jobUrl })
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

    @Nested
    @DisplayName("employer job alerts")
    inner class EmployerAlerts {

        private fun alert(subject: String, html: String) = JDState(
            intake = IntakeContext.Email(
                emailId = "a-1", subject = subject, from = "Microsoft Careers <donotreply@email.careers.microsoft.com>",
                rawBody = "", htmlBody = html, isRecruiter = false, isDigest = false, isInlineDigest = false,
            )
        )

        @Test
        @DisplayName("an alert becomes a digest of link-only children, with no LLM call")
        fun alertBecomesLinkOnlyDigest() {
            val llm = RecordingLlm("""{"is_job_posting": false}""")
            val html = """<p>New jobs match your profile</p>
                <a href="https://apply.careers.microsoft.com/careerhub/explore/jobs/1970393556874530?utm_source=position_notification">Senior SDET</a>"""
            val result = ScanEmailNode(llm = llm).process(alert("New jobs at Microsoft that match your profile", html))

            assertTrue(result.isDigest && result.isJobPosting)
            val child = result.digestJobs.single()
            assertTrue(child.submitAsUrl)
            assertEquals("watchlist:eightfold:apply.careers.microsoft.com:1970393556874530", child.postingKey)
            assertNull(llm.lastPrompt, "the alert is parsed, not sent to the LLM")
            // A one-link alert still fans out: its one-line summary is not a failed scrape.
            val disposition = com.jd.pipeline.pipeline.EmailResolution.classify(result)
            assertTrue(disposition is com.jd.pipeline.pipeline.EmailDisposition.ReEnqueueChildren, "$disposition")
        }

        @Test
        @DisplayName("other mail from the same sender is read as a direct email (it may be an application update)")
        fun nonAlertFallsBackToRecruiterPath() {
            val llm = RecordingLlm("""{"is_job_posting": false, "is_application_update": true, "jd_text": "", "company": "Microsoft", "role_title": "SDET"}""")
            val html = """<p>Thank you for applying to Senior SDET.</p>
                <a href="https://apply.careers.microsoft.com/careers/job/1970393556874530">View the job</a>"""
            val result = ScanEmailNode(llm = llm).process(alert("Thank you for applying to Senior SDET", html))

            assertTrue(llm.lastPrompt != null, "read by the LLM like any direct email")
            assertTrue(result.isApplicationUpdate)
            assertTrue(result.digestJobs.isEmpty())
        }

        @Test
        @DisplayName("a Workday tenant's job alert (<tenant>@myworkday.com) is parsed; its application mail is not")
        fun workdayAlertsAndApplicationMail() {
            fun workday(subject: String, html: String) = JDState(
                intake = IntakeContext.Email(
                    emailId = "w-1", subject = subject, from = "walmart@myworkday.com", rawBody = "", htmlBody = html,
                    isRecruiter = false, isDigest = false, isInlineDigest = false,
                )
            )
            val alertHtml = """<p>New jobs matching your job alert</p>
                <a href="https://walmart.wd5.myworkdayjobs.com/en-US/WalmartExternal/job/Bellevue-WA/Senior-QA-Engineer_R-2345678">Senior QA Engineer</a>"""
            val alert = ScanEmailNode(llm = RecordingLlm("""{"is_job_posting": false}""")).process(workday("Job Alert: QA Seattle", alertHtml))
            assertEquals("watchlist:workday:walmart/WalmartExternal:R-2345678", alert.digestJobs.single().postingKey)

            val llm = RecordingLlm("""{"is_job_posting": false, "is_application_update": true, "jd_text": "", "company": "Walmart", "role_title": "Senior QA Engineer"}""")
            val applied = ScanEmailNode(llm = llm).process(workday("Thanks for applying to Senior QA Engineer", alertHtml.replace("New jobs matching your job alert", "We received your application")))
            assertTrue(applied.isApplicationUpdate && applied.digestJobs.isEmpty())
        }
    }
}
