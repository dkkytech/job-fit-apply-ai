package com.jd.jobbot.http

import com.jd.jobbot.actions.StatusReport
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.mcp.JobbotTools
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

/**
 * jobbot-mcp's HTTP surface:
 *  - `/health`: unauthenticated liveness for the compose healthcheck.
 *  - `/plugin/tap`, `/plugin/status`: deterministic endpoints for the Hermes `jobbot_actions` plugin.
 *  - `/mcp`: the model's tools (MCP, stateless Streamable HTTP, JSON responses).
 * Everything but /health needs `Authorization: Bearer <JOBBOT_MCP_TOKEN>`; a blank token refuses all.
 */
fun Application.jobbotModule(
    apiToken: String,
    allowedHosts: List<String>,
    taps: TapService,
    status: StatusReport,
    tools: JobbotTools,
    approvals: ((Long) -> com.jd.jobbot.actions.TapResponse?)? = null,
    outbox: com.jd.jobbot.actions.Outbox? = null,
    accounts: (() -> String)? = null,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path == "/health") return@intercept
        val presented = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
        if (apiToken.isBlank() || !constantTimeEquals(presented, apiToken)) {
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            finish()
        }
    }

    mcpStatelessStreamableHttp(path = "/mcp", allowedHosts = allowedHosts) { tools.server() }

    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        post("/plugin/tap") {
            val req = try {
                JSON.decodeFromString(TapRequest.serializer(), call.receiveText())
            } catch (e: Exception) {
                call.respondText("""{"error":"bad request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val resp = taps.tap(req)
            call.respondText(JSON.encodeToString(com.jd.jobbot.actions.TapResponse.serializer(), resp), ContentType.Application.Json)
        }
        // The preview + Send/Cancel buttons for an approval the model requested (posted by the plugin).
        get("/plugin/approval/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val body = id?.let { approvals?.invoke(it) }
            if (body == null) {
                call.respondText("""{"error":"not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            } else {
                call.respondText(JSON.encodeToString(com.jd.jobbot.actions.TapResponse.serializer(), body), ContentType.Application.Json)
            }
        }
        // Messages for the plugin to post (fill results, hand-offs, reminders), then acknowledge.
        get("/plugin/outbox") {
            val items = outbox?.pending() ?: emptyList()
            call.respondText(JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.jd.jobbot.actions.Outbox.Item.serializer()), items), ContentType.Application.Json)
        }
        get("/plugin/outbox/{id}/photo") {
            val bytes = call.parameters["id"]?.toLongOrNull()?.let { outbox?.photo(it) }
            if (bytes == null) call.respondText("", status = HttpStatusCode.NotFound)
            else call.respondBytes(bytes, ContentType.Image.PNG)
        }
        post("/plugin/outbox/{id}/delivered") {
            call.parameters["id"]?.toLongOrNull()?.let { outbox?.delivered(it) }
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }
        get("/plugin/accounts") {
            val body = buildJsonObject { put("text", accounts?.invoke() ?: "Apply is not enabled, so JobBot has no site accounts.") }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
        get("/plugin/status") {
            val body = buildJsonObject { put("text", status.text()) }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
