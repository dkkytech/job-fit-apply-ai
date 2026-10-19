package com.jd.jobbot.apply

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SitePolicyTest {
    private val job = "https://www.glassdoor.com/partner/jobListing.htm?jobListingId=1"

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        // Google: sign-in and consent only; the live dkkytech@ session must never reach mail or files.
        "https://accounts.google.com/o/oauth2/v2/auth?client_id=x, true",
        "https://accounts.google.com/v3/signin/identifier, true",
        "https://accounts.google.com/AccountChooser, true",
        // Google Identity Services: the "Sign in with Google" popup.
        "https://accounts.google.com/gsi/select?client_id=x, true",
        "https://accounts.google.com/b/0/ManageAccount, false",
        "https://myaccount.google.com/security, false",
        "https://mail.google.com/mail/u/0/, false",
        "https://drive.google.com/drive/my-drive, false",
        "https://www.google.com/search?q=x, false",
        "https://gmail.com, false",
        // The application's path.
        "https://www.glassdoor.com/job-listing/x, true",
        "https://boards.greenhouse.io/acme/jobs/1, true",
        "https://acme.wd5.myworkdayjobs.com/en-US/careers, true",
        "https://jobs.lever.co/acme/1/apply, true",
        "https://careers.acme.com/apply, true",
        "https://acme-inc.com/careers, true",
        // Everything else.
        "https://evil.example/steal, false",
        "javascript:alert(1), false",
        "file:///etc/passwd, false",
        "about:blank, true",
    )
    fun `navigation guard`(url: String, allowed: Boolean) {
        assertEquals(allowed, SitePolicy.navigationAllowed(url, job, "Acme"), url)
    }

    @Test
    fun `short company names do not open arbitrary domains`() {
        assertFalse(SitePolicy.navigationAllowed("https://hp-scam.com", job, "HP"))
    }

    @ParameterizedTest(name = "[{0}] filled={2} -> {3}")
    @CsvSource(
        "Submit application, submit, true, true",
        "Submit, button, false, true",
        "Apply, submit, true, true",
        "Finish, button, true, true",
        "Send application, button, true, true",
        "'', submit, true, true",
        "Apply now, button, false, false",
        "Apply for this job, button, false, false",
        "Next, submit, true, false",
        "Save and Continue, submit, true, false",
        "Continue, button, true, false",
        "Review, button, true, false",
        "Sign in, submit, false, false",
        "Create account, submit, true, false",
        "Upload, button, true, false",
    )
    fun `submit detection`(text: String, type: String, filled: Boolean, submit: Boolean) {
        assertEquals(submit, SitePolicy.isSubmitControl(text, type, filled), "$text/$type/$filled")
    }

    @Test
    fun `only basic Google sign-in consent is automatic`() {
        assertTrue(SitePolicy.consentIsBasic("Acme wants to access your Google Account. See your personal info, including any personal info you've made publicly available. See your primary Google Account email address"))
        assertFalse(SitePolicy.consentIsBasic("Acme wants to: Read, compose, send, and permanently delete all your email from Gmail"))
        assertFalse(SitePolicy.consentIsBasic("See and download all your Google Drive files. See your primary Google Account email address"))
        assertFalse(SitePolicy.consentIsBasic("Sign in to continue"))
        // The screen shows the account address (…@gmail.com) and a "manage Sign in with Google" note: neither is a scope.
        assertTrue(SitePolicy.consentIsBasic("Sign in to jobright.ai with google.com dkkytech@gmail.com By continuing, Google will share your name, " +
            "email address, language preference, and profile picture with jobright.ai. You can manage Sign in with Google in your Google Account."))
        assertTrue(SitePolicy.consentIsSensitive("dkkytech@gmail.com — Read, compose and send email from your Gmail account"))
        assertFalse(SitePolicy.consentIsSensitive("Continue as Richard dkkytech@gmail.com"))
    }

    @Test
    fun `verification mail must come from the site or its ATS`() {
        assertTrue(SitePolicy.verificationSenderAllowed("Acme Careers <no-reply@acme.com>", "acme.com"))
        assertTrue(SitePolicy.verificationSenderAllowed("Workday <acme@myworkday.com>", "acme.wd5.myworkdayjobs.com"))
        assertFalse(SitePolicy.verificationSenderAllowed("Phish <x@evil.example>", "acme.com"))
        assertFalse(SitePolicy.verificationSenderAllowed("no address", "acme.com"))
    }

    @Test
    fun `verification links must point back at the site`() {
        val body = "Click https://evil.example/verify?t=1 or https://careers.acme.com/account/verify?token=abc. Thanks"
        assertEquals("https://careers.acme.com/account/verify?token=abc", SitePolicy.verificationLink(body, "acme.com"))
        assertNull(SitePolicy.verificationLink("https://evil.example/verify?t=1", "acme.com"))
        assertNull(SitePolicy.verificationLink("https://careers.acme.com/jobs", "acme.com"), "not a verification link")
    }

    @Test
    fun `verification codes`() {
        assertEquals("482913", SitePolicy.verificationCode("Your verification code is 482913."))
        assertEquals("AB12CD", SitePolicy.verificationCode("Code: AB12CD"))
        assertEquals("553201", SitePolicy.verificationCode("Use 553201 to sign in"))
        assertNull(SitePolicy.verificationCode("Welcome aboard"))
    }
}
