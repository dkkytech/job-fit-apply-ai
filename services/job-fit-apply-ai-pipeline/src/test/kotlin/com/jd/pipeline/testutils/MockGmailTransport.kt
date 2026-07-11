package com.jd.pipeline.testutils

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState

/**
 * Mock GmailTransport for integration testing.
 * Simulates email retrieval without connecting to real Gmail API.
 */
class MockGmailTransport {

    private val emails = mutableListOf<MockEmail>()
    private val labeledEmailIds = mutableSetOf<String>()

    var shouldFailFetch = false
    var shouldFailLabel = false
    var failMessage = "Mock Gmail failure"

    data class MockEmail(
        val id: String,
        val subject: String,
        val from: String,
        val body: String,
        val htmlBody: String = ""
    )

    fun reset() {
        emails.clear()
        labeledEmailIds.clear()
        shouldFailFetch = false
        shouldFailLabel = false
    }

    fun addEmail(email: MockEmail) {
        emails.add(email)
    }

    fun addJobPostingEmail(
        id: String = "mock-email-${System.nanoTime()}",
        company: String = "TestCorp",
        roleTitle: String = "Software Engineer",
        isRecruiter: Boolean = false,
        isDigest: Boolean = false
    ) {
        val body = if (isDigest) {
            """
            Check out these jobs:

            1. $company - $roleTitle - San Francisco, CA
            https://example.com/job/1

            2. $company - Senior $roleTitle - Remote
            https://example.com/job/2
            """.trimIndent()
        } else {
            """
            Hi,

            We are hiring a $roleTitle at $company.

            Location: San Francisco, CA
            Salary: \$120k - \$180k
            Remote: Yes

            Requirements:
            - 3+ years of experience
            - Proficiency in Kotlin or Java
            - Experience with cloud services (AWS/GCP)

            Apply here: https://example.com/apply
            """.trimIndent()
        }

        emails.add(MockEmail(
            id = id,
            subject = if (isDigest) "Daily Job Digest" else "Job Opportunity: $roleTitle at $company",
            from = if (isRecruiter) "recruiter@$company.com" else "hr@$company.com",
            body = body,
            htmlBody = "<html><body><p>$body</p></body></html>"
        ))
    }

    fun fetchEmails(labelId: String, maxResults: Int = 10): List<MockEmail> {
        if (shouldFailFetch) throw RuntimeException(failMessage)
        return emails.take(maxResults)
    }

    fun getEmailById(id: String): MockEmail? = emails.find { it.id == id }

    fun applyLabel(emailId: String, labelId: String) {
        if (shouldFailLabel) throw RuntimeException(failMessage)
        labeledEmailIds.add(emailId)
    }

    fun wasLabeled(emailId: String) = labeledEmailIds.contains(emailId)

    /**
     * Convert mock email to JDState for pipeline testing.
     */
    fun toJDState(email: MockEmail): JDState = JDState(
        isJobPosting = true,
        intake = IntakeContext.Email(
            emailId = email.id,
            subject = email.subject,
            from = email.from,
            rawBody = email.body,
            htmlBody = email.htmlBody,
            isRecruiter = false,
            isDigest = false,
            isInlineDigest = false,
        ),
        company = extractCompanyFromBody(email.body) ?: extractCompanyFromSubject(email.subject) ?: "Unknown",
        roleTitle = extractRoleFromSubject(email.subject) ?: "Unknown"
    )

    private fun extractCompanyFromBody(body: String): String? {
        val atPattern = Regex("""at\s+([A-Za-z0-9\s]+)""", RegexOption.IGNORE_CASE)
        return atPattern.find(body)?.groupValues?.get(1)?.trim()
    }

    private fun extractCompanyFromSubject(subject: String): String? {
        val atPattern = Regex("""at\s+([A-Za-z0-9\s]+)$""", RegexOption.IGNORE_CASE)
        return atPattern.find(subject)?.groupValues?.get(1)?.trim()
    }

    private fun extractRoleFromSubject(subject: String): String? {
        val rolePattern = Regex("""Job Opportunity:\s*(.+?)\s+at\s+""", RegexOption.IGNORE_CASE)
        return rolePattern.find(subject)?.groupValues?.get(1)?.trim()
    }
}