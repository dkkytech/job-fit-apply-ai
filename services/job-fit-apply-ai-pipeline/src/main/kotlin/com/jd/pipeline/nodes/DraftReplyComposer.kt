package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.utils.CandidateProfileRenderer
import java.nio.file.Files

/**
 * Generates the recruiter reply body — the LLM/templating half of the old CreateDraftReplyNode,
 * with NO Gmail. The Processor calls this to fill [JDState.draftText]; the Poller (which owns
 * Gmail) creates the actual draft from that text. Extracted so reply composition is testable
 * and container-safe (no OAuth on the Processor).
 *
 * [generate] is injectable so tests can stub the LLM.
 */
class DraftReplyComposer(
    private val generate: (String) -> String = { prompt ->
        LlmClient.fromModelString(Config.DRAFT_REPLY_MODEL, jsonMode = false, temperature = 0.3, nodeKey = "draft_reply")
            .call(prompt)
    },
) {

    /** Returns the reply body, or null when the state is not a recruiter email we can reply to. */
    fun compose(state: JDState): String? {
        val email = state.intake as? IntakeContext.Email ?: return null
        if (!email.isRecruiter || email.emailId.isBlank()) return null
        val prompt = fillTemplate(loadSkillTemplate(), state, email)
        return generate(prompt)
    }

    private fun loadSkillTemplate(): String {
        val skillPath = Config.SKILLS_DIR.resolve("DRAFT_REPLY_SKILL.md")
        return if (Files.exists(skillPath)) Files.readString(skillPath) else DEFAULT_SKILL_TEMPLATE
    }

    private fun fillTemplate(template: String, input: JDState, email: IntakeContext.Email): String {
        val prefs = input.candidateProfile?.preferences
        val strengths = input.strengths.joinToString(", ").ifBlank { "strong automation and CI/CD background" }
        val scoreStr = input.fitScore?.let { "%.0f".format(it) } ?: "N/A"
        val safeEmailBody = sanitizeEmailBody(email.rawBody)
        val preferencesBlock = buildPreferencesBlock(prefs)
        // Full résumé + profile grounding so the reply only answers what the candidate can back up.
        val profileBlock = input.candidateProfile?.let { CandidateProfileRenderer.renderForReply(it) } ?: ""
        val missingInfoDirective = buildMissingInfoDirective(input)
        return template
            .replace("{{role_title}}", input.roleTitle.ifBlank { "the role" })
            .replace("{{company}}", input.company.ifBlank { "your company" })
            .replace("{{location}}", input.location.ifBlank { "unspecified" })
            .replace("{{fit_score}}", scoreStr)
            .replace("{{strengths}}", strengths)
            .replace("{{email_body}}", safeEmailBody)
            .replace("{{preferences}}", preferencesBlock)
            .replace("{{candidate_profile}}", profileBlock)
            .replace("{{missing_info_directive}}", missingInfoDirective)
            .replace("{{author_name}}", input.candidateProfile?.identity?.fullName?.ifBlank { "Applicant" } ?: "Applicant")
    }

    /**
     * When a recruiter forwards a JD without naming the client or stating the salary, the reply
     * must open by asking for whichever is missing. Detection is deterministic here (not left to the
     * LLM) so the first-sentence behavior is reliable; the directive is empty when both are present.
     */
    private fun buildMissingInfoDirective(input: JDState): String {
        val clientMissing = input.company.isBlank() || input.company.trim().lowercase() in PLACEHOLDER_COMPANIES
        val salaryMissing = input.salaryRange.isBlank() && input.postedCompMin == null && input.postedCompMax == null
        if (!clientMissing && !salaryMissing) return ""

        val asks = buildList {
            if (clientMissing) add("who the end client / company is")
            if (salaryMissing) add("what the budget for the role is")
        }
        return "IMPORTANT: The recruiter did not disclose ${asks.joinToString(" and/or ")}. " +
            "The FIRST sentence of your reply MUST politely ask for ${if (asks.size > 1) "these" else "this"} " +
            "before anything else."
    }

    private fun buildPreferencesBlock(prefs: com.jd.pipeline.models.CandidatePreferences?): String {
        if (prefs == null) return ""
        return buildString {
            appendLine("## Candidate Preferences")
            appendLine()
            appendLine("- **Visa status:** ${prefs.visaStatus}")
            if (!prefs.visaSponsorshipRequired) {
                appendLine("  → Does not require visa sponsorship")
            }
            appendLine("- **Work arrangement:** ${prefs.preferredWorkArrangement}")
            append("- **Available to start:** ")
            appendLine(
                when {
                    prefs.availableToStartDate != null -> prefs.availableToStartDate
                    prefs.noticePeriodDays != null -> "${prefs.noticePeriodDays}-day notice period"
                    else -> "Immediately"
                }
            )
            if (prefs.willingToRelocate) {
                appendLine("- **Relocation:** Willing to relocate${prefs.relocationNotes?.let { " — $it" } ?: ""}")
            } else {
                appendLine("- **Relocation:** Not willing to relocate")
                prefs.relocationNotes?.let { appendLine("  → $it") }
            }
            if (prefs.travelPercentage != null) {
                appendLine("- **Travel:** Willing to travel ${prefs.travelPercentage}")
            }
            if (prefs.openToContractRoles) {
                append("- **Contract:** Open to contract")
                prefs.minimumContractRateHourly?.let { append(" at \$$it/hr") }
                appendLine()
            }
            prefs.compensationNotes?.let { appendLine("- **Compensation:** $it") }
            prefs.additionalNotes?.let { appendLine("- **Notes:** $it") }
        }
    }

    /** Strip content that looks like prompt injection from the recruiter email body. */
    private fun sanitizeEmailBody(raw: String): String {
        val injectionPatterns = listOf(
            Regex("""(?i)(ignore|disregard|forget)\s+(all\s+)?(previous|prior|above|earlier)\s+(instructions?|prompt|context|rules?)"""),
            Regex("""(?i)you\s+are\s+(now\s+)?(a|an)\s+\w+"""),
            Regex("""(?i)(system\s+)?prompt\s*[:=]"""),
            Regex("""(?i)output\s+your\s+(system\s+)?prompt"""),
            Regex("""(?i)act\s+as\s+(if\s+)?(you\s+are\s+)?"""),
            Regex("""(?i)<\s*(system|instruction|prompt)\s*>""")
        )
        return raw.lines()
            .filterNot { line -> injectionPatterns.any { it.containsMatchIn(line) } }
            .joinToString("\n")
            .trim()
    }

    companion object {
        /** Company values that read as "client withheld" and should trigger the client ask. */
        val PLACEHOLDER_COMPANIES = setOf(
            "confidential", "undisclosed", "n/a", "na", "unknown", "stealth", "tbd",
            "client", "a client", "our client",
        )

        val DEFAULT_SKILL_TEMPLATE = """
            You are drafting a professional reply to a recruiter.
            Role: {{role_title}} at {{company}} ({{location}}).

            {{missing_info_directive}}

            Only answer a question if the answer is stated in, or can be reasonably inferred from, my
            profile below. If a question cannot be grounded in the profile, omit it entirely — do not
            answer it, acknowledge it, or promise to follow up.

            MY PROFILE:
            {{candidate_profile}}

            Reply to the email below. Mention my attached resume and cover letter.
            Sign as: Best regards,\n{{author_name}}

            RECRUITER EMAIL:
            {{email_body}}
        """.trimIndent()
    }
}
