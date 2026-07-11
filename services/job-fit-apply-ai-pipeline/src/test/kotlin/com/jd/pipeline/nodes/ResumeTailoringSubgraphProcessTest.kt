package com.jd.pipeline.nodes

import com.jd.pipeline.models.*
import com.jd.pipeline.nodes.tailor.*
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("ResumeTailoringSubgraph — process()")
class ResumeTailoringSubgraphProcessTest {

    private fun minProfile() = CandidateProfile(
        identity = CandidateIdentity(
            name = "Jane Doe", firstName = "Jane", lastName = "Doe",
            email = "jane@doe.com", phone = "555-1234", location = "Seattle, WA"
        ),
        background = CandidateBackground(
            targetTitle = "Staff SDET", yearsExperience = 10,
            summary = "Experienced SDET.", education = emptyList(),
            careerHistory = listOf(
                CareerEntry(
                    role = "Senior SDET", company = "Acme",
                    startDate = "2020-01", endDate = "2024-01",
                    location = "",
                    bullets = listOf("Led mobile test framework."),
                )
            ),
            coreStrengths = listOf("Mobile automation"),
            languages = emptyList(),
            domainExpertise = listOf("SDET")
        ),
        skills = CandidateSkills(
            primaryStack = listOf("Kotlin"),
            mobileAutomation = listOf("Appium"),
            ciCdPlatforms = listOf("GitHub Actions"),
            webApiAutomation = listOf("Playwright"),
            infrastructureObservability = listOf("K8s"),
            leadershipAbilities = listOf("Mentoring")
        )
    )

    private fun jdText(wordCount: Int = 200): String =
        "We are hiring a Staff SDET with mobile automation experience. ".repeat(wordCount / 10)

    private fun tailorInput(jdText: String = jdText(), @TempDir tempDir: Path? = null): JDState {
        val base = JDState(
            isJobPosting     = true,
            company          = "Acme",
            roleTitle        = "Staff SDET",
            jdText           = jdText,
            pipelineAction   = PipelineAction.TAILOR,
            candidateProfile = minProfile(),
        )
        return if (tempDir != null) base.copy(outputPath = tempDir.toString()) else base
    }

    // ── Non-TAILOR guard ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("non-TAILOR input passes through unchanged")
    inner class NonTailorPassThrough {

        @Test
        @DisplayName("SKIP action → returns input unchanged")
        fun skipPassesThrough() {
            val subgraph = ResumeTailoringSubgraph()
            val input = JDState(pipelineAction = PipelineAction.SKIP, company = "Corp")
            val result = subgraph.process(input)
            assertEquals(input, result)
        }

        @Test
        @DisplayName("no tailor nodes are called when action is SKIP")
        fun noNodesCalledForSkip() {
            val mockJdExt = mock<JdExtractionNode>()
            val subgraph = ResumeTailoringSubgraph(jdExtraction = mockJdExt)
            subgraph.process(JDState(pipelineAction = PipelineAction.SKIP))
            verify(mockJdExt, never()).process(any())
        }
    }

    // ── Null candidateProfile guard ───────────────────────────────────────────

    @Nested
    @DisplayName("null candidateProfile guard")
    inner class NullProfileGuard {

        @Test
        @DisplayName("returns error state when candidateProfile is null")
        fun nullProfileReturnsError(@TempDir tempDir: Path) {
            val subgraph = ResumeTailoringSubgraph()
            val input = tailorInput(tempDir = tempDir).copy(candidateProfile = null)
            val result = subgraph.process(input)
            assertTrue(result.error.isNotBlank(), "Expected error for null profile")
            assertTrue(result.error.contains("candidateProfile is null"))
        }
    }

    // ── Sparse jdText guard ───────────────────────────────────────────────────

    @Nested
    @DisplayName("sparse jdText (< 50 non-URL words)")
    inner class SparseJdText {

        @Test
        @DisplayName("renders untailored profile and returns without calling tailor nodes")
        fun sparseJdSkipsTailorNodes(@TempDir tempDir: Path) {
            val mockJdExt = mock<JdExtractionNode>()
            val mockHtmlNode = mock<GenerateResumeHtmlNode>()
            whenever(mockHtmlNode.renderFromProfile(any())).thenReturn("<html>resume</html>")
            val subgraph = ResumeTailoringSubgraph(
                jdExtraction = mockJdExt,
                resumeHtmlNode = mockHtmlNode,
            )
            val sparse = "short jd text"
            val input = tailorInput(jdText = sparse).copy(
                outputPath = tempDir.toString()
            )
            subgraph.process(input)
            verify(mockJdExt, never()).process(any())
            verify(mockHtmlNode).renderFromProfile(any())
        }
    }

    // ── Full orchestration with mock nodes ────────────────────────────────────

