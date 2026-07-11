package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import java.nio.file.Files

/**
 * Tailor subgraph node 6/6: ATS Scoring
 */
class AtsScoringNode(
    private val llm: LlmCaller = LlmClient.orchestrationClient(nodeKey = "ats_scoring")
) {
    private val mapper = ObjectMapper()

    fun process(state: TailorState): TailorState {
        if (state.jdStructured == null) return state.copy(error = "ats_scoring: jdStructured is null")
        if (state.gapAnalysis == null) return state.copy(error = "ats_scoring: gapAnalysis is null")
        if (state.tailoredSummary == null) return state.copy(error = "ats_scoring: tailoredSummary is null")
        if (state.tailoredBullets == null) return state.copy(error = "ats_scoring: tailoredBullets is null")
        if (state.restructuredSkills == null) return state.copy(error = "ats_scoring: restructuredSkills is null")

        println("[ats_scoring] Scoring tailored resume for: ${state.roleTitle}")

        return try {
            val prompt = buildPrompt(state)
            val response = llm.call(prompt)
            val cleaned = stripJsonFences(response)
            val parsed = mapper.readValue(cleaned, AtsScore::class.java)
            println("\u001B[92m[ats_scoring] ATS overall score = ${parsed.overallScore}\u001B[0m")
            state.copy(atsScore = parsed)
        } catch (e: Exception) {
            state.copy(error = "ats_scoring: ${e.message}")
        }
    }

    private fun buildPrompt(state: TailorState): String {
        val jd = state.jdStructured!!
        val gap = state.gapAnalysis!!
        val bullets = state.tailoredBullets!!
        val skills = state.restructuredSkills!!

        val skillPrompt = try {
            if (Files.exists(Config.ATS_SCORING_SKILL)) Files.readString(Config.ATS_SCORING_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val bulletsSample = bullets.take(10).joinToString("\n") { "- ${it.rewritten}" }

        return """
            $skillPrompt

            TARGET ROLE: ${jd.roleTitle} (${jd.seniority}) at ${state.company}
            JD REQUIRED SKILLS: ${jd.requiredSkills.joinToString(", ")}
            JD ATS PHRASES: ${jd.atsExactPhrases.joinToString(", ")}
            KEYWORD COVERAGE (from gap analysis): ${gap.keywordCoverageScore}/100
            REMAINING GAPS: ${gap.topGaps.joinToString(", ")}

            TAILORED SUMMARY:
            ${state.tailoredSummary}

            TAILORED BULLETS (sample):
            $bulletsSample

            RESTRUCTURED SKILLS:
            ${skills.restructuredText}
            JD-MATCHED SKILLS: ${skills.jdMatchedSkills.joinToString(", ")}
        """.trimIndent()
    }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    companion object {
        private val DEFAULT_PROMPT = """
            |You are an ATS expert. Score the tailored resume output against the job description.
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "overall_score": integer (0-100),
            |  "keyword_match": integer (0-100),
            |  "skill_coverage": integer (0-100),
            |  "seniority_alignment": integer (0-100),
            |  "quantification": integer (0-100),
            |  "format_safety": integer (0-100),
            |  "remaining_gaps": ["string", ...],
            |  "top_3_improvements": ["string", ...]
            |}
            |
            |Sub-score guidance:
            |- keyword_match: fraction of JD ATS phrases present in the tailored output
            |- skill_coverage: required skills covered vs. total required
            |- seniority_alignment: title/scope match to the stated level
            |- quantification: fraction of bullets that include a measurable impact
            |- format_safety: absence of tables, columns, graphics, non-ASCII (higher = safer)
            |- overall_score: weighted composite (keyword 30%, skills 25%, seniority 20%, quant 15%, format 10%)
        """.trimMargin()
    }
}
