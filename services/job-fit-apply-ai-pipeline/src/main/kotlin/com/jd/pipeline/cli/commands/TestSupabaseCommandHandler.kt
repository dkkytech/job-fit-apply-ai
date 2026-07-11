package com.jd.pipeline.cli.commands

import com.jd.pipeline.config.Config
import com.jd.pipeline.nodes.SupabaseTrackNode
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction

object TestSupabaseCommandHandler {
    fun run() {
        println("[INFO] Testing Supabase integration...")

        if (Config.SUPABASE_PROJECT_URL.isEmpty() || Config.SUPABASE_SERVICE_ROLE_KEY.isEmpty()) {
            println("[WARN] SUPABASE not configured - skipping test")
            return
        }

        val mockState = JDState(
            intake = IntakeContext.Email(
                emailId = "test-supabase-001",
                subject = "Staff SDET Opportunity – Mobile Platform @ Acme Corp",
                from = "recruiter@acmecorp.com",
                rawBody = "We are looking for a Staff SDET...",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false,
            ),
            isJobPosting = true,
            roleTitle = "Staff SDET (Mobile Platform)",
            company = "Acme Corp",
            location = "Seattle",
            remotePolicy = "Hybrid",
            fitScore = 95.0f,
            pipelineAction = PipelineAction.TAILOR,
            techStack = listOf("XCUITest", "Espresso", "Bitrise", "GitHub Actions"),
            strengths = listOf("Mobile automation depth", "CI/CD ownership"),
            gaps = listOf("Kotlin/Swift not primary"),
            fitReasoning = "Strong alignment with mobile test automation requirements.",
            jdText = "We are looking for a Staff SDET...",
            outputPath = "/tmp/test_supabase_output",
            artifactUrl = "https://example.com/artifacts/test_supabase_output"
        )

        val supabaseNode = SupabaseTrackNode()
        val result = supabaseNode.process(mockState)

        if (result.isSupabaseTracked) {
            println("[OK] Pushed to Supabase: ${result.trackUrl}")
        } else {
            println("[ERROR] Push failed: ${result.error}")
        }

        TestCleanup.removeTestRecord("test-supabase-001")
    }
}
