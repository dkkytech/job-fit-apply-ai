package com.jd.watchlist.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal HTTP surface the adapters need — an interface so tests can serve canned feeds. */
interface Http {
    /** [headers] are extra request headers (DirectEmployers' search API requires `X-Origin`). */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String
    /** [headers] are extra request headers (TikTok's API requires `website-path`). */
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String
}

class HttpStatusException(val url: String, val status: Int) :
    RuntimeException("HTTP $status from $url")

/**
 * JDK-client implementation. A browser-like User-Agent: several ATS front doors reject bare clients.
 * HTTP 429 is retried after `Retry-After` (capped) or an exponential backoff — Eightfold
 * (Microsoft) rate-limits bursts partway through a paged search, and failing the whole employer
 * there would discard the pages already read.
 */
class JdkHttp(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val backoffMs: List<Long> = listOf(3_000, 9_000, 27_000),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Http {
    override fun get(url: String, headers: Map<String, String>): String =
        send(base(url).also { b -> headers.forEach { (k, v) -> b.header(k, v) } }.GET().build())

    override fun postJson(url: String, json: String, headers: Map<String, String>): String =
        send(
            base(url).header("Content-Type", "application/json")
                .also { b -> headers.forEach { (k, v) -> b.header(k, v) } }
                .POST(HttpRequest.BodyPublishers.ofString(json)).build()
        )

    private fun base(url: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json, application/rss+xml, text/xml, text/html;q=0.9, */*;q=0.8")

    private fun send(req: HttpRequest): String {
        var attempt = 0
        while (true) {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 429 && attempt < backoffMs.size) {
                val retryAfterMs = resp.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.times(1000)
                sleep(minOf(retryAfterMs ?: backoffMs[attempt], MAX_RETRY_AFTER_MS))
                attempt++
                continue
            }
            if (resp.statusCode() !in 200..299) throw HttpStatusException(req.uri().toString(), resp.statusCode())
            return resp.body()
        }
    }

    companion object {
        private const val MAX_RETRY_AFTER_MS = 60_000L
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129 Safari/537.36"
    }
}
