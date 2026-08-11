package com.jd.pipeline.nodes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.models.EvidenceItem
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.JdStructured
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.isDigest
import java.nio.file.Files

/**
 * Node: score_fit
 *
 * Scores candidate fit AND extracts structured JD fields in a single LLM call.
 * The combined response populates both the fit score fields and [JdStructured] on
 * [JDState], so the tailor subgraph's JdExtractionNode can skip its LLM call entirely.
 *
 * Uses LlmClient (temperature=0, no thinking) for deterministic output.
 */
class ScoreFitNode(
    private val llm: LlmCaller = LlmClient.fromModelString(Config.SCORE_MODEL, jsonMode = true, temperature = 0.0, nodeKey = "score_fit")
) : Node<JDState> {

    private val mapper = ObjectMapper()

    override fun process(input: JDState): JDState {
        val jdText = input.jdText
        if (jdText.isBlank()) {
            return input.copy(
                fitScore = 0f,
                pipelineAction = PipelineAction.SKIP,
                skippedReason = "No JD text to score"
            )
        }
        // A digest child seeds its jdText with the one-line summary from the digest email
        // ("<role> @ <company> | <loc> | <salary> | <url>"). When the follow-up scrape of the real
        // posting fails — blocked, auth wall, or a thin logged-out preview — that stub is what
        // survives, and scoring it produces a confident number derived from a job title and a URL.
        // Refuse instead. Deliberately enforced HERE and not by blanking jdText upstream: the bridge
        // rejects a submit under 150 chars, so a blanked digest child would be silently dropped
        // before it ever got a job of its own. Skipping at score time keeps the terminal label, the
        // completed event and the run-log line intact, so the scrape gap stays visible and retryable.
        if (input.isDigest && jdText.length < MIN_DIGEST_JD_CHARS) {
            println("[score_fit] Skipping ${input.roleTitle} @ ${input.company} — digest stub JD (${jdText.length} chars)")
            return input.copy(
                fitScore = 0f,
                pipelineAction = PipelineAction.SKIP,
                skippedReason = "JD is a ${jdText.length}-char digest summary, not a real posting " +
                    "(the follow-up scrape did not return one)"
            )
        }

        println("[score_fit] Scoring: ${input.roleTitle} @ ${input.company}")

        return try {
            val prompt = "${loadSkillPrompt(input.candidateProfile)}\n\nJOB DESCRIPTION:\n$jdText"
            val response = llm.call(prompt)
            val result = parseLlmResponse(input, response)
            try { saveScoreToFile(result) } catch (e: Exception) {
                System.err.println("[score_fit] WARN: failed to save score_fit.txt: ${e.message}")
            }
            result
        } catch (e: Exception) {
            System.err.println("[score_fit] ERROR: ${e.message}")
            input.copy(fitScore = 0f, error = "score_fit: ${e.message}")
        }
    }

    private fun loadSkillPrompt(candidateProfile: CandidateProfile?): String {
        val template = try {
            if (Files.exists(Config.SCORE_SKILL)) Files.readString(Config.SCORE_SKILL)
            else DEFAULT_SKILL_PROMPT
        } catch (e: Exception) {
            DEFAULT_SKILL_PROMPT
        }
        val profileBlock = candidateProfile?.let { renderCandidateProfile(it) }
            ?: "(no candidate profile loaded — score against generic SDET expectations)"
        return template.replace(CANDIDATE_PROFILE_PLACEHOLDER, profileBlock)
    }

    private fun parseLlmResponse(input: JDState, responseText: String): JDState {
        val cleaned = responseText.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

        return try {
            val node = mapper.readTree(cleaned)
            val score = node.path("fit_score").floatValue().takeIf { it > 0f } ?: 0f
            println("[92m[score_fit] Score fit = $score[0m")

            val strengthsWithEvidence = parseEvidenceArray(node.path("strengths"))
            val gapsWithEvidence = parseEvidenceArray(node.path("gaps"))
            val dimensionScores = parseDimensionScores(node.path("dimension_scores"))
            val llmHardGates = node.path("hard_gate_violations").map { it.asText() }.filter { it.isNotBlank() }
            val compMin = node.path("posted_comp_min").takeIf { !it.isNull && !it.isMissingNode }?.intValue()
            val compMax = node.path("posted_comp_max").takeIf { !it.isNull && !it.isMissingNode }?.intValue()
            val workArrangement = node.path("work_arrangement").asText("unknown")
            val officeLocation = node.path("office_location").asText("")
            val confidence = node.path("confidence").takeIf { !it.isNull && !it.isMissingNode }?.floatValue()

            // Deterministic hard-gate checks using extracted JD data vs profile preferences
            val deterministicGates = computeHardGates(input, compMin, compMax, workArrangement, officeLocation)
            val allHardGates = (llmHardGates + deterministicGates).distinct()
            if (allHardGates.isNotEmpty()) println("[score_fit] Hard-gate violations: $allHardGates")

            val action = when {
                allHardGates.isNotEmpty() -> PipelineAction.SKIP
                score >= Config.FIT_THRESHOLD -> PipelineAction.TAILOR
                else -> PipelineAction.SKIP
            }
            val skippedReason = when {
                allHardGates.isNotEmpty() -> "Hard gate: ${allHardGates.first()}"
                action == PipelineAction.SKIP -> "Fit score below threshold"
                else -> ""
            }

            // Deserialise JdStructured from the same response — null on parse failure (non-fatal)
            val jdStructured = try {
                mapper.treeToValue(node, JdStructured::class.java)
                    .takeIf { it.requiredSkills.isNotEmpty() || it.roleTitle.isNotBlank() }
            } catch (_: Exception) { null }
            if (jdStructured != null) println("[score_fit] JD structure extracted: ${jdStructured.requiredSkills.size} required skills")

            input.copy(
                fitScore = score,
                fitReasoning = node.path("fit_reasoning").asText(""),
                strengths = strengthsWithEvidence.map { it.claim },
                gaps = gapsWithEvidence.map { it.claim },
                redFlags = node.path("red_flags").map { it.asText() },
                strengthsWithEvidence = strengthsWithEvidence,
                gapsWithEvidence = gapsWithEvidence,
                dimensionScores = dimensionScores,
                hardGateViolations = allHardGates,
                salaryRange = input.salaryRange.ifBlank { formatCompRange(compMin, compMax) },
                postedCompMin = compMin,
                postedCompMax = compMax,
                workArrangement = workArrangement,
                officeLocation = officeLocation,
                scoreConfidence = confidence,
                pipelineAction = action,
                skippedReason = skippedReason,
                jdStructured = jdStructured
            )
        } catch (e: Exception) {
            System.err.println("[score_fit] Parse failed: ${e.message}")
            System.err.println("[score_fit] Raw response (first 500 chars): ${cleaned.take(500)}")
            input.copy(
                fitScore = 0f,
                pipelineAction = PipelineAction.SKIP,
                error = "score_fit: Parse failed. Raw: ${cleaned.take(200)}"
            )
        }
    }

    /**
     * Builds a human-readable salary string from the numeric posted-comp fields.
     * Used as a fallback when no salaryRange display string survived scraping.
     * Comp values are full-dollar integers (e.g. 150000 → "$150K").
     */
    private fun formatCompRange(min: Int?, max: Int?): String {
        fun k(v: Int) = if (v >= 1000) "$${v / 1000}K" else "$$v"
        return when {
            min != null && max != null -> "${k(min)} – ${k(max)}"
            min != null -> "${k(min)}+"
            max != null -> "up to ${k(max)}"
            else -> ""
        }
    }

    /**
     * Parses a JSON array that may contain either plain strings or evidence objects
     * ({claim, jd_evidence}). Falls back gracefully for both formats.
     */
    private fun parseEvidenceArray(node: JsonNode): List<EvidenceItem> {
        if (node.isMissingNode || !node.isArray) return emptyList()
        return node.mapNotNull { item ->
            when {
                item.isObject -> EvidenceItem(
                    claim = item.path("claim").asText(""),
                    jdEvidence = item.path("jd_evidence").asText("")
                ).takeIf { it.claim.isNotBlank() }
                item.isTextual -> EvidenceItem(claim = item.asText()).takeIf { it.claim.isNotBlank() }
                else -> null
            }
        }
    }

    private fun parseDimensionScores(node: JsonNode): Map<String, Int> {
        if (node.isMissingNode || !node.isObject) return emptyMap()
        return buildMap {
            node.fields().forEach { (key, value) ->
                if (value.isNumber) put(key, value.intValue())
            }
        }
    }

    /**
     * Computes hard-gate violations deterministically from extracted JD fields vs profile
     * preferences. Returns an empty list when no user profile is loaded.
     */
    private fun computeHardGates(
        input: JDState,
        @Suppress("UNUSED_PARAMETER") compMin: Int?,
        compMax: Int?,
        workArrangement: String,
        officeLocation: String
    ): List<String> {
        val prefs = input.candidateProfile?.preferences ?: return emptyList()
        val identity = input.candidateProfile.identity
        val gates = mutableListOf<String>()

        // Compensation: flag when posted max is explicitly below target
        val targetTc = prefs.minimumTotalCompensation?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        if (targetTc != null && compMax != null && compMax > 0 && compMax < targetTc) {
            gates.add("Compensation band (\$$compMax) is below target (\$$targetTc)")
        }

        // Location: onsite outside candidate's home metro when they won't relocate
        if (workArrangement == "onsite" && officeLocation.isNotBlank() && !prefs.willingToRelocate) {
            val homeCity = identity.location.substringBefore(",").trim().lowercase()
            if (!officeLocation.lowercase().contains(homeCity)) {
                gates.add("Onsite-only in $officeLocation (candidate in ${identity.location}, no relocation)")
            }
        }

        return gates
    }

    private fun saveScoreToFile(state: JDState) {
        val outputDir = com.jd.pipeline.utils.OutputUtils.getOutputDirectory(state)
        Files.createDirectories(outputDir)
        val content = buildString {
            append("Fit Score: ${state.fitScore}\n")
            append("Pipeline Action: ${state.pipelineAction.asDbValue()}\n")
            if (state.skippedReason.isNotBlank()) append("Skipped Reason: ${state.skippedReason}\n")
            if (state.scoreConfidence != null) append("Confidence: ${state.scoreConfidence}\n")
            append("\nReasoning:\n${state.fitReasoning}\n")
            if (state.dimensionScores.isNotEmpty()) {
                append("\nDimension Scores:\n")
                state.dimensionScores.forEach { (k, v) -> append("  $k: $v\n") }
            }
            if (state.strengthsWithEvidence.isNotEmpty()) {
                append("\nStrengths:\n")
                state.strengthsWithEvidence.forEach {
                    append("- ${it.claim}")
                    if (it.jdEvidence.isNotBlank() && it.jdEvidence != "(not stated)") append(" [\"${it.jdEvidence}\"]")
                    append("\n")
                }
            } else if (state.strengths.isNotEmpty()) {
                append("\nStrengths:\n"); state.strengths.forEach { append("- $it\n") }
            }
            if (state.gapsWithEvidence.isNotEmpty()) {
                append("\nGaps:\n")
                state.gapsWithEvidence.forEach {
                    append("- ${it.claim}")
                    if (it.jdEvidence.isNotBlank() && it.jdEvidence != "(not stated)") append(" [\"${it.jdEvidence}\"]")
                    append("\n")
                }
            } else if (state.gaps.isNotEmpty()) {
                append("\nGaps:\n"); state.gaps.forEach { append("- $it\n") }
            }
            if (state.redFlags.isNotEmpty()) {
                append("\nRed Flags:\n"); state.redFlags.forEach { append("- $it\n") }
            }
            if (state.hardGateViolations.isNotEmpty()) {
                append("\nHard Gate Violations:\n"); state.hardGateViolations.forEach { append("- $it\n") }
            }
            if (state.postedCompMin != null || state.postedCompMax != null) {
                append("\nPosted Comp: min=${state.postedCompMin ?: "?"} max=${state.postedCompMax ?: "?"}\n")
            }
            if (state.workArrangement != "unknown") {
                append("Work Arrangement: ${state.workArrangement}")
                if (state.officeLocation.isNotBlank()) append(" (${state.officeLocation})")
                append("\n")
            }
        }
        Files.writeString(outputDir.resolve("score_fit.txt"), content, java.nio.charset.StandardCharsets.UTF_8)
        println("[score_fit] Saved scoring results to: ${outputDir.resolve("score_fit.txt")}")
    }

    companion object {
        /**
         * Shortest JD a digest-derived job may be scored on. Below this it is the digest email's
         * one-line summary rather than a real posting, so the scrape that should have replaced it
         * failed. Matches the run-analyzer's own thin-digest threshold (`analyzer/sources.py`), so
         * what the pipeline refuses to score is exactly what the analyzer reports.
         */
        const val MIN_DIGEST_JD_CHARS = 400

        const val CANDIDATE_PROFILE_PLACEHOLDER = "{{CANDIDATE_PROFILE}}"

        /**
         * Renders a [CandidateProfile] into the markdown block consumed by SCORE_SKILL.md.
         * Delegates to [com.jd.pipeline.utils.CandidateProfileRenderer.renderForScoring].
         * Public for testability.
         */
        fun renderCandidateProfile(profile: CandidateProfile): String =
            com.jd.pipeline.utils.CandidateProfileRenderer.renderForScoring(profile)

        val DEFAULT_SKILL_PROMPT = """
            |You are a job fit scorer and JD parser. Given a job description, do two things in one response:
            |
            |1. Score how well the candidate fits the role.
            |2. Extract structured JD fields for resume tailoring.
            |
            |## Candidate Profile
            |
            |$CANDIDATE_PROFILE_PLACEHOLDER
            |
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "fit_score": integer (0-100),
            |  "fit_reasoning": "string — narrative explanation of the score",
            |  "dimension_scores": {"mobile": int, "cicd": int, "web_api": int, "seniority": int, "stack_overlap": int, "location": int, "domain": int},
            |  "strengths": [{"claim": "string", "jd_evidence": "string"}, ...],
            |  "gaps": [{"claim": "string", "jd_evidence": "string"}, ...],
            |  "red_flags": ["string", ...],
            |  "hard_gate_violations": ["string", ...],
            |  "posted_comp_min": integer or null,
            |  "posted_comp_max": integer or null,
            |  "work_arrangement": "remote|hybrid|onsite|unknown",
            |  "office_location": "string",
            |  "confidence": float 0.0-1.0,
            |  "role_title": "string",
            |  "seniority": "string (e.g. Staff, Senior, Principal, IC5)",
            |  "required_skills": ["string", ...],
            |  "preferred_skills": ["string", ...],
            |  "domain_keywords": ["string", ...],
            |  "ats_exact_phrases": ["string", ...],
            |  "company_value_signals": ["string", ...]
            |}
            |
            |Scoring guidance:
            |- fit_score >= 50: worth tailoring; < 50: skip
            |- strengths/gaps: 2-5 items each; jd_evidence is a verbatim JD phrase or "(not stated)"
            |- red_flags: soft concerns that pull score down but do not gate
            |- hard_gate_violations: pure manual QA, 80%+ product dev work, active security clearance required
            |- confidence: 0.9+ for detailed JD; 0.5-0.7 vague; <0.5 very sparse
            |
            |Extraction guidance:
            |- required_skills: explicitly stated requirements
            |- preferred_skills: nice-to-haves or "plus" items
            |- domain_keywords: industry-specific terms, acronyms, platform names, methodologies
            |- ats_exact_phrases: multi-word phrases to include verbatim in resume for ATS matching
            |- company_value_signals: culture/values clues ("move fast", "data-driven", etc.)
            |- Do not invent skills not present in the JD
        """.trimMargin()
    }
}
