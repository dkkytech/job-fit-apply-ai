package com.jd.notifier.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [NotifierBridgeClient] against an in-process stub bridge (no real bridge needed).
 * Complements [NotifierBridgeContractTest] (which runs against a live bridge) by exercising the
 * request URLs, snake_case → camelCase mapping, cursor/limit params, and the non-200 failure paths.
 */
@DisplayName("NotifierBridgeClient (HTTP)")
class NotifierBridgeClientHttpTest {

    private lateinit var server: HttpServer
    private lateinit var client: NotifierBridgeClient

    @Volatile private var lastQuery: String = ""
    @Volatile private var completedResponse: Pair<Int, String> = 200 to "[]"
    @Volatile private var headResponse: Pair<Int, String> = 200 to """{"max_seq":0}"""

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/jobs/completed") { ex ->
            if (ex.requestURI.path.endsWith("/head")) {
                respond(ex, headResponse.first, headResponse.second)
            } else {
                lastQuery = ex.requestURI.query ?: ""
                respond(ex, completedResponse.first, completedResponse.second)
            }
        }
        server.start()
        client = NotifierBridgeClient("http://127.0.0.1:${server.address.port}")
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    @DisplayName("fetchEvents maps snake_case fields into CompletedEvent and passes the cursor/limit/all params")
    fun fetchEventsMapsFields() {
        completedResponse = 200 to """
            [{"job_id":"j1","completed_seq":7,"status":"done","company":"Acme","role_title":"Staff SDET",
              "fit_score":91,"pipeline_action":"tailor","job_url":"https://acme.co/j","artifact_url":"http://mark/x"}]
        """.trimIndent()

        val events = client.fetchEvents(since = 5, limit = 25)

        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("j1", e.jobId)
        assertEquals(7L, e.completedSeq)
        assertEquals("Acme", e.company)
        assertEquals("Staff SDET", e.roleTitle)
        assertEquals(91, e.fitScore)
        assertEquals("tailor", e.pipelineAction)
        assertEquals("https://acme.co/j", e.jobUrl)

        assertTrue(lastQuery.contains("since=5"), "query: $lastQuery")
        assertTrue(lastQuery.contains("limit=25"), "query: $lastQuery")
        assertTrue(lastQuery.contains("all=true"), "query: $lastQuery")
    }

    @Test
    @DisplayName("fetchEvents returns an empty list for an empty feed")
    fun fetchEventsEmpty() {
        completedResponse = 200 to "[]"
        assertTrue(client.fetchEvents(since = 0).isEmpty())
    }

    @Test
    @DisplayName("fetchEvents throws with the status code on a non-200 response")
    fun fetchEventsThrowsOnError() {
        completedResponse = 500 to """{"error":"boom"}"""
        val ex = assertThrows<IllegalStateException> { client.fetchEvents(since = 0) }
        assertTrue(ex.message!!.contains("500"), "message: ${ex.message}")
    }

    @Test
    @DisplayName("headSeq reads max_seq for cold-start cursor seeding")
    fun headSeqReadsMaxSeq() {
        headResponse = 200 to """{"max_seq":42}"""
        assertEquals(42L, client.headSeq())
    }

    // NOTE: headSeq() does NOT actually default to 0 when max_seq is absent — `JsonNode.get("max_seq")`
    // returns null before `.asLong(0L)` runs, so a `{}` head response throws NPE. That looks like a
    // latent bug (`.get` should be `.path`); flagged to the user rather than asserted as intended
    // behavior. The happy path (max_seq present) is covered above.

    @Test
    @DisplayName("headSeq throws with the status code on a non-200 response")
    fun headSeqThrowsOnError() {
        headResponse = 503 to """{"error":"unavailable"}"""
        val ex = assertThrows<IllegalStateException> { client.headSeq() }
        assertTrue(ex.message!!.contains("503"), "message: ${ex.message}")
    }
}
