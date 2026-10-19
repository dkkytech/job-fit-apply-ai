package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("DigestParseHelpers")
class DigestParseHelpersTest {

    private fun emailState(rawBody: String = "", htmlBody: String = ""): JDState = JDState(
        intake = IntakeContext.Email(
            emailId = "test-id",
            from = "jobs@board.com",
            subject = "Job Digest",
            rawBody = rawBody,
            htmlBody = htmlBody,
            isRecruiter = false,
            isDigest = false,
            isInlineDigest = false,
        )
    )

    // ── inferRemotePolicy ────────────────────────────────────────────────────

    @Nested
    @DisplayName("inferRemotePolicy")
    inner class InferRemotePolicy {

        @Test
        @DisplayName("returns 'remote' when any value contains 'remote'")
        fun detectsRemote() {
            assertEquals("remote", inferRemotePolicy("Remote, US"))
            assertEquals("remote", inferRemotePolicy("", "Work remotely"))
            assertEquals("remote", inferRemotePolicy("San Francisco", "REMOTE ok"))
        }

        @Test
        @DisplayName("returns 'hybrid' when any value contains 'hybrid'")
        fun detectsHybrid() {
            assertEquals("hybrid", inferRemotePolicy("Hybrid schedule"))
            assertEquals("hybrid", inferRemotePolicy("New York", "hybrid 2 days"))
        }

        @Test
        @DisplayName("hybrid takes priority over remote when both present")
        fun hybridPriorityOverRemote() {
            assertEquals("hybrid", inferRemotePolicy("hybrid remote"))
        }

        @Test
        @DisplayName("returns 'onsite' for on-site and onsite variants")
        fun detectsOnsite() {
            assertEquals("onsite", inferRemotePolicy("On-site required"))
            assertEquals("onsite", inferRemotePolicy("onsite role"))
        }

        @Test
        @DisplayName("returns 'unknown' when no remote signal present")
        fun unknownDefault() {
            assertEquals("unknown", inferRemotePolicy("New York, NY"))
            assertEquals("unknown", inferRemotePolicy("", "", ""))
        }
    }

    // ── looksLikeLocation ────────────────────────────────────────────────────

    @Nested
    @DisplayName("looksLikeLocation")
    inner class LooksLikeLocation {

        @Test
        @DisplayName("matches 'Remote'")
        fun matchesRemote() {
            assertTrue(looksLikeLocation("Remote"))
            assertTrue(looksLikeLocation("Remote (US)"))
        }

        @Test
        @DisplayName("matches City, ST format")
        fun matchesCityState() {
            assertTrue(looksLikeLocation("Seattle, WA"))
            assertTrue(looksLikeLocation("New York, NY"))
            assertTrue(looksLikeLocation("San Francisco, CA (Hybrid)"))
        }

        @Test
        @DisplayName("matches 'within the US'")
        fun matchesWithinUS() {
            assertTrue(looksLikeLocation("Anywhere within the US"))
        }

        @Test
        @DisplayName("matches pipe-separated values")
        fun matchesPipeSeparated() {
            assertTrue(looksLikeLocation("Seattle, WA | Remote"))
        }

        @Test
        @DisplayName("rejects plain role titles")
        fun rejectsRoleTitles() {
            assertFalse(looksLikeLocation("Senior Software Engineer"))
            assertFalse(looksLikeLocation("Staff SDET"))
        }
    }

    // ── buildDigestSummaryText ───────────────────────────────────────────────

    @Nested
    @DisplayName("buildDigestSummaryText")
    inner class BuildDigestSummaryText {

        @Test
        @DisplayName("includes role and company")
        fun includesRoleAndCompany() {
            val state = JDState(roleTitle = "Staff SDET", company = "Acme")
            val text = buildDigestSummaryText(state)
            assertTrue(text.contains("Staff SDET"))
            assertTrue(text.contains("Acme"))
        }

        @Test
        @DisplayName("appends location when non-blank")
        fun appendsLocation() {
            val state = JDState(roleTitle = "Engineer", company = "Corp", location = "Seattle, WA")
            val text = buildDigestSummaryText(state)
            assertTrue(text.contains("Seattle, WA"))
        }

        @Test
        @DisplayName("omits blank optional fields")
        fun omitsBlankOptionalFields() {
            val state = JDState(roleTitle = "Engineer", company = "Corp")
            val text = buildDigestSummaryText(state)
            assertFalse(text.trimEnd().endsWith("|"))
        }

        @Test
        @DisplayName("includes job URL when present")
        fun includesJobUrl() {
            val state = JDState(roleTitle = "Engineer", company = "Corp", jobUrl = "https://example.com/job/1")
            val text = buildDigestSummaryText(state)
            assertTrue(text.contains("https://example.com/job/1"))
        }
    }

    // ── applyDigestSummary ───────────────────────────────────────────────────

