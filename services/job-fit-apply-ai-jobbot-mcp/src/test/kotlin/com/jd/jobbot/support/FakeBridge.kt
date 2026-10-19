package com.jd.jobbot.support

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-process stand-in for the bridge's read routes, serving a fixed completed feed and tracks
 * list. Records every request so tests can assert what jobbot-mcp asked for.
 */
class FakeBridge(
    var events: List<JsonObject> = emptyList(),
    var tracks: List<JsonObject> = emptyList(),
    var trackEvents: Map<Long, List<JsonObject>> = emptyMap(),
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var failWith: Int? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = ex.requestURI.query.orEmpty()
            requests += ex.requestMethod + " " + path + (if (query.isNotEmpty()) "?$query" else "")
            val params = query.split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val (code, body) = failWith?.let { it to """{"error":"boom"}""" } ?: when {
                path == "/api/jobs/completed/head" ->
                    200 to """{"max_seq":${events.maxOfOrNull { it.seq() } ?: 0}}"""
                path == "/api/jobs/completed" -> {
                    val since = params["since"]?.toLong() ?: 0
                    val limit = params["limit"]?.toInt() ?: 50
                    200 to JsonArray(events.filter { it.seq() > since }.sortedBy { it.seq() }.take(limit)).toString()
                }
                path == "/api/tracks" -> 200 to JsonArray(tracks).toString()
                Regex("/api/tracks/(\\d+)/events").matches(path) ->
                    200 to JsonArray(trackEvents[path.split('/')[3].toLong()] ?: emptyList()).toString()
                else -> 404 to """{"error":"not found"}"""
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    companion object {
        private fun JsonObject.seq() = this["completed_seq"]!!.jsonPrimitive.long

        fun event(
            seq: Long,
            company: String = "Acme",
            title: String = "Staff SDET",
            fit: Int = 80,
            jobUrl: String? = "https://acme.co/jobs/$seq",
            messageId: String? = null,
            recruiter: Boolean = false,
            terminalLabel: String? = null,
            artifactUrl: String? = "http://markserv:8081/20261001_000000_acme_$seq/",
            action: String = "TAILOR",
            skipReason: String? = null,
        ): JsonObject = buildJsonObject {
            put("job_id", "job-$seq")
            put("completed_seq", seq)
            put("status", "done")
            put("company", company)
            put("role_title", title)
            put("fit_score", fit)
            put("pipeline_action", action)
            skipReason?.let { put("skip_reason", it) }
            put("job_url", jobUrl)
            put("message_id", messageId)
            put("is_recruiter", recruiter)
            put("terminal_label", terminalLabel)
            put("artifact_url", artifactUrl)
        }

        fun track(id: Long, company: String, title: String, status: String = "backlog", artifactUrl: String = ""): JsonObject =
            buildJsonObject {
                put("id", id); put("company", company); put("role_title", title)
                put("status", status); put("artifact_url", artifactUrl); put("location", "Remote")
                put("created_at", "2026-10-01T00:00:00Z")
            }
    }
}
