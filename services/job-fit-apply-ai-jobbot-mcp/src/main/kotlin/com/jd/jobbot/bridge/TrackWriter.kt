package com.jd.jobbot.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The only bridge writes JobBot may make: append to a track's history and set a track's status,
 * always as `source=jobbot`. Two routes, nothing else — the job queue, results, artifacts and
 * email write-back stay unreachable.
 */
open class TrackWriter(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) {
    open fun addEvent(trackId: Long, kind: String, summary: String?, details: JsonElement? = null) {
        require(kind in KINDS) { "event kind not allowed: $kind" }
        post("/api/tracks/$trackId/events", buildJsonObject {
            put("kind", kind)
            put("summary", summary)
            put("source", SOURCE)
            details?.let { put("details", it) }
        })
    }

    open fun setStatus(trackId: Long, status: String) {
        require(status in STATUSES) { "status not allowed: $status" }
        post("/api/tracks/$trackId/status", buildJsonObject { put("status", status); put("source", SOURCE) })
    }

    private fun post(path: String, body: JsonObject) {
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw BridgeReadClient.BridgeException(resp.statusCode(), "POST $path → ${resp.statusCode()}: ${resp.body().take(200)}")
        }
    }

    companion object {
        const val SOURCE = "jobbot"

        /** Mirrors the bridge's TracksStore.ALLOWED_EVENT_KINDS. */
        val KINDS = setOf(
            "status_changed", "note", "email_received", "email_sent", "reply_drafted",
            "archived", "unarchived", "applied", "application_filled", "application_submitted",
            "account_created", "interview", "rejected", "offer",
        )

        /** Mirrors the bridge's TracksStore.ALLOWED_STATUSES. */
        val STATUSES = setOf("backlog", "duplicate", "applied", "interested", "skipped", "interviewing", "rejected", "offer")
    }
}
