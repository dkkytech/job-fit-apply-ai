package com.jd.pipeline.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.config.Config
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.emailIntake
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MetadataUtils {

    private val mapper = ObjectMapper()
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    private fun JDState.sourceLabel(): String = when (intake) {
        is IntakeContext.Email -> "email"
        is IntakeContext.Api -> "api"
        is IntakeContext.Synthetic -> "synthetic"
        is IntakeContext.WebCapture -> "extension"
        null -> "unknown"
    }

    fun writeMetadata(state: JDState): String {
        if (state.outputPath.isEmpty()) return ""
        val outputDir = Path.of(state.outputPath)
        if (!Files.exists(outputDir)) return ""

        val generatedAt = isoFormatter.format(Instant.now())
        val artifactBase = state.artifactUrl.trimEnd('/')
        val metadataUrl = if (artifactBase.isNotEmpty()) "$artifactBase/report.md" else ""

        val outputDirUrl = if (artifactBase.isNotEmpty()) "$artifactBase/" else null

        val resumeUrl = when {
            state.resumeHtmlPdf.isEmpty() -> null
            artifactBase.isNotEmpty() -> {
                val filename = Path.of(state.resumeHtmlPdf).fileName?.toString() ?: ""
                if (filename.isNotEmpty()) "$artifactBase/$filename" else state.resumeHtmlPdf
            }
            else -> state.resumeHtmlPdf
        }

        val coverLetterUrl = when {
            state.pipelineAction == PipelineAction.TAILOR && state.coverLetter.isNotEmpty() && artifactBase.isNotEmpty() ->
                "$artifactBase/cover_letter.txt"
            state.coverLetter.isNotEmpty() ->
                "${state.outputPath}/cover_letter.txt"
            else -> null
        }

        writeJson(state, generatedAt, outputDirUrl, resumeUrl, coverLetterUrl, outputDir)
        writeMarkdown(state, generatedAt, outputDirUrl, resumeUrl, coverLetterUrl, outputDir)
        return metadataUrl
    }

    private fun writeJson(
        state: JDState,
        generatedAt: String,
        outputDirUrl: String?,
        resumeUrl: String?,
        coverLetterUrl: String?,
        outputDir: Path
    ) {
        val node = mapper.createObjectNode().apply {
            put("generated_at", generatedAt)
            put("source", state.sourceLabel())
            put("company", state.company)
            put("job_title", state.roleTitle)
            putNullable("location", state.location.ifEmpty { null })
            putNullable("remote_policy", state.remotePolicy.takeIf { it != "unknown" })
            putNullable("employment_type", state.employmentType.ifEmpty { null })
            putNullable("seniority_level", state.seniorityLevel.ifEmpty { null })
            if (state.yoeRequired != null) put("years_experience_required", state.yoeRequired) else putNull("years_experience_required")
            putNullable("salary_range", state.salaryRange.ifEmpty { null })
            set<com.fasterxml.jackson.databind.JsonNode>("tech_stack", mapper.valueToTree(state.techStack))
            putNullable("job_board", state.jobBoard.ifEmpty { null })
            if (state.fitScore != null) put("fit_score", state.fitScore) else putNull("fit_score")
            put("fit_threshold", Config.FIT_THRESHOLD)
            put("pipeline_action", state.pipelineAction.asDbValue() as String)
            put("resume_generation", resumeGenerationDisplay(state))
            set<com.fasterxml.jackson.databind.JsonNode>("resume_degraded_nodes", mapper.valueToTree(state.tailoringDegradedNodes))
            putNullable("skipped_reason", state.skippedReason.ifEmpty { null })
            putNullable("job_posting_url", state.jobUrl.ifEmpty { null })
            putNull("job_board_url")
            putNullable("output_directory_url", outputDirUrl)
            putNullable("tailored_resume_url", resumeUrl)
            putNullable("tailored_cover_letter_url", coverLetterUrl)
            if (state.trackId != null) put("supabase_track_id", state.trackId) else putNull("supabase_track_id")
            put("is_duplicate", state.isDuplicate)
            putNullable("email_subject", state.emailIntake?.subject?.ifEmpty { null })
            putNullable("email_from", state.emailIntake?.from?.ifEmpty { null })
            putNullable("email_url", gmailUrl(state.emailIntake?.emailId))
            putNullable("fit_reasoning", state.fitReasoning.ifEmpty { null })
            set<com.fasterxml.jackson.databind.JsonNode>("strengths", mapper.valueToTree(state.strengths))
            set<com.fasterxml.jackson.databind.JsonNode>("gaps", mapper.valueToTree(state.gaps))
            set<com.fasterxml.jackson.databind.JsonNode>("red_flags", mapper.valueToTree(state.redFlags))
            putNullable("error", state.error.ifEmpty { null })
        }

        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
        Files.writeString(outputDir.resolve("metadata.json"), json)
        println("[metadata] Wrote metadata.json to $outputDir")
    }

    private fun com.fasterxml.jackson.databind.node.ObjectNode.putNullable(fieldName: String, value: String?) {
        if (value != null) put(fieldName, value) else putNull(fieldName)
    }

    /** Whether the tailored resume was fully generated or short-circuited (fell back on some nodes). */
    private fun resumeGenerationDisplay(state: JDState): String = when {
        state.pipelineAction != PipelineAction.TAILOR -> "N/A (not tailored)"
        state.tailoringDegradedNodes.isEmpty()        -> "✅ Fully generated"
        else -> "⚠️ Short-circuited — fell back on: ${state.tailoringDegradedNodes.joinToString(", ")}"
    }

    private fun writeMarkdown(
        state: JDState,
        generatedAt: String,
        outputDirUrl: String?,
        resumeUrl: String?,
        coverLetterUrl: String?,
        outputDir: Path
    ) {
        val fitScoreDisplay = if (state.fitScore != null) "%.1f / 100".format(state.fitScore) else "—"
        val isDuplicateDisplay = if (state.isDuplicate) "Yes" else "No"
        val skippedDisplay = state.skippedReason.ifEmpty { "—" }

        val md = buildString {
            appendLine("# ${state.roleTitle.ifEmpty { "Unknown Role" }} — ${state.company.ifEmpty { "Unknown Company" }}")
            appendLine()

            // Job Details
            appendLine("## Job Details")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|-------|-------|")
            appendLine("| Company | ${state.company.ifEmpty { "—" }} |")
            appendLine("| Job Title | ${state.roleTitle.ifEmpty { "—" }} |")
            appendLine("| Fit Score | $fitScoreDisplay |")
            appendLine("| Location | ${state.location.ifEmpty { "—" }} |")
            appendLine("| Remote Policy | ${state.remotePolicy.takeIf { it != "unknown" } ?: "—"} |")
            appendLine("| Salary | ${state.salaryRange.ifEmpty { "—" }} |")
            appendLine("| Employment Type | ${state.employmentType.ifEmpty { "—" }} |")
            appendLine("| Seniority | ${state.seniorityLevel.ifEmpty { "—" }} |")
            appendLine("| YOE Required | ${state.yoeRequired?.toString() ?: "—"} |")
            appendLine("| Source | ${sourceCell(state)} |")
            appendLine("| Job Board | ${state.jobBoard.ifEmpty { "—" }} |")
            appendLine("| Job Posting | ${linkOrNA(state.jobUrl)} |")
            appendLine()

            // Artifacts
            appendLine("## Artifacts")
            appendLine()
            appendLine("| Artifact | Link |")
            appendLine("|----------|------|")
            appendLine("| Tailored Resume | ${linkOrNA(resumeUrl, "PDF")} |")
            appendLine("| Cover Letter | ${linkOrNA(coverLetterUrl, "TXT")} |")
            appendLine("| Output Directory | ${linkOrNA(outputDirUrl)} |")
            appendLine()


            // Pipeline Result
            appendLine("## Pipeline Result")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|-------|-------|")
            appendLine("| Action | ${state.pipelineAction} |")
            appendLine("| Fit Score | $fitScoreDisplay |")
            appendLine("| Fit Threshold | ${Config.FIT_THRESHOLD} |")
            appendLine("| Skipped Reason | $skippedDisplay |")
            appendLine("| Is Duplicate | $isDuplicateDisplay |")
            appendLine("| Resume Generation | ${resumeGenerationDisplay(state)} |")
            appendLine()

            // Fit Analysis (only if scored)
            if (state.fitScore != null || state.fitReasoning.isNotEmpty()) {
                appendLine("## Fit Analysis")
                appendLine()
                if (state.fitReasoning.isNotEmpty()) {
                    appendLine("**Reasoning:** ${state.fitReasoning}")
                    appendLine()
                }

                appendLine("### Strengths")
                appendLine()
                if (state.strengths.isEmpty()) appendLine("_None identified._")
                else state.strengths.forEach { appendLine("- $it") }
                appendLine()

                appendLine("### Gaps")
                appendLine()
                if (state.gaps.isEmpty()) appendLine("_None identified._")
                else state.gaps.forEach { appendLine("- $it") }
                appendLine()

                appendLine("### Red Flags")
                appendLine()
                if (state.redFlags.isEmpty()) appendLine("_None identified._")
                else state.redFlags.forEach { appendLine("- $it") }
                appendLine()
            }

            // Tech Stack
            if (state.techStack.isNotEmpty()) {
                appendLine("## Tech Stack")
                appendLine()
                appendLine(state.techStack.joinToString("  ") { "`$it`" })
                appendLine()
            }

            // Email context (if from email)
            val email = state.emailIntake
            if (email != null && (email.subject.isNotEmpty() || email.from.isNotEmpty())) {
                appendLine("## Email Context")
                appendLine()
                appendLine("| Field | Value |")
                appendLine("|-------|-------|")
                appendLine("| From | ${email.from.ifEmpty { "—" }} |")
                appendLine("| Subject | ${email.subject.ifEmpty { "—" }} |")
                appendLine()
            }

            // Error (if any)
            if (state.error.isNotEmpty()) {
                appendLine("## Error")
                appendLine()
                appendLine("```")
                appendLine(state.error)
                appendLine("```")
                appendLine()
            }

            append("---")
            appendLine()
            appendLine()
            append("_Processed: $generatedAt · Pipeline action: ${state.pipelineAction}_")
        }

        Files.writeString(outputDir.resolve("report.md"), md)
        println("[metadata] Wrote report.md to $outputDir")
    }

    private fun linkOrNA(url: String?, label: String = "URL"): String {
        return if (!url.isNullOrEmpty()) "<a href=\"$url\" target=\"_blank\" rel=\"noopener noreferrer\">$label</a>" else "N/A"
    }

    // The "u/0" in the deep-link is the first signed-in Gmail account. This is a
    // single-user system, so the account index is fixed at 0.
    private const val GMAIL_ACCOUNT_INDEX = 0

    /** Gmail web deep-link to the originating email, or null when there's no usable id. */
    private fun gmailUrl(emailId: String?): String? =
        emailId?.takeIf { it.isNotBlank() }
            ?.let { "https://mail.google.com/mail/u/$GMAIL_ACCOUNT_INDEX/#all/$it" }

    /** Source label, linked to the originating email in Gmail for email-sourced jobs. */
    private fun sourceCell(state: JDState): String {
        val label = state.sourceLabel()
        val url = gmailUrl(state.emailIntake?.emailId) ?: return label
        return "<a href=\"$url\" target=\"_blank\" rel=\"noopener noreferrer\">$label</a>"
    }
}
