package com.jd.pipeline.nodes

import com.jd.pipeline.models.*
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Tests for ScoreFitNode — profile rendering, evidence parsing, and hard-gate logic.
 * The LLM call itself is covered by integration tests; everything here is pure-function.
 */
@DisplayName("ScoreFitNodeTest")
class ScoreFitNodeTest {

    // ── Profile rendering ─────────────────────────────────────────────────────

    @Test
    @DisplayName("renderCandidateProfile includes identity, target title, career history, and preferences")
    fun rendersCoreFields() {
        val profile = sampleProfile()
        val out = ScoreFitNode.renderCandidateProfile(profile)

        assertTrue(out.contains("Jane Doe"), "expected name; got:\n$out")
        assertTrue(out.contains("Staff SDET"), "expected target title")
        assertTrue(out.contains("Seattle, WA"), "expected location")
        assertTrue(out.contains("12+ years"))
        assertTrue(out.contains("B.S. CS"))
        // Career history table
        assertTrue(out.contains("| Senior SDET | Acme | — | 2020-01 – 2024-01 |"), "expected career row with location column; got:\n$out")
        // Skills section
        assertTrue(out.contains("Mobile Automation:") && out.contains("Espresso"))
        // Preferences block
        assertTrue(out.contains("Target total compensation"))
        assertTrue(out.contains("\$200,000"))
        assertTrue(out.contains("US Citizen"))
        assertTrue(out.contains("Open to contract roles:** yes"))
    }

    @Test
    @DisplayName("renderCandidateProfile leaves no unresolved placeholder braces")
    fun noUnresolvedBraces() {
        val out = ScoreFitNode.renderCandidateProfile(sampleProfile())
        assertFalse(out.contains("{{"), "rendered profile should not contain template markers")
        assertFalse(out.contains("}}"))
    }

    @Test
    @DisplayName("renderCandidateProfile gracefully handles empty optional collections")
    fun handlesEmptyCollections() {
        val profile = sampleProfile().copy(
            background = sampleProfile().background.copy(
                careerHistory = emptyList(),
                coreStrengths = emptyList(),
                languages = emptyList(),
                domainExpertise = emptyList()
            ),
            skills = emptyList()
        )
        val out = ScoreFitNode.renderCandidateProfile(profile)
        assertTrue(out.contains("Jane Doe"))
        assertFalse(out.contains("Career History"), "should omit empty career table")
        assertFalse(out.contains("Languages:"), "should omit empty languages line")
    }

    @Test
    @DisplayName("DEFAULT_SKILL_PROMPT carries the placeholder so substitution works without the resource file")
    fun defaultPromptHasPlaceholder() {
        assertTrue(
            ScoreFitNode.DEFAULT_SKILL_PROMPT.contains(ScoreFitNode.CANDIDATE_PROFILE_PLACEHOLDER),
            "fallback prompt must include {{CANDIDATE_PROFILE}} for runtime substitution"
        )
    }

