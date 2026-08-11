package com.jd.pipeline.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SteelClient] against an in-process stub HTTP server (no external Steel needed),
 * covering the request shape (sessionContext injection), response parsing, context export, and
 * error handling.
 */
@DisplayName("SteelClient")
class SteelClientTest {

    private val mapper = ObjectMapper()
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    // Captured by the stub for assertions.
    @Volatile private var lastCreateBody: String = ""
    @Volatile private var releaseHit: Boolean = false
    @Volatile private var failCreate: Boolean = false
    @Volatile private var failContext: Boolean = false

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/sessions") { ex ->
            val path = ex.requestURI.path
            val method = ex.requestMethod
            when {
                path == "/v1/sessions" && method == "POST" -> {
                    lastCreateBody = ex.requestBody.readBytes().decodeToString()
                    if (failCreate) {
                        respond(ex, 500, """{"success":false,"message":"launch_failed"}""")
                    } else {
                        respond(
                            ex, 200,
                            """{"id":"sess-1","websocketUrl":"ws://localhost:3000/","debugUrl":"http://localhost:3000/v1/sessions/debug","sessionViewerUrl":"http://localhost:3000/","status":"live","extra":"ignored"}""",
                        )
                    }
                }
                path.endsWith("/context") && method == "GET" ->
                    if (failContext) respond(ex, 500, """{"message":"Browser or primary page not initialized"}""")
                    else respond(ex, 200, """{"cookies":[{"name":"li_at","value":"xyz"}],"localStorage":[],"sessionStorage":[],"indexedDB":[]}""")
                path.endsWith("/release") && method == "POST" -> {
                    releaseHit = true
                    respond(ex, 200, "{}")
                }
                else -> respond(ex, 404, """{"message":"not found"}""")
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    @DisplayName("createSession parses the session and omits sessionContext when null")
    fun createSessionNoContext() {
        val s = SteelClient(baseUrl).createSession(sessionContext = null, timeoutMs = 60_000)
        assertEquals("sess-1", s.id)
        assertEquals("ws://localhost:3000/", s.websocketUrl)
        assertEquals("http://localhost:3000/v1/sessions/debug", s.debugUrl)

        val body = mapper.readTree(lastCreateBody)
        assertEquals(60_000, body.get("timeout").asLong())
        assertTrue(!body.has("sessionContext"), "sessionContext must be absent when null")
    }

    @Test
    @DisplayName("createSession injects sessionContext when provided")
    fun createSessionWithContext() {
        val ctx = mapper.readTree("""{"cookies":[{"name":"li_at","value":"abc"}],"localStorage":[]}""")
        SteelClient(baseUrl).createSession(sessionContext = ctx, timeoutMs = 30_000)

        val body = mapper.readTree(lastCreateBody)
        assertTrue(body.has("sessionContext"), "sessionContext must be present")
        assertEquals("li_at", body.get("sessionContext").get("cookies").get(0).get("name").asText())
    }

    @Test
    @DisplayName("exportContext returns the cookies/localStorage tree")
    fun exportContext() {
        val ctx = SteelClient(baseUrl).exportContext("sess-1")
        assertTrue(ctx != null && ctx.has("cookies"))
        assertEquals("li_at", ctx!!.get("cookies").get(0).get("name").asText())
    }

    @Test
    @DisplayName("releaseSession hits the release endpoint")
    fun releaseSession() {
        SteelClient(baseUrl).releaseSession("sess-1")
        assertTrue(releaseHit)
    }

    @Test
    @DisplayName("createSession throws a SteelHttpException carrying the status code on a non-2xx response")
    fun createSessionError() {
        failCreate = true
        val e = assertThrows<SteelHttpException> {
            SteelClient(baseUrl).createSession(sessionContext = null, timeoutMs = 1_000)
        }
        assertEquals(500, e.statusCode)
    }

    @Test
    @DisplayName("exportContext returns null on a non-2xx response")
    fun exportContextMissing() {
        failContext = true
        assertNull(SteelClient(baseUrl).exportContext("sess-1"))
    }
}
