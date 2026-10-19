package com.jd.jobbot.jobs

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.bridge.long
import com.jd.jobbot.bridge.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Resolves a job reference to its completed event and, when one matches, its tracks row.
 *
 * `/api/tracks` returns every row (tens of thousands), so it is cached briefly rather than
 * fetched per lookup.
 */
open class JobLookup(
    private val bridge: BridgeReadClient,
    private val clock: Clock = Clock.systemUTC(),
    private val tracksTtl: Duration = Duration.ofMinutes(5),
) {
    data class Found(val event: JsonObject, val track: JsonObject?, val trackMatch: String?)

    @Volatile private var tracksCache: Pair<Instant, JsonArray>? = null

    open fun event(seq: Long): JsonObject? = bridge.completedEvent(seq)

    open fun find(seq: Long): Found? {
        val event = event(seq) ?: return null
        val (track, how) = matchTrack(event)
        return Found(event, track, how)
    }

    /**
     * The bridge links a completion to its track by `track_id` once that column exists; before
     * that, an exact artifact_url match is reliable (tailored jobs), and company + title is a
     * best-effort fallback that the caller must present as fuzzy.
     */
    internal fun matchTrack(event: JsonObject): Pair<JsonObject?, String?> {
        val tracks = runCatching { tracks() }.getOrNull() ?: return null to null
        event.long("track_id")?.let { id ->
            tracks.firstOrNull { it.jsonObject.long("id") == id }?.let { return it.jsonObject to "track_id" }
        }
        event.str("artifact_url")?.let { url ->
            tracks.firstOrNull { it.jsonObject.str("artifact_url") == url }?.let { return it.jsonObject to "artifact_url" }
        }
        val company = event.str("company")?.lowercase() ?: return null to null
        val title = event.str("role_title")?.lowercase() ?: return null to null
        val fuzzy = tracks.map { it.jsonObject }.filter {
            it.str("company")?.lowercase() == company && it.str("role_title")?.lowercase() == title
        }.maxByOrNull { it.long("id") ?: 0 }
        return fuzzy to fuzzy?.let { "company+title (fuzzy)" }
    }

    open fun tracks(): JsonArray {
        val now = clock.instant()
        tracksCache?.let { (at, rows) -> if (Duration.between(at, now) < tracksTtl) return rows }
        val rows = bridge.tracks()
        tracksCache = now to rows
        return rows
    }
}
