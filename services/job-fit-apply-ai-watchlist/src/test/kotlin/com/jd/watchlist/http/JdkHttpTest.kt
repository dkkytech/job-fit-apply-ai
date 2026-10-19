package com.jd.watchlist.http

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JdkHttpTest {
    private var server: HttpServer? = null

    /** Serves [responses] in order: (status, Retry-After header or null). Body is always "ok". */
    private fun serve(vararg responses: Pair<Int, String?>): String {
        val queue = ArrayDeque(responses.toList())
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val (code, retryAfter) = queue.removeFirstOrNull() ?: (200 to null)
                retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
                val body = "ok".toByteArray()
                ex.sendResponseHeaders(code, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
        return "http://127.0.0.1:${server!!.address.port}/x"
    }

    @AfterEach fun stop() { server?.stop(0) }

    @Test
    fun `429 is retried with backoff, honouring and capping Retry-After`() {
        val sleeps = mutableListOf<Long>()
        val url = serve(429 to null, 429 to "5", 429 to "3600")
        val http = JdkHttp(backoffMs = listOf(10, 20, 30), sleep = { sleeps += it })
        assertEquals("ok", http.get(url))
        assertEquals(listOf(10L, 5_000L, 60_000L), sleeps, "default backoff, then Retry-After seconds, then capped at 60s")
    }

    @Test
    fun `gives up after the last backoff and other errors are not retried`() {
        val sleeps = mutableListOf<Long>()
        val url = serve(429 to null, 429 to null, 429 to null)
        val e = assertFailsWith<HttpStatusException> { JdkHttp(backoffMs = listOf(1, 1), sleep = { sleeps += it }).get(url) }
        assertEquals(429, e.status)
        assertEquals(2, sleeps.size)

        server?.stop(0)
        val url2 = serve(503 to null)
        assertEquals(503, assertFailsWith<HttpStatusException> { JdkHttp(sleep = { error("must not sleep") }).get(url2) }.status)
    }
}
