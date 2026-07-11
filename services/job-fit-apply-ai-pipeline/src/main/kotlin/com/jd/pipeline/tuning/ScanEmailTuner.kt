package com.jd.pipeline.tuning

import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.config.Config
import com.jd.pipeline.nodes.ScanEmailNode
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isDigest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * ScanEmailTuner
 *
 * Tuning helper pipeline for investigating email parsing quality.
 * It fetches a real Gmail message, dumps investigation artifacts, runs ScanEmailNode,
 * and writes a comparison report against the user-provided expected visible data.
 */
class ScanEmailTuner {

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 5
    }

    private val gmailClient = GmailTransport()
    private val scanEmailNode = ScanEmailNode()

    fun invoke(
        emailSubject: String,
        expectedVisibleData: String,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        debug: Boolean = false
    ): JDState {
        val tunerSkill = loadTunerSkill()
        val bundle = gmailClient.fetchEmailInvestigationBySubject(emailSubject, debug)
            ?: throw IllegalArgumentException("No email found matching subject: $emailSubject")

        val outputDir = createOutputDir(emailSubject)
        writeInvestigationArtifacts(bundle, expectedVisibleData, outputDir, maxIterations, tunerSkill)

        val input = bundle.state.copy(
            expectedScanData = expectedVisibleData,
            scanTuningOutputDir = outputDir.toString()
        )

        val scanned = scanEmailNode.process(input)
        val result = scanned.copy(
            expectedScanData = expectedVisibleData,
            scanTuningOutputDir = outputDir.toString()
        )

        val comparisonReport = buildComparisonReport(bundle.state, result, expectedVisibleData, maxIterations, tunerSkill)
        val finalResult = result.copy(scanComparisonReport = comparisonReport)
        writeString(outputDir.resolve("scan_comparison_report.md"), comparisonReport)
        writeString(outputDir.resolve("scan_result_dump.txt"), buildResultDump(finalResult, maxIterations))

        return finalResult
    }

    private fun writeInvestigationArtifacts(
        bundle: GmailTransport.EmailInvestigationBundle,
        expectedVisibleData: String,
        outputDir: Path,
        maxIterations: Int,
        tunerSkill: String
    ) {
        writeString(outputDir.resolve("email_subject.txt"), bundle.state.emailIntake?.subject ?: "")
        writeString(outputDir.resolve("email_from.txt"), bundle.state.emailIntake?.from ?: "")
        writeString(outputDir.resolve("max_iterations.txt"), maxIterations.toString())
        writeString(outputDir.resolve("scan_email_tuner_skill_path.txt"), Config.SCAN_EMAIL_TUNER_SKILL.toString())
        writeString(outputDir.resolve("scan_email_tuner_skill_snapshot.md"), tunerSkill)
        writeString(outputDir.resolve("expected_visible_data.txt"), expectedVisibleData)
        writeString(outputDir.resolve("email_plain_text.txt"), bundle.plainTextBody)
        writeString(outputDir.resolve("email_part_summary.txt"), bundle.partSummary)

        val htmlDir = outputDir.resolve("html_parts")
        Files.createDirectories(htmlDir)
        if (bundle.htmlBodies.isEmpty()) {
            writeString(htmlDir.resolve("part_00_empty.txt"), "No HTML parts found.")
        } else {
            bundle.htmlBodies.forEachIndexed { index, html ->
                writeString(htmlDir.resolve("part_${index.toString().padStart(2, '0')}.html"), html)
            }
        }

        writeString(
            outputDir.resolve("inline_scripts.txt"),
            if (bundle.inlineScripts.isEmpty()) "No inline scripts found." else bundle.inlineScripts.joinToString("\n\n---\n\n")
        )
        writeString(
            outputDir.resolve("script_urls.txt"),
            if (bundle.scriptUrls.isEmpty()) "No script URLs found." else bundle.scriptUrls.joinToString("\n")
        )
    }

    private fun buildComparisonReport(
        original: JDState,
        result: JDState,
        expectedVisibleData: String,
        maxIterations: Int,
        tunerSkill: String
    ): String {
        val expectedLower = expectedVisibleData.lowercase()
        val expectedUrls = extractUrls(expectedVisibleData)
        val extractedUrls = mutableListOf<String>()
        if (result.jobUrl.isNotBlank()) extractedUrls.add(result.jobUrl)
        extractedUrls.addAll(result.digestJobs.map { it.jobUrl }.filter { it.isNotBlank() })
        val digestJobs = result.digestJobs
        val digestCompanyMatches = digestJobs.count { containsIgnoreCase(expectedLower, it.company) }
        val digestRoleMatches = digestJobs.count { containsIgnoreCase(expectedLower, it.roleTitle) }
        val digestLocationMatches = digestJobs.count { containsIgnoreCase(expectedLower, it.location) }
        val digestSalaryMatches = digestJobs.count { containsIgnoreCase(expectedLower, it.salaryRange) }

        return buildString {
            appendLine("# Scan Email Tuner Report")
            appendLine()
            appendLine("## Email")
            appendLine("- Subject: ${original.emailIntake?.subject ?: ""}")
            appendLine("- From: ${original.emailIntake?.from ?: ""}")
            appendLine("- Max Iterations: $maxIterations")
            appendLine("- Tuner Skill: ${Config.SCAN_EMAIL_TUNER_SKILL}")
            appendLine("- Dataset Dir: ${Config.SCAN_EMAIL_TUNER_DATASET_DIR}")
            appendLine("- Output Dir: ${result.scanTuningOutputDir}")
            appendLine()
            appendLine("## Tuner Skill")
            appendLine("- source_of_truth_loaded: ${tunerSkill.isNotBlank()}")
            appendLine("- skill_snapshot: ${Path.of(result.scanTuningOutputDir).resolve("scan_email_tuner_skill_snapshot.md")}")
            appendLine()
            appendLine("## Extracted Fields")
            appendLine("- is_job_posting: ${result.isJobPosting}")
            appendLine("- is_digest: ${result.isDigest}")
            appendLine("- company: ${formatValue(result.company)}")
            appendLine("- role_title: ${formatValue(result.roleTitle)}")
            appendLine("- location: ${formatValue(result.location)}")
            appendLine("- remote_policy: ${formatValue(result.remotePolicy)}")
            appendLine("- job_url: ${formatValue(result.jobUrl)}")
            appendLine("- digest_job_count: ${result.digestJobs.size}")
            appendLine("- tech_stack: ${if (result.techStack.isEmpty()) "(empty)" else result.techStack.joinToString(", ")}")
            appendLine("- jd_text_preview: ${formatValue(result.jdText.take(400))}")
            appendLine()
            appendLine("## Expected Data Checks")
            appendLine("- top-level company appears in expected dump: ${containsIgnoreCase(expectedLower, result.company)}")
            appendLine("- top-level role title appears in expected dump: ${containsIgnoreCase(expectedLower, result.roleTitle)}")
            appendLine("- top-level location appears in expected dump: ${containsIgnoreCase(expectedLower, result.location)}")
            appendLine("- job_url appears in expected dump: ${containsUrl(expectedUrls, result.jobUrl)}")
            appendLine("- extracted URLs matched against expected URLs: ${if (expectedUrls.isEmpty()) "no URLs present in expected dump" else extractedUrls.count { containsUrl(expectedUrls, it) }}/${extractedUrls.size}")
            if (digestJobs.isNotEmpty()) {
                appendLine("- digest companies matched in expected dump: $digestCompanyMatches/${digestJobs.size}")
                appendLine("- digest role titles matched in expected dump: $digestRoleMatches/${digestJobs.size}")
                appendLine("- digest locations matched in expected dump: $digestLocationMatches/${digestJobs.size}")
                appendLine("- digest salary ranges matched in expected dump: $digestSalaryMatches/${digestJobs.size}")
            }
            appendLine()
            appendLine("## Digest Jobs")
            if (digestJobs.isEmpty()) {
                appendLine("- none")
            } else {
                digestJobs.forEachIndexed { index, job ->
                    appendLine(
                        "- [${index + 1}] ${formatValue(job.company)} | ${formatValue(job.roleTitle)} | ${formatValue(job.location)} | ${formatValue(job.salaryRange)} | company_match=${containsIgnoreCase(expectedLower, job.company)} | title_match=${containsIgnoreCase(expectedLower, job.roleTitle)} | location_match=${containsIgnoreCase(expectedLower, job.location)} | salary_match=${containsIgnoreCase(expectedLower, job.salaryRange)} | ${formatValue(job.jobUrl)}"
                    )
                }
            }
            appendLine()
            appendLine("## Errors")
            appendLine("- error: ${formatValue(result.error)}")
            appendLine("- skipped_reason: ${formatValue(result.skippedReason)}")
        }
    }

    private fun buildResultDump(result: JDState, maxIterations: Int): String {
        return buildString {
            appendLine("email_id=${result.emailIntake?.emailId ?: ""}")
            appendLine("email_subject=${result.emailIntake?.subject ?: ""}")
            appendLine("email_from=${result.emailIntake?.from ?: ""}")
            appendLine("max_iterations=$maxIterations")
            appendLine("is_job_posting=${result.isJobPosting}")
            appendLine("is_digest=${result.isDigest}")
            appendLine("company=${result.company}")
            appendLine("role_title=${result.roleTitle}")
            appendLine("location=${result.location}")
            appendLine("remote_policy=${result.remotePolicy}")
            appendLine("job_url=${result.jobUrl}")
            appendLine("tech_stack=${result.techStack.joinToString(",")}")
            appendLine("error=${result.error}")
            appendLine("skipped_reason=${result.skippedReason}")
            appendLine("digest_job_count=${result.digestJobs.size}")
            appendLine()
            appendLine("jd_text:")
            appendLine(result.jdText)
            if (result.digestJobs.isNotEmpty()) {
                appendLine()
                appendLine("digest_jobs:")
                result.digestJobs.forEachIndexed { index, job ->
                    appendLine("[${index + 1}] company=${job.company}")
                    appendLine("[${index + 1}] role_title=${job.roleTitle}")
                    appendLine("[${index + 1}] location=${job.location}")
                    appendLine("[${index + 1}] salary_range=${job.salaryRange}")
                    appendLine("[${index + 1}] job_url=${job.jobUrl}")
                }
            }
        }
    }

    private fun createOutputDir(subject: String): Path {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val safeSubject = com.jd.pipeline.utils.OutputUtils.sanitizeFileName(subject)
            .take(80)
            .ifBlank { "email" }
        val outputDir = Config.OUTPUT_DIR.resolve("scan_tuner").resolve("${timestamp}_$safeSubject")
        Files.createDirectories(outputDir)
        return outputDir
    }

    private fun loadTunerSkill(): String {
        val path = Config.SCAN_EMAIL_TUNER_SKILL
        require(Files.exists(path)) { "ScanEmailTuner skill file not found: $path" }
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun writeString(path: Path, content: String) {
        Files.writeString(
            path,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    private fun formatValue(value: String?): String {
        return if (value.isNullOrBlank()) "(empty)" else value
    }

    private fun containsIgnoreCase(expectedLower: String, value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return expectedLower.contains(value.lowercase())
    }

    private fun containsUrl(expectedUrls: Set<String>, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return expectedUrls.contains(url.trim())
    }

    private fun extractUrls(text: String): Set<String> {
        val matcher = Pattern.compile("https?://[^\\s<>\"'\\]\\)]+").matcher(text)
        val urls = mutableSetOf<String>()
        while (matcher.find()) {
            urls.add(matcher.group().trim())
        }
        return urls
    }
}