    @Nested
    @DisplayName("applyDigestSummary")
    inner class ApplyDigestSummary {

        private val base = emailState()

        @Test
        @DisplayName("returns result unchanged when job list is empty")
        fun returnsUnchangedWhenEmpty() {
            val result = JDState(company = "Original")
            val out = applyDigestSummary(result, emptyList())
            assertEquals("Original", out.company)
        }

        @Test
        @DisplayName("single job: copies company, role, location verbatim")
        fun singleJobCopiesFields() {
            val job = JDState(company = "Acme", roleTitle = "Engineer", location = "Remote", jobUrl = "https://j.com/1")
            val out = applyDigestSummary(base, listOf(job))
            assertEquals("Acme", out.company)
            assertEquals("Engineer", out.roleTitle)
            assertEquals("Remote", out.location)
        }

        @Test
        @DisplayName("multiple companies: collapses to 'Multiple companies'")
        fun multipleCompaniesLabel() {
            val jobs = listOf(
                JDState(company = "Acme", roleTitle = "Engineer"),
                JDState(company = "Initech", roleTitle = "Developer"),
            )
            val out = applyDigestSummary(base, jobs)
            assertEquals("Multiple companies", out.company)
        }

        @Test
        @DisplayName("multiple jobs: role becomes 'Job digest (N jobs)'")
        fun multiJobRoleLabel() {
            val jobs = listOf(
                JDState(company = "Acme", roleTitle = "Engineer"),
                JDState(company = "Acme", roleTitle = "Architect"),
            )
            val out = applyDigestSummary(base, jobs)
            assertTrue(out.roleTitle.contains("2"))
        }

        @Test
        @DisplayName("jdText includes numbered entries for each job")
        fun jdTextNumbered() {
            val jobs = listOf(
                JDState(company = "Acme", roleTitle = "Engineer", location = "Remote"),
                JDState(company = "Initech", roleTitle = "Developer", location = "Seattle, WA"),
            )
            val out = applyDigestSummary(base, jobs)
            assertTrue(out.jdText.contains("1."))
            assertTrue(out.jdText.contains("2."))
        }
    }

    // ── cleanUrl ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cleanUrl")
    inner class CleanUrl {

        @Test
        @DisplayName("decodes HTML entity ampersand")
        fun decodesAmpersand() {
            assertEquals("https://x.com/job?a=1&b=2", cleanUrl("https://x.com/job?a=1&amp;b=2"))
        }

        @Test
        @DisplayName("strips trailing punctuation")
        fun stripsTrailingPunctuation() {
            assertEquals("https://x.com/job", cleanUrl("https://x.com/job."))
            assertEquals("https://x.com/job", cleanUrl("https://x.com/job,"))
        }

        @Test
        @DisplayName("leaves clean URLs unchanged")
        fun leavesCleanUrls() {
            val url = "https://example.com/jobs/view/12345"
            assertEquals(url, cleanUrl(url))
        }
    }

    // ── isEligibleJobUrl ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("isEligibleJobUrl")
    inner class IsEligibleJobUrl {

        @Test
        @DisplayName("returns false for null URL")
        fun rejectsNull() {
            assertFalse(isEligibleJobUrl(null, "linkedin.com"))
        }

        @Test
        @DisplayName("Jobright: only /jobs/info/ posting pages, not the email's generic or recommendation links")
        fun jobrightPostingPagesOnly() {
            assertTrue(isEligibleJobUrl("https://jobright.ai/jobs/info/6a63ab620c8e2b4f36dcf19f?utm_source=1121", "jobright.ai"))
            assertFalse(isEligibleJobUrl("https://jobright.ai/?retarget=jobalert", "jobright.ai"))
            assertFalse(isEligibleJobUrl("https://jobright.ai/jobs/recommend?utm_source=1121&utm_medium=email", "jobright.ai"))
        }

        @Test
        @DisplayName("returns false for non-http URL")
        fun rejectsNonHttp() {
            assertFalse(isEligibleJobUrl("ftp://example.com/job", "example.com"))
        }

        @Test
        @DisplayName("returns false for unsubscribe links")
        fun rejectsUnsubscribe() {
            assertFalse(isEligibleJobUrl("https://email.com/unsubscribe?id=123", "email.com"))
        }

        @Test
        @DisplayName("returns false for image links")
        fun rejectsImageLinks() {
            assertFalse(isEligibleJobUrl("https://cdn.com/image.png", "cdn.com"))
            assertFalse(isEligibleJobUrl("https://cdn.com/logo.gif?v=1", "cdn.com"))
        }

        @Test
        @DisplayName("returns true for LinkedIn job view URL")
        fun acceptsLinkedInJobUrl() {
            assertTrue(isEligibleJobUrl("https://www.linkedin.com/jobs/view/123456789", "linkedin.com"))
        }

        @Test
        @DisplayName("returns false for LinkedIn non-job URL")
        fun rejectsLinkedInNonJobUrl() {
            assertFalse(isEligibleJobUrl("https://www.linkedin.com/feed/", "linkedin.com"))
        }

        @Test
        @DisplayName("returns true for generic /jobs/ path")
        fun acceptsGenericJobsPath() {
            assertTrue(isEligibleJobUrl("https://company.com/careers/jobs/engineer-123", "company.com"))
        }
    }
}
