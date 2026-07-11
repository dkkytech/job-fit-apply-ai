package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("GlassdoorDigestStrategy")
class GlassdoorDigestStrategyTest {

    private val parent = JDState()
    private val baseEmail = IntakeContext.Email(
        emailId = "gd-1", from = "noreply@glassdoor.com", subject = "Jobs for you",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )

    // ── extractGlassdoorJobUrls (public helper) ──────────────────────────────

    @Nested
    @DisplayName("extractGlassdoorJobUrls")
    inner class ExtractJobUrls {

        @Test
        @DisplayName("extracts partner jobListing URLs")
        fun extractsPartnerUrls() {
            val body = """
                Check out these jobs:
                https://www.glassdoor.com/partner/jobListing.htm?pos=1&ao=123&rdforyou=true
                https://www.glassdoor.com/partner/jobListing.htm?pos=2&ao=456&rdforyou=true
            """.trimIndent()
            val urls = GlassdoorDigestStrategy.extractGlassdoorJobUrls(body)
            assertEquals(2, urls.size)
            assertTrue(urls.all { it.contains("/partner/jobListing.htm") })
        }

        @Test
        @DisplayName("returns empty list when no matching URLs found")
        fun emptyWhenNoUrls() {
            assertEquals(emptyList(), GlassdoorDigestStrategy.extractGlassdoorJobUrls("No jobs here"))
        }

        @Test
        @DisplayName("deduplicates repeated URLs")
        fun deduplicates() {
            val url = "https://www.glassdoor.com/partner/jobListing.htm?pos=1&ao=1&rdforyou=true"
            val body = "$url\n$url"
            val urls = GlassdoorDigestStrategy.extractGlassdoorJobUrls(body)
            assertEquals(1, urls.size)
        }

        @Test
        @DisplayName("caps at MAX_JOBS_PER_EMAIL")
        fun capsAtMax() {
            val body = (1..30).joinToString("\n") {
                "https://www.glassdoor.com/partner/jobListing.htm?pos=$it&ao=$it&rdforyou=true"
            }
            val urls = GlassdoorDigestStrategy.extractGlassdoorJobUrls(body)
            assertTrue(urls.size <= MAX_JOBS_PER_EMAIL)
        }
    }

    // ── expand via HTML ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("expand — HTML parsing path")
    inner class ExpandHtml {

        private fun htmlWithJob(company: String, role: String, location: String, salary: String, url: String) = """
            <html><body>
            <a href="$url">
                <p>$company</p>
                <p>$role</p>
                <p>$location</p>
                <p>$salary</p>
            </a>
            </body></html>
        """.trimIndent()

        @Test
        @DisplayName("returns empty list when HTML is blank")
        fun emptyHtml() {
            val email = baseEmail.copy(htmlBody = "")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(0, jobs.size)
        }

        @Test
        @DisplayName("returns empty list when HTML has no jobListing anchors")
        fun noJobListingAnchors() {
            val email = baseEmail.copy(htmlBody = "<html><body><a href='https://glassdoor.com/home'>Home</a></body></html>")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(0, jobs.size)
        }

        @Test
        @DisplayName("parses job from HTML anchor with jobListing URL")
        fun parsesHtmlJob() {
            val url = "https://www.glassdoor.com/partner/jobListing.htm?pos=1&ao=1&rdforyou=true"
            val email = baseEmail.copy(
                htmlBody = htmlWithJob("Acme Corp", "Staff SDET", "Seattle, WA", "\$120K–\$180K (Employer est.)", url)
            )
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertTrue(jobs.isNotEmpty())
            assertTrue(jobs[0].isJobPosting)
        }
    }

    // ── expand via plain text ─────────────────────────────────────────────────

    @Nested
    @DisplayName("expand — plain text fallback")
    inner class ExpandPlainText {

        @Test
        @DisplayName("returns empty when no Glassdoor URLs in plain text and HTML is blank")
        fun emptyForUnknownInput() {
            val email = baseEmail.copy(rawBody = "No jobs here.")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(0, jobs.size)
        }
    }
}
