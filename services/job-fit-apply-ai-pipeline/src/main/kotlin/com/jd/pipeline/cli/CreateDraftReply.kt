package com.jd.pipeline.cli

import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.config.Config
import com.jd.pipeline.models.CandidatePreferences
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.ProcessingResult
import java.io.File
import java.nio.file.Files

/**
 * Ingestion-side helper that creates a Gmail draft reply to a recruiter.
 *
 * Called AFTER the worker has finished processing and artifacts are downloaded.
 * No pipeline state — everything needed is passed explicitly.
 */
object CreateDraftReply {

    private val llm = LlmClient.fromModelString(
        Config.DRAFT_REPLY_MODEL,
        jsonMode    = false,
        temperature = 0.3,
        nodeKey     = "draft_reply"
    )

    /**
     * Generate and create a Gmail draft reply.
     * Returns the new draft ID, or null if anything fails.
     */
    fun run(
        intake: IntakeContext.Email,
        result: ProcessingResult,
        resumePdf: File,
        coverLetter: File?,
        profile: CandidateProfile?,
    ): String? {
        if (!intake.isRecruiter || intake.emailId.isBlank()) return null

        return try {
            val replyBody = generateReply(intake, result, profile)
            val toAddress = extractEmailAddress(intake.from)
            val subject   = buildSubject(intake.subject)
            val attachments = buildList {
                if (resumePdf.exists()) add(resumePdf.absolutePath)
                if (coverLetter?.exists() == true) add(coverLetter.absolutePath)
            }

            val gmail = GmailTransport()
            val draftId = gmail.createDraftReply(
                originalEmailId = intake.emailId,
                toAddress       = toAddress,
                subject         = subject,
                body            = replyBody,
                attachmentPaths = attachments,
            )
            println("[draft_reply] Draft created (id=$draftId) with ${attachments.size} attachment(s)")
            draftId
        } catch (e: Exception) {
            System.err.println("[draft_reply] ERROR: ${e.message}")
            null
        }
    }

    // ── LLM reply generation ──────────────────────────────────────────────────

    private fun generateReply(
        intake: IntakeContext.Email,
        result: ProcessingResult,
        profile: CandidateProfile?,
    ): String {
        val template = loadSkillTemplate()
        val prefs    = profile?.preferences
        val strengths = result.strengths.joinToString(", ").ifBlank { "strong automation and CI/CD background" }
        val scoreStr  = result.fitScore.toString()
        val safeBody  = sanitizeEmailBody(intake.rawBody)
        val prefBlock = buildPreferencesBlock(prefs)
        return template
            .replace("{{role_title}}",  "(the role)")        // not in result; could be from jdRecord
            .replace("{{company}}",     "(your company)")
            .replace("{{location}}",    "unspecified")
            .replace("{{fit_score}}",   scoreStr)
            .replace("{{strengths}}",   strengths)
            .replace("{{email_body}}",  safeBody)
            .replace("{{preferences}}", prefBlock)
            .replace("{{author_name}}", profile?.identity?.fullName?.ifBlank { "Applicant" } ?: "Applicant")
    }

    private fun loadSkillTemplate(): String {
        val skillPath = Config.SKILLS_DIR.resolve("DRAFT_REPLY_SKILL.md")
        return if (Files.exists(skillPath)) Files.readString(skillPath) else DEFAULT_SKILL_TEMPLATE
    }

    private fun buildPreferencesBlock(prefs: CandidatePreferences?): String {
        if (prefs == null) return ""
        return buildString {
            appendLine("## Candidate Preferences")
            appendLine()
            appendLine("- **Visa status:** ${prefs.visaStatus}")
            if (!prefs.visaSponsorshipRequired) appendLine("  → Does not require visa sponsorship")
            appendLine("- **Work arrangement:** ${prefs.preferredWorkArrangement}")
            append("- **Available to start:** ")
            appendLine(when {
                prefs.availableToStartDate != null -> prefs.availableToStartDate
                prefs.noticePeriodDays != null     -> "${prefs.noticePeriodDays}-day notice period"
                else                               -> "Immediately"
            })
            if (prefs.willingToRelocate) {
                appendLine("- **Relocation:** Willing to relocate${prefs.relocationNotes?.let { " — $it" } ?: ""}")
            } else {
                appendLine("- **Relocation:** Not willing to relocate")
                prefs.relocationNotes?.let { appendLine("  → $it") }
            }
            prefs.travelPercentage?.let { appendLine("- **Travel:** Willing to travel $it") }
            if (prefs.openToContractRoles) {
                append("- **Contract:** Open to contract")
                prefs.minimumContractRateHourly?.let { append(" at \$$it/hr") }
                appendLine()
            }
            prefs.compensationNotes?.let { appendLine("- **Compensation:** $it") }
            prefs.additionalNotes?.let { appendLine("- **Notes:** $it") }
        }
    }

    private fun sanitizeEmailBody(raw: String): String {
        val injectionPatterns = listOf(
            Regex("""(?i)(ignore|disregard|forget)\s+(all\s+)?(previous|prior|above|earlier)\s+(instructions?|prompt|context|rules?)"""),
            Regex("""(?i)you\s+are\s+(now\s+)?(a|an)\s+\w+"""),
            Regex("""(?i)(system\s+)?prompt\s*[:=]"""),
            Regex("""(?i)output\s+your\s+(system\s+)?prompt"""),
            Regex("""(?i)act\s+as\s+(if\s+)?(you\s+are\s+)?"""),
            Regex("""(?i)<\s*(system|instruction|prompt)\s*>"""),
        )
        return raw.lines()
            .filterNot { line -> injectionPatterns.any { it.containsMatchIn(line) } }
            .joinToString("\n")
            .trim()
    }

    private fun buildSubject(original: String): String {
        val trimmed = original.trim()
        return if (trimmed.startsWith("Re:", ignoreCase = true)) trimmed else "Re: $trimmed"
    }

    private fun extractEmailAddress(from: String): String =
        Regex("""<([^>]+)>""").find(from)?.groupValues?.get(1) ?: from.trim()

    private val DEFAULT_SKILL_TEMPLATE = """
        You are drafting a professional reply to a recruiter.
        Role: {{role_title}} at {{company}} ({{location}}).
        My strengths: {{strengths}}.
        {{preferences}}

        Reply to the email below. Answer any questions directly. Mention my attached resume and cover letter.
        Sign as: Best regards,\n{{author_name}}

        RECRUITER EMAIL:
        {{email_body}}
    """.trimIndent()
}
