package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LLM-mocked tests for [AtsScoringNode].
 */
@DisplayName("AtsScoringNodeTest")
class AtsScoringNodeTest {

    private val baseProfile = CandidateProfile(
        identity = CandidateIdentity(name = "X", firstName = "X", lastName = "Y", email = "e", phone = "p", location = "R"),
        background = CandidateBackground(
            targetTitle = "Eng", yearsExperience = 5,
            summary = "", education = emptyList(),
            careerHistory = listOf(CareerEntry("Eng", "Acme", "", "2020", null, listOf("B1"))),
            coreStrengths = emptyList(), languages = emptyList(), domainExpertise = emptyList()
        ),
        skills = CandidateSkills(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    )

    private val baseState = TailorState(
        jdText = "jd",
        candidateProfile = baseProfile,
        fitScore = 80f,
        strengths = emptyList(), gaps = emptyList(),
        company = "Acme", roleTitle = "Eng", trackId = 1,
        jdStructured = JdStructured("Eng", "Sr", listOf("Kotlin")),
        gapAnalysis = GapAnalysis(keywordCoverageScore = 90, topGaps = listOf("Rust")),
        tailoredSummary = "Summary text",
        tailoredCareerHistory = baseProfile.background.careerHistory,
        tailoredProjects = emptyList(),
        tailoredBullets = emptyList(),
        restructuredSkills = RestructuredSkills(restructuredText = "Skills text")
    )

    @Test
    @DisplayName("returns error when any prerequisite is null")
    fun prerequisiteErrors() {
        val node = AtsScoringNode()
        assertTrue(node.process(baseState.copy(jdStructured = null)).error.contains("jdStructured is null"))
        assertTrue(node.process(baseState.copy(gapAnalysis = null)).error.contains("gapAnalysis is null"))
        assertTrue(node.process(baseState.copy(tailoredSummary = null)).error.contains("tailoredSummary is null"))
        assertTrue(node.process(baseState.copy(tailoredBullets = null)).error.contains("tailoredBullets is null"))
        assertTrue(node.process(baseState.copy(restructuredSkills = null)).error.contains("restructuredSkills is null"))
    }

    @Test
    @DisplayName("buildPrompt includes sub-scores and remaining gaps")
    fun buildPromptContent() {
        val node = AtsScoringNode()
        val m = AtsScoringNode::class.java.getDeclaredMethod("buildPrompt", TailorState::class.java)
        m.isAccessible = true
        val prompt = m.invoke(node, baseState) as String
        assertTrue(prompt.contains("TAILORED SUMMARY"))
        assertTrue(prompt.contains("REMAINING GAPS"))
        assertTrue(prompt.contains("KEYWORD COVERAGE"))
    }

    @Test
    @DisplayName("mocked LLM returns parsed AtsScore")
    fun mockedLlmReturnsScore() {
        val json = """
            {"overall_score":88,"keyword_match":90,"skill_coverage":85,"seniority_alignment":80,"quantification":75,"format_safety":95,"remaining_gaps":[],"top_3_improvements":[]}
        """.trimIndent()
        val mockLlm = LlmCaller { json }
        val result = AtsScoringNode(llm = mockLlm).process(baseState)
        assertNotNull(result.atsScore)
        assertEquals(88, result.atsScore!!.overallScore)
        assertEquals("", result.error)
    }
}
