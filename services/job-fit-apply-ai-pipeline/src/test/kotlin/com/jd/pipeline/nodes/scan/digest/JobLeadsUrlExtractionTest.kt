package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * URL extraction for JobLeads digests.
 *
 * Regression context: JobLeads changed their digest template around 2026-09, replacing the
 * `View job:` anchor with `View full details and apply`. The old regex was anchored on the
 * literal old text, so it stopped matching entirely — 0 URLs for 87 emails — leaving every
 * child job with an empty `jobUrl`. With no URL there is no scrape, so the extracted text was
 * just the card header (95 chars), the bridge rejected it with HTTP 422 (`jd_text must be at
 * least 150 characters`), and the parent was labelled `JD_Error`.
 *
 * The fix tries both known anchors first, then falls back to matching the `/job/` path
 * structurally so the next template change degrades gracefully instead of silently failing.
 */
@DisplayName("JobLeads URL extraction")
class JobLeadsUrlExtractionTest {

    private val JOB_A = "https://www.jobleads.com/job/ed1962e14d440e73609234c0a921aeafe?kuuid=abc&utm_source=x"
    private val JOB_B = "https://www.jobleads.com/job/e22b4501b802295f6dfd20f394a815ac5?kuuid=def&saved=true"

    @Nested
    @DisplayName("anchored templates")
    inner class Anchored {

        @Test
        @DisplayName("old template: 'View job:' anchor still works (no regression)")
        fun oldTemplate() {
            val body = "Some blurb\nView job: $JOB_A\nmore text"
            assertEquals(listOf(JOB_A), JobLeadsDigestStrategy.extractJobUrls(body))
        }

        @Test
        @DisplayName("new template: 'View full details and apply' anchor is matched")
        fun newTemplate() {
            // Reproduces the real body shape: no whitespace between the anchor and the URL.
            val body = "USD 120,000 - 170,000- full_timeView full details and apply$JOB_A---"
            assertEquals(listOf(JOB_A), JobLeadsDigestStrategy.extractJobUrls(body))
        }

        @Test
        @DisplayName("new template with whitespace after the anchor is also matched")
        fun newTemplateWithSpace() {
            val body = "View full details and apply $JOB_A"
            assertEquals(listOf(JOB_A), JobLeadsDigestStrategy.extractJobUrls(body))
        }

        @Test
        @DisplayName("both anchors present yields both URLs in order")
        fun bothAnchors() {
            val body = "View job: $JOB_A\nView full details and apply $JOB_B"
            assertEquals(listOf(JOB_A, JOB_B), JobLeadsDigestStrategy.extractJobUrls(body))
        }

        @Test
        @DisplayName("multiple old-format entries stay aligned to their cards")
        fun multipleOldEntries() {
            val body = (1..5).joinToString("\n") { "View job: https://www.jobleads.com/job/$it" }
            val urls = JobLeadsDigestStrategy.extractJobUrls(body)
            assertEquals(5, urls.size)
            assertEquals("https://www.jobleads.com/job/1", urls.first())
            assertEquals("https://www.jobleads.com/job/5", urls.last())
        }
    }

    @Nested
    @DisplayName("structural fallback")
    inner class Fallback {

        @Test
        @DisplayName("recovers the URL when the anchor text has changed again")
        fun unknownAnchor() {
            // A hypothetical future template: no known anchor, but the posting URL is present.
            val body = "Your match today!  See the role here$JOB_A and apply soon."
            assertEquals(listOf(JOB_A), JobLeadsDigestStrategy.extractJobUrls(body))
        }

        @Test
        @DisplayName("ignores JobLeads newsletter links that are not job postings")
        fun ignoresNonPostingLinks() {
            // These all appear in real JobLeads mail and must never be picked as a job.
            val body = """
                Was this a good match? [Yes] https://www.jobleads.com/us/jobs?email-action=feedback-positive&job_rank=1
                [No] https://www.jobleads.com/us/jobs?email-action=feedback-negative&job_rank=1
                Upload resumehttps://www.jobleads.com/us/jobs?email-action=resume-update
                Unsubscribe https://www.jobleads.com/manage-email-notifications?utm_source=
                Help https://support.jobleads.com
            """.trimIndent()
            assertEquals(
                emptyList(),
                JobLeadsDigestStrategy.extractJobUrls(body),
                "only /job/ URLs are postings; feedback/resume/optout links must be excluded",
            )
        }

        @Test
        @DisplayName("picks only the posting when posting and newsletter links are mixed")
        fun mixedLinks() {
            val body = """
                Senior QA Engineer - Bothell, WA- hybrid- USD 120,000View full details and apply$JOB_A
                Was this a good match? [Yes] https://www.jobleads.com/us/jobs?email-action=feedback-positive
                Unsubscribe https://www.jobleads.com/manage-email-notifications
            """.trimIndent()
            assertEquals(listOf(JOB_A), JobLeadsDigestStrategy.extractJobUrls(body))
        }

        @Test
        @DisplayName("stops at a closing quote/bracket rather than swallowing markup")
        fun stopsAtDelimiter() {
            val body = """<a href="$JOB_A">click</a>"""
            val urls = JobLeadsDigestStrategy.extractJobUrls(body)
            assertEquals(listOf(JOB_A), urls)
        }
    }

