package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.nodes.GenerateResumeHtmlNode
import com.jd.pipeline.nodes.Node
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.utils.OutputUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * ResumeTailoringSubgraph — the Node<JDState> entry point.
 *
 * Runs six sequential tailoring nodes on an internal [TailorState] (read
 * exclusively from `candidateProfile`, never from HTML), assembles a
 * [TailoredProfile] from the outputs, and renders it to
 * `output/<timestamp>/tailored_resume.html` via
 * [GenerateResumeHtmlNode.renderFromProfile].
 *
 * Pipeline:
 *   JdExtractionNode → GapAnalysisNode → SummaryRewriteNode
 *   → BulletRewriteNode → SkillsRestructureNode → AtsScoringNode
 */
class ResumeTailoringSubgraph(
    private val jdExtraction: JdExtractionNode        = JdExtractionNode(),
    private val gapAnalysis: GapAnalysisNode          = GapAnalysisNode(),
    private val summaryRewrite: SummaryRewriteNode    = SummaryRewriteNode(),
    private val bulletRewrite: BulletRewriteNode      = BulletRewriteNode(),
    private val skillsRestructure: SkillsRestructureNode = SkillsRestructureNode(),
    private val atsScoring: AtsScoringNode            = AtsScoringNode(),
    private val resumeHtmlNode: GenerateResumeHtmlNode = GenerateResumeHtmlNode(),
) : Node<JDState> {

    private val mapper = ObjectMapper()

    override fun process(input: JDState): JDState {
        if (input.pipelineAction != PipelineAction.TAILOR) return input

        println("[tailor_subgraph] Starting tailoring pipeline for: ${input.roleTitle} @ ${input.company}")

        // ── Resolve / create output directory ─────────────────────────────────
        val outputDir = OutputUtils.getOutputDirectory(input).also { Files.createDirectories(it) }
        val outputPath = outputDir.toString()

        // ── Guard: candidateProfile must be loaded ─────────────────────────────
        val profile = input.candidateProfile
        if (profile == null) {
            System.err.println("[tailor_subgraph] ERROR: candidateProfile is null — run --init-profile first")
            return input.copy(outputPath = outputPath, error = "tailor_subgraph: candidateProfile is null")
        }

        // ── Guard: jdText must have enough content to tailor meaningfully ──────
        // If the JD is just metadata (e.g. LinkedIn scrape failed), render the
        // untailored profile so the PDF render step still produces an artifact.
        val jdWordCount = input.jdText.split(Regex("\\s+"))
            .count { it.isNotBlank() && !it.startsWith("http") }
        if (jdWordCount < 50) {
            System.err.println("[tailor_subgraph] WARN: jdText too sparse ($jdWordCount non-URL words) — rendering untailored profile")
            writeUntailoredHtml(outputDir, profile)
            return input.copy(outputPath = outputPath)
        }

        // ── Initialise TailorState ─────────────────────────────────────────────
        var state = TailorState(
            jdText = input.jdText,
            candidateProfile = profile,
            fitScore = input.fitScore ?: 0f,
            strengths = input.strengths,
            gaps = input.gaps,
            company = input.company,
            roleTitle = input.roleTitle,
            trackId = input.trackId ?: 0,
            jdStructured = input.jdStructured  // pre-extracted by score_fit; skips JdExtractionNode LLM call
        )

        // ── Sequential execution — early exit on node error ────────────────────
        state = jdExtraction.process(state)
        if (state.error.isNotEmpty()) {
            System.err.println("[tailor_subgraph] ERROR (jd_extraction): ${state.error}")
            return input.copy(outputPath = outputPath, error = state.error)
        }

        state = gapAnalysis.process(state)
        if (state.error.isNotEmpty()) {
            System.err.println("[tailor_subgraph] ERROR (gap_analysis): ${state.error}")
            return input.copy(outputPath = outputPath, error = state.error)
        }

        state = summaryRewrite.process(state)
        if (state.error.isNotEmpty()) {
            System.err.println("[tailor_subgraph] ERROR (summary_rewrite): ${state.error}")
            return input.copy(outputPath = outputPath, error = state.error)
        }

        state = bulletRewrite.process(state)
        if (state.error.isNotEmpty()) {
            System.err.println("[tailor_subgraph] ERROR (bullet_rewrite): ${state.error}")
            return input.copy(outputPath = outputPath, error = state.error)
        }

        state = skillsRestructure.process(state)
        if (state.error.isNotEmpty()) {
            // Non-fatal: render with the untailored skill buckets and skip ats_scoring
            System.err.println("[tailor_subgraph] WARN (skills_restructure): ${state.error} — proceeding without restructured skills")
            state = state.copy(error = "")
        }

        if (state.restructuredSkills != null) {
            state = atsScoring.process(state)
        }
        if (state.error.isNotEmpty()) {
            System.err.println("[tailor_subgraph] ERROR (ats_scoring): ${state.error}")
            return input.copy(outputPath = outputPath, error = state.error)
        }

        // ── Save output files ──────────────────────────────────────────────────
        saveOutputFiles(outputDir, state)

        // ── Render tailored HTML from structured profile ──────────────────────
        return try {
            val tailored = buildTailoredProfile(profile, state)
            val html = resumeHtmlNode.renderFromProfile(tailored)
            Files.writeString(outputDir.resolve("tailored_resume.html"), html)
            println("[tailor_subgraph] tailored_resume.html written to $outputDir")

            println("[tailor_subgraph] Complete — ATS score: ${state.atsScore?.overallScore} → $outputPath")

            input.copy(outputPath = outputPath)
        } catch (e: Exception) {
            val msg = "tailor_subgraph: render_resume_html failed: ${e.message}"
            System.err.println("[tailor_subgraph] ERROR: $msg")
            input.copy(outputPath = outputPath, error = msg)
        }
    }

    /**
     * Build a [TailoredProfile] from the subgraph's outputs. Falls back to the
     * corresponding field on the base profile when a tailored field is null
     * (e.g. when skills_restructure failed non-fatally).
     */
    private fun buildTailoredProfile(profile: com.jd.pipeline.models.CandidateProfile, state: TailorState): TailoredProfile {
        val skillGroups = state.restructuredSkills?.groupedByCategory
            ?.takeIf { it.isNotEmpty() }
            ?: TailoredProfile.untailored(profile).skillGroups
        val jdMatched = state.restructuredSkills?.jdMatchedSkills ?: emptyList()
        return TailoredProfile(
            base = profile,
            summary = state.tailoredSummary?.takeIf { it.isNotBlank() } ?: profile.background.summary,
            careerHistory = state.tailoredCareerHistory ?: profile.background.careerHistory,
            projects = state.tailoredProjects ?: profile.projects,
            skillGroups = skillGroups,
            jdMatchedSkills = jdMatched
        )
    }

    private fun writeUntailoredHtml(outputDir: Path, profile: com.jd.pipeline.models.CandidateProfile) {
        try {
            val html = resumeHtmlNode.renderFromProfile(TailoredProfile.untailored(profile))
            Files.writeString(outputDir.resolve("tailored_resume.html"), html)
            println("[tailor_subgraph] Wrote untailored render of candidate profile (no JD content to tailor against)")
        } catch (e: Exception) {
            System.err.println("[tailor_subgraph] WARN: failed to write untailored tailored_resume.html: ${e.message}")
        }
    }

    private fun saveOutputFiles(outputDir: Path, state: TailorState) {
        // tailored_summary.txt
        state.tailoredSummary?.let { summary ->
            try {
                Files.writeString(outputDir.resolve("tailored_summary.txt"), summary)
            } catch (e: Exception) {
                System.err.println("[tailor_subgraph] WARN: failed to save tailored_summary.txt: ${e.message}")
            }
        }

        // tailored_bullets.txt — human-readable
        state.tailoredBullets?.let { bullets ->
            try {
                val content = buildString {
                    bullets.forEach { b ->
                        appendLine("ORIGINAL:  ${b.original}")
                        appendLine("REWRITTEN: ${b.rewritten}")
                        appendLine("ALIGNMENT: ${b.jdAlignmentScore}/100")
                        appendLine()
                    }
                }
                Files.writeString(outputDir.resolve("tailored_bullets.txt"), content)
            } catch (e: Exception) {
                System.err.println("[tailor_subgraph] WARN: failed to save tailored_bullets.txt: ${e.message}")
            }
        }

        // restructured_skills.txt
        state.restructuredSkills?.let { skills ->
            try {
                Files.writeString(outputDir.resolve("restructured_skills.txt"), skills.restructuredText)
            } catch (e: Exception) {
                System.err.println("[tailor_subgraph] WARN: failed to save restructured_skills.txt: ${e.message}")
            }
        }

        // ats_score.txt — human-readable scorecard
        state.atsScore?.let { score ->
            try {
                val content = buildString {
                    appendLine("ATS Score Report")
                    appendLine("================")
                    appendLine("Overall Score:       ${score.overallScore}/100")
                    appendLine("Keyword Match:       ${score.keywordMatch}/100")
                    appendLine("Skill Coverage:      ${score.skillCoverage}/100")
                    appendLine("Seniority Alignment: ${score.seniorityAlignment}/100")
                    appendLine("Quantification:      ${score.quantification}/100")
                    appendLine("Format Safety:       ${score.formatSafety}/100")
                    if (score.remainingGaps.isNotEmpty()) {
                        appendLine()
                        appendLine("Remaining Gaps:")
                        score.remainingGaps.forEach { appendLine("  - $it") }
                    }
                    if (score.top3Improvements.isNotEmpty()) {
                        appendLine()
                        appendLine("Top Improvements:")
                        score.top3Improvements.forEach { appendLine("  - $it") }
                    }
                }
                Files.writeString(outputDir.resolve("ats_score.txt"), content)
            } catch (e: Exception) {
                System.err.println("[tailor_subgraph] WARN: failed to save ats_score.txt: ${e.message}")
            }
        }

        // gap_analysis.json — machine-readable for downstream tooling
        state.gapAnalysis?.let { gap ->
            try {
                Files.writeString(
                    outputDir.resolve("gap_analysis.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(gap)
                )
            } catch (e: Exception) {
                System.err.println("[tailor_subgraph] WARN: failed to save gap_analysis.json: ${e.message}")
            }
        }
    }
}
