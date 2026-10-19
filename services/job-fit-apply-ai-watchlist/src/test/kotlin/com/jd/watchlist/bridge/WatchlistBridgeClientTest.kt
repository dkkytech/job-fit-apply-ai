package com.jd.watchlist.bridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchlistBridgeClientTest {

    @Test
    fun `encodes snake_case with source CAREER_SITE by default`() {
        val tree = ObjectMapper().readTree(
            WatchlistBridgeClient(baseUrl = "http://unused").encode(
                SubmitJobRequest(jdText = "x", roleTitle = "SDET", company = "Acme", jobUrl = "https://a/1", idempotencyKey = "watchlist:greenhouse:acme:1")
            )
        )
        assertEquals("CAREER_SITE", tree["source"].asText())
        assertEquals("SDET", tree["role_title"].asText())
        assertEquals("watchlist:greenhouse:acme:1", tree["idempotency_key"].asText())
        assertTrue(tree.has("jd_text") && tree.has("job_url"))
    }

    @Test
    fun `parses 202 queued and 200 deduped, returns null on 422`() {
        val statuses = ArrayDeque(listOf(202 to """{"job_id":"j1","status":"PENDING"}""", 200 to """{"job_id":"j0","status":"PENDING","deduped":true}""",
            422 to """{"error":"jd_text must be at least 150 characters"}"""))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/jobs") { ex ->
                val (code, body) = statuses.removeFirst()
                ex.sendResponseHeaders(code, body.length.toLong()); ex.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        try {
            val client = WatchlistBridgeClient(baseUrl = "http://127.0.0.1:${server.address.port}")
            val req = SubmitJobRequest(jdText = "x".repeat(150))
            assertEquals("j1", client.submit(req)!!.jobId)
            assertTrue(client.submit(req)!!.deduped)
            assertNull(client.submit(req))
        } finally {
            server.stop(0)
        }
    }
}
