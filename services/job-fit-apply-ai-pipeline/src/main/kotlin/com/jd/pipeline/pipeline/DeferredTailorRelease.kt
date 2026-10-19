package com.jd.pipeline.pipeline

import com.jd.pipeline.client.Alert
import com.jd.pipeline.client.AlertService
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.CloudQuota
import com.jd.pipeline.client.DeferredJob
import com.jd.pipeline.client.DeferredRelease
import com.jd.pipeline.client.Severity
import com.jd.pipeline.config.Config

/**
 * The processor's between-jobs sweep for DEFERRED tailoring (jobs that would have been tailored
 * while the cloud quota was used up). Every [intervalMs] it asks the bridge to expire deferred jobs
 * older than [maxAgeDays] and — once the quota is back — to queue the best fits as TAILOR_REQUESTs,
 * keeping at most [batch] in flight so new intake is never starved behind a backlog. Expired jobs
 * get one "tailor by hand" alert. Best effort: a failed sweep is logged and retried next interval.
 */
class DeferredTailorRelease(
    private val release: (limit: Int, maxAgeDays: Int) -> DeferredRelease,
    private val quotaExhausted: () -> Boolean = { CloudQuota.shared.isExhausted() },
    private val batch: Int = Config.DEFERRED_RELEASE_BATCH,
    private val intervalMs: Long = Config.DEFERRED_RELEASE_INTERVAL_MINUTES * 60_000L,
    private val maxAgeDays: Int = Config.DEFERRED_MAX_AGE_DAYS,
    private val alert: (Alert) -> Unit = { a -> runCatching { AlertService().send(a) } },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(bridge: BridgeClient) : this(release = bridge::releaseDeferred)

    private var nextRunAt = 0L

    /** Run the sweep if it is due. Never throws. */
    fun maybeRun() {
        val now = clock()
        if (now < nextRunAt) return
        nextRunAt = now + intervalMs
        // The processor loop calls this between jobs: nothing here may take the loop down.
        try {
            // Expiry runs even while the quota is out; releasing waits for it to come back.
            val outcome = release(if (quotaExhausted()) 0 else batch, maxAgeDays)
            outcome.released.forEach {
                println("[deferred_tailor] released ${it.jobId} → ${it.tailorJobId} (${it.roleTitle} @ ${it.company}, fit ${it.fitScore})")
            }
            if (outcome.expired.isNotEmpty()) alert(expiredAlert(outcome.expired))
        } catch (e: Exception) {
            System.err.println("[deferred_tailor] release sweep failed: ${e.message}")
        }
    }

    private fun expiredAlert(jobs: List<DeferredJob>): Alert = Alert(
        Severity.WARN,
        "Deferred tailoring expired — tailor by hand",
        "${jobs.size} job(s) waited more than $maxAgeDays days for the cloud LLM quota and were not tailored:\n" +
            jobs.joinToString("\n") { j ->
                val link = (j.artifactUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/report.md" } ?: j.jobUrl).orEmpty()
                "• ${j.company ?: "Unknown"} — ${j.roleTitle ?: "Unknown"} (${j.fitScore ?: "?"})" + if (link.isNotBlank()) " $link" else ""
            },
    )
}
