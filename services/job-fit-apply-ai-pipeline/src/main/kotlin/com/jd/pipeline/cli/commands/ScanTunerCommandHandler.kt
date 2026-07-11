package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.CliOutput
import com.jd.pipeline.cli.Command
import com.jd.pipeline.tuning.ScanEmailTuner
import java.nio.file.Files
import java.nio.file.Path

object ScanTunerCommandHandler {
    fun run(cmd: Command.ScanTuner) {
        val tunerFile = cmd.file
        if (tunerFile.isNullOrBlank()) {
            println("[ERROR] --scantuner now requires a tuner input file path")
            return
        }

        val path = Path.of(tunerFile)
        if (!Files.exists(path)) {
            println("[ERROR] tuner input file not found: $path")
            return
        }

        val fileContent = Files.readString(path)
        val lines = fileContent.lines()
        if (lines.isEmpty() || lines.first().isBlank()) {
            println("[ERROR] tuner input file must start with the email subject on line 1")
            return
        }

        val subject = parseScanTunerSubject(lines.first())
        if (subject.isBlank()) {
            println("[ERROR] failed to parse email subject from tuner input: $path")
            return
        }

        val expected = lines.drop(1).joinToString("\n").trim()
        if (expected.isBlank()) {
            println("[ERROR] tuner input file must include expected visible job data after the first line")
            return
        }

        println("[INFO] Running ScanEmailTuner for subject: $subject")
        println("[INFO] Tuner input: $path")
        println("[INFO] Max iterations: ${cmd.maxIterations}")

        try {
            val tuner = ScanEmailTuner()
            val result = tuner.invoke(subject, expected, cmd.maxIterations, cmd.debug)
            println("[INFO] ScanEmailTuner output: ${result.scanTuningOutputDir}")
            CliOutput.printResult(result)
            if (result.scanComparisonReport.isNotBlank()) {
                println("    → comparison_report: ${Path.of(result.scanTuningOutputDir).resolve("scan_comparison_report.md")}")
            }
        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }

    private fun parseScanTunerSubject(firstLine: String): String {
        val trimmed = firstLine.trim()
        val patterns = listOf(
            Regex("""^Email subject:\s*(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^Email subject\s+(.+)$""", RegexOption.IGNORE_CASE)
        )
        val value = patterns.firstNotNullOfOrNull { regex ->
            regex.matchEntire(trimmed)?.groupValues?.getOrNull(1)
        } ?: trimmed
        return value.removeSurrounding("\"").trim()
    }
}
