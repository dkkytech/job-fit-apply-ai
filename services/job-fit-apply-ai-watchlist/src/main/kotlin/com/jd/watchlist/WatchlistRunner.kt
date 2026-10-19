package com.jd.watchlist

import com.jd.watchlist.adapters.FeedAdapter
import com.jd.watchlist.alert.AlertSink
import com.jd.watchlist.bridge.SubmitJobRequest
import com.jd.watchlist.bridge.WatchlistBridgeClient
import com.jd.watchlist.config.Config
import com.jd.watchlist.filter.PostingFilter
import com.jd.watchlist.model.Employer
import com.jd.watchlist.state.EmployerHealth
import com.jd.watchlist.state.HealthReport
import com.jd.watchlist.state.RunHealth
import com.jd.watchlist.state.RunSchedule
import com.jd.watchlist.state.RunState
import java.nio.file.Files
import java.nio.file.Path

/**
 * One watchlist pass, gated by the persisted last-run: if no sweep is due under [schedule]
 * (fixed local times, or an interval), skip. Otherwise, for each enabled employer whose platform has an adapter: list postings,
 * pre-filter on title + location, fetch descriptions only for the survivors, and submit each to
 * the bridge as source CAREER_SITE. One employer's failure never stops the sweep.
 */
class WatchlistRunner(
    private val employers: List<Employer>,
    private val adapters: Map<String, FeedAdapter>,
    private val filter: PostingFilter,
    private val bridge: WatchlistBridgeClient,
    private val state: RunState,
    private val health: HealthReport,
    private val schedule: RunSchedule = RunSchedule(Config.WATCHLIST_INTERVAL_MS, Config.RUN_AT, Config.TZ),
    /** Gets the epoch second the next sweep is due, for the shell loop's sleep (null = don't write). */
    private val nextDueFile: Path? = null,
    private val interEmployerDelayMs: Long = Config.INTER_EMPLOYER_DELAY_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val alerts: AlertSink = AlertSink {},
    private val alertAfterRuns: Int = Config.ALERT_AFTER_RUNS,
) {
    sealed interface Outcome
    data class Skipped(val ageMs: Long) : Outcome
    data class Ran(val health: RunHealth) : Outcome

    fun runOnce(now: Long): Outcome {
        val last = state.lastRun()
        if (last != null && !schedule.isDue(last, now)) {
            val ageMs = now - last
            writeNextDue(schedule.nextDue(last, now))
            println("[watchlist] last run ${ageMs / 3_600_000}h ago; sweeps run ${schedule.describe()} — skipping")
            return Skipped(ageMs)
        }

        val active = employers.filter { it.enabled && it.ats in adapters }
        val unsupported = employers.count { it.enabled && it.ats !in adapters }
        println("[watchlist] polling ${active.size} employers ($unsupported on platforms without an adapter yet)")

        val previous = health.previousSuspectRuns()
        val results = active.mapIndexed { i, e ->
            if (i > 0 && interEmployerDelayMs > 0) sleep(interEmployerDelayMs)
            pollEmployer(e, adapters.getValue(e.ats))
        }.map { h -> h.copy(suspectRuns = if (h.suspect) (previous[h.company] ?: 0) + 1 else 0) }
        val run = RunHealth(ranAt = now, employers = results, unsupported = unsupported)
        health.write(run)
        alertStaleFeeds(results)

        // A sweep where every feed failed is an outage (network, DNS), not a run: don't advance the
        // gate, so the next hourly tick retries instead of waiting a day.
        if (results.isEmpty() || results.any { it.error == null }) state.recordRun(now)
        writeNextDue(schedule.nextDue(state.lastRun(), now))

        println(
            "[watchlist] done — ${results.sumOf { it.listed }} listed, ${results.sumOf { it.matched }} matched, " +
                "${results.sumOf { it.queued }} queued, ${results.sumOf { it.deduped }} deduped; " +
                "${run.failing.size} feeds failing, ${run.empty.size} empty"
        )
        if (run.failing.isNotEmpty()) println("[watchlist] failing: ${run.failing.joinToString()}")
        if (run.empty.isNotEmpty()) println("[watchlist] empty: ${run.empty.joinToString()}")
        return Ran(run)
    }

    /**
     * Tells the shell loop when to wake: the due time rounded up to the second (waking a moment
     * early would find nothing due). Removed when a sweep is due now (an outage didn't advance the
     * gate), so the loop falls back to its regular tick instead of retrying at once.
     */
    private fun writeNextDue(dueMs: Long?) {
        val file = nextDueFile ?: return
        runCatching {
            if (dueMs == null) Files.deleteIfExists(file) else Files.writeString(file, ((dueMs + 999) / 1000).toString())
        }.onFailure { System.err.println("[watchlist] failed to write $file: ${it.message}") }
    }

    /**
     * Feeds rot quietly (an employer changes ATS, renames its board, or a site starts blocking
     * scripts), so one message goes out when an employer *reaches* [alertAfterRuns] consecutive
     * failing/empty runs — not every run after; doctor.sh keeps showing it until it recovers.
     */
    private fun alertStaleFeeds(results: List<EmployerHealth>) {
        val stale = results.filter { it.suspectRuns == alertAfterRuns }
        if (stale.isEmpty()) return
        val lines = stale.take(MAX_ALERT_LINES).map { h -> "• ${h.company} (${h.ats}): ${h.error?.take(120) ?: "listed 0 jobs"}" }
        val more = if (stale.size > MAX_ALERT_LINES) listOf("…and ${stale.size - MAX_ALERT_LINES} more") else emptyList()
        alerts.send(
            (listOf("⚠️ Watchlist: ${stale.size} employer feed(s) failing or empty for $alertAfterRuns runs in a row — check employers.yaml") +
                lines + more).joinToString("\n")
        )
    }

    private fun pollEmployer(e: Employer, adapter: FeedAdapter): EmployerHealth {
        val listed = try {
            adapter.list(e)
        } catch (ex: Exception) {
            System.err.println("[watchlist] ${e.company} (${e.ats}) list failed: ${ex.message}")
            return EmployerHealth(e.company, e.ats, error = ex.message ?: ex.javaClass.simpleName, mayBeEmpty = e.mayBeEmpty)
        }
        val matched = listed.filter(filter::matches)
        var queued = 0; var deduped = 0; var skipped = 0
        for (p in matched) {
            val detail = runCatching { adapter.detail(e, p) }
                .onFailure { System.err.println("[watchlist] ${e.company} describe failed for ${p.url}: ${it.message}") }
                .getOrNull()
            val text = detail?.text
            if (text == null || text.length < MIN_JD_CHARS) { skipped++; continue }   // bridge rejects < 150 chars
            // A list with no locations: the posting page's location decides, before anything is sent.
            val location = p.location.ifBlank { detail.location.orEmpty() }
            if (p.location.isBlank() && !filter.locationMatches(location)) { skipped++; continue }
            val resp = runCatching {
                bridge.submit(
                    SubmitJobRequest(
                        jdText = text, roleTitle = p.title, company = e.company,
                        location = location.ifBlank { null }, jobUrl = p.url.ifBlank { null },
                        idempotencyKey = idempotencyKey(e, p.externalId),
                    )
                )
            }.onFailure { System.err.println("[watchlist] bridge submit failed for ${p.url}: ${it.message}") }.getOrNull()
            when {
                resp == null -> skipped++
                resp.deduped -> deduped++
                else -> queued++
            }
        }
        if (matched.isNotEmpty()) println("[watchlist] ${e.company}: ${listed.size} listed, ${matched.size} matched, $queued queued, $deduped deduped")
        return EmployerHealth(e.company, e.ats, listed.size, matched.size, queued, deduped, skipped, mayBeEmpty = e.mayBeEmpty)
    }

    companion object {
        const val MIN_JD_CHARS = 150
        private const val MAX_ALERT_LINES = 15

        /** Stable across runs and company renames: platform + board + the employer's own posting id. */
        fun idempotencyKey(e: Employer, externalId: String): String = "watchlist:${e.ats}:${e.boardKey}:$externalId"
    }
}