    // ── Hard-gate logic ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Compensation vs. the candidate's minimum")
    inner class CompTests {

        // Profile minimums: $200,000 total compensation, $85/hr contract rate.
        private val state = stateWithProfile()

        private fun assess(compMax: Int?, period: String = "", salaryRange: String = "", employmentType: String = "") =
            ScoreFitNode().assessComp(state.copy(salaryRange = salaryRange, employmentType = employmentType), compMax, period)

        @Test
        @DisplayName("annual pay at or above the minimum costs nothing")
        fun meetsTarget() {
            assertEquals(0, assess(250_000)!!.penalty)
            assertEquals(0, assess(200_000)!!.penalty)
            assertFalse(assess(200_000)!!.skip)
        }

        @Test
        @DisplayName("annual shortfall tiers: 10% → -5, 20% → -15, 30% → -25, 40% → -35, past that a skip")
        fun annualTiers() {
            // Edges are inclusive: exactly 10% short is still the first tier.
            assertEquals(-5, assess(190_000)!!.penalty)
            assertEquals(-5, assess(180_000)!!.penalty)
            assertEquals(-15, assess(179_999)!!.penalty)
            assertEquals(-15, assess(160_000)!!.penalty)
            assertEquals(-25, assess(150_000)!!.penalty)
            assertEquals(-25, assess(140_000)!!.penalty)
            assertEquals(-35, assess(139_999)!!.penalty)
            assertEquals(-35, assess(120_000)!!.penalty)
            val skip = assess(119_999)!!
            assertTrue(skip.skip)
            assertEquals(0, skip.penalty)
        }

        @Test
        @DisplayName("summaries read the way a card says them")
        fun summaries() {
            assertEquals("Posted pay (max \$170K) is 15% below the \$200K target (−15)", assess(170_000)!!.summary)
            assertEquals("Posted pay (max \$100K) is more than 40% below the \$200K target", assess(100_000)!!.summary)
        }

        @Test
        @DisplayName("the printed shortfall never reads as a tier edge it has just passed")
        fun printedPercentMatchesTier() {
            // 10.0005% short is the 20% tier: prints 11%, not 10%.
            assertEquals("Posted pay (max \$180K) is 11% below the \$200K target (−15)", assess(179_999)!!.summary)
            // Exactly 10% short is still the first tier and prints 10%.
            assertEquals("Posted pay (max \$180K) is 10% below the \$200K target (−5)", assess(180_000)!!.summary)
        }

        @Test
        @DisplayName("hourly pay is judged against the hourly contract rate, not the annual minimum")
        fun hourlyUsesContractRate() {
            // $80/hr would be far below $200K/yr if misread as annual; against $85/hr it is 6% short.
            val a = assess(80, period = "hour")!!
            assertEquals(-5, a.penalty)
            assertEquals("Posted pay (max \$80/hr) is 6% below the \$85/hr target (−5)", a.summary)
            assertEquals(0, assess(95, period = "hour")!!.penalty)
            assertTrue(assess(50, period = "hour")!!.skip)
            assertEquals("Posted pay (max \$50/hr) is more than 40% below the \$85/hr target", assess(50, period = "hour")!!.summary)
        }

        @Test
        @DisplayName("hourly is recognised from the board's salary text when the model gave no period")
        fun hourlyFromSalaryText() {
            assertEquals(-5, assess(80, salaryRange = "\$70 - \$80/hr")!!.penalty)
            assertEquals(-5, assess(80, salaryRange = "\$70 to \$80 per hour")!!.penalty)
            assertEquals(-5, assess(190_000, salaryRange = "\$170K - \$190K/yr")!!.penalty)
        }

        @Test
        @DisplayName("with no period or text, a sub-1000 amount is hourly and a larger one annual")
        fun hourlyFromMagnitude() {
            assertEquals(-5, assess(80)!!.penalty)
            assertEquals(-5, assess(190_000)!!.penalty)
        }

        @Test
        @DisplayName("the board's salary text wins over a contradicting model period")
        fun textWinsOverPeriod() {
            // The model said "year" for a "$55 - $65/hr" posting: still judged hourly, 24% under $85/hr.
            val a = assess(65, period = "year", salaryRange = "\$55 - \$65/hr")!!
            assertEquals(-25, a.penalty)
            assertEquals("Posted pay (max \$65/hr) is 24% below the \$85/hr target (−25)", a.summary)
            // …and the other way round: annual text, "hour" period, an annual amount.
            assertEquals(-5, assess(190_000, period = "hour", salaryRange = "\$170K - \$190K/yr")!!.penalty)
        }

        @Test
        @DisplayName("salary text with no unit leaves the decision to the model's period")
        fun unitlessTextDefersToPeriod() {
            assertEquals(-5, assess(80, period = "hour", salaryRange = "\$70 - \$80")!!.penalty)
        }

        @Test
        @DisplayName("a unit that contradicts its amount can't be assessed, so costs nothing")
        fun misreadUnit() {
            assertNull(assess(190_000, period = "hour"), "four-figure \"hourly\" rate")
            assertNull(assess(150, period = "year"), "three-figure \"annual\" salary")
        }

        @Test
        @DisplayName("unknown or zero pay is never assessed")
        fun unknownPay() {
            assertNull(assess(null))
            assertNull(assess(0))
        }

        @Test
        @DisplayName("no minimum for the posted unit means no assessment")
        fun noMinimumForUnit() {
            val noRate = stateWithProfile(sampleProfile().preferences.copy(minimumContractRateHourly = null))
            assertNull(ScoreFitNode().assessComp(noRate, 60, "hour"), "hourly pay, no hourly rate set")
            assertEquals(-35, ScoreFitNode().assessComp(noRate, 130_000, "year")!!.penalty, "annual pay still uses TC")

            val noTc = stateWithProfile(sampleProfile().preferences.copy(minimumTotalCompensation = null))
            assertNull(ScoreFitNode().assessComp(noTc, 100_000, "year"), "annual pay, no TC set")
            assertEquals(-5, ScoreFitNode().assessComp(noTc, 80, "hour")!!.penalty, "hourly pay still uses the rate")
        }

        @Test
        @DisplayName("no profile means no assessment")
        fun noProfile() {
            assertNull(ScoreFitNode().assessComp(JDState(jdText = "jd"), 100_000, "year"))
        }

        @Test
        @DisplayName("every accepted period spelling is recognised, ignoring case and padding")
        fun periodAliases() {
            for (p in listOf("hour", "hourly", "hr", " HOUR ", "Hourly")) {
                assertTrue(ScoreFitNode().isHourlyPeriod(p, "", 190_000), "'$p' should be hourly")
            }
            for (p in listOf("year", "yearly", "annual", "annually", "yr", "YEAR")) {
                assertFalse(ScoreFitNode().isHourlyPeriod(p, "", 65), "'$p' should be annual")
            }
        }

        @Test
        @DisplayName("an unrecognised period falls through to the salary text, then to the amount")
        fun unknownPeriodFallsThrough() {
            assertTrue(ScoreFitNode().isHourlyPeriod("monthly", "\$60/hr", 190_000))
            assertFalse(ScoreFitNode().isHourlyPeriod("monthly", "\$190K/yr", 65))
            assertTrue(ScoreFitNode().isHourlyPeriod("", "", 65))
            assertFalse(ScoreFitNode().isHourlyPeriod("", "", 190_000))
            assertFalse(ScoreFitNode().isHourlyPeriod("", "", null))
        }

        @Test
        @DisplayName("salary text spellings for hourly and annual pay")
        fun salaryTextSpellings() {
            for (t in listOf("\$55/hr", "\$55 / hour", "\$55 - \$65/HR", "\$55 per hour", "\$60 hourly")) {
                assertTrue(ScoreFitNode().isHourlyPeriod("", t, null), "'$t' should be hourly")
            }
            for (t in listOf("\$150K/yr", "\$150K / year", "\$150,000 per year", "\$150K annual")) {
                assertFalse(ScoreFitNode().isHourlyPeriod("", t, 65), "'$t' should be annual")
            }
        }

        @Test
        @DisplayName("hourly tier edges against the \$85/hr rate")
        fun hourlyTierEdges() {
            assertEquals(0, assess(85, "hour")!!.penalty)
            assertEquals(-5, assess(77, "hour")!!.penalty) // 9.4% short
            assertEquals(-15, assess(76, "hour")!!.penalty) // 10.6% short
            assertEquals(-35, assess(51, "hour")!!.penalty) // exactly 40% short
            assertFalse(assess(51, "hour")!!.skip)
            assertTrue(assess(50, "hour")!!.skip)
        }

        @Test
        @DisplayName("exactly 40% short is the last penalty tier; only beyond it skips")
        fun exactlyFortyPercent() {
            val edge = assess(120_000)!!
            assertEquals(-35, edge.penalty)
            assertFalse(edge.skip)
        }

        @Test
        @DisplayName("the annual minimum parses from currency formatting; blank or non-numeric means none")
        fun targetParsing() {
            fun tc(value: String?) = stateWithProfile(sampleProfile().preferences.copy(minimumTotalCompensation = value))
            assertEquals(-5, ScoreFitNode().assessComp(tc("200000"), 190_000, "year")!!.penalty)
            assertEquals(-5, ScoreFitNode().assessComp(tc("\$200,000"), 190_000, "year")!!.penalty)
            assertNull(ScoreFitNode().assessComp(tc(""), 190_000, "year"))
            assertNull(ScoreFitNode().assessComp(tc("TBD"), 190_000, "year"))
        }

        @Test
        @DisplayName("a zero hourly rate means no minimum was set")
        fun zeroHourlyRate() {
            val zero = stateWithProfile(sampleProfile().preferences.copy(minimumContractRateHourly = 0))
            assertNull(ScoreFitNode().assessComp(zero, 60, "hour"))
        }

        @Test
        @DisplayName("hourly pay on an employee role is annualized (× 2080) and judged against total compensation")
        fun employeeHourlyAnnualized() {
            val a = assess(60, period = "hour", employmentType = "Full-time")!! // $124.8K vs $200K
            assertEquals(-35, a.penalty)
            assertEquals("Posted pay (max \$60/hr ≈ \$124.8K/yr) is 38% below the \$200K target (−35)", a.summary)
            assertEquals(0, assess(100, period = "hour", employmentType = "Full-time")!!.penalty) // $208K
            assertEquals(-5, assess(90, period = "hour", employmentType = "Part-time")!!.penalty) // $187.2K, 6.4% short
            val far = assess(50, period = "hour", employmentType = "Full-time")!! // $104K, 48% short
            assertTrue(far.skip)
            assertEquals("Posted pay (max \$50/hr ≈ \$104K/yr) is more than 40% below the \$200K target", far.summary)
        }

        @Test
        @DisplayName("contract or unknown employment keeps the hourly contract rate")
        fun contractHourlyUsesRate() {
            for (type in listOf("Contract", "CONTRACTOR", "Contract W2", "Temporary", "C2C", "")) {
                assertEquals(-25, assess(60, period = "hour", employmentType = type)!!.penalty, "'$type'") // 29% under $85/hr
            }
        }

        @Test
        @DisplayName("an employee hourly role with no total-compensation minimum isn't assessed, even with a contract rate set")
        fun employeeHourlyNeedsTc() {
            val noTc = stateWithProfile(sampleProfile().preferences.copy(minimumTotalCompensation = null))
                .copy(employmentType = "Full-time")
            assertNull(ScoreFitNode().assessComp(noTc, 60, "hour"))
        }

        @Test
        @DisplayName("employee vs contract classification")
        fun employeeRoleClassification() {
            for (t in listOf("Full-time", "FULL_TIME", "fulltime", "Part-time", "Permanent", "Employee", "W2", "W-2", "Internship")) {
                assertTrue(ScoreFitNode().isEmployeeRole(t), "'$t' should be an employee role")
            }
            for (t in listOf("Contract", "Contractor", "Contract W2", "Contract-to-hire", "C2C", "Corp-to-Corp", "1099", "Temporary", "Freelance", "", "Other")) {
                assertFalse(ScoreFitNode().isEmployeeRole(t), "'$t' should not be an employee role")
            }
        }

        @Test
        @DisplayName("an employee hourly role far under is gated in annualized terms")
        fun employeeHourlyGate() {
            assertEquals(
                listOf("Posted pay (max \$45/hr ≈ \$93.6K/yr) is more than 40% below the \$200K target"),
                invokeComputeHardGates(state.copy(employmentType = "Full-time"), compMax = 45, compPeriod = "hour"),
            )
        }

        @Test
        @DisplayName("only a shortfall past the last tier is a hard gate")
        fun onlyFarBelowIsGated() {
            assertEquals(emptyList(), invokeComputeHardGates(state, compMax = 150_000))
            assertEquals(emptyList(), invokeComputeHardGates(state, compMax = 120_000))
            assertEquals(
                listOf("Posted pay (max \$110K) is more than 40% below the \$200K target"),
                invokeComputeHardGates(state, compMax = 110_000),
            )
            assertEquals(
                listOf("Posted pay (max \$40/hr) is more than 40% below the \$85/hr target"),
                invokeComputeHardGates(state, compMax = 40, compPeriod = "hour"),
            )
        }

        @Test
        @DisplayName("no gate when posted comp is null (not disclosed) or the profile has no minimum")
        fun noGateWhenUnknown() {
            assertTrue(invokeComputeHardGates(state, compMin = null, compMax = null).isEmpty())
            val noTc = stateWithProfile(sampleProfile().preferences.copy(minimumTotalCompensation = null))
            assertTrue(invokeComputeHardGates(noTc, compMin = 100_000, compMax = 120_000).isEmpty())
        }

        @Test
        @DisplayName("money reads the way a card says it")
        fun moneyFormatting() {
            assertEquals("\$85K", ScoreFitNode.money(85_000))
            assertEquals("\$137.5K", ScoreFitNode.money(137_500))
            assertEquals("\$1.2M", ScoreFitNode.money(1_200_000))
            assertEquals("\$950", ScoreFitNode.money(950))
        }
    }