    @Nested
    @DisplayName("cleaning and edge cases")
    inner class Cleaning {

        @Test
        @DisplayName("decodes &amp; so the URL is usable")
        fun decodesHtmlEntity() {
            val body = "View full details and applyhttps://www.jobleads.com/job/abc?kuuid=1&amp;utm_source=x"
            val url = JobLeadsDigestStrategy.extractJobUrls(body).single()
            assertTrue(url.contains("&utm_source=x"), "should be decoded to '&', got: $url")
            assertTrue(!url.contains("&amp;"), "raw entity leaked into the URL: $url")
        }

        @Test
        @DisplayName("strips trailing punctuation glued to the URL")
        fun stripsTrailingPunctuation() {
            val body = "View full details and applyhttps://www.jobleads.com/job/abc?x=1."
            val url = JobLeadsDigestStrategy.extractJobUrls(body).single()
            assertTrue(url.endsWith("x=1"), "trailing period should be stripped: $url")
        }

        @Test
        @DisplayName("strips the '---' section divider JobLeads glues to the URL")
        fun stripsSectionDivider() {
            // Real bodies end `...View full details and apply<url>---` with no separator, and the
            // hyphens are a section divider rather than part of the URL.
            val body = "USD 120,000- full_timeView full details and apply$JOB_A---"
            val url = JobLeadsDigestStrategy.extractJobUrls(body).single()
            assertTrue(!url.endsWith("-"), "divider hyphens leaked into the URL: $url")
            assertEquals(JOB_A, url)
        }

        @Test
        @DisplayName("keeps a single trailing hyphen (a legitimate slug character)")
        fun keepsSingleHyphen() {
            val body = "View job: https://www.jobleads.com/job/some-slug-"
            val url = JobLeadsDigestStrategy.extractJobUrls(body).single()
            assertTrue(url.endsWith("some-slug-"), "a lone hyphen may be part of the slug: $url")
        }

        @Test
        @DisplayName("no job URLs yields an empty list, not a malformed entry")
        fun nothingToFind() {
            assertEquals(emptyList(), JobLeadsDigestStrategy.extractJobUrls("no links at all"))
            assertEquals(emptyList(), JobLeadsDigestStrategy.extractJobUrls(""))
        }
    }

    @Nested
    @DisplayName("against the real reported email")
    inner class RealFixture {

        private fun fixture(): String =
            javaClass.getResourceAsStream("/jobleads/new-template-teaser.txt")
                ?.bufferedReader()?.readText()
                ?: error("fixture /jobleads/new-template-teaser.txt missing")

        @Test
        @DisplayName("extracts the posting URL from the real teaser that produced JD_Error")
        fun extractsFromRealEmail() {
            val body = fixture()
            val urls = JobLeadsDigestStrategy.extractJobUrls(body)

            assertEquals(1, urls.size, "the real email contains exactly one posting link")
            val url = urls.single()
            assertTrue(
                url.startsWith("https://www.jobleads.com/job/"),
                "should be the /job/ posting URL, got: $url",
            )
            // The bug: this was empty, so no scrape ran and the child 422'd.
            assertTrue(url.length > 40, "URL looks truncated: $url")
        }

        @Test
        @DisplayName("does not mistake the newsletter feedback/unsubscribe links for the posting")
        fun ignoresRealNonPostingLinks() {
            val url = JobLeadsDigestStrategy.extractJobUrls(fixture()).single()
            assertTrue(!url.contains("email-action"), "picked a feedback link: $url")
            assertTrue(!url.contains("manage-email-notifications"), "picked the optout link: $url")
            assertTrue(!url.contains("/us/jobs"), "picked a newsletter link: $url")
        }

        @Test
        @DisplayName("decodes the trailing tracking params into a usable single URL")
        fun urlIsUsable() {
            val url = JobLeadsDigestStrategy.extractJobUrls(fixture()).single()
            assertTrue(!url.contains("&amp;"), "raw HTML entity left in URL: $url")
            assertTrue(!url.contains(" "), "URL contains whitespace: $url")
            assertTrue(!url.endsWith("."), "URL has trailing punctuation: $url")
        }
    }

