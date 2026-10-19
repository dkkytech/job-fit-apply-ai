package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Employer job-alert emails → one link-only child per posting. Fixtures are synthetic, shaped like
 * the real Microsoft Careers (Eightfold), REI (iCIMS) and SuccessFactors (jobs2web) alerts that the
 * processor used to label JD_Not_Found or JD_Scrape_Failed.
 */
class EmployerAlertDigestStrategyTest {

    private fun email(from: String, subject: String, html: String, text: String = "") = IntakeContext.Email(
        emailId = "m1", from = from, subject = subject, rawBody = text, htmlBody = html,
        isRecruiter = false, isDigest = false, isInlineDigest = false,
    )

    private fun expand(e: IntakeContext.Email) = EmployerAlertDigestStrategy.expand(JDState(intake = e), e)

    /** Eightfold's click-tracker around a careerhub link, as in Microsoft's alerts. */
    private fun vsimp(target: String) =
        "https://microsoft.eightfold.ai/vsimp?d=e30.sig&amp;n=" + URLEncoder.encode(target, Charsets.UTF_8)

    private val microsoftHtml = """
        <html><body>
          <p>New jobs match your profile</p>
          <a href="${vsimp("https://apply.careers.microsoft.com/careerhub/explore/jobs/1970393556874530?utm_source=position_notification")}">Senior Software Engineer</a>
          <a href="${vsimp("https://apply.careers.microsoft.com/careerhub/explore/jobs/1970393556874530?utm_source=position_notification")}">Apply Now</a>
          <a href="${vsimp("https://apply.careers.microsoft.com/careerhub/explore/jobs/1970393557026431?utm_source=position_notification")}">Apply Now</a>
          <a href="${vsimp("https://apply.careers.microsoft.com/careerhub/explore/jobs?r=x")}">View all matching jobs</a>
          <a href="${vsimp("https://apply.careers.microsoft.com/careerhub/me?action=edit")}">Review profile</a>
          <a href="${vsimp("https://apply.careers.microsoft.com/careerhub/explore/jobs/notification_settings?x=1")}">Manage alerts</a>
          <a href="${vsimp("https://www.linkedin.com/company/1035/")}">LinkedIn</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `Microsoft Careers alert - one child per posting, keyed and fetched the watchlist's way`() {
        val kids = expand(email("Microsoft Careers <donotreply@email.careers.microsoft.com>", "New jobs at Microsoft that match your profile", microsoftHtml))

        assertEquals(
            listOf("watchlist:eightfold:apply.careers.microsoft.com:1970393556874530", "watchlist:eightfold:apply.careers.microsoft.com:1970393557026431"),
            kids.map { it.postingKey },
            "settings, profile, search and social links are not postings; a job linked twice is one child",
        )
        assertEquals("https://apply.careers.microsoft.com/careers/job/1970393556874530", kids[0].jobUrl, "public page, not the sign-in careerhub")
        assertTrue(kids.all { it.submitAsUrl && it.isJobPosting && it.isDigest })
        assertEquals(listOf("Microsoft", "Microsoft"), kids.map { it.company })
        assertEquals(listOf("Senior Software Engineer", ""), kids.map { it.roleTitle }, "the title anchor wins over Apply Now; none for the second")
    }

    @Test
    fun `iCIMS alert (REI) - the posting link is kept, opaque trackers are not`() {
        val html = """
            <p>Great news! We found open opportunities that match your REI job search criteria.</p>
            <a href="https://www.rei.jobs/jobs/31561?lang=en-us">Senior Software Engineer</a>
            <a href="https://tracking.icims.com/f/a/HPXq~~/AAIB5hA~/ArxYsx">Set up a new job alert</a>
            <a href="https://tracking.icims.com/f/a/FIZU~~/AAIB5hA~/_9pRx">See instructions</a>
        """.trimIndent()
        val kids = expand(email("REI Careers <rei@nurture.icims.com>", "New Job Opportunities at REI Careers!", html))
        assertEquals(listOf("https://www.rei.jobs/jobs/31561?lang=en-us"), kids.map { it.jobUrl })
        assertEquals("REI", kids.single().company)
        assertEquals("Senior Software Engineer", kids.single().roleTitle)
    }

    @Test
    fun `SuccessFactors alert (jobs2web) - the per-email eid never splits one posting into two`() {
        fun alert(eid: String) = email(
            "qualitesti-jobnotification@noreply44.jobs2web.com", "New jobs posted from careers.quality-ai.com",
            """<a href="http://careers.quality-ai.com/job/Santa-Clara-QA-Engineer-CA-95054/60304044/?from=email&amp;eid=$eid&amp;locale=en_GB">QA Engineer - Santa Clara, CA</a>
               <a href="https://careers.quality-ai.com/unsubscribe/">Unsubscribe</a>""",
        )
        val monday = expand(alert("27544-1"))
        val tuesday = expand(alert("27544-2"))
        assertEquals(1, monday.size)
        assertEquals(monday.single().postingKey, tuesday.single().postingKey)
        assertEquals("", monday.single().company, "no display name to take a company from")
    }

    @Test
    fun `a text-only alert is read from its links`() {
        val text = "New jobs for you\nQA Engineer https://jobs.lever.co/acme/0f1e2d3c-4b5a-4978-8a9b-0c1d2e3f4a5b\nUnsubscribe https://jobs.lever.co/unsubscribe?x=1"
        val kids = expand(email("Acme <no-reply@hire.lever.co>", "Job alert", html = "", text = text))
        assertEquals(listOf("watchlist:lever:acme:0f1e2d3c-4b5a-4978-8a9b-0c1d2e3f4a5b"), kids.map { it.postingKey })
    }

    @Test
    fun `mail that does not read as an alert yields nothing, even with a job link`() {
        val html = """<p>Thank you for applying to Senior Software Engineer.</p>
            <a href="https://apply.careers.microsoft.com/careers/job/1970393556874530">View the job</a>"""
        assertEquals(emptyList(), expand(email("Microsoft Careers <donotreply@email.careers.microsoft.com>", "Thank you for your application", html)))
    }

    @Test
    fun `at most MAX_JOBS_PER_EMAIL children`() {
        val links = (1..40).joinToString("\n") { """<a href="https://boards.greenhouse.io/acme/jobs/${1000 + it}">Job $it</a>""" }
        assertEquals(MAX_JOBS_PER_EMAIL, expand(email("Acme <no-reply@greenhouse.io>", "New jobs at Acme", links)).size)
    }

    @Test
    fun `the company comes from the sender's display name`() {
        assertEquals("Microsoft", EmployerAlertDigestStrategy.companyFrom("Microsoft Careers <donotreply@email.careers.microsoft.com>"))
        assertEquals("REI", EmployerAlertDigestStrategy.companyFrom("\"REI Careers\" <rei@nurture.icims.com>"))
        assertEquals("Acme", EmployerAlertDigestStrategy.companyFrom("Acme Talent Acquisition <x@y.com>"))
        assertEquals("", EmployerAlertDigestStrategy.companyFrom("alerts@noreply44.jobs2web.com"))
        assertEquals("", EmployerAlertDigestStrategy.companyFrom("Job Alerts <alerts@x.com>"))
    }
}
