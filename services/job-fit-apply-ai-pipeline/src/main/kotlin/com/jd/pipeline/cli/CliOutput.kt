package com.jd.pipeline.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest
import com.jd.pipeline.utils.JobFormatter
import com.jd.pipeline.utils.NodeTimer
import java.time.Duration
import java.time.Instant

object CliOutput {

    fun printBanner() {
        println("╔═══════════════════════════════════════════════════════════╗")
        println("║                   JD Pipeline (Kotlin)                    ║")
        println("╚═══════════════════════════════════════════════════════════╝")
        println()
    }

    fun printModels() {
        println("╔═══════════════════════════════════════════════════════════╗")
        println("║                     Models in Use                         ║")
        println("╠═══════════��═══════════════════════════════════════════════╣")
        println("║  SCAN_MODEL            │ ${Config.SCAN_MODEL} - JD scanning.")
        println("║  SCORE_MODEL           │ ${Config.SCORE_MODEL} - Fit scoring + JD extraction + tailoring orchestration.")
        println("║  RESUME_REASONING_MODEL│ ${Config.RESUME_REASONING_MODEL} - Resume tailoring (rewrite).")
        println("║  COVER_LETTER_MODEL    │ ${Config.COVER_LETTER_MODEL} - Cover letter.")
        println("║  DRAFT_REPLY_MODEL     │ ${Config.DRAFT_REPLY_MODEL} - Recruiter reply.")
        println("║  RESUME_GEN_MODEL      │ ${Config.RESUME_GEN_MODEL} - DOCX/PDF → HTML resume gen.")
        println("╚═══════════════════════════════════════════════════════════╝")
        println()
    }

    fun printResult(result: JDState) {
        val action = result.pipelineAction.asDbValue()

        // Digest emails are containers
        if (result.isDigest || result.isInlineDigest) {
            val reason = result.skippedReason
            if (reason.isNotEmpty()) {
                println("  ↳ $reason")
            } else {
                println("  ↳ Digest email processed")
            }
            return
        }

        if (!result.isJobPosting) {
            println("  ↳ Not a job posting — skipped")
            return
        }

        val color = when (action) {
            "tailor" -> "\u001B[32m"
            "skip" -> "\u001B[31m"
            else -> "\u001B[0m"
        }

        // Use aligned format for single job output
        val jobLine = JobFormatter.formatSingleJob(result, color)
        println("  $jobLine")

        if (action == "tailor") {
            val outputPath = result.outputPath
            if (outputPath.isNotEmpty()) {
                println("    → output: $outputPath")
            }
            // Note: artifact URL now appears in the job line above
        }

        val jobUrl = result.jobUrl
        if (jobUrl.isNotEmpty()) {
            println("    → job_url: $jobUrl")
        }

        val trackUrl = result.trackUrl
        if (trackUrl.isNotEmpty()) {
            println("    → supabase: $trackUrl")
        }

        if (result.isRecruiterResponseRequired) {
            val draftId = result.draftId
            println("    → draft reply: ${if (draftId.isNotEmpty()) draftId else "(queued)"}")
        }

        val skippedReason = result.skippedReason
        if (skippedReason.isNotEmpty()) {
            println("    → reason: $skippedReason")
        }
    }

    fun printBatchSummary(
        emailsProcessed: Int,
        jobs: Int,
        tailored: Int,
        skipped: Int,
        duplicate: Int,
        batchStartTime: Instant,
        scoredJobs: List<JDState>
    ) {
        val batchEndTime = Instant.now()
        val totalDuration = Duration.between(batchStartTime, batchEndTime)
        val totalSeconds = totalDuration.seconds
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val runTimeStr = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"

        val threshold = Config.FIT_THRESHOLD.toInt()

        val rows = listOf(
            "Emails processed"     to emailsProcessed.toString(),
            "Job postings"         to jobs.toString(),
            "Tailored (≥ $threshold)" to tailored.toString(),
            "Skipped"              to skipped.toString(),
            "Duplicate"            to duplicate.toString(),
            "Run time"             to runTimeStr
        )

        val labelW = rows.maxOf { it.first.length }
        val valW   = maxOf(rows.maxOf { it.second.length }, 4)
        val title  = "Batch Summary"
        // Row format: ║ {label.padEnd(labelW)} │ {value.padEnd(adjValW)} ║
        // Total chars = labelW + adjValW + 7 (borders + spaces + separator)
        // so boxW must equal labelW + valW + 7
        val boxW    = maxOf(labelW + valW + 7, title.length + 4)
        val adjValW = boxW - labelW - 7

        fun hDouble(l: Char, sep: Char, r: Char) =
            "$l${"═".repeat(labelW + 2)}$sep${"═".repeat(adjValW + 2)}$r"

        fun hSingle(l: Char, sep: Char, r: Char) =
            "$l${"─".repeat(labelW + 2)}$sep${"─".repeat(adjValW + 2)}$r"

        val titlePad = (boxW - title.length) / 2 - 1
        val titleRow = "║" + " ".repeat(titlePad) + title + " ".repeat(boxW - titlePad - title.length - 2) + "║"

        println()
        println("╔${"═".repeat(boxW - 2)}╗")
        println(titleRow)
        println(hDouble('╠', '╤', '╣'))
        for ((label, value) in rows) {
            println("║ ${label.padEnd(labelW)} │ ${value.padEnd(adjValW)} ║")
        }
        println(hSingle('╚', '╧', '╝'))

        // Print scored jobs table if there are any
        if (scoredJobs.isNotEmpty()) {
            println()
            println("Scored Jobs")
            val jobLines = JobFormatter.formatScoredJobsTable(scoredJobs)
            for (line in jobLines) {
                println(line)
            }
        }

        printNodeTimingTable()
    }