    @Nested
    @DisplayName("Compensation penalty applied to the score")
    inner class CompScoreTests {

        // Rubric dimensions summing to 100, no mobile bonus: a "perfect" job.
        private val perfect = """{"framework": 15, "cicd": 20, "web_api": 20, "seniority": 20, "stack_overlap": 10, "location": 10, "domain": 5, "mobile": 0}"""
        private val perfectWithMobile = perfect.replace("\"mobile\": 0", "\"mobile\": 15")

        private fun response(dimensions: String, max: Int?, period: String = "year") = """
            {
              "fit_score": 100, "fit_reasoning": "r", "dimension_scores": $dimensions,
              "strengths": [], "gaps": [], "red_flags": ["soft"], "hard_gate_violations": [],
              "posted_comp_min": ${max?.let { it - 10 }}, "posted_comp_max": $max, "posted_comp_period": "$period",
              "work_arrangement": "remote", "confidence": 0.9, "role_title": "Staff SDET"
            }
        """.trimIndent()

        private fun score(dimensions: String, max: Int?, period: String = "year", employmentType: String = ""): JDState =
            ScoreFitNode(llm = LlmCaller { response(dimensions, max, period) })
                .process(stateWithProfile().copy(
                    jdText = "A staff SDET role. ".repeat(30),
                    employmentType = employmentType,
                    outputPath = java.nio.file.Files.createTempDirectory("score_fit_comp").toString(),
                ))

        @Test
        @DisplayName("at or above the minimum a perfect job stays at 100")
        fun noPenalty() {
            val r = score(perfect, 210_000)
            assertEquals(100f, r.fitScore)
            assertFalse(r.dimensionScores.containsKey("comp_penalty"))
            assertEquals(listOf("soft"), r.redFlags)
        }

        @Test
        @DisplayName("a perfect job slightly under the minimum scores 95 and is still tailored, not gated")
        fun slightlyUnder() {
            val r = score(perfect, 190_000)
            assertEquals(95f, r.fitScore)
            assertEquals(PipelineAction.TAILOR, r.pipelineAction)
            assertTrue(r.hardGateViolations.isEmpty())
            assertEquals(-5, r.dimensionScores["comp_penalty"])
            assertEquals(listOf("soft", "Posted pay (max \$190K) is 5% below the \$200K target (−5)"), r.redFlags)
        }

        @Test
        @DisplayName("the penalty comes off after the 100 clamp, so a mobile bonus can't absorb it")
        fun appliedAfterClamp() {
            // 100 + 15 clamps to 100; a 15% shortfall then takes 15 off.
            assertEquals(85f, score(perfectWithMobile, 170_000).fitScore)
        }

        @Test
        @DisplayName("a 35-point penalty drops a mid-scoring job under the threshold")
        fun penaltyCanDropBelowThreshold() {
            val mid = """{"framework": 10, "cicd": 10, "web_api": 15, "seniority": 15, "stack_overlap": 6, "location": 10, "domain": 4, "mobile": 0}""" // 70
            val r = score(mid, 130_000) // 35% short
            assertEquals(35f, r.fitScore)
            assertEquals(PipelineAction.SKIP, r.pipelineAction)
            assertEquals("Fit score below threshold", r.skippedReason)
        }

        @Test
        @DisplayName("more than 40% under skips on a hard gate and keeps the rubric score")
        fun farUnderSkips() {
            val r = score(perfect, 100_000)
            assertEquals(100f, r.fitScore)
            assertEquals(PipelineAction.SKIP, r.pipelineAction)
            assertEquals("Hard gate: Posted pay (max \$100K) is more than 40% below the \$200K target", r.skippedReason)
        }

        @Test
        @DisplayName("hourly pay is penalised against the contract rate")
        fun hourly() {
            val r = score(perfect, 80, "hour") // $85/hr minimum → 6% short
            assertEquals(95f, r.fitScore)
            assertEquals("hour", r.postedCompPeriod)
            assertTrue(r.redFlags.last().startsWith("Posted pay (max \$80/hr)"), r.redFlags.toString())
        }

        @Test
        @DisplayName("exactly 40% short on a perfect job is still tailored at 65")
        fun fortyPercentStillTailored() {
            val r = score(perfect, 120_000)
            assertEquals(65f, r.fitScore)
            assertEquals(PipelineAction.TAILOR, r.pipelineAction)
            assertEquals(-35, r.dimensionScores["comp_penalty"])
        }

        @Test
        @DisplayName("the score never goes below zero")
        fun floorsAtZero() {
            val weak = """{"framework": 0, "cicd": 5, "web_api": 5, "seniority": 5, "stack_overlap": 0, "location": 5, "domain": 0, "mobile": 0}""" // 20
            val r = score(weak, 130_000) // 35% short
            assertEquals(0f, r.fitScore)
            assertEquals(PipelineAction.SKIP, r.pipelineAction)
        }

        @Test
        @DisplayName("far-under hourly pay is a hard-gate skip worded in dollars per hour")
        fun hourlyFarUnder() {
            val r = score(perfect, 40, "hour")
            assertEquals(100f, r.fitScore)
            assertEquals(PipelineAction.SKIP, r.pipelineAction)
            assertEquals("Hard gate: Posted pay (max \$40/hr) is more than 40% below the \$85/hr target", r.skippedReason)
            assertFalse(r.dimensionScores.containsKey("comp_penalty"), "a skip keeps the rubric score")
        }

        @Test
        @DisplayName("hourly pay at or above the contract rate is not penalised")
        fun hourlyMeetsRate() {
            val r = score(perfect, 90, "hour")
            assertEquals(100f, r.fitScore)
            assertEquals(listOf("soft"), r.redFlags)
        }

        @Test
        @DisplayName("a response without posted_comp_period (older prompt) is read by magnitude")
        fun missingPeriodField() {
            val noPeriod = response(perfect, 190_000).replace("\"posted_comp_period\": \"year\",", "")
            val r = ScoreFitNode(llm = LlmCaller { noPeriod }).process(
                stateWithProfile().copy(
                    jdText = "A staff SDET role. ".repeat(30),
                    outputPath = java.nio.file.Files.createTempDirectory("score_fit_comp").toString(),
                )
            )
            assertEquals(95f, r.fitScore)
            assertEquals("", r.postedCompPeriod)
        }

        @Test
        @DisplayName("without a candidate profile no pay penalty is applied")
        fun noProfileNoPenalty() {
            val r = ScoreFitNode(llm = LlmCaller { response(perfect, 50_000) }).process(
                JDState(
                    jdText = "A staff SDET role. ".repeat(30),
                    outputPath = java.nio.file.Files.createTempDirectory("score_fit_comp").toString(),
                )
            )
            assertEquals(100f, r.fitScore)
            assertTrue(r.hardGateViolations.isEmpty())
        }

        @Test
        @DisplayName("the stored salary range is unit-aware when the board gave none, and untouched when it did")
        fun salaryRangeFallback() {
            val hourly = score(perfect, 80, "hour")
            assertEquals("\$70 – \$80/hr", hourly.salaryRange)
            val annual = score(perfect, 190_000)
            assertEquals("\$189K – \$190K", annual.salaryRange)

            val withBoardText = ScoreFitNode(llm = LlmCaller { response(perfect, 80, "hour") }).process(
                stateWithProfile().copy(
                    jdText = "A staff SDET role. ".repeat(30),
                    salaryRange = "\$70 - \$80/hr",
                    outputPath = java.nio.file.Files.createTempDirectory("score_fit_comp").toString(),
                )
            )
            assertEquals("\$70 - \$80/hr", withBoardText.salaryRange)
        }

        @Test
        @DisplayName("score_fit.txt records the pay unit and the comp penalty")
        fun reportShowsPenalty() {
            val r = score(perfect, 80, "hour")
            val text = java.nio.file.Files.readString(java.nio.file.Path.of(r.scoreOutputPath).resolve("score_fit.txt"))
            assertTrue(text.contains("Posted Comp: min=70 max=80 (hour)"), text)
            assertTrue(text.contains("comp_penalty: -5"), text)
            assertTrue(text.contains("Posted pay (max \$80/hr) is 6% below the \$85/hr target (−5)"), text)
        }

        @Test
        @DisplayName("a full-time hourly job is scored on its annualized pay")
        fun employeeHourlyScored() {
            val r = score(perfect, 92, "hour", employmentType = "Full-time") // $191.4K, 4.3% short
            assertEquals(95f, r.fitScore)
            assertEquals(PipelineAction.TAILOR, r.pipelineAction)
            assertEquals("Posted pay (max \$92/hr ≈ \$191.4K/yr) is 5% below the \$200K target (−5)", r.redFlags.last())
        }

        @Test
        @DisplayName("a model \"year\" label on an hourly board posting is still penalised against the rate")
        fun mislabelledHourlyScored() {
            val r = ScoreFitNode(llm = LlmCaller { response(perfect, 65, "year") }).process(
                stateWithProfile().copy(
                    jdText = "A staff SDET role. ".repeat(30),
                    salaryRange = "\$55 - \$65/hr",
                    outputPath = java.nio.file.Files.createTempDirectory("score_fit_comp").toString(),
                )
            )
            assertEquals(75f, r.fitScore)
            assertEquals(-25, r.dimensionScores["comp_penalty"])
        }

        @Test
        @DisplayName("unknown pay costs nothing")
        fun unknownPay() {
            val r = score(perfect, null)
            assertEquals(100f, r.fitScore)
            assertEquals(PipelineAction.TAILOR, r.pipelineAction)
        }
    }