    @Nested
    @DisplayName("end-to-end digest expansion with the new template")
    inner class Expansion {

        private val baseEmail = IntakeContext.Email(
            emailId = "jl-1", from = "noreply@jobleads.com", subject = "Top pick today",
            rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
        )
        private val parent = JDState(intake = baseEmail)

        private fun email(rawBody: String, htmlBody: String) = IntakeContext.Email(
            emailId = "jl-1", from = "noreply@jobleads.com", subject = "Top pick today",
            rawBody = rawBody, htmlBody = htmlBody, isRecruiter = false, isDigest = false,
            isInlineDigest = false,
        )

        private fun card(title: String, company: String, location: String, salary: String = "") =
            """
            <table><tbody><tr>
            <td style="padding: 16px">
              <div>$title</div>
              <div>$company</div>
              <div>$location</div>
              ${if (salary.isNotBlank()) "<div>$salary</div>" else ""}
            </td>
            </tr></tbody></table>
            """.trimIndent()

        @Test
        @DisplayName("new-template email yields a card WITH its job URL (the reported bug)")
        fun newTemplateCarriesUrl() {
            val html = "<html><body>${card("Senior Automation QA Engineer", "Tech Asst", "Bothell, WA", "USD 120,000 - 170,000")}</body></html>"
            val body = "USD 120,000 - 170,000- full_timeView full details and apply$JOB_A---"
            val jobs = JobLeadsDigestStrategy.expand(parent, email(body, html))

            assertEquals(1, jobs.size)
            assertEquals(JOB_A, jobs[0].jobUrl, "the whole JD_Error chain started with this being empty")
            assertTrue(jobs[0].jobUrl.isNotBlank(), "an empty jobUrl means no scrape and a 422 downstream")
        }

        @Test
        @DisplayName("old-template email is unaffected")
        fun oldTemplateUnaffected() {
            val html = "<html><body>${card("Staff SDET", "Acme", "Remote")}</body></html>"
            val jobs = JobLeadsDigestStrategy.expand(parent, email("View job: $JOB_A", html))
            assertEquals(1, jobs.size)
            assertEquals(JOB_A, jobs[0].jobUrl)
        }

        @Test
        @DisplayName("mixed-template digest keeps URLs aligned to their cards")
        fun mixedTemplatesAlign() {
            val html = "<html><body>${card("Role One", "Corp A", "Remote")}${card("Role Two", "Corp B", "Seattle, WA")}</body></html>"
            val body = "View job: $JOB_A\nView full details and apply$JOB_B"
            val jobs = JobLeadsDigestStrategy.expand(parent, email(body, html))
            assertEquals(2, jobs.size)
            assertEquals(JOB_A, jobs[0].jobUrl)
            assertEquals(JOB_B, jobs[1].jobUrl)
        }

        @Test
        @DisplayName("mixed templates are matched in DOCUMENT order, not grouped by anchor")
        fun mixedTemplatesKeepDocumentOrder() {
            // New-template entry appears FIRST in the body. Collecting all old-anchor matches
            // before the new-anchor ones would swap these and attach the wrong URL to each job.
            val html = "<html><body>${card("Role One", "Corp A", "Remote")}${card("Role Two", "Corp B", "Seattle, WA")}</body></html>"
            val body = "View full details and apply$JOB_B\nView job: $JOB_A"
            val jobs = JobLeadsDigestStrategy.expand(parent, email(body, html))
            assertEquals(2, jobs.size)
            assertEquals(JOB_B, jobs[0].jobUrl, "card 1 must get the URL that appeared first in the body")
            assertEquals(JOB_A, jobs[1].jobUrl, "card 2 must get the second URL")
        }
    }
}
