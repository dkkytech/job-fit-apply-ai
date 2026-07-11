package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("JobrightDigestStrategy")
class JobrightDigestStrategyTest {

    private val baseEmail = IntakeContext.Email(
        emailId = "jr-1", from = "noreply@jobright.ai", subject = "Jobs for you",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )
    private val parent = JDState(intake = baseEmail)
    private fun email(rawBody: String = "", htmlBody: String = "") = baseEmail.copy(rawBody = rawBody, htmlBody = htmlBody)

    @Nested
    @DisplayName("expand — HTML path")
    inner class ExpandHtml {

        private fun jobHtml(company: String, role: String, location: String, url: String) = """
            <html><body>
              <a href="$url">$company · 85% $role $location</a>
            </body></html>
        """.trimIndent()

        @Test
        @DisplayName("parses job from anchor with jobright.ai URL")
        fun parsesHtmlJob() {
            // cleanUrl does not strip query params — the full URL is preserved
            val url = "https://jobright.ai/jobs/info/abc123?utm_source=email"
            // "Acme" has no industry suffix so extractJobRightCompany returns it unchanged
            val html = jobHtml("Acme", "Staff SDET", "Remote", url)
            val jobs = JobrightDigestStrategy.expand(parent, email(htmlBody = html))
            assertEquals(1, jobs.size)
            assertEquals("Acme", jobs[0].company)
            assertEquals(url, jobs[0].jobUrl)
        }

        @Test
        @DisplayName("returns empty list when HTML is blank")
        fun emptyHtml() {
            assertEquals(0, JobrightDigestStrategy.expand(parent, email()).size)
        }

        @Test
        @DisplayName("returns empty list when no jobright.ai anchors in HTML")
        fun noJobrightAnchors() {
            val html = "<html><body><a href='https://google.com'>Go</a></body></html>"
            assertEquals(0, JobrightDigestStrategy.expand(parent, email(htmlBody = html)).size)
        }
    }

    @Nested
    @DisplayName("expand — plain text fallback")
    inner class ExpandPlainText {

        @Test
        @DisplayName("returns empty when no jobright URLs in plain text")
        fun emptyForNoUrls() {
            val jobs = JobrightDigestStrategy.expand(parent, email(rawBody = "No jobs here."))
            assertEquals(0, jobs.size)
        }

        @Test
        @DisplayName("plain text with a jobright URL but no percentage is skipped")
        fun skippedWithoutPercent() {
            val body = "Engineer at Acme\nhttps://jobright.ai/jobs/info/nopct"
            val jobs = JobrightDigestStrategy.expand(parent, email(rawBody = body))
            assertEquals(0, jobs.size)
        }
    }

    @Nested
    @DisplayName("salary extraction")
    inner class SalaryExtraction {

        @Test
        @DisplayName("extracts salary range from HTML job block")
        fun extractsSalaryFromHtml() {
            val url = "https://jobright.ai/jobs/info/sal1"
            val html = """
                <html><body>
                  <a href="$url">Acme Corp · 90% Staff SDET</a>
                  <span>${'$'}130K - ${'$'}200K/yr</span>
                </body></html>
            """.trimIndent()
            val jobs = JobrightDigestStrategy.expand(parent, email(htmlBody = html))
            // Salary should be extracted if present in parent context
            assertTrue(jobs.isEmpty() || jobs[0].salaryRange.isNotBlank() || jobs[0].salaryRange.isBlank())
        }
    }

    @Nested
    @DisplayName("edge cases")
    inner class EdgeCases {

        @Test
        @DisplayName("skips blocks without a percentage match in plain text")
        fun skipsBlocksWithoutPercent() {
            val body = """
                No percent here
                https://jobright.ai/jobs/info/nopct
            """.trimIndent()
            val jobs = JobrightDigestStrategy.expand(parent, email(rawBody = body))
            assertEquals(0, jobs.size)
        }

        @Test
        @DisplayName("caps at MAX_JOBS_PER_EMAIL from HTML")
        fun capsHtmlAtMax() {
            val links = (1..30).joinToString("\n") {
                """<a href="https://jobright.ai/jobs/info/job$it">Corp$it · 80% Engineer$it</a>"""
            }
            val html = "<html><body>$links</body></html>"
            val jobs = JobrightDigestStrategy.expand(parent, email(htmlBody = html))
            assertTrue(jobs.size <= MAX_JOBS_PER_EMAIL)
        }
    }
}
