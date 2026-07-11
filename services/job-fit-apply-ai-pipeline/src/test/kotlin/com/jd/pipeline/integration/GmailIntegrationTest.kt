package com.jd.pipeline.integration

import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.testutils.MockGmailTransport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Gmail client integration.
 *
 * Tests email fetching, parsing, and label application
 * using a mock Gmail client that simulates real behavior.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@DisplayName("GmailIntegrationTest")
class GmailIntegrationTest {

    private lateinit var mockGmail: MockGmailTransport

    @BeforeEach
    fun setup() {
        mockGmail = MockGmailTransport()
    }

    @Test
    @DisplayName("Should fetch and parse recruiter job posting")
    fun testFetchRecruiterJobPosting() {
        // Given
        mockGmail.addJobPostingEmail(
            id = "email-001",
            company = "TechStartup",
            roleTitle = "Senior Android Developer",
            isRecruiter = true
        )

        // When
        val emails = mockGmail.fetchEmails("INBOX", 10)
        val email = emails.first()

        // Then
        assertEquals(1, emails.size)
        assertEquals("email-001", email.id)
        assertTrue(email.subject.contains("Android Developer"))
        assertTrue(email.from.contains("recruiter"))
    }

    @Test
    @DisplayName("Should fetch and parse digest email with multiple jobs")
    fun testFetchDigestEmail() {
        // Given
        mockGmail.addJobPostingEmail(
            id = "email-002",
            company = "JobBoard",
            roleTitle = "Various Positions",
            isRecruiter = false,
            isDigest = true
        )

        // When
        val emails = mockGmail.fetchEmails("INBOX", 10)
        val email = emails.first()

        // Then
        assertEquals(1, emails.size)
        assertTrue(email.body.contains("Check out these jobs"))
        assertTrue(email.body.contains("https://example.com/job/"))
    }

    @Test
    @DisplayName("Should convert mock email to JDState")
    fun testEmailToJDStateConversion() {
        // Given
        mockGmail.addJobPostingEmail(
            id = "email-003",
            company = "Acme Corp",
            roleTitle = "Kotlin Developer",
            isRecruiter = true
        )
        val emails = mockGmail.fetchEmails("INBOX", 10)

        // When
        val state = mockGmail.toJDState(emails.first())

        // Then
        assertEquals("email-003", state.emailIntake?.emailId)
        assertEquals("Acme Corp", state.company)
        assertEquals("Kotlin Developer", state.roleTitle)
        assertTrue(state.emailIntake?.from?.contains("recruiter") == true)
    }

    @Test
    @DisplayName("Should handle empty inbox gracefully")
    fun testEmptyInbox() {
        // Given: no emails added

        // When
        val emails = mockGmail.fetchEmails("INBOX", 10)

        // Then
        assertTrue(emails.isEmpty())
    }

    @Test
    @DisplayName("Should apply label to email")
    fun testApplyLabel() {
        // Given
        mockGmail.addJobPostingEmail(id = "email-004")

        // When
        mockGmail.applyLabel("email-004", "PROCESSED")

        // Then
        assertTrue(mockGmail.wasLabeled("email-004"))
    }

    @Test
    @DisplayName("Should handle fetch failure")
    fun testFetchFailureHandling() {
        // Given
        mockGmail.shouldFailFetch = true
        mockGmail.failMessage = "Simulated Gmail API error"

        // When/Then
        try {
            mockGmail.fetchEmails("INBOX", 10)
            throw AssertionError("Should have thrown exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Gmail API error"))
        }
    }

    @Test
    @DisplayName("Should handle label application failure")
    fun testLabelFailureHandling() {
        // Given
        mockGmail.addJobPostingEmail(id = "email-005")
        mockGmail.shouldFailLabel = true

        // When/Then
        try {
            mockGmail.applyLabel("email-005", "PROCESSED")
            throw AssertionError("Should have thrown exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Gmail failure"))
        }
    }

    @Test
    @DisplayName("Should convert multiple emails to JDState list")
    fun testMultipleEmailsToJDState() {
        // Given
        mockGmail.addJobPostingEmail(id = "email-010", company = "CompanyA", roleTitle = "Engineer A")
        mockGmail.addJobPostingEmail(id = "email-011", company = "CompanyB", roleTitle = "Engineer B")
        mockGmail.addJobPostingEmail(id = "email-012", company = "CompanyC", roleTitle = "Engineer C")

        // When
        val emails = mockGmail.fetchEmails("INBOX", 10)
        val states = emails.map { mockGmail.toJDState(it) }

        // Then
        assertEquals(3, states.size)
        assertEquals("CompanyA", states[0].company)
        assertEquals("CompanyB", states[1].company)
        assertEquals("CompanyC", states[2].company)
    }

    @Test
    @DisplayName("Should limit max results")
    fun testMaxResultsLimit() {
        // Given: add 20 emails
        for (i in 1..20) {
            mockGmail.addJobPostingEmail(id = "email-$i")
        }

        // When: request only 5
        val emails = mockGmail.fetchEmails("INBOX", 5)

        // Then
        assertEquals(5, emails.size)
    }
}