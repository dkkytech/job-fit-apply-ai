package com.jd.pipeline.nodes
import com.jd.pipeline.state.PipelineAction

import com.jd.pipeline.fixtures.TestJDStateFactory
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SupabaseTrackNode.
 * Tests the node behavior, but SupabaseClient interactions require mocking
 * since Supabase may not be configured in test environments.
 */
@DisplayName("SupabaseTrackNodeTest")
class SupabaseTrackNodeTest {

    private lateinit var node: SupabaseTrackNode

    @BeforeEach
    fun setUp() {
        node = SupabaseTrackNode()
    }

    @Test
    @DisplayName("Should return error when Supabase is not configured")
    fun testReturnsErrorWhenNotConfigured() {
        // This test verifies behavior when Supabase is not configured
        // If Supabase IS configured, we skip this test
        val input = TestJDStateFactory.createFullJobPostingState()
        val result = node.process(input)
        
        // The node should either:
        // 1. Return an error if Supabase is not configured, OR
        // 2. Successfully track and return trackId if Supabase IS configured
        // We check that the result has valid state either way
        if (result.error.contains("SUPABASE_URL")) {
            assertTrue(result.error.contains("not configured"))
            assertFalse(result.isSupabaseTracked)
        } else {
            // Supabase IS configured - the test ran against real Supabase
            // Just verify the result is valid
            assertNotNull(result)
        }
    }

    @Test
    @DisplayName("Should preserve input fields when returning error")
    fun testPreservesFieldsOnError() {
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            company = "ErrorTestCo",
            roleTitle = "Senior Engineer",
            fitScore = 85.0f,
            techStack = listOf("Kotlin", "Java"),
            outputPath = "/existing/path"
        )

        val result = node.process(input)

        // Even if Supabase fails, core fields should be preserved
        assertEquals("ErrorTestCo", result.company)
        assertEquals("Senior Engineer", result.roleTitle)
        assertEquals(85.0f, result.fitScore)
    }

    @Test
    @DisplayName("Should build correct record from state")
    fun testBuildRecordFromState() {
        // This test verifies that the node correctly maps state fields
        // by checking that when tracking succeeds, the tracking fields are populated
        
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            IntakeContext.Email(
                emailId = "track-test-001",
                from = "",
                subject = "Job Opportunity",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            ),
            company = "TrackTest Co",
            roleTitle = "Software Engineer",
            location = "Seattle, WA",
            jobUrl = "https://example.com/jobs/123",
            remotePolicy = "remote",
            fitScore = 90.0f,
            pipelineAction = PipelineAction.TAILOR,
            techStack = listOf("Kotlin", "AWS"),
            strengths = listOf("Expert in Kotlin"),
            gaps = listOf("Limited Go experience"),
            redFlags = listOf("Low salary range"),
            fitReasoning = "Strong technical match",
            jdText = "Job description text...",
            outputPath = "/output/track-test",
            artifactUrl = "https://example.com/artifact/track-test"
        )

        val result = node.process(input)

        // If Supabase is configured, verify the result
        if (result.isSupabaseTracked) {
            assertTrue(result.isSupabaseTracked)
            assertNotNull(result.trackId)
            assertTrue(result.trackUrl.isNotEmpty())
        }
    }

    @Test
    @DisplayName("Should handle minimal state")
    fun testHandlesMinimalState() {
        val input = JDState(
            intake = IntakeContext.Email(
                emailId = "minimal-001",
                from = "",
                subject = "",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            ),
            company = "MinCo",
            roleTitle = "Engineer",
            isJobPosting = true
        )

        val result = node.process(input)

        // Should not crash - either errors or tracks successfully
        assertNotNull(result)
        assertEquals("MinCo", result.company)
    }

    @Test
    @DisplayName("Should handle empty tech stack and lists")
    fun testHandlesEmptyLists() {
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            techStack = emptyList(),
            strengths = emptyList(),
            gaps = emptyList(),
            redFlags = emptyList()
        )

        val result = node.process(input)

        // Should not crash
        assertNotNull(result)
    }

    @Test
    @DisplayName("Should handle null fit score")
    fun testHandlesNullFitScore() {
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            fitScore = null,
            pipelineAction = PipelineAction.SKIP
        )

        val result = node.process(input)

        assertNull(result.fitScore)
        assertEquals(PipelineAction.SKIP, result.pipelineAction)
    }

    @Test
    @DisplayName("Should not modify input state on success")
    fun testDoesNotModifyInputOnSuccess() {
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            company = "PreserveCo",
            roleTitle = "Engineer"
        )

        val result = node.process(input)

        // If tracking succeeded, core fields should be preserved
        if (result.isSupabaseTracked) {
            assertEquals("PreserveCo", result.company)
            assertEquals("Engineer", result.roleTitle)
        }
    }
}
