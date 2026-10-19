package com.jd.jsearch

import com.jd.jsearch.bridge.JsearchBridgeClient
import com.jd.jsearch.bridge.SubmitJobRequest
import com.jd.jsearch.client.JSearchClient
import com.jd.jsearch.config.Config
import com.jd.jsearch.search.JSearchConfig
import com.jd.jsearch.state.RunState

/**
 * One JSearch ingest pass, gated by the persisted last-run so restarts don't burn API quota:
 * if the last real fetch was < [intervalMs] ago, skip (no API calls). Otherwise fetch listings and
 * submit each as a JD_SCRAPED job; the bridge dedups by the JSearch jobId.
 *
 * The client is built lazily (only when actually fetching) so a skipped run needs no API key.
 */
class IngestRunner(
    private val clientFactory: () -> JSearchClient,
    private val bridge: JsearchBridgeClient,
    private val state: RunState,
    private val intervalMs: Long = Config.JSEARCH_INTERVAL_MS,
    private val configs: List<JSearchConfig> = JSearchConfig.DEFAULT_LIST,
) {
    sealed interface Outcome
    data class Skipped(val ageMs: Long) : Outcome
    data class Ran(val fetched: Int, val queued: Int, val deduped: Int, val skipped: Int, val calls: Int) : Outcome

    fun runOnce(now: Long): Outcome {
        val last = state.lastRun()
        if (last != null && now - last < intervalMs) {
            val ageMs = now - last
            println("[jsearch] last run ${ageMs / 3_600_000}h ago (< ${intervalMs / 3_600_000}h window) — skipping, no API calls")
            return Skipped(ageMs)
        }

        println("[jsearch] fetching via JSearch API...")
        val client = clientFactory()
        val listings = client.search(configs)

        var queued = 0; var deduped = 0; var skipped = 0
        for (l in listings) {
            val jd = l.jobDescription ?: ""
            if (jd.length < 150) { skipped++; continue }   // bridge rejects < 150 chars
            val location = listOfNotNull(l.jobCity, l.jobState).joinToString(", ")
                .ifBlank { if (l.jobIsRemote) "Remote" else "Unknown" }
            val resp = bridge.submit(
                SubmitJobRequest(
                    jdText = jd, roleTitle = l.jobTitle, company = l.employerName,
                    location = location, jobUrl = l.jobApplyLink, source = "JSEARCH", idempotencyKey = l.jobId,
                    salaryRange = l.salaryRange(), remotePolicy = l.remotePolicy(),
                    employmentType = l.employmentType(), yoeRequired = l.yoeRequired(),
                    techStack = l.techStack(),
                )
            )
            when {
                resp == null -> skipped++
                resp.deduped -> deduped++
                else -> queued++
            }
        }

        state.recordRun(now)
        println("[jsearch] done — ${listings.size} fetched, $queued queued, $deduped deduped, $skipped skipped; ${client.callCount} API calls this run")
        return Ran(listings.size, queued, deduped, skipped, client.callCount)
    }
}
