package com.jd.jobbot.apply

import java.net.URI

/**
 * The fill loop's hard limits, as pure functions so they are tested exhaustively and cannot be
 * argued with by the model:
 *  - where the apply browser may navigate (the browser holds a live Google session for dkkytech@,
 *    so Gmail, Drive and account settings are never reachable);
 *  - which controls count as "submit" (the loop never clicks them; only a ✅ Submit tap does);
 *  - which Google consent screens may be approved automatically (sign-in scopes only);
 *  - which verification emails and links belong to the site being signed up for.
 */
object SitePolicy {
    /** ATS and job-board domains an application commonly moves through. */
    val ATS_DOMAINS = setOf(
        "greenhouse.io", "lever.co", "ashbyhq.com", "myworkdayjobs.com", "myworkdaysite.com", "workday.com",
        "icims.com", "smartrecruiters.com", "jobvite.com", "bamboohr.com", "workable.com", "recruitee.com",
        "taleo.net", "successfactors.com", "oraclecloud.com", "ultipro.com", "paylocity.com", "applytojob.com",
        "jazzhr.com", "breezy.hr", "rippling.com", "dover.com", "wellfound.com", "teamtailor.com",
        "pinpointhq.com", "personio.de", "recruiterbox.com", "eightfold.ai", "phenompeople.com",
        "glassdoor.com", "linkedin.com", "indeed.com", "jobright.ai", "ziprecruiter.com", "dice.com",
    )

    /** Google pages the sign-in flow needs. Everything else on Google is off limits. */
    private val GOOGLE_AUTH_PATHS = listOf("/o/oauth2", "/signin", "/v3/signin", "/accountchooser", "/servicelogin", "/oauthchooseaccount", "/gsi/")
    private val GOOGLE_FAMILY = setOf("google.com", "gmail.com", "googleusercontent.com", "youtube.com", "gstatic.com")

    /**
     * May the main frame navigate to [url] while applying for a job whose posting is [jobUrl] at
     * [company]? Subresources (images, scripts) are not navigations and are not checked here.
     */
    fun navigationAllowed(url: String, jobUrl: String?, company: String?, extraHosts: Set<String> = emptySet()): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme == "about" || uri.scheme == "data" || uri.scheme == "blob") return true
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        if (host in extraHosts) return true
        val domain = Credentials.registrableDomain(host)
        if (domain in GOOGLE_FAMILY) {
            return host == "accounts.google.com" && GOOGLE_AUTH_PATHS.any { (uri.path ?: "").lowercase().startsWith(it) }
        }
        if (domain in ATS_DOMAINS) return true
        val jobHost = jobUrl?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
        if (jobHost != null && Credentials.registrableDomain(jobHost) == domain) return true
        // The company's own careers site: its domain carries the company's name.
        val token = company.orEmpty().lowercase().replace(Regex("[^a-z0-9]"), "")
        return token.length >= 3 && domain.substringBefore('.').replace("-", "").contains(token.take(12))
    }

    private val SUBMIT_WORDS = Regex(
        """\b(submit|send application|finish|complete( application)?|confirm( and submit)?|apply now|apply for this job|apply)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SAFE_STEP_WORDS = Regex("""\b(next|continue|save( and|&) continue|review|back|previous|add|upload|sign in|log ?in|create account|sign up|register|verify|search)\b""", RegexOption.IGNORE_CASE)

    /**
     * Is clicking this control a final application submission? `insideFilledForm` is true when the
     * control belongs to a form that already has user-entered values — an "Apply now" link on a
     * bare job page opens the form and is fine; the same word at the end of a filled form submits.
     */
    fun isSubmitControl(text: String, type: String?, insideFilledForm: Boolean): Boolean {
        val t = text.trim()
        if (SAFE_STEP_WORDS.containsMatchIn(t) && !Regex("""\bsubmit\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return false
        if (!insideFilledForm) return Regex("""\bsubmit\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
        if (SUBMIT_WORDS.containsMatchIn(t)) return true
        // A bare submit-type control at the end of a filled form, labelled with something we do not know.
        return type.equals("submit", ignoreCase = true) && t.isBlank()
    }

    private val BASIC_SCOPE_WORDS = listOf("name", "profile picture", "email address", "personal info", "language preference")
    // Not "manage": Google's own sign-in screen says "You can manage Sign in with Google in your Google Account".
    private val SENSITIVE_SCOPE_WORDS = listOf(
        "gmail", "email messages", "drive", "documents", "spreadsheets", "contacts", "calendar", "photos",
        "youtube", "delete", "send email", "your files", "location history", "phone number",
    )
    private val EMAIL_ADDRESS = Regex("""[a-z0-9._%+-]+@[a-z0-9.-]+""")

    /** Consent text without email addresses: the screen shows the account (…@gmail.com), which is not a scope. */
    private fun scopeText(consentText: String) = consentText.lowercase().replace(EMAIL_ADDRESS, " ")

    /** Does a Google consent screen ask for anything beyond sign-in (mail, files, contacts …)? */
    fun consentIsSensitive(consentText: String): Boolean = scopeText(consentText).let { t -> SENSITIVE_SCOPE_WORDS.any { it in t } }

    /** May a Google consent screen with this visible text be approved automatically? */
    fun consentIsBasic(consentText: String): Boolean {
        if (consentIsSensitive(consentText)) return false
        val t = scopeText(consentText)
        return BASIC_SCOPE_WORDS.any { it in t }
    }

    /** Mail domains ATSs send verification email from (on behalf of the company). */
    val ATS_MAIL_DOMAINS = setOf(
        "myworkday.com", "workday.com", "greenhouse.io", "greenhouse-mail.io", "lever.co", "hire.lever.co", "ashbyhq.com",
        "icims.com", "smartrecruiters.com", "jobvite.com", "bamboohr.com", "workablemail.com", "workable.com",
        "taleo.net", "oracle.com", "successfactors.com", "ultipro.com", "paylocity.com",
    )

    /** Is a verification email from [sender] plausibly from the site keyed [site]? */
    fun verificationSenderAllowed(sender: String, site: String): Boolean {
        val addr = Regex("""[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+)""").find(sender)?.groupValues?.get(1)?.lowercase() ?: return false
        val senderDomain = Credentials.registrableDomain(addr)
        val siteDomain = Credentials.registrableDomain(site)
        return senderDomain == siteDomain || senderDomain in ATS_MAIL_DOMAINS
    }

    /** A verification link in [body] that points at the site keyed [site], or null. */
    fun verificationLink(body: String, site: String): String? {
        val siteDomain = Credentials.registrableDomain(site)
        return Regex("""https://[^\s"'<>)]+""").findAll(body).map { it.value.trimEnd('.', ',') }.firstOrNull { link ->
            val host = runCatching { URI(link).host?.lowercase() }.getOrNull() ?: return@firstOrNull false
            val d = Credentials.registrableDomain(host)
            (d == siteDomain || d in ATS_DOMAINS) &&
                Regex("""verif|confirm|activat|validate|token|code""", RegexOption.IGNORE_CASE).containsMatchIn(link)
        }
    }

    /** A one-time code in a verification email, or null. */
    fun verificationCode(body: String): String? =
        Regex("""(?i)(?:code|passcode|pin)[^0-9A-Z]{0,20}([0-9]{4,8}|[A-Z0-9]{6,8})\b""").find(body)?.groupValues?.get(1)
            ?: Regex("""\b([0-9]{6})\b""").find(body)?.groupValues?.get(1)
}
