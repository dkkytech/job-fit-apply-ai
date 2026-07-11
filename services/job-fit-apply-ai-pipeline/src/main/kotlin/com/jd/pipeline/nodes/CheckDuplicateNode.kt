package com.jd.pipeline.nodes

import com.jd.pipeline.client.SupabaseClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Node: check_duplicate
 *
 * Dedup key: (company, roleTitle, location) — the posting identity.
 * fitScore is intentionally excluded: it is a candidate↔JD pair property, not a posting
 * identity. The same job on LinkedIn vs. Indeed will have different JD text, yielding a
 * different score even at temperature=0, causing false negatives if score were included.
 *
 * Window: a posting seen within DUPLICATE_WINDOW_DAYS is a duplicate; one older than
 * the window is treated as a re-opened position and processed fresh.
 *
 * Primary: Supabase query (persists across process restarts).
 * Fallback: in-memory set (used when Supabase is not configured — local dev / tests).
 */
class CheckDuplicateNode : Node<JDState> {

    override fun process(input: JDState): JDState {
        val key = buildKey(input.company, input.roleTitle, input.location)

        val isDuplicate = if (SupabaseClient.isConfigured()) {
            querySupabase(input.company, input.roleTitle, input.location)
        } else {
            // Offline fallback — in-memory only, resets on restart
            key in PROCESSED_JOBS_FALLBACK
        }

        if (!isDuplicate) PROCESSED_JOBS_FALLBACK.add(key)

        return if (isDuplicate) {
            println("[check_duplicate] Duplicate within ${Config.DUPLICATE_WINDOW_DAYS}d: $key")
            input.copy(
                isDuplicate = true,
                skippedReason = "Duplicate within ${Config.DUPLICATE_WINDOW_DAYS}d: ${input.company} — ${input.roleTitle}"
            )
        } else {
            println("[check_duplicate] New job: $key")
            input.copy(isDuplicate = false)
        }
    }

    /**
     * Query the tracks table for any row matching (company, role_title, location)
     * created within the configured window. Returns true if at least one row exists.
     */
    private fun querySupabase(company: String, roleTitle: String, location: String): Boolean {
        return try {
            val cutoff = Instant.now()
                .minus(Config.DUPLICATE_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
                .toString()   // ISO-8601, e.g. "2025-03-13T10:00:00Z"

            val rows = SupabaseClient.query(
                table = "tracks",
                filters = mapOf(
                    "company"    to "eq.$company",
                    "role_title" to "eq.$roleTitle",
                    "location"   to "eq.$location",
                    "created_at" to "gte.$cutoff"
                ),
                select = "id",
                limit = 1
            )
            rows.isNotEmpty()
        } catch (e: Exception) {
            System.err.println("[check_duplicate] Supabase query failed — treating as new: ${e.message}")
            false   // fail-open: on error, allow the job through rather than silently dropping it
        }
    }

    private fun buildKey(company: String, roleTitle: String, location: String): String {
        val c = company.lowercase().trim()
        val r = roleTitle.lowercase().trim()
        val l = location.lowercase().trim()
        return "$c|$r|$l"
    }

    companion object {
        private val PROCESSED_JOBS_FALLBACK = ConcurrentHashMap.newKeySet<String>()

        /** Clear the in-memory fallback set (for tests). */
        fun resetFallback() {
            PROCESSED_JOBS_FALLBACK.clear()
        }
    }
}
