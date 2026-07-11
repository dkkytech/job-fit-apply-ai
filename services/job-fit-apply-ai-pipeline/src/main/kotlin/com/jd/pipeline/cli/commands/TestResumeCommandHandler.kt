package com.jd.pipeline.cli.commands

import com.jd.pipeline.nodes.RenderResumePdfNode
import com.jd.pipeline.nodes.tailor.ResumeTailoringSubgraph
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction

object TestResumeCommandHandler {
    fun run() {
        println("[INFO] Testing resume tailoring subgraph + PDF render...")

        val mockState = JDState(
            isJobPosting = true,
            company = "Acme Corp",
            roleTitle = "Staff Software Engineer in Test",
            location = "Remote (US)",
            remotePolicy = "Remote",
            fitScore = 88.0f,
            pipelineAction = PipelineAction.TAILOR,
            jdText = """
                Staff Software Engineer in Test — Acme Corp (Remote)

                About the role:
                We are looking for a Staff SDET to lead quality engineering across our mobile and
                backend platforms. You will own the test strategy, build automation frameworks from
                scratch, and partner closely with engineering leadership to define quality gates.

                Requirements:
                - 7+ years of software engineering experience with a focus on test automation
                - Expert-level proficiency in Kotlin and/or Swift for mobile test automation
                - Hands-on experience with Espresso, XCUITest, and Jetpack Compose UI testing
                - Strong CI/CD skills — Bitrise, GitHub Actions, or CircleCI pipeline ownership
                - Experience building test infrastructure: device farms (Firebase Test Lab, BrowserStack)
                - Solid understanding of API testing (REST, gRPC) and contract testing (Pact)
                - Ability to mentor engineers and drive testing culture across teams
                - Experience with observability tools: Datadog, Grafana, or equivalent

                Preferred:
                - Kotlin Multiplatform (KMP) experience
                - Contributions to open-source test tooling
                - Experience in a platform or infrastructure team at scale

                What we value:
                - Ownership mentality — you treat quality as a product, not a phase
                - Data-driven quality: metrics, flakiness dashboards, coverage gates
                - Move fast without breaking things
            """.trimIndent(),
            strengths = listOf(
                "Mobile automation depth with Espresso and XCUITest",
                "CI/CD pipeline ownership on Bitrise and GitHub Actions",
                "KMP expertise across Android and iOS"
            ),
            gaps = listOf("gRPC contract testing (Pact) not explicitly listed on resume"),
            redFlags = emptyList(),
            fitReasoning = "Strong alignment with mobile test automation, CI/CD, and staff-level leadership requirements.",
            techStack = listOf("Kotlin", "Espresso", "XCUITest", "Bitrise", "GitHub Actions", "KMP", "Firebase Test Lab")
        )

        // Step 1: Run tailoring subgraph (produces tailored_resume.html + .txt files)
        val subgraph = ResumeTailoringSubgraph()
        val tailored = subgraph.process(mockState)

        if (tailored.error.isNotEmpty()) {
            System.err.println("[ERROR] Tailoring failed: ${tailored.error}")
            return
        }
        println("[OK] Tailoring complete → ${tailored.outputPath}")

        // Step 2: Render PDF from tailored_resume.html
        val rendered = RenderResumePdfNode().process(tailored)

        if (rendered.error.isNotEmpty()) {
            System.err.println("[ERROR] PDF render failed: ${rendered.error}")
        } else if (rendered.resumeHtmlPdf.isNotEmpty()) {
            println("[OK] PDF generated → ${rendered.resumeHtmlPdf}")
        }
    }
}
