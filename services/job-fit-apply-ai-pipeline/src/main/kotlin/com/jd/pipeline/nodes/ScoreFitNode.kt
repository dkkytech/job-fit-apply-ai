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
import com.jd.pipeline.utils.UsStates
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
    private val llm: LlmCaller = LlmClient.forIngest(Config.SCORE_MODEL, jsonMode = true, temperature = 0.0, nodeKey = "score_fit")
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
            val prompt = "${loadSkillPrompt(input.candidateProfile)}\n\n${postingDetails(input)}JOB DESCRIPTION:\n$jdText"
            val response = llm.call(prompt)
            val result = parseLlmResponse(input, response)
            val dir = try { saveScoreToFile(result) } catch (e: Exception) {
                System.err.println("[score_fit] WARN: failed to save score_fit.txt: ${e.message}")
                null
            }
            dir?.let { result.copy(scoreOutputPath = it.toString()) } ?: result
        } catch (e: com.jd.pipeline.client.TransientLlmFailure) {
            // Re-propagate so the processor handler's retry classification can act on it
            // instead of swallowing into a terminal JD_Error.
            throw com.jd.pipeline.client.RetryableLlmError("score_fit: ${e.message}", e)
        } catch (e: Exception) {
            System.err.println("[score_fit] ERROR: ${e.message}")
            input.copy(fitScore = 0f, error = "score_fit: ${e.message}")
        }
    }

    /**
     * The job board's structured fields for this posting (captured at scrape time), so the model
     * doesn't have to infer work model and office location from prose alone. Empty when none are known.
     */
    internal fun postingDetails(input: JDState): String {
        val lines = listOfNotNull(
            input.location.takeIf { it.isNotBlank() }?.let { "Location: $it" },
            input.remotePolicy.takeIf { it.isNotBlank() && it != "unknown" }?.let { "Work model: $it" },
            input.salaryRange.takeIf { it.isNotBlank() }?.let { "Salary: $it" },
            input.employmentType.takeIf { it.isNotBlank() }?.let { "Employment type: $it" },
        )
        if (lines.isEmpty()) return ""
        return "POSTING DETAILS (from the job board's structured data):\n${lines.joinToString("\n")}\n\n"
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
            val modelScore = node.path("fit_score").floatValue().takeIf { it > 0f } ?: 0f
            val dimensionScores = parseDimensionScores(node.path("dimension_scores"))
            // The rubric is arithmetic over the dimensions (base + capped mobile bonus); apply it
            // here rather than trusting the model's sum. Falls back to the model's number when the
            // response doesn't carry the rubric's dimensions.
            val score = scoreFromDimensions(dimensionScores)?.also {
                if (it != modelScore) println("[score_fit] Model said $modelScore; rubric sum of dimensions = $it")
            } ?: modelScore
            println("[92m[score_fit] Score fit = $score[0m")

            val strengthsWithEvidence = parseEvidenceArray(node.path("strengths"))
            val gapsWithEvidence = parseEvidenceArray(node.path("gaps"))
            val llmHardGates = node.path("hard_gate_violations").map { it.asText() }.filter { it.isNotBlank() }
            val compMin = node.path("posted_comp_min").takeIf { !it.isNull && !it.isMissingNode }?.intValue()
            val compMax = node.path("posted_comp_max").takeIf { !it.isNull && !it.isMissingNode }?.intValue()
            val compPeriod = node.path("posted_comp_period").asText("")
            val workArrangement = node.path("work_arrangement").asText("unknown")
            val officeLocation = node.path("office_location").asText("")
            val officeState = node.path("office_state").asText("")
            val confidence = node.path("confidence").takeIf { !it.isNull && !it.isMissingNode }?.floatValue()

            // Deterministic hard-gate checks using extracted JD data vs profile preferences
            val deterministicGates = computeHardGates(input, compMin, compMax, workArrangement, officeLocation, officeState, compPeriod)
            val allHardGates = (llmHardGates + deterministicGates).distinct()
            if (allHardGates.isNotEmpty()) println("[score_fit] Hard-gate violations: $allHardGates")

            // Pay a little under target costs points rather than skipping; far under is a hard gate above.
            val comp = assessComp(input, compMax, compPeriod)
            val compPenalty = comp?.penalty ?: 0
            val finalScore = (score + compPenalty).coerceAtLeast(0f)
            if (comp != null && compPenalty < 0) println("[score_fit] ${comp.summary}; score $score → $finalScore")

            val action = when {
                allHardGates.isNotEmpty() -> PipelineAction.SKIP
                finalScore >= Config.FIT_THRESHOLD -> PipelineAction.TAILOR
                else -> PipelineAction.SKIP
            }
            val skippedReason = when {
                allHardGates.isNotEmpty() -> "Hard gate: ${allHardGates.joinToString("; ")}"
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
                fitScore = finalScore,
                fitReasoning = node.path("fit_reasoning").asText(""),
                strengths = strengthsWithEvidence.map { it.claim },
                gaps = gapsWithEvidence.map { it.claim },
                redFlags = node.path("red_flags").map { it.asText() } +
                    listOfNotNull(comp?.takeIf { it.penalty < 0 }?.summary),
                strengthsWithEvidence = strengthsWithEvidence,
                gapsWithEvidence = gapsWithEvidence,
                dimensionScores = if (compPenalty < 0) dimensionScores + (COMP_PENALTY_KEY to compPenalty) else dimensionScores,
                hardGateViolations = allHardGates,
                salaryRange = input.salaryRange.ifBlank { formatCompRange(compMin, compMax, isHourlyPeriod(compPeriod, "", compMax)) },
                postedCompMin = compMin,
                postedCompMax = compMax,
                postedCompPeriod = compPeriod,
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
     * Comp values are full-dollar integers (e.g. 150000 → "$150K"); hourly values are dollars per hour ("$65/hr").
     */
    private fun formatCompRange(min: Int?, max: Int?, hourly: Boolean = false): String {
        fun k(v: Int) = if (v >= 1000) "$${v / 1000}K" else "$$v"
        val unit = if (hourly) "/hr" else ""
        return when {
            min != null && max != null -> "${k(min)} – ${k(max)}$unit"
            min != null -> "${k(min)}+$unit"
            max != null -> "up to ${k(max)}$unit"
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

    /**
     * `min(100, base dimensions + mobile bonus)`, each dimension clamped to its rubric maximum.
     * Null unless every base dimension is present (e.g. an older or foreign response shape).
     */
    internal fun scoreFromDimensions(dimensions: Map<String, Int>): Float? {
        if (!BASE_DIMENSION_MAX.keys.all { it in dimensions }) return null
        val base = BASE_DIMENSION_MAX.entries.sumOf { (key, max) -> dimensions.getValue(key).coerceIn(0, max) }
        val bonus = (dimensions[MOBILE_BONUS_KEY] ?: 0).coerceIn(0, MOBILE_BONUS_MAX)
        return minOf(100, base + bonus).toFloat()
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
    internal fun computeHardGates(
        input: JDState,
        @Suppress("UNUSED_PARAMETER") compMin: Int?,
        compMax: Int?,
        workArrangement: String,
        officeLocation: String,
        officeState: String = "",
        compPeriod: String = "",
    ): List<String> {
        val prefs = input.candidateProfile?.preferences ?: return emptyList()
        val identity = input.candidateProfile.identity
        val gates = mutableListOf<String>()

        // Compensation: skip only when the posted max is far under target; a smaller shortfall is a score penalty (assessComp)
        assessComp(input, compMax, compPeriod)?.takeIf { it.skip }?.let { gates.add(it.summary) }

        if (!prefs.willingToRelocate) {
            locationGate(input, identity.location, workArrangement, officeLocation, officeState)?.let { gates.add(it) }
        }
        return gates
    }

    /**
     * How the posted pay compares with the candidate's minimum, compared on the posted MAX (the best the
     * role could pay):
     * - annual pay against `minimum_total_compensation`;
     * - hourly pay on an employee (full- or part-time) role annualized at [HOURS_PER_YEAR] and compared
     *   against `minimum_total_compensation` too — a W2 rate carries benefits a contract rate doesn't;
     * - any other hourly pay (contract, or employment type unknown) against [CandidatePreferences.minimumContractRateHourly].
     * Null when it can't be assessed — no pay posted, no minimum set for that basis, or an amount that
     * doesn't fit its unit — so unknown pay never costs points.
     */
    internal fun assessComp(input: JDState, compMax: Int?, compPeriod: String): CompAssessment? {
        val prefs = input.candidateProfile?.preferences ?: return null
        if (compMax == null || compMax <= 0) return null
        val hourly = isHourlyPeriod(compPeriod, input.salaryRange, compMax)
        // An hourly rate in four figures, or an annual one in three, means the unit was misread: can't tell.
        if (hourly == (compMax >= HOURLY_MAX_PLAUSIBLE)) return null
        val minimumTc = prefs.minimumTotalCompensation?.replace(NON_DIGITS, "")?.toIntOrNull()?.takeIf { it > 0 }

        val amount: Int
        val target: Int
        val postedText: String
        val targetText: String
        when {
            !hourly -> {
                amount = compMax; target = minimumTc ?: return null
                postedText = money(compMax); targetText = money(target)
            }
            isEmployeeRole(input.employmentType) -> {
                amount = compMax * HOURS_PER_YEAR; target = minimumTc ?: return null
                postedText = "${payAmount(compMax, hourly = true)} ≈ ${money(amount)}/yr"; targetText = money(target)
            }
            else -> {
                amount = compMax; target = prefs.minimumContractRateHourly?.takeIf { it > 0 } ?: return null
                postedText = payAmount(compMax, hourly = true); targetText = payAmount(target, hourly = true)
            }
        }

        val shortfall = (target - amount).toLong() * 100 // percent of target, scaled by target, to stay in integers
        // Rounded up, so a shortfall just past a tier edge never prints as the edge itself ("10% … (−15)")
        val pct = ((shortfall + target - 1) / target).toInt()
        val posted = "Posted pay (max $postedText)"
        val vs = "the $targetText target"
        if (shortfall <= 0) return CompAssessment(0, false, "$posted meets $vs")
        COMP_SHORTFALL_TIERS.firstOrNull { (maxPct, _) -> shortfall <= maxPct.toLong() * target }?.let { (_, penalty) ->
            return CompAssessment(-penalty, false, "$posted is $pct% below $vs (−$penalty)")
        }
        return CompAssessment(0, true, "$posted is more than $COMP_SKIP_SHORTFALL_PCT% below $vs")
    }

    /**
     * The board's salary text wins when it names a unit — it is the posting's own structured data, and the
     * model has been seen labelling hourly pay "year". Otherwise the model's period; otherwise sub-1000
     * amounts are hourly.
     */
    internal fun isHourlyPeriod(compPeriod: String, salaryRange: String, compMax: Int?): Boolean {
        val text = salaryRange.lowercase()
        if (HOURLY_TEXT.containsMatchIn(text)) return true
        if (ANNUAL_TEXT.containsMatchIn(text)) return false
        when (compPeriod.trim().lowercase()) {
            "hour", "hourly", "hr" -> return true
            "year", "yearly", "annual", "annually", "yr" -> return false
        }
        return compMax != null && compMax in 1 until HOURLY_MAX_PLAUSIBLE
    }

    /**
     * An employee role (full- or part-time) as opposed to a contract. Anything naming a contract wins
     * ("Contract W2" is a contract), and an unknown type is not an employee role: hourly postings are
     * mostly contracts.
     */
    internal fun isEmployeeRole(employmentType: String): Boolean {
        val type = employmentType.lowercase()
        if (CONTRACT_TYPE.containsMatchIn(type)) return false
        return EMPLOYEE_TYPE.containsMatchIn(type)
    }

    private fun payAmount(amount: Int, hourly: Boolean) = if (hourly) "$$amount/hr" else money(amount)

    /** [penalty] is zero or negative; [skip] means the shortfall is past the last tier; [summary] is the card/report wording. */
    internal data class CompAssessment(val penalty: Int, val skip: Boolean, val summary: String)

    /**
     * Onsite or hybrid work outside the candidate's home STATE (not city: listings use suburbs and
     * neighborhoods, and a city list always misses one). Only skips when the posting positively names
     * another state or a non-US office; a location with no identifiable state is "can't tell" → no gate.
     */
    private fun locationGate(
        input: JDState,
        homeLocation: String,
        llmArrangement: String,
        officeLocation: String,
        officeState: String,
    ): String? {
        val boardPolicy = input.remotePolicy.lowercase()
        // Either source saying remote means no gate: failing open beats skipping a real fit.
        if (boardPolicy.contains("remote") || llmArrangement.equals("remote", ignoreCase = true)) return null
        val arrangement = llmArrangement.lowercase().takeIf { it == "onsite" || it == "hybrid" }
            ?: when {
                boardPolicy.contains("hybrid") -> "hybrid"
                boardPolicy.contains("onsite") || boardPolicy.contains("on-site") || boardPolicy.contains("office") -> "onsite"
                else -> return null
            }

        val homeState = UsStates.statesIn(homeLocation).firstOrNull()
        val where = officeLocation.ifBlank { input.location }
        val label = if (arrangement == "onsite") "Onsite-only" else "Hybrid"
        if (homeState == null) {
            // Home location names no state — fall back to the old home-city match.
            val homeCity = homeLocation.substringBefore(",").trim().lowercase()
            if (homeCity.isBlank() || officeLocation.isBlank() || officeLocation.lowercase().contains(homeCity)) return null
            return "$label in $officeLocation (candidate in $homeLocation, no relocation)"
        }

        val foreign = officeState.trim().equals("non-US", ignoreCase = true)
        val states = buildSet {
            UsStates.codeOf(officeState)?.let { add(it) }
            addAll(UsStates.statesIn(officeLocation))
            addAll(UsStates.statesIn(input.location))
        }
        if (homeState in states) return null
        if (states.isEmpty() && !foreign) return null
        val place = where.ifBlank { if (foreign) "a non-US office" else states.joinToString("/") }
        return "$label in $place — outside $homeState (candidate in $homeLocation, no relocation)"
    }

    private fun saveScoreToFile(state: JDState): java.nio.file.Path {
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
                append("\nPosted Comp: min=${state.postedCompMin ?: "?"} max=${state.postedCompMax ?: "?"}")
                if (state.postedCompPeriod.isNotBlank()) append(" (${state.postedCompPeriod})")
                append("\n")
            }
            if (state.workArrangement != "unknown") {
                append("Work Arrangement: ${state.workArrangement}")
                if (state.officeLocation.isNotBlank()) append(" (${state.officeLocation})")
                append("\n")
            }
        }
        Files.writeString(outputDir.resolve("score_fit.txt"), content, java.nio.charset.StandardCharsets.UTF_8)
        println("[score_fit] Saved scoring results to: ${outputDir.resolve("score_fit.txt")}")
        return outputDir
    }

    companion object {
        /** Dollars as a card would say them: 85000 → "$85K", 137500 → "$137.5K", 1200000 → "$1.2M". */
        internal fun money(dollars: Int): String = when {
            dollars >= 1_000_000 -> "$" + trim(dollars / 1_000_000.0) + "M"
            dollars >= 1_000 -> "$" + trim(dollars / 1_000.0) + "K"
            else -> "$$dollars"
        }

        private fun trim(v: Double): String = "%.1f".format(java.util.Locale.ROOT, v).removeSuffix(".0")

        /**
         * Shortest JD a digest-derived job may be scored on. Below this it is the digest email's
         * one-line summary rather than a real posting, so the scrape that should have replaced it
         * failed. Matches the run-analyzer's own thin-digest threshold (`analyzer/sources.py`), so
         * what the pipeline refuses to score is exactly what the analyzer reports.
         */
        const val MIN_DIGEST_JD_CHARS = 400

        const val CANDIDATE_PROFILE_PLACEHOLDER = "{{CANDIDATE_PROFILE}}"

        /** Rubric maxima (SCORE_SKILL.md). The base dimensions sum to 100; mobile is a bonus on top. */
        val BASE_DIMENSION_MAX = linkedMapOf(
            "framework" to 15, "cicd" to 20, "web_api" to 20, "seniority" to 20,
            "stack_overlap" to 10, "location" to 10, "domain" to 5,
        )
        const val MOBILE_BONUS_KEY = "mobile"
        const val MOBILE_BONUS_MAX = 15

        /**
         * Posted max up to N% under the candidate's minimum → N points off the final score
         * (applied after the 100 clamp, so a perfect job reads 95 a little short). Further under
         * than the last tier is a hard-gate skip.
         */
        internal val COMP_SHORTFALL_TIERS = listOf(10 to 5, 20 to 15, 30 to 25, 40 to 35)
        internal const val COMP_SKIP_SHORTFALL_PCT = 40
        const val COMP_PENALTY_KEY = "comp_penalty"

        /** Posted amounts at or above this are annual, below it hourly (no hourly rate reaches $1,000). */
        private const val HOURLY_MAX_PLAUSIBLE = 1000

        /** Full-time hours in a year (40 × 52), to annualize an employee's hourly rate. */
        internal const val HOURS_PER_YEAR = 2080

        private val NON_DIGITS = Regex("[^0-9]")
        private val HOURLY_TEXT = Regex("""/\s*(hr|hour)\b|per hour|hourly""")
        private val ANNUAL_TEXT = Regex("""/\s*(yr|year)\b|per year|annual""")
        private val CONTRACT_TYPE = Regex("""contract|c2c|corp.to.corp|1099|temp|freelance""")
        private val EMPLOYEE_TYPE = Regex("""full|part|permanent|employee|\bw-?2\b|intern""")

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
            |  "dimension_scores": {"framework": int 0-15, "cicd": int 0-20, "web_api": int 0-20, "seniority": int 0-20, "stack_overlap": int 0-10, "location": int 0-10, "domain": int 0-5, "mobile": int 0-15 bonus},
            |  "strengths": [{"claim": "string", "jd_evidence": "string"}, ...],
            |  "gaps": [{"claim": "string", "jd_evidence": "string"}, ...],
            |  "red_flags": ["string", ...],
            |  "hard_gate_violations": ["string", ...],
            |  "posted_comp_min": integer or null,
            |  "posted_comp_max": integer or null,
            |  "posted_comp_period": "year|hour — the unit posted_comp_min/max are in",
            |  "work_arrangement": "remote|hybrid|onsite|unknown",
            |  "office_location": "string",
            |  "office_state": "two-letter US state code of the office (from city or neighborhood), non-US, or empty",
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
            |- fit_score = min(100, sum of the base dimensions + the mobile bonus); mobile is never a penalty
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
