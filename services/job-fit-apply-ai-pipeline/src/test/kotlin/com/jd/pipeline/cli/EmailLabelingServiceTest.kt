package com.jd.pipeline.cli

import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.fixtures.TestJDStateFactory
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.mockito.kotlin.*
import org.mockito.Mockito.times
import org.mockito.Mockito.never

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for EmailLabelingService.
 * Tests the labeling, archiving, starring, and unread-marking behaviors
 * for different email categories per the design document.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailLabelingServiceTest {

    private lateinit var gmailClient: GmailTransport
    private lateinit var labelingService: EmailLabelingService

    @BeforeEach
    fun setUp() {
        gmailClient = mock<GmailTransport>()
        labelingService = EmailLabelingServiceImpl()

        // Default mock behaviors for non-void methods
        whenever(gmailClient.getOrCreateLabel(any())).thenReturn("label-123")
        
        // Stub void methods with doNothing()
        doNothing().whenever(gmailClient).labelEmail(any(), any())
        doNothing().whenever(gmailClient).archiveEmail(any())
        doNothing().whenever(gmailClient).starEmail(any())
        doNothing().whenever(gmailClient).markUnread(any())
    }

    // ============================================================================
    // JD_Not_Found Tests (Section 5.1 of Design Doc)
    // ============================================================================

    @Test
    fun shouldLabelJdNotFoundAndKeepInInbox() {
        // Given
        val state = TestJDStateFactory.createJdNotFoundState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).getOrCreateLabel("JD_Not_Found")
        verify(gmailClient, times(1)).labelEmail("jd-not-found-001", "label-123")
        verify(gmailClient, never()).archiveEmail("jd-not-found-001")
        verify(gmailClient, never()).starEmail("jd-not-found-001")
    }

    @Test
    fun shouldMarkUnreadForJdNotFound() {
        // Given
        val state = TestJDStateFactory.createJdNotFoundState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).markUnread("jd-not-found-001")
    }

    @Test
    fun shouldNotArchiveJdNotFound() {
        // Given
        val state = TestJDStateFactory.createJdNotFoundState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, never()).archiveEmail(any())
    }

    @Test
    fun shouldNotStarJdNotFound() {
        // Given
        val state = TestJDStateFactory.createJdNotFoundState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, never()).starEmail(any())
    }

    @Test
    fun shouldReturnCorrectLabelingResultForJdNotFound() {
        // Given
        val state = TestJDStateFactory.createJdNotFoundState()

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then
        assertEquals("JD_Not_Found", result.labelApplied)
        assertFalse(result.wasArchived)
        assertFalse(result.wasStarred)
        assertTrue(result.wasMarkedUnread)
        assertTrue(result.wasKeptInInbox)
    }

    // ============================================================================
    // Recruiter_Response_Required Tests (Section 5.2 of Design Doc)
    // ============================================================================

    @Test
    fun shouldLabelRecruiterResponseRequired() {
        // Given
        val state = TestJDStateFactory.createRecruiterResponseRequiredState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).getOrCreateLabel("Recruiter_Response_Required")
    }

    @Test
    fun shouldStarRecruiterResponse() {
        // Given
        val state = TestJDStateFactory.createRecruiterResponseRequiredState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).starEmail("recruiter-response-001")
    }

    @Test
    fun shouldMarkUnreadForRecruiterResponse() {
        // Given
        val state = TestJDStateFactory.createRecruiterResponseRequiredState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).markUnread("recruiter-response-001")
    }

    @Test
    fun shouldNotArchiveRecruiterResponse() {
        // Given
        val state = TestJDStateFactory.createRecruiterResponseRequiredState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, never()).archiveEmail(any())
    }

    @Test
    fun shouldKeepRecruiterResponseInInbox() {
        // Given
        val state = TestJDStateFactory.createRecruiterResponseRequiredState()

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then
        assertTrue(result.wasKeptInInbox)
        assertFalse(result.wasArchived)
    }

    @Test
    fun shouldTakeRecruiterResponsePrecedenceOverJdNotFound() {
        // Given - state with both isRecruiterResponseRequired=true and isJobPosting=false
        val state = JDState(
            IntakeContext.Email(
                emailId = "test-email-001",
                from = "",
                subject = "",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            ),
            isRecruiterResponseRequired = true,
            isJobPosting = false
        )

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then - Recruiter_Response_Required path taken, NOT JD_Not_Found
        assertEquals("Recruiter_Response_Required", result.labelApplied)
        verify(gmailClient, times(1)).getOrCreateLabel("Recruiter_Response_Required")
        verify(gmailClient, never()).getOrCreateLabel("JD_Not_Found")
    }

    // ============================================================================
    // Digest Email Tests (Section 5.3 of Design Doc)
    // ============================================================================

    @Test
    fun shouldLabelDigestWithCorrectLabel() {
        // Given
        val state = TestJDStateFactory.createDigestState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).getOrCreateLabel("JD_Processed_Digest")
    }

    @Test
    fun shouldArchiveDigestEmail() {
        // Given
        val state = TestJDStateFactory.createDigestState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).archiveEmail("email-789")
    }

    @Test
    fun shouldNotStarDigest() {
        // Given
        val state = TestJDStateFactory.createDigestState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, never()).starEmail(any())
    }

    @Test
    fun shouldNotMarkDigestUnread() {
        // Given
        val state = TestJDStateFactory.createDigestState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, never()).markUnread(any())
    }

    @Test
    fun shouldHandleInlineDigest() {
        // Given
        val state = TestJDStateFactory.createInlineDigestState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).getOrCreateLabel("JD_Processed_Digest")
        verify(gmailClient, times(1)).archiveEmail("inline-digest-001")
    }

    @Test
    fun shouldReturnCorrectLabelingResultForDigest() {
        // Given
        val state = TestJDStateFactory.createDigestState()

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then
        assertEquals("JD_Processed_Digest", result.labelApplied)
        assertTrue(result.wasArchived)
        assertFalse(result.wasStarred)
        assertFalse(result.wasMarkedUnread)
        assertFalse(result.wasKeptInInbox)
    }

    // ============================================================================
    // Regular Job Posting Tests (Section 5.4 of Design Doc)
    // ============================================================================

    @Test
    fun shouldLabelRegularJobPosting() {
        // Given - regular job posting (not digest, not inline digest)
        val state = TestJDStateFactory.createFullJobPostingState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).getOrCreateLabel("JD_Processed")
    }

    @Test
    fun shouldArchiveRegularJobPosting() {
        // Given
        val state = TestJDStateFactory.createFullJobPostingState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).archiveEmail("email-123")
    }

    @Test
    fun shouldNotStarRegularJobPosting() {
        // Given
        val state = TestJDStateFactory.createFullJobPostingState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, never()).starEmail(any())
    }

    @Test
    fun shouldNotMarkRegularJobUnread() {
        // Given
        val state = TestJDStateFactory.createFullJobPostingState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, never()).markUnread(any())
    }

    @Test
    fun shouldReturnCorrectLabelingResultForRegularJobPosting() {
        // Given
        val state = TestJDStateFactory.createFullJobPostingState()

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then
        assertEquals("JD_Processed", result.labelApplied)
        assertTrue(result.wasArchived)
        assertFalse(result.wasStarred)
        assertFalse(result.wasMarkedUnread)
        assertFalse(result.wasKeptInInbox)
    }

    @Test
    fun shouldNotArchiveWhenErrorIsSet() {
        // Given - a regular job posting that errored
        val state = TestJDStateFactory.createFullJobPostingState().copy(
            error = "render_pdf: failed"
        )

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then
        assertEquals("JD_Error", result.labelApplied)
        assertFalse(result.wasArchived)
        assertTrue(result.wasKeptInInbox)
        assertTrue(result.wasMarkedUnread)
        verify(gmailClient, times(1)).getOrCreateLabel("JD_Error")
        verify(gmailClient, never()).archiveEmail(any())
    }

    @Test
    fun shouldTakeErrorPrecedenceOverDigest() {
        // Given - digest with an error
        val state = TestJDStateFactory.createDigestState().copy(
            error = "tailor_subgraph: failed"
        )

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then - JD_Error takes precedence, NOT JD_Processed_Digest
        assertEquals("JD_Error", result.labelApplied)
        verify(gmailClient, times(1)).getOrCreateLabel("JD_Error")
        verify(gmailClient, never()).getOrCreateLabel("JD_Processed_Digest")
        verify(gmailClient, never()).archiveEmail(any())
    }

    @Test
    fun shouldTakeErrorPrecedenceOverRecruiterResponse() {
        // Given - recruiter response required with an error
        val state = TestJDStateFactory.createRecruiterResponseRequiredState().copy(
            error = "tailor_subgraph: failed"
        )

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then - JD_Error takes precedence, NOT Recruiter_Response_Required
        assertEquals("JD_Error", result.labelApplied)
        verify(gmailClient, times(1)).getOrCreateLabel("JD_Error")
        verify(gmailClient, never()).getOrCreateLabel("Recruiter_Response_Required")
        verify(gmailClient, never()).archiveEmail(any())
    }

    // ============================================================================
    // Edge Cases (Section 5.5 of Design Doc)
    // ============================================================================

    @Test
    fun shouldHandleEmptyEmailId() {
        // Given
        val state = JDState(
            IntakeContext.Email(
                emailId = "",
                from = "",
                subject = "",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            ),
            isJobPosting = false
        )

        // When - should not crash
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then
        verify(gmailClient, times(1)).labelEmail(eq(""), any())
        assertEquals("JD_Not_Found", result.labelApplied)
    }

    @Test
    fun shouldHandleNullLabelId() {
        // Given - mock returns null/empty for label
        whenever(gmailClient.getOrCreateLabel("JD_Not_Found")).thenReturn("")
        // Re-stub void method after changing the mock behavior
        doNothing().whenever(gmailClient).labelEmail(any(), eq(""))

        val state = TestJDStateFactory.createJdNotFoundState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then - labelEmail should still be called because the service calls it
        // (GmailTransport.labelEmail checks for empty labelId internally)
        verify(gmailClient, times(1)).labelEmail(any(), eq(""))
    }

    @Test
    fun shouldHandleRecruiterResponseWithDigest() {
        // Given - both Recruiter_Response_Required and isDigest are true
        val state = TestJDStateFactory.createDigestState().copy(
            isRecruiterResponseRequired = true
        )

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then - Recruiter_Response_Required takes precedence (checked first in when expression)
        assertEquals("Recruiter_Response_Required", result.labelApplied)
        verify(gmailClient, times(1)).getOrCreateLabel("Recruiter_Response_Required")
        verify(gmailClient, never()).getOrCreateLabel("JD_Processed_Digest")
    }

    @Test
    fun shouldHandleAllFalseFlags() {
        // Given - all flags are false, should fall through to JD_Not_Found path
        val state = JDState(
            IntakeContext.Email(
                emailId = "all-false-001",
                from = "",
                subject = "",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            ),
            isJobPosting = false,
            isRecruiterResponseRequired = false
        )

        // When
        val result = labelingService.applyLabeling(state, gmailClient)

        // Then - JD_Not_Found path taken (correct default)
        assertEquals("JD_Not_Found", result.labelApplied)
        assertTrue(result.wasMarkedUnread)
        assertFalse(result.wasArchived)
    }

    @Test
    fun shouldHandleEmptyLabelName() {
        // Given - getOrCreateLabel returns empty string
        whenever(gmailClient.getOrCreateLabel(any())).thenReturn("")
        // Re-stub void method after changing the mock behavior
        doNothing().whenever(gmailClient).labelEmail(any(), eq(""))

        val state = TestJDStateFactory.createFullJobPostingState()

        // When
        labelingService.applyLabeling(state, gmailClient)

        // Then - labelEmail is called with empty string (the service calls it,
        // but GmailTransport.labelEmail checks for empty labelId internally before making API call)
        verify(gmailClient, times(1)).labelEmail(any(), eq(""))
    }
}
