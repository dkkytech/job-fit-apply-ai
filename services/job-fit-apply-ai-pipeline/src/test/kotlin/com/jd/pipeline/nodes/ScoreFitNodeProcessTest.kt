package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("ScoreFitNode — process()")
class ScoreFitNodeProcessTest {

    private fun baseInput(jdText: String = "We are hiring a Staff SDET. " .repeat(30)) = JDState(
        isJobPosting = true,
        company      = "Acme Corp",
        roleTitle    = "Staff SDET",
        jdText       = jdText,
    )

    private fun scoreJson(
        score: Int = 85,
        action: String = "TAILOR",
        hardGates: List<String> = emptyList(),
        strengths: List<String> = listOf("Kotlin expertise"),
        gaps: List<String> = listOf("iOS experience"),
    ) = """
        {
          "fit_score": $score,
          "fit_reasoning": "Good match on SDET skills",
          "dimension_scores": {"mobile": 80, "cicd": 90},
          "strengths": [${strengths.joinToString(",") { """{"claim":"$it","jd_evidence":"stated"}""" }}],
          "gaps": [${gaps.joinToString(",") { """{"claim":"$it","jd_evidence":"not stated"}""" }}],
          "red_flags": [],
          "hard_gate_violations": ${hardGates.map { "\"$it\"" }},
          "posted_comp_min": 180000,
          "posted_comp_max": 240000,
          "work_arrangement": "hybrid",
          "office_location": "Seattle, WA",
          "confidence": 0.9,
          "role_title": "Staff SDET",
          "seniority": "Staff",
          "required_skills": ["Kotlin","Playwright"],
          "preferred_skills": ["iOS"],
          "domain_keywords": ["mobile","CI/CD"],
          "ats_exact_phrases": ["test automation"],
          "company_value_signals": ["move fast"]
        }
    """.trimIndent()

    // ── Early-exit (no LLM call) ──────────────────────────────────────────────

    @Nested
    @DisplayName("blank jdText — no LLM call")
    inner class BlankJdText {

        @Test
        @DisplayName("returns SKIP with fitScore=0 when jdText is blank")
        fun blankJdTextReturnsSkip() {
            var llmCalled = false
            val node = ScoreFitNode(llm = LlmCaller { llmCalled = true; "{}" })
            val result = node.process(baseInput(jdText = ""))
            assertFalse(llmCalled, "LLM should not be called for blank jdText")
            assertEquals(PipelineAction.SKIP, result.pipelineAction)
            assertEquals(0f, result.fitScore)
        }

        @Test
        @DisplayName("blank jdText sets skippedReason")
        fun blankJdTextSetsReason() {
            val node = ScoreFitNode(llm = LlmCaller { "{}" })
            val result = node.process(baseInput(jdText = "  "))
            assertTrue(result.skippedReason.isNotBlank())
        }
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("valid LLM response")
    inner class ValidResponse {

        @Test
        @DisplayName("high score produces TAILOR action")
        fun highScoreProducesTailor(@TempDir tempDir: Path) {
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(score = 85) })
            val result = node.process(baseInput().withOutputDir(tempDir))
            assertEquals(PipelineAction.TAILOR, result.pipelineAction)
            assertEquals(85f, result.fitScore)
        }

