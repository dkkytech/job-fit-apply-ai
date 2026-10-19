package com.jd.jobbot.http

import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.ApplyNotImplemented
import com.jd.jobbot.actions.StatusReport
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.files.OutputFiles
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.mcp.JobbotTools
import com.jd.jobbot.profile.ProfileReader
import com.jd.jobbot.support.FakeBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real HTTP surface on a real port: auth on /plugin and /mcp, the tap and status endpoints,
 * and an actual MCP client (the SDK's Streamable HTTP transport, as Hermes uses) listing and
 * calling the tools.
 */
class ServerTest {
    private val token = "test-token"
    private val user = "8679792351"
    private lateinit var bridge: FakeBridge
    private lateinit var server: EmbeddedServer<*, *>
    private val port = ServerSocket(0).use { it.localPort }
    private val base get() = "http://127.0.0.1:$port"
    private val http = HttpClient(ClientCIO) { install(SSE) }

    @BeforeTest
    fun start() {
        bridge = FakeBridge(events = listOf(FakeBridge.event(11), FakeBridge.event(12, jobUrl = null)))
        val client = BridgeReadClient(bridge.url)
        val lookup = JobLookup(client)
        val store = ActionStore(":memory:")
        val taps = TapService(lookup, store, mapOf("apply" to ApplyNotImplemented), setOf(user), setOf("apply"), false, Duration.ofDays(7))
        val tools = JobbotTools(lookup, client, OutputFiles("/nonexistent"), ProfileReader("/nonexistent/a", "/nonexistent/b"), 55, 400)
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            jobbotModule(token, listOf("127.0.0.1", "localhost"), taps, StatusReport(client, store, setOf("apply"), false), tools)
        }.start(wait = false)
    }

    @AfterTest
    fun stop() {
        http.close()
        server.stop(100, 500)
        bridge.close()
    }

    private fun tapBody(verb: String = "apply", seq: Long = 11, from: String = user) =
        """{"verb":"$verb","seq":$seq,"user_id":"$from","chat_id":"$user","message_id":5,"message_date":${Instant.now().epochSecond}}"""

    @Test
    fun `health needs no token`() = runBlocking {
        assertEquals(HttpStatusCode.OK, http.get("$base/health").status)
    }

    @Test
    fun `plugin and mcp routes refuse a missing or wrong token`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, http.post("$base/plugin/tap") { setBody(tapBody()) }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("$base/plugin/status") { header("Authorization", "Bearer nope") }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.post("$base/mcp") { setBody("{}") }.status)
    }

    @Test
    fun `an Apply tap answers Not implemented yet`() = runBlocking {
        val resp = http.post("$base/plugin/tap") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(tapBody())
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val o = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("not_implemented", o["outcome"]!!.jsonPrimitive.content)
        assertEquals("Not implemented yet", o["reply"]!!.jsonPrimitive.content)
        assertTrue("action_row" !in o, "null fields are omitted so the plugin leaves the keyboard alone: $o")
    }

    @Test
    fun `a malformed tap is a 400`() = runBlocking {
        val resp = http.post("$base/plugin/tap") { header("Authorization", "Bearer $token"); setBody("""{"verb":1}""") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `status returns the jdstatus text`() = runBlocking {
        val text = Json.parseToJsonElement(
            http.get("$base/plugin/status") { header("Authorization", "Bearer $token") }.bodyAsText(),
        ).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("JobBot status"), text)
        assertTrue(text.contains("Latest JFAA job: #J12"), text)
    }

    @Test
    fun `an MCP client lists the read-only tools and calls get_job`() = runBlocking {
        val transport = StreamableHttpClientTransport(http, "$base/mcp", requestBuilder = { header("Authorization", "Bearer $token") })
        val client = Client(Implementation("jobbot-test", "1"))
        client.connect(transport)
        try {
            val tools = client.listTools().tools
            assertEquals(
                setOf("get_job", "list_high_fit", "read_job_file", "list_tracks", "get_profile"),
                tools.map { it.name }.toSet(),
            )
            tools.forEach { assertEquals(true, it.annotations?.readOnlyHint, "${it.name} must be read-only") }

            val result = client.callTool("get_job", mapOf("ref" to "#J11"))
            val text = (result.content.single() as TextContent).text
            assertTrue(text.contains("\"company\": \"Acme\""), text)

            bridge.failWith = 500
            val outage = client.callTool("get_job", mapOf("ref" to "#J11"))
            assertEquals(true, outage.isError, "a bridge outage must be a tool error, not a transport failure")

            // A malformed feed event (no completed_seq) must not crash the tool either.
            bridge.failWith = null
            bridge.events = bridge.events + kotlinx.serialization.json.buildJsonObject {
                put("completed_seq", kotlinx.serialization.json.JsonPrimitive(13)); put("fit_score", kotlinx.serialization.json.JsonPrimitive(90))
            }
            val listed = client.callTool("list_high_fit", emptyMap())
            assertEquals(null, listed.isError, (listed.content.single() as TextContent).text)
        } finally {
            client.close()
        }
    }
}