    @Nested
    @DisplayName("full tailor orchestration with mock nodes")
    inner class FullOrchestration {

        private fun passThrough(state: TailorState) = state

        @Test
        @DisplayName("all six tailor nodes are called in sequence")
        fun allNodesCalledInSequence(@TempDir tempDir: Path) {
            val calls = mutableListOf<String>()

            fun trackingNode(name: String) = JdExtractionNode::class.java.let {
                // We return mock nodes that log their call order and pass state through
            }

            val jdExt  = mock<JdExtractionNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "jdExtraction"
                    (inv.arguments[0] as TailorState).copy(
                        jdStructured = JdStructured("Staff SDET", "Staff", listOf("Kotlin"))
                    )
                }
            }
            val gapAn  = mock<GapAnalysisNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "gapAnalysis"
                    (inv.arguments[0] as TailorState).copy(
                        gapAnalysis = GapAnalysis(topGaps = listOf("iOS"), topStrengths = listOf("Kotlin"))
                    )
                }
            }
            val sumRew = mock<SummaryRewriteNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "summaryRewrite"
                    (inv.arguments[0] as TailorState).copy(tailoredSummary = "Tailored summary.")
                }
            }
            val bulRew = mock<BulletRewriteNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "bulletRewrite"
                    (inv.arguments[0] as TailorState).copy(
                        tailoredBullets = listOf(TailoredBullet("orig", "rewritten", 90)),
                        tailoredCareerHistory = minProfile().background.careerHistory,
                    )
                }
            }
            val sklRst = mock<SkillsRestructureNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "skillsRestructure"
                    (inv.arguments[0] as TailorState).copy(
                        restructuredSkills = RestructuredSkills(
                            restructuredText = "Kotlin | Playwright",
                            jdMatchedSkills = listOf("Kotlin"),
                            groupedByCategory = mapOf("Languages" to listOf("Kotlin"))
                        )
                    )
                }
            }
            val atsSc  = mock<AtsScoringNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "atsScoring"
                    (inv.arguments[0] as TailorState).copy(
                        atsScore = AtsScore(overallScore = 88)
                    )
                }
            }
            val htmlNd = mock<GenerateResumeHtmlNode>().apply {
                whenever(renderFromProfile(any())).doReturn("<html>tailored</html>")
            }

            val subgraph = ResumeTailoringSubgraph(
                jdExtraction    = jdExt,
                gapAnalysis     = gapAn,
                summaryRewrite  = sumRew,
                bulletRewrite   = bulRew,
                skillsRestructure = sklRst,
                atsScoring      = atsSc,
                resumeHtmlNode  = htmlNd,
            )
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertEquals(
                listOf("jdExtraction", "gapAnalysis", "summaryRewrite", "bulletRewrite", "skillsRestructure", "atsScoring"),
                calls,
                "Nodes must execute in pipeline order"
            )
            assertTrue(result.error.isBlank(), "Expected no error, got: ${result.error}")
        }

        @Test
        @DisplayName("jdExtraction error causes early return without calling later nodes")
        fun jdExtractionErrorEarlyReturn(@TempDir tempDir: Path) {
            val jdExt = mock<JdExtractionNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    (inv.arguments[0] as TailorState).copy(error = "jd_extraction: LLM failed")
                }
            }
            val gapAn = mock<GapAnalysisNode>()
            val htmlNd = mock<GenerateResumeHtmlNode>()

            val subgraph = ResumeTailoringSubgraph(jdExtraction = jdExt, gapAnalysis = gapAn, resumeHtmlNode = htmlNd)
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertTrue(result.error.isNotBlank())
            verify(gapAn, never()).process(any())
        }

        @Test
        @DisplayName("skillsRestructure error is non-fatal: atsScoring is skipped, pipeline continues")
        fun skillsRestructureErrorIsNonFatal(@TempDir tempDir: Path) {
            // Wire all nodes to succeed except skillsRestructure
            val passthroughTailorState: (Any) -> TailorState = { args ->
                (args as TailorState).copy(
                    jdStructured = JdStructured("Staff SDET", "Staff", listOf("Kotlin")),
                    gapAnalysis = GapAnalysis(topStrengths = listOf("Kotlin")),
                    tailoredSummary = "Summary.",
                    tailoredBullets = listOf(TailoredBullet("orig", "rewritten", 90)),
                    tailoredCareerHistory = minProfile().background.careerHistory,
                )
            }
            val jdExt  = mock<JdExtractionNode>().apply { whenever(process(any())).doAnswer { passthroughTailorState(it.arguments[0]) } }
            val gapAn  = mock<GapAnalysisNode>().apply { whenever(process(any())).doAnswer { passthroughTailorState(it.arguments[0]) } }
            val sumRew = mock<SummaryRewriteNode>().apply { whenever(process(any())).doAnswer { passthroughTailorState(it.arguments[0]) } }
            val bulRew = mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer { passthroughTailorState(it.arguments[0]) } }
            val sklRst = mock<SkillsRestructureNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    (inv.arguments[0] as TailorState).copy(error = "skills_restructure: bad LLM output")
                }
            }
            val atsSc  = mock<AtsScoringNode>()
            val htmlNd = mock<GenerateResumeHtmlNode>().apply { whenever(renderFromProfile(any())).doReturn("<html/>") }

            val subgraph = ResumeTailoringSubgraph(jdExt, gapAn, sumRew, bulRew, sklRst, atsSc, htmlNd)
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            // atsScoring must NOT be called when restructuredSkills is null
            verify(atsSc, never()).process(any())
            // Pipeline continues and renders HTML
            verify(htmlNd).renderFromProfile(any())
            assertTrue(result.error.isBlank(), "Non-fatal skills error should not propagate: ${result.error}")
        }
    }
}
