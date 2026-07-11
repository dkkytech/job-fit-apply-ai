package com.jd.pipeline.tuning

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.config.Config
import com.jd.pipeline.nodes.ScrapeJdNode
import com.jd.pipeline.state.JDState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ScrapeJdTuner
 *
 * Tuning helper pipeline for investigating ScrapeJdNode quality.
 * It reads a dataset file containing a job URL and optional expected data,
 * runs ScrapeJdNode, dumps investigation artifacts, and writes a comparison report.
 */
class ScrapeJdTuner {

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 5
    }

    private val scrapeJdNode = ScrapeJdNode()
    private val mapper = ObjectMapper()

    fun invoke(
        datasetFilePath: String,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS
    ): JDState {
        val tunerSkill = loadTunerSkill()
        val input = parseDatasetFile(datasetFilePath)

        val outputDir = createOutputDir(input.company, input.roleTitle)
        writeInputArtifacts(input, datasetFilePath, outputDir, maxIterations, tunerSkill)

        scrapeJdNode.verbose = false
        val scraped = scrapeJdNode.process(input)
        val result = scraped.copy(scrapeJdTuningOutputDir = outputDir.toString())

        val comparisonReport = buildComparisonReport(input, result, maxIterations, tunerSkill)
        val finalResult = result.copy(scrapeJdComparisonReport = comparisonReport)
        writeString(outputDir.resolve("scrape_comparison_report.md"), comparisonReport)
        writeString(outputDir.resolve("scrape_result_dump.txt"), buildResultDump(finalResult, maxIterations))

        if (finalResult.scrapedContent.isNotEmpty()) {
            writeString(outputDir.resolve("scraped_content.txt"), finalResult.scrapedContent)
        }

        return finalResult
    }

    private fun parseDatasetFile(path: String): JDState {
        val filePath = Path.of(path)
        val content = Files.readString(filePath, StandardCharsets.UTF_8).trim()

        // JSON format (preferred)
        if (path.endsWith(".json", ignoreCase = true) || content.startsWith("{")) {
            return parseDatasetJson(content)
        }

        // Legacy text format (backward compatibility)
        return parseDatasetText(content)
    }

    private fun parseDatasetJson(content: String): JDState {
        val node = mapper.readTree(content)

        val jobUrl = node.path("job_url").asText("")
        val expectedJdText = node.path("expected_jd_text").asText("")

        return JDState(
            jobUrl = jobUrl,
            company = node.path("company").asText(""),
            roleTitle = node.path("role_title").asText(""),
            location = node.path("location").asText(""),
            salaryRange = node.path("salary_range").asText(""),
            jdText = node.path("email_jd_text").asText(""),
            isJobPosting = jobUrl.isNotBlank(),
            expectedScanData = expectedJdText
        )
    }

    private fun parseDatasetText(content: String): JDState {
        val jobUrl = parseTextField(content, "job_url") ?: ""
        val expectedJdText = parseTextMultilineField(content, "expected_jd_text") ?: ""

        return JDState(
            jobUrl = jobUrl,
            company = parseTextField(content, "company") ?: "",
            roleTitle = parseTextField(content, "role_title") ?: "",
            location = parseTextField(content, "location") ?: "",
            salaryRange = parseTextField(content, "salary_range") ?: "",
            jdText = parseTextMultilineField(content, "email_jd_text") ?: "",
            isJobPosting = jobUrl.isNotBlank(),
            expectedScanData = expectedJdText
        )
    }

    private fun parseTextField(content: String, key: String): String? {
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("$key:") && !trimmed.startsWith("${key}_")) {
                val value = trimmed.removePrefix("$key:").trim()
                return value.ifEmpty { null }
            }
        }
        return null
    }

    private fun parseTextMultilineField(content: String, key: String): String? {
        val marker = "$key:"
        val idx = content.indexOf(marker)
        if (idx < 0) return null

        val afterMarker = content.substring(idx + marker.length)
        val lines = afterMarker.lines()

        // Skip the first line (may be blank or inline value)
        val bodyLines = mutableListOf<String>()
        var started = false
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            // Stop at next top-level key or comment section
            if (trimmed.matches(Regex("[a-z_]+:.*")) || trimmed.startsWith("# ══")) break
            if (!started && trimmed.isEmpty()) continue
            started = true
            if (trimmed.startsWith("#")) break
            bodyLines.add(line)
        }

        // Also check inline value on same line as marker
        val inlineLine = lines.firstOrNull()?.trim() ?: ""
        if (inlineLine.isNotEmpty()) {
            return inlineLine
        }

        val body = bodyLines.joinToString("\n").trim()
        return body.ifEmpty { null }
    }

    private fun writeInputArtifacts(
        input: JDState,
        datasetFilePath: String,
        outputDir: Path,
        maxIterations: Int,
        tunerSkill: String
    ) {
        writeString(outputDir.resolve("dataset_file.txt"), datasetFilePath)
        writeString(outputDir.resolve("job_url.txt"), input.jobUrl)
        writeString(outputDir.resolve("company.txt"), input.company)
        writeString(outputDir.resolve("role_title.txt"), input.roleTitle)
        writeString(outputDir.resolve("max_iterations.txt"), maxIterations.toString())
        writeString(outputDir.resolve("scrape_jd_tuner_skill_path.txt"), Config.SCRAPE_JD_URL_TUNER_SKILL.toString())
        writeString(outputDir.resolve("scrape_jd_tuner_skill_snapshot.md"), tunerSkill)
        if (input.expectedScanData.isNotBlank()) {
            writeString(outputDir.resolve("expected_jd_text.txt"), input.expectedScanData)
        }
        if (input.jdText.isNotBlank()) {
            writeString(outputDir.resolve("email_jd_text.txt"), input.jdText)
        }
    }

    private fun buildComparisonReport(
        input: JDState,
        result: JDState,
        maxIterations: Int,
        tunerSkill: String
    ): String {
        val expectedJdText = input.expectedScanData
        val hasExpected = expectedJdText.isNotBlank()
        val jdTextLength = result.jdText.length

        val jdTextContainsExpected = if (hasExpected && result.jdText.isNotBlank()) {
            val expectedTokens = expectedJdText.lowercase().split(Regex("\\s+")).filter { it.length > 5 }.take(20)
            val jdLower = result.jdText.lowercase()
            val matchCount = expectedTokens.count { jdLower.contains(it) }
            matchCount to expectedTokens.size
        } else {
            null
        }

        return buildString {
            appendLine("# Scrape JD Tuner Report")
            appendLine()
            appendLine("## Input")
            appendLine("- Job URL: ${input.jobUrl}")
            appendLine("- Company (from dataset): ${formatValue(input.company)}")
            appendLine("- Role Title (from dataset): ${formatValue(input.roleTitle)}")
            appendLine("- Location (from dataset): ${formatValue(input.location)}")
            appendLine("- Salary Range (from dataset): ${formatValue(input.salaryRange)}")
            appendLine("- Max Iterations: $maxIterations")
            appendLine("- Tuner Skill: ${Config.SCRAPE_JD_URL_TUNER_SKILL}")
            appendLine("- Dataset Dir: ${Config.SCRAPE_JD_URL_TUNER_DATASET_DIR}")
            appendLine("- Output Dir: ${result.scrapeJdTuningOutputDir}")
            appendLine()
            appendLine("## Tuner Skill")
            appendLine("- source_of_truth_loaded: ${tunerSkill.isNotBlank()}")
            appendLine("- skill_snapshot: ${Path.of(result.scrapeJdTuningOutputDir).resolve("scrape_jd_tuner_skill_snapshot.md")}")
            appendLine()
            appendLine("## Scraped Fields")
            appendLine("- is_job_posting: ${result.isJobPosting}")
            appendLine("- company: ${formatValue(result.company)}")
            appendLine("- role_title: ${formatValue(result.roleTitle)}")
            appendLine("- location: ${formatValue(result.location)}")
            appendLine("- remote_policy: ${formatValue(result.remotePolicy)}")
            appendLine("- salary_range: ${formatValue(result.salaryRange)}")
            appendLine("- yoe_required: ${result.yoeRequired ?: "(null)"}")
            appendLine("- tech_stack: ${if (result.techStack.isEmpty()) "(empty)" else result.techStack.joinToString(", ")}")
            appendLine("- jd_text_length: $jdTextLength chars")
            appendLine("- jd_text_preview: ${formatValue(result.jdText.take(500))}")
            appendLine()
            appendLine("## Field Comparison (dataset vs scraped)")
            appendLine("- company match: ${fuzzyMatch(input.company, result.company)}")
            appendLine("- role_title match: ${fuzzyMatch(input.roleTitle, result.roleTitle)}")
            appendLine("- location match: ${fuzzyMatch(input.location, result.location)}")
            appendLine("- salary_range match: ${fuzzyMatch(input.salaryRange, result.salaryRange)}")
            appendLine()
            appendLine("## JD Text Quality")
            appendLine("- jd_text populated: ${result.jdText.isNotBlank()}")
            appendLine("- jd_text length: $jdTextLength chars")
            if (hasExpected) {
                appendLine("- expected_jd_text provided: true")
                if (jdTextContainsExpected != null) {
                    val (matched, total) = jdTextContainsExpected
                    appendLine("- expected token coverage: $matched/$total key tokens found in jd_text")
                }
            } else {
                appendLine("- expected_jd_text provided: false (populate dataset file to enable content comparison)")
            }
            appendLine()
            appendLine("## Errors")
            appendLine("- error: ${formatValue(result.error)}")
            appendLine("- chrome_session_expired: ${result.isChromeSessionExpired}")
        }
    }

    private fun buildResultDump(result: JDState, maxIterations: Int): String {
        return buildString {
            appendLine("job_url=${result.jobUrl}")
            appendLine("max_iterations=$maxIterations")
            appendLine("is_job_posting=${result.isJobPosting}")
            appendLine("company=${result.company}")
            appendLine("role_title=${result.roleTitle}")
            appendLine("location=${result.location}")
            appendLine("remote_policy=${result.remotePolicy}")
            appendLine("salary_range=${result.salaryRange}")
            appendLine("yoe_required=${result.yoeRequired ?: ""}")
            appendLine("tech_stack=${result.techStack.joinToString(",")}")
            appendLine("error=${result.error}")
            appendLine("chrome_session_expired=${result.isChromeSessionExpired}")
            appendLine("jd_text_length=${result.jdText.length}")
            appendLine()
            appendLine("jd_text:")
            appendLine(result.jdText)
        }
    }

    private fun createOutputDir(company: String, roleTitle: String): Path {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val label = com.jd.pipeline.utils.OutputUtils.sanitizeFileName("$company $roleTitle")
            .take(80)
            .ifBlank { "job" }
        val outputDir = Config.OUTPUT_DIR.resolve("scrape_tuner").resolve("${timestamp}_$label")
        Files.createDirectories(outputDir)
        return outputDir
    }

    private fun loadTunerSkill(): String {
        val path = Config.SCRAPE_JD_URL_TUNER_SKILL
        require(Files.exists(path)) { "ScrapeJdTuner skill file not found: $path" }
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

    private fun fuzzyMatch(expected: String, actual: String): String {
        if (expected.isBlank()) return "n/a (no expected value)"
        if (actual.isBlank()) return "FAIL (actual empty)"
        val e = expected.lowercase().trim()
        val a = actual.lowercase().trim()
        return if (a.contains(e) || e.contains(a)) "PASS" else "PARTIAL/FAIL (expected='$expected', got='$actual')"
    }
}