    @Nested
    @DisplayName("Hard Gate: Location")
    inner class LocationHardGateTests {

        @Test
        @DisplayName("flags gate when onsite outside home city and no relocation")
        fun flagsOnsiteOutsideHomeCity() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(willingToRelocate = false)
            )
            val gates = invokeComputeHardGates(state, workArrangement = "onsite", officeLocation = "New York, NY")
            assertTrue(gates.any { it.contains("Onsite-only") }, "expected gate; got: $gates")
        }

        @Test
        @DisplayName("no gate when onsite in home city")
        fun noGateOnsiteInHomeCity() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(willingToRelocate = false)
            )
            val gates = invokeComputeHardGates(state, workArrangement = "onsite", officeLocation = "Seattle, WA")
            assertTrue(gates.isEmpty(), "should not flag onsite in home city; got: $gates")
        }

        @Test
        @DisplayName("no gate when arrangement is remote regardless of location")
        fun noGateForRemote() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(willingToRelocate = false)
            )
            val gates = invokeComputeHardGates(state, workArrangement = "remote", officeLocation = "New York, NY")
            assertTrue(gates.isEmpty(), "remote should never trigger location gate")
        }

        @Test
        @DisplayName("no gate when candidate is willing to relocate")
        fun noGateWhenWillingToRelocate() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(willingToRelocate = true)
            )
            val gates = invokeComputeHardGates(state, workArrangement = "onsite", officeLocation = "Austin, TX")
            assertTrue(gates.isEmpty(), "should not flag when willing to relocate")
        }
    }

    @Nested
    @DisplayName("Hard Gate: Location is Washington state, not Seattle")
    inner class StateLocationGateTests {

        private fun gates(arrangement: String, office: String, officeState: String = "", state: JDState = stateWithProfile()) =
            invokeComputeHardGates(state, workArrangement = arrangement, officeLocation = office, officeState = officeState)

        @Test
        @DisplayName("onsite in a Seattle suburb is home state, not a skip")
        fun onsiteSuburbAllowed() {
            assertTrue(gates("onsite", "Bothell, WA").isEmpty())
            assertTrue(gates("onsite", "Spokane, Washington").isEmpty())
        }

        @Test
        @DisplayName("hybrid outside Washington is skipped")
        fun hybridOutOfStateGated() {
            val g = gates("hybrid", "Merrifield, VA")
            assertTrue(g.single().startsWith("Hybrid in Merrifield, VA — outside WA"), "got: $g")
        }

        @Test
        @DisplayName("a neighborhood name with no state never causes a skip")
        fun neighborhoodFailsOpen() {
            assertTrue(gates("onsite", "South Lake Union").isEmpty())
            assertTrue(gates("hybrid", "United States").isEmpty())
        }

        @Test
        @DisplayName("the model's office_state resolves a neighborhood either way")
        fun officeStateUsed() {
            assertTrue(gates("hybrid", "South Lake Union", officeState = "WA").isEmpty())
            assertTrue(gates("hybrid", "The Loop", officeState = "IL").isNotEmpty())
            assertTrue(gates("onsite", "Shoreditch, London", officeState = "non-US").isNotEmpty())
        }

        @Test
        @DisplayName("multi-location posting that includes Washington is allowed")
        fun multiLocationWithHomeStateAllowed() {
            assertTrue(gates("hybrid", "San Francisco, CA; Seattle, WA").isEmpty())
            assertTrue(gates("hybrid", "San Francisco, CA; Chicago, IL; Salt Lake City, UT").isNotEmpty())
        }

        @Test
        @DisplayName("Washington, DC is not Washington state")
        fun washingtonDcIsNotWa() {
            assertTrue(gates("onsite", "Washington, DC").isNotEmpty())
        }

        @Test
        @DisplayName("falls back to the board's work model and location when the model says unknown")
        fun boardFieldsFallback() {
            val s = stateWithProfile().copy(remotePolicy = "Hybrid", location = "Austin, TX")
            assertTrue(invokeComputeHardGates(s).single().startsWith("Hybrid in Austin, TX"))
        }

        @Test
        @DisplayName("remote from either the board or the model means no location skip")
        fun remoteNeverGated() {
            assertTrue(invokeComputeHardGates(stateWithProfile().copy(remotePolicy = "Remote"),
                workArrangement = "hybrid", officeLocation = "Austin, TX").isEmpty())
            assertTrue(invokeComputeHardGates(stateWithProfile().copy(remotePolicy = "Hybrid", location = "Austin, TX"),
                workArrangement = "remote").isEmpty())
        }
    }

    @Nested
    @DisplayName("Rubric arithmetic: base dimensions + mobile bonus")
    inner class RubricArithmeticTests {
        private val fullBase = mapOf("framework" to 15, "cicd" to 20, "web_api" to 20, "seniority" to 20,
            "stack_overlap" to 10, "location" to 10, "domain" to 5)

        @Test
        @DisplayName("a role with no mobile work can still reach 100")
        fun noMobileNoPenalty() {
            assertEquals(100f, ScoreFitNode().scoreFromDimensions(fullBase + ("mobile" to 0)))
        }

        @Test
        @DisplayName("mobile adds up to 15 on top of the base, and the total caps at 100")
        fun mobileBonusCapped() {
            val partial = fullBase + ("cicd" to 10) + ("seniority" to 10)   // base 80
            assertEquals(95f, ScoreFitNode().scoreFromDimensions(partial + ("mobile" to 15)))
            assertEquals(95f, ScoreFitNode().scoreFromDimensions(partial + ("mobile" to 40)), "bonus clamps to 15")
            assertEquals(100f, ScoreFitNode().scoreFromDimensions(fullBase + ("mobile" to 15)), "total caps at 100")
        }

        @Test
        @DisplayName("out-of-range dimensions clamp to their rubric maximum")
        fun clampsDimensions() {
            val inflated = fullBase + ("framework" to 25) + ("location" to -3)   // → 15 and 0
            assertEquals(90f, ScoreFitNode().scoreFromDimensions(inflated))
        }

        @Test
        @DisplayName("falls back (null) when a base dimension is missing, e.g. the old rubric's keys")
        fun nullWithoutRubricKeys() {
            val oldRubric = mapOf("mobile" to 25, "cicd" to 20, "web_api" to 15, "seniority" to 15,
                "stack_overlap" to 10, "location" to 10, "domain" to 5)
            assertEquals(null, ScoreFitNode().scoreFromDimensions(oldRubric))
        }
    }

    @Test
    @DisplayName("postingDetails passes the board's location, work model, salary and type to the scorer")
    fun postingDetailsBlock() {
        val s = JDState(location = "United States", remotePolicy = "Remote", salaryRange = "\$101K/yr - \$127K/yr", employmentType = "Full-time")
        val block = ScoreFitNode().postingDetails(s)
        assertTrue(block.contains("Location: United States") && block.contains("Work model: Remote") &&
            block.contains("Salary: \$101K/yr") && block.contains("Employment type: Full-time"), block)
        assertEquals("", ScoreFitNode().postingDetails(JDState()))
    }

    @Nested
    @DisplayName("Hard Gate: No profile")
    inner class NoProfileTests {

        @Test
        @DisplayName("returns empty gates when candidateProfile is null")
        fun noGatesWithoutProfile() {
            val state = JDState(jdText = "some JD", candidateProfile = null)
            val gates = invokeComputeHardGates(state, compMax = 100_000, workArrangement = "onsite", officeLocation = "Chicago, IL")
            assertTrue(gates.isEmpty(), "should return no gates when profile is absent")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun invokeComputeHardGates(
        state: JDState,
        compMin: Int? = null,
        compMax: Int? = null,
        workArrangement: String = "unknown",
        officeLocation: String = "",
        officeState: String = "",
        compPeriod: String = "",
    ): List<String> = ScoreFitNode().computeHardGates(state, compMin, compMax, workArrangement, officeLocation, officeState, compPeriod)

    private fun stateWithProfile(preferences: CandidatePreferences = sampleProfile().preferences): JDState =
        JDState(
            jdText = "some JD",
            candidateProfile = sampleProfile().copy(preferences = preferences)
        )

    private fun sampleProfile(): CandidateProfile = CandidateProfile(
        identity = CandidateIdentity(
            name = "Jane Doe",
            firstName = "Jane",
            lastName = "Doe",
            email = "jane@example.com",
            phone = "555-1234",
            location = "Seattle, WA"
        ),
        background = CandidateBackground(
            targetTitle = "Staff SDET",
            yearsExperience = 12,
            education = listOf(
                EducationEntry(degree = "B.S. CS", school = "Test U", location = "Test City, TC", year = "2012")
            ),
            careerHistory = listOf(
                CareerEntry(
                    role = "Senior SDET",
                    company = "Acme",
                    startDate = "2020-01",
                    endDate = "2024-01",
                    bullets = listOf(Bullet("", "KMP framework"))
                )
            ),
            coreStrengths = listOf("Mobile Test Automation"),
            languages = listOf("Kotlin", "Swift"),
            domainExpertise = listOf("retail/commerce")
        ),
        skills = listOf(
            SkillGroup("Primary Stack", listOf("Kotlin", "Swift")),
            SkillGroup("Mobile Automation", listOf("Espresso", "XCUITest")),
            SkillGroup("CI/CD Platforms", listOf("GitHub Actions")),
            SkillGroup("Web & API Automation", listOf("Playwright")),
            SkillGroup("Infrastructure & Observability", listOf("Docker")),
            SkillGroup("Leadership", listOf("Mentoring"))
        ),
        preferences = CandidatePreferences(
            willingToRelocate = false,
            relocationNotes = "Prefer Seattle",
            visaStatus = "US Citizen",
            visaSponsorshipRequired = false,
            preferredWorkArrangement = "Remote-first; hybrid Seattle",
            minimumTotalCompensation = "$200,000",
            openToContractRoles = true,
            minimumContractRateHourly = 85
        )
    )
}