        @Test
        @DisplayName("LLM response with score 30 sets fitScore to 30")
        fun scoreParsedCorrectly() {
            // We don't assert the action here as it depends on FIT_THRESHOLD env config.
            // We just verify the score is correctly read from the LLM response.
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(score = 30) })
            val result = node.process(baseInput())
            assertEquals(30f, result.fitScore)
        }

        @Test
        @DisplayName("strengths and gaps are populated from LLM response")
        fun strengthsAndGapsPopulated() {
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(
                score = 30,
                strengths = listOf("Strong Kotlin skills", "Mobile test expertise"),
                gaps = listOf("No iOS experience")
            ) })
            val result = node.process(baseInput())
            assertEquals(2, result.strengths.size)
            assertEquals(1, result.gaps.size)
            assertTrue(result.strengths.any { it.contains("Kotlin") })
        }

        @Test
        @DisplayName("dimensionScores map is populated")
        fun dimensionScoresPopulated() {
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(score = 30) })
            val result = node.process(baseInput())
            assertTrue(result.dimensionScores.containsKey("mobile"))
            assertTrue(result.dimensionScores.containsKey("cicd"))
        }

        @Test
        @DisplayName("work arrangement and office location are captured")
        fun workArrangementCaptured() {
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(score = 30) })
            val result = node.process(baseInput())
            assertEquals("hybrid", result.workArrangement)
            assertEquals("Seattle, WA", result.officeLocation)
        }

        @Test
        @DisplayName("fitReasoning is populated")
        fun fitReasoningPopulated() {
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(score = 30) })
            val result = node.process(baseInput())
            assertTrue(result.fitReasoning.isNotBlank())
        }
    }

    // ── Hard gate violations ──────────────────────────────────────────────────

    @Nested
    @DisplayName("hard gate violations")
    inner class HardGates {

        @Test
        @DisplayName("hard gate forces SKIP even with high fit score")
        fun hardGateForcesSkip() {
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(
                score = 90,
                hardGates = listOf("Requires active security clearance")
            ) })
            val result = node.process(baseInput())
            assertEquals(PipelineAction.SKIP, result.pipelineAction)
            assertTrue(result.hardGateViolations.isNotEmpty())
        }

        @Test
        @DisplayName("skippedReason contains hard gate description")
        fun skippedReasonContainsGate() {
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(
                score = 90,
                hardGates = listOf("Requires active security clearance")
            ) })
            val result = node.process(baseInput())
            assertTrue(result.skippedReason.contains("Hard gate"))
        }

        @Test
        @DisplayName("skippedReason lists every hard gate, and the score dir is kept for the notes")
        fun skippedReasonListsAllGates() {
            val node = ScoreFitNode(llm = LlmCaller { scoreJson(
                score = 90,
                hardGates = listOf("Requires active security clearance", "Pure manual QA")
            ) })
            val result = node.process(baseInput())
            assertEquals("Hard gate: Requires active security clearance; Pure manual QA", result.skippedReason)
            assertTrue(result.scoreOutputPath.isNotBlank(), "score_fit.txt's dir is kept in state")
            assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(result.scoreOutputPath, "score_fit.txt")))
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("LLM error / malformed response")
    inner class ErrorHandling {

        @Test
        @DisplayName("malformed JSON response produces error state with SKIP")
        fun malformedJsonProducesError() {
            val node = ScoreFitNode(llm = LlmCaller { "not valid json at all {{" })
            val result = node.process(baseInput())
            assertEquals(PipelineAction.SKIP, result.pipelineAction)
            assertEquals(0f, result.fitScore)
            assertTrue(result.error.isNotBlank())
        }

        @Test
        @DisplayName("LLM exception produces error state and does not rethrow")
        fun llmExceptionDoesNotRethrow() {
            val node = ScoreFitNode(llm = LlmCaller { throw RuntimeException("LLM timeout") })
            val result = node.process(baseInput())
            assertEquals(PipelineAction.SKIP, result.pipelineAction)
            assertTrue(result.error.contains("score_fit"))
        }

        @Test
        @DisplayName("empty JSON object gives fitScore 0 (no error path)")
        fun emptyJsonGivesZeroScore() {
            val node = ScoreFitNode(llm = LlmCaller { "{}" })
            val result = node.process(baseInput())
            // fitScore will be 0 because fit_score field is missing / zero
            assertEquals(0f, result.fitScore)
            // error should be blank — parse succeeded even if score is 0
            assertTrue(result.error.isBlank(), "Expected no parse error, got: ${result.error}")
        }
    }

    // ── Strengths/gaps evidence format ────────────────────────────────────────

    @Nested
    @DisplayName("evidence-backed strengths and gaps")
    inner class EvidenceFormat {

        @Test
        @DisplayName("plain string strengths are accepted (legacy format)")
        fun plainStringStrengthsAccepted() {
            val json = """
                {"fit_score": 30, "strengths": ["Strong Kotlin"],
                 "gaps": [], "hard_gate_violations": [],
                 "work_arrangement": "unknown", "office_location": ""}
            """.trimIndent()
            val node = ScoreFitNode(llm = LlmCaller { json })
            val result = node.process(baseInput())
            assertEquals(listOf("Strong Kotlin"), result.strengths)
        }

        @Test
        @DisplayName("evidence object strengths populate strengthsWithEvidence")
        fun evidenceObjectPopulatesWithEvidence() {
            val json = """
                {"fit_score": 30,
                 "strengths": [{"claim": "Kotlin expertise", "jd_evidence": "Kotlin required"}],
                 "gaps": [], "hard_gate_violations": [],
                 "work_arrangement": "unknown", "office_location": ""}
            """.trimIndent()
            val node = ScoreFitNode(llm = LlmCaller { json })
            val result = node.process(baseInput())
            assertEquals(1, result.strengthsWithEvidence.size)
            assertEquals("Kotlin expertise", result.strengthsWithEvidence[0].claim)
            assertEquals("Kotlin required", result.strengthsWithEvidence[0].jdEvidence)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Override outputPath to write score_fit.txt into a temp dir instead of a real output dir. */
    private fun JDState.withOutputDir(tempDir: Path): JDState =
        copy(outputPath = tempDir.resolve("output").also { java.nio.file.Files.createDirectories(it) }.toString())

    @Test
    @DisplayName("the rubric sum of dimensions replaces the model's fit_score and drives the action")
    fun rubricSumReplacesModelScore() {
        // Model claims 85, but its dimensions add up to 45 (no mobile bonus) — under FIT_THRESHOLD.
        val json = scoreJson(score = 85).replace(
            "\"dimension_scores\": {\"mobile\": 80, \"cicd\": 90}",
            "\"dimension_scores\": {\"framework\": 5, \"cicd\": 10, \"web_api\": 10, \"seniority\": 10, " +
                "\"stack_overlap\": 3, \"location\": 5, \"domain\": 2, \"mobile\": 0}",
        )
        val result = ScoreFitNode(llm = LlmCaller { json }).process(baseInput())
        assertEquals(45f, result.fitScore)
        assertEquals(PipelineAction.SKIP, result.pipelineAction)
    }
}
