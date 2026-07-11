package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.CandidateProfileRenderer
import java.nio.file.Files

/**
 * Tailor subgraph node 3/6: Summary Rewrite
 */
class SummaryRewriteNode(
    private val llm: LlmCaller = LlmClient.reasoningClient(nodeKey = "summary_rewrite")
) {

    fun process(state: TailorState): TailorState {
        val jd = state.jdStructured ?: return state.copy(error = "summary_rewrite: jdStructured is null")
        val gap = state.gapAnalysis ?: return state.copy(error = "summary_rewrite: gapAnalysis is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "summary_rewrite: candidateProfile is null")

        println("[summary_rewrite] Rewriting summary for: ${state.roleTitle} @ ${state.company}")

        return try {
            val prompt = buildPrompt(jd, gap, state, CandidateProfileRenderer.renderForTailoring(profile))
            val summary = llm.call(prompt).trim()
            println("[summary_rewrite] Summary length: ${summary.length} chars")
            state.copy(tailoredSummary = summary)
        } catch (e: Exception) {
            state.copy(error = "summary_rewrite: ${e.message}")
        }
    }

    private fun buildPrompt(jd: JdStructured, gap: GapAnalysis, state: TailorState, profileMarkdown: String): String {
        val skillPrompt = try {
            if (Files.exists(Config.SUMMARY_REWRITE_SKILL)) Files.readString(Config.SUMMARY_REWRITE_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val currentSummary = state.candidateProfile?.background?.summary?.takeIf { it.isNotBlank() }
            ?: "(no prior summary on the profile)"

        return """
            $skillPrompt

            TARGET ROLE: ${jd.roleTitle} (${jd.seniority}) at ${state.company}
            REQUIRED SKILLS: ${jd.requiredSkills.take(10).joinToString(", ")}
            ATS PHRASES TO INCLUDE: ${jd.atsExactPhrases.take(5).joinToString(", ")}
            COMPANY VALUE SIGNALS: ${jd.companyValueSignals.joinToString(", ")}
            TOP STRENGTHS (from gap analysis): ${gap.topStrengths.take(5).joinToString(", ")}

            CURRENT SUMMARY (refine — do not start from scratch):
            $currentSummary

            CANDIDATE PROFILE (structured; do not fabricate anything not here):
            $profileMarkdown
        """.trimIndent()
    }

    companion object {
        private val DEFAULT_PROMPT = """
            |You are a professional resume writer. Rewrite the candidate's summary section to align with
            |the target role. Write in first-person-implicit (no "I") professional tone.
            |
            |Rules:
            |- Maximum 4 sentences.
            |- Use only experience, skills, and metrics already evidenced in the resume. Do not fabricate.
            |- Naturally incorporate at least 2 of the ATS phrases provided.
            |- Mirror the seniority level of the target role.
            |- Plain text only — no markdown, no bullet points, no special characters.
            |- Output ONLY the rewritten summary text. No preamble, no explanation.
        """.trimMargin()
    }
}
