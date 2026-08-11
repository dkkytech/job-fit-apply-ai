package com.jd.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures the notifier's outbound Discord + Telegram posts. The compose override points
 * DISCORD_API_BASE / TELEGRAM_API_BASE at this server, so the paths arrive in the real
 * shapes: `/api/v10/channels/{id}/messages` and `/bot{token}/sendMessage`.
 */
class MockNotificationSink(private val port: Int) {
    /** [status] is what the notifier saw, so a retry and its eventual success are distinguishable. */
    data class Received(val channel: String, val path: String, val body: JsonNode, val status: Int = 200)

    /**
     * One queued reply for a channel. Delivery failure is the whole point of the Notifier
     * retry/cursor contracts, and it cannot be provoked from the outside — the sink has to
     * refuse on cue. A refused post is still [Received]: counting attempts is how a test tells
     * "retried once then succeeded" from "delivered once".
     */
    data class PlannedResponse(
        val status: Int = 200,
        val delayMs: Long = 0,
        val body: String = """{"ok":true}""",
    )

    companion object {
        fun ok() = PlannedResponse()

        /** Discord/Telegram 5xx and 429 are the retryable shapes the notifier must survive. */
        fun failure(status: Int, body: String = """{"message":"e2e injected $status"}""") =
            PlannedResponse(status = status, body = body)

        fun stall(delayMs: Long) = PlannedResponse(delayMs = delayMs)
    }

    private val mapper = ObjectMapper()
    private var engine: ApplicationEngine? = null

    /** Per-channel FIFO replies installed by one scenario; 200 OK resumes when exhausted. */
    private val responsePlan = ConcurrentHashMap<String, ConcurrentLinkedQueue<PlannedResponse>>()

    /**
     * CopyOnWriteArrayList, not synchronizedList: the accessors below iterate this while
     * the Netty handler thread appends (the notifier posts Discord then Telegram
     * back-to-back), and a synchronizedList's iterator is not itself synchronized — a
     * plain `filter` would intermittently throw ConcurrentModificationException and abort
     * the whole suite from @BeforeAll.
     */
    val received: MutableList<Received> = CopyOnWriteArrayList()

    fun start() {
        // 0.0.0.0 so the notifier container reaches us via host.docker.internal; this is
        // LAN/tailnet-visible for the run, and only ever returns a canned ack (or a
        // deliberately injected refusal — see [reset]).
        engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            routing {
                post("{...}") {
                    val path = call.request.path()
                    val body = mapper.readTree(call.receiveText())
                    // Classify strictly against the credentials compose actually gave the
                    // notifier. A post to a plausible-but-wrong channel id or bot token
                    // lands in "unknown" and is reported, rather than being accepted as a
                    // healthy delivery.
                    val channel = when (path) {
                        "/api/v10/channels/${E2eConfig.discordChannelId}/messages" -> "discord"
                        "/bot${E2eConfig.telegramBotToken}/sendMessage" -> "telegram"
                        else -> "unknown"
                    }
                    val planned = responsePlan[channel]?.poll() ?: PlannedResponse()
                    received += Received(channel, path, body, planned.status)
                    if (planned.delayMs > 0) delay(planned.delayMs)
                    println(
                        "[sink] $channel ← $path" +
                            (if (planned.status !in 200..299) " → HTTP ${planned.status} (injected)" else ""),
                    )
                    call.respondText(
                        planned.body,
                        ContentType.Application.Json,
                        HttpStatusCode.fromValue(planned.status),
                    )
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 2000)
    }

    /** Isolate observations between scenario-level transactions, and install queued replies. */
    fun reset(responses: Map<String, List<PlannedResponse>> = emptyMap()) {
        received.clear()
        responsePlan.clear()
        responses.forEach { (channel, planned) ->
            responsePlan[channel] = ConcurrentLinkedQueue(planned)
        }
    }

    /** Every post that reached [channel], refused ones included — i.e. delivery *attempts*. */
    fun attempts(channel: String): List<Received> = received.filter { it.channel == channel }

    /** Only the posts the sink accepted; what the recipient would actually have seen. */
    fun delivered(channel: String): List<Received> =
        received.filter { it.channel == channel && it.status in 200..299 }

    /** Delivered Discord messages. A refused attempt is not a message the user ever saw. */
    fun discordTexts(): List<String> = delivered("discord").map { it.body.path("content").asText() }

    fun telegramTexts(): List<String> = delivered("telegram").map { it.body.path("text").asText() }

    /**
     * Everything that arrived at an unrecognised path — a wrong channel id, a wrong bot
     * token, or a changed URL shape. Tests fold this into their timeout messages so the
     * symptom ("no Discord message") names the cause instead of pointing at the notifier.
     */
    fun unknownPaths(): List<String> =
        received.filter { it.channel == "unknown" }.map { it.path }.distinct()

    /** Diagnostic summary for failure messages: what actually landed here. */
    fun describe(): String =
        received.groupingBy { it.channel }.eachCount().toString() +
            unknownPaths().takeIf { it.isNotEmpty() }?.let { " unexpected paths=$it" }.orEmpty()
}