    fun printScrapeBatchWarnings(ingestionPipeline: com.jd.pipeline.pipeline.IngestionPipeline) {
        if (ingestionPipeline.batchLinkedInSessionExpired()) {
            println("\n\u001B[33m[WARN] LinkedIn session expired — re-authenticate Chrome profile ${Config.CHROME_PROFILE_DIRECTORY} to enable LinkedIn job scraping\u001B[0m")
        }
        val blocked = ingestionPipeline.batchBlockedDomains()
        if (blocked.isNotEmpty()) {
            println("\u001B[33m[WARN] Sites that blocked scraping this batch (falling back to email data): ${blocked.joinToString(", ")}\u001B[0m")
        }
    }

    fun printJsonSummary(state: Map<String, Any?>) {
        val obj = LinkedHashMap<String, Any?>()
        obj["output_path"] = state["output_path"] ?: ""
        obj["fit_score"] = state["fit_score"] ?: 0
        obj["pipeline_action"] = state["pipeline_action"] ?: "skip"
        obj["track_url"] = state["track_url"] ?: ""
        obj["artifact_url"] = state["artifact_url"] ?: ""
        obj["error"] = state["error"] ?: ""
        val mapper = ObjectMapper()
        println(mapper.writeValueAsString(obj))
    }

    fun printJsonSummary(state: JDState) {
        val obj = LinkedHashMap<String, Any?>()
        obj["output_path"] = state.outputPath
        obj["fit_score"] = state.fitScore?.toDouble() ?: 0
        obj["pipeline_action"] = state.pipelineAction.asDbValue()
        obj["track_url"] = state.trackUrl
        obj["artifact_url"] = state.artifactUrl
        obj["error"] = state.error
        val mapper = ObjectMapper()
        println(mapper.writeValueAsString(obj))
    }

    private fun printNodeTimingTable() {
        val entries = NodeTimer.summary()
        if (entries.isEmpty()) return

        fun formatSec(sec: Double): String {
            val totalSec = sec.toLong()
            return if (totalSec < 60) "%.1fs".format(sec)
            else "${totalSec / 60}m${"${totalSec % 60}".padStart(2, '0')}s"
        }

        val nodeW  = maxOf("Node".length,  entries.maxOf { it.displayName.length })
        val modelW = maxOf("Model".length, entries.maxOf { it.model.length })
        val nW     = maxOf(1, entries.maxOf { it.count.toString().length })
        val timeW  = maxOf("Avg".length, entries.maxOf { e ->
            maxOf(formatSec(e.avgSec).length, formatSec(e.minSec).length, formatSec(e.maxSec).length)
        })

        val colWidths   = listOf(nodeW, modelW, nW, timeW, timeW, timeW)
        val rightAlign  = listOf(false,  false,  true, true,  true,  true)

        fun hRule(l: Char, sep: Char, r: Char) =
            l + colWidths.joinToString("$sep") { "─".repeat(it + 2) } + r

        fun row(cols: List<String>): String {
            val cells = cols.zip(colWidths).zip(rightAlign).joinToString(" │ ") { (cw, right) ->
                val (cell, w) = cw
                if (right) cell.padStart(w) else cell.padEnd(w)
            }
            return "│ $cells │"
        }

        println()
        println("Node Timings (LLM calls this batch)")
        println(hRule('┌', '┬', '┐'))
        println(row(listOf("Node", "Model", "n", "Avg", "Min", "Max")))
        println(hRule('├', '┼', '┤'))
        for (e in entries) {
            println(row(listOf(
                e.displayName,
                e.model,
                e.count.toString(),
                formatSec(e.avgSec),
                formatSec(e.minSec),
                formatSec(e.maxSec)
            )))
        }
        println(hRule('└', '┴', '┘'))
    }
}
