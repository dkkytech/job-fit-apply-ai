package com.jd.pipeline.client

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A Steel REST call returned a non-2xx status. Carries the [statusCode] so callers can decide
 * whether to retry — e.g. a fast, transient HTTP 5xx (the SingletonLock race while Chrome relaunches)
 * is worth an in-scrape retry, whereas a timeout (a different exception type) is not.
 */
class SteelHttpException(val statusCode: Int, message: String) : RuntimeException(message)

/**
 * Thin REST client for a self-hosted Steel Browser (https://github.com/steel-dev/steel-browser).
 *
 * Only the three calls the scraper needs:
 *  - [createSession] — `POST /v1/sessions`, optionally injecting a saved `sessionContext`
 *    (cookies + localStorage + sessionStorage + indexedDB) so the browser starts logged in;
 *  - [exportContext] — `GET /v1/sessions/{id}/context`, to persist the refreshed cookies on close;
 *  - [releaseSession] — `POST /v1/sessions/{id}/release`, to free the browser when the batch ends.
 *
 * The Playwright CDP connection itself is made by [SteelBrowser] against the session's websocketUrl.
 */
class SteelClient(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder()
        // Steel's Fastify server speaks HTTP/1.1; Java's HttpClient defaults to HTTP/2 and its h2c
        // upgrade attempt makes the server close the socket ("header parser received no bytes").
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private val root = baseUrl.trimEnd('/')
    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** The subset of Steel's session object the scraper uses (extra fields are ignored). */
    data class SteelSession(
        val id: String,
        val websocketUrl: String,
        val debugUrl: String? = null,
        val sessionViewerUrl: String? = null,
    )

    /**
     * Create a browser session. [sessionContext], when non-null, is the previously-exported
     * `{cookies,localStorage,sessionStorage,indexedDB}` object — Steel loads it so the session
     * resumes already authenticated. Throws on a non-2xx response.
     */
    fun createSession(sessionContext: JsonNode?, timeoutMs: Long): SteelSession {
        val body = mapper.createObjectNode()
        body.put("timeout", timeoutMs)
        if (sessionContext != null && !sessionContext.isNull) {
            body.set<JsonNode>("sessionContext", sessionContext)
        }
        val req = HttpRequest.newBuilder(URI.create("$root/v1/sessions"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(90))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw SteelHttpException(
                resp.statusCode(),
                "Steel createSession failed: HTTP ${resp.statusCode()} — ${resp.body().take(300)}",
            )
        }
        return mapper.readValue(resp.body(), SteelSession::class.java)
    }

    /** Export the live session's browser state for persistence, or null if it can't be read. */
    fun exportContext(sessionId: String): JsonNode? {
        val req = HttpRequest.newBuilder(URI.create("$root/v1/sessions/$sessionId/context"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        return if (resp.statusCode() in 200..299) mapper.readTree(resp.body()) else null
    }

    /** Release the browser session. Best-effort — swallows failures so it never breaks teardown. */
    fun releaseSession(sessionId: String) {
        val req = HttpRequest.newBuilder(URI.create("$root/v1/sessions/$sessionId/release"))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        runCatching { http.send(req, HttpResponse.BodyHandlers.ofString()) }
    }
}
