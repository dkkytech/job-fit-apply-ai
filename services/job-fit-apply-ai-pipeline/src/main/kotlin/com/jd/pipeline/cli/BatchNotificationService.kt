package com.jd.pipeline.cli

import com.jd.pipeline.client.NotificationClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.NodeTimer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ScoredJob(
    val company: String,
    val roleTitle: String,
    val fitScore: Int?,
    val pipelineAction: String?,
    val error: String?,
)

/**
 * Formats and dispatches post-batch notifications to Discord and Telegram.
 *
 * Discord receives three messages: batch summary, node timings, and the scored-jobs list.
 * Telegram receives a high-fit ping only when at least one job clears [fitThreshold].
 *
 * Silently skips when [NotificationClient.discordConfigured] and
 * [NotificationClient.telegramConfigured] are both false, or when no emails were processed.
 */
class BatchNotificationService(
    private val client: NotificationClient = NotificationClient(),
    private val fitThreshold: Int = Config.NOTIFICATION_FIT_THRESHOLD,
) {

    data class BatchSummary(
        val emailsProcessed: Int,
        val jobs: Int,
        val tailored: Int,
        val skipped: Int,
        val duplicate: Int,
        val startTime: Instant,
        val scoredJobs: List<ScoredJob>,
    )

    fun notify(summary: BatchSummary) {
        if (!client.discordConfigured && !client.telegramConfigured) return
        if (summary.emailsProcessed == 0) return

        val dateStr = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm z")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())

        postBatchSummary(summary, dateStr)
        postNodeTimings()
        postScoredJobs(summary.scoredJobs)
        pingHighFit(summary.scoredJobs)
    }

    // ── Discord messages ──────────────────────────────────────────────────────

    private fun postBatchSummary(summary: BatchSummary, dateStr: String) {
        val elapsed  = Duration.between(summary.startTime, Instant.now())
        val mins     = elapsed.toMinutes()
        val secs     = elapsed.seconds % 60
        val runTime  = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        val threshold = Config.FIT_THRESHOLD.toInt()

        val rows = listOf(
            "Emails processed"        to summary.emailsProcessed.toString(),
            "Job postings"            to summary.jobs.toString(),
            "Tailored (≥ $threshold)" to summary.tailored.toString(),
            "Skipped"                 to summary.skipped.toString(),
            "Duplicate"               to summary.duplicate.toString(),
            "Run time"                to runTime,
        )
        val labelW = rows.maxOf { it.first.length }
        val table  = rows.joinToString("\n") { (k, v) -> "${k.padEnd(labelW)}  $v" }

        client.postDiscord("\n**JD Pipeline Complete** — $dateStr\n```\n$table\n```")
    }

    private fun postNodeTimings() {
        val entries = NodeTimer.summary()
        if (entries.isEmpty()) return

        fun fmt(sec: Double): String {
            val s = sec.toLong()
            return if (s < 60) "%.1fs".format(sec) else "${s / 60}m${"${s % 60}".padStart(2, '0')}s"
        }

        val nodeW  = maxOf("Node".length,  entries.maxOf { it.displayName.length })
        val modelW = maxOf("Model".length, entries.maxOf { it.model.length })
        val nW     = maxOf(1, entries.maxOf { it.count.toString().length })
        val tW     = maxOf("Avg".length, entries.maxOf { e ->
            maxOf(fmt(e.avgSec).length, fmt(e.minSec).length, fmt(e.maxSec).length)
        })

        val sep = "  "
        val header  = "${"Node".padEnd(nodeW)}$sep${"Model".padEnd(modelW)}$sep${"n".padStart(nW)}$sep${"Avg".padStart(tW)}$sep${"Min".padStart(tW)}$sep${"Max".padStart(tW)}"
        val divider = "${"─".repeat(nodeW)}$sep${"─".repeat(modelW)}$sep${"─".repeat(nW)}$sep${"─".repeat(tW)}$sep${"─".repeat(tW)}$sep${"─".repeat(tW)}"
        val rows    = entries.map { e ->
            "${e.displayName.padEnd(nodeW)}$sep${e.model.padEnd(modelW)}$sep${e.count.toString().padStart(nW)}$sep${fmt(e.avgSec).padStart(tW)}$sep${fmt(e.minSec).padStart(tW)}$sep${fmt(e.maxSec).padStart(tW)}"
        }

        client.postDiscord("**Node Timings (LLM calls this batch)**\n```\n$header\n$divider\n${rows.joinToString("\n")}\n```")
    }

    private fun postScoredJobs(jobs: List<ScoredJob>) {
        if (jobs.isEmpty()) return
        val lines = mutableListOf("**Scored Jobs**")
        for (job in jobs) {
            val score = job.fitScore?.toString() ?: "?"
            val title = job.roleTitle.ifBlank { "*(no title)*" }
            lines += "• ${job.company} — $title — **$score**"
        }
        client.postDiscord(lines.joinToString("\n"))
    }

    // ── Telegram ping ─────────────────────────────────────────────────────────

    private fun pingHighFit(jobs: List<ScoredJob>) {
        val highFit = jobs.filter { (it.fitScore ?: 0) >= fitThreshold && it.error == null }
        if (highFit.isEmpty()) return
        val lines = mutableListOf("High-fit jobs (≥$fitThreshold):")
        for (job in highFit) {
            val title = job.roleTitle.ifBlank { "*(no title)*" }
            lines += "• ${job.company} — $title — ${job.fitScore}"
        }
        client.postTelegram(lines.joinToString("\n"))
    }
}
