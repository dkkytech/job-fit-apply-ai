package com.jd.jobbot.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Read-only bridge client. It can only issue GETs, and only to an allowlist of routes: the bridge
 * has no auth, and `GET /api/queue/claim` changes state, so "GET" alone is not a safety property.
 * There are deliberately no write methods here at all.
 */
open class BridgeReadClient(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val timeout: Duration = Duration.ofSeconds(20),
) {
    class BridgeException(val status: Int, message: String) : RuntimeException(message)

    internal fun get(path: String): String {
        require(ALLOWED_GET.any { it.matches(path.substringBefore('?')) }) { "bridge route not allowed: $path" }
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(timeout).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw BridgeException(resp.statusCode(), "GET $path → ${resp.statusCode()}: ${resp.body().take(200)}")
        }
        return resp.body()
    }

    /** The job's tailored resume PDF (for a reply draft JobBot creates itself). */
    open fun resumePdf(jobId: String): ByteArray {
        val path = "/api/jobs/${enc(jobId)}/resume.pdf"
        require(ALLOWED_GET.any { it.matches(path) }) { "bridge route not allowed: $path" }
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(timeout).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() !in 200..299) throw BridgeException(resp.statusCode(), "GET $path → ${resp.statusCode()}")
        return resp.body()
    }

    /** One completion by its seq, or null when the feed has no such event. */
    open fun completedEvent(seq: Long): JsonObject? {
        if (seq <= 0) return null
        val events = completed(since = seq - 1, limit = 1)
        return events.firstOrNull()?.takeIf { it.long("completed_seq") == seq }
    }

    /** Events with completed_seq > [since], oldest first (the bridge's full event stream). */
    open fun completed(since: Long, limit: Int): List<JsonObject> =
        JSON.parseToJsonElement(get("/api/jobs/completed?since=$since&limit=${limit.coerceIn(1, 200)}&all=true"))
            .jsonArray.map { it.jsonObject }

    open fun headSeq(): Long =
        JSON.parseToJsonElement(get("/api/jobs/completed/head")).jsonObject["max_seq"]?.jsonPrimitive?.longOrNull ?: 0L

    open fun job(jobId: String): JsonObject =
        JSON.parseToJsonElement(get("/api/jobs/${enc(jobId)}")).jsonObject

    open fun coverLetter(jobId: String): String = get("/api/jobs/${enc(jobId)}/cover_letter.txt")

    open fun tracks(): JsonArray = JSON.parseToJsonElement(get("/api/tracks")).jsonArray

    open fun trackEvents(trackId: Long, limit: Int = 50): JsonArray =
        JSON.parseToJsonElement(get("/api/tracks/$trackId/events?limit=$limit")).jsonArray

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        /** Every route this client may GET. The write routes and queue/claim are absent on purpose. */
        val ALLOWED_GET = listOf(
            Regex("/api/jobs/completed"),
            Regex("/api/jobs/completed/head"),
            // A job id slot; /api/queue/claim (a GET that claims work) is not under /api/jobs.
            Regex("/api/jobs/[A-Za-z0-9%_-]+"),
            Regex("/api/jobs/[A-Za-z0-9%_-]+/cover_letter\\.txt"),
            Regex("/api/jobs/[A-Za-z0-9%_-]+/resume\\.pdf"),
            Regex("/api/tracks"),
            Regex("/api/tracks/\\d+/events"),
            Regex("/health"),
        )
    }
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/** A JSON array of strings; anything else (absent, null, wrong type) is null. */
internal fun JsonObject.strings(key: String): List<String>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }

internal fun JsonObject.long(key: String): Long? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"

internal fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject
