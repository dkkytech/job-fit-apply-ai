package com.jd.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.e2e.MockNotificationSink.Companion.failure
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-level checks on the notification sink — no Docker, no compose slice.
 *
 * The sink is the only thing that can make a delivery *fail*, so the Notifier retry and
 * cursor-recovery contracts are only as trustworthy as these routing and refusal rules.
 * The distinction the black-box scenarios lean on is attempts vs deliveries: a refused post
 * still happened, and "retried once then succeeded" must not read as "delivered once".
 *
 * Tagged tier-b: it asserts canned-fixture behaviour alongside the other exact-value checks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("tier-b")
@DisplayName("MockNotificationSink delivery")
class MockNotificationSinkTest {

    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var port = 0
    private lateinit var sink: MockNotificationSink

    @BeforeAll
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        sink = MockNotificationSink(port)
        sink.start()
    }

    @AfterAll
    fun stop() = sink.stop()

    private fun post(path: String, body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun discord(text: String) =
        post("/api/v10/channels/${E2eConfig.discordChannelId}/messages", mapper.writeValueAsString(mapOf("content" to text)))

    private fun telegram(text: String) =
        post("/bot${E2eConfig.telegramBotToken}/sendMessage", mapper.writeValueAsString(mapOf("text" to text)))

    @Test
    @DisplayName("with no plan every post is accepted and classified by channel")
    fun defaultsAcceptEverything() {
        sink.reset()

        assertEquals(200, discord("hello").statusCode())
        assertEquals(200, telegram("High-fit: hello").statusCode())

        assertEquals(listOf("hello"), sink.discordTexts())
        assertEquals(listOf("High-fit: hello"), sink.telegramTexts())
        assertTrue(sink.unknownPaths().isEmpty())
    }

    @Test
    @DisplayName("a queued refusal is served once, then the channel recovers")
    fun refusalsAreServedInOrderThenRecover() {
        sink.reset(mapOf("discord" to listOf(failure(500))))

        val refused = discord("first attempt")
        val accepted = discord("second attempt")

        assertEquals(500, refused.statusCode())
        assertEquals(200, accepted.statusCode())
        // Two attempts, one delivery — the exact distinction the retry contract turns on.
        assertEquals(2, sink.attempts("discord").size)
        assertEquals(listOf("second attempt"), sink.discordTexts())
    }

    @Test
    @DisplayName("a refusal on one channel does not affect the other")
    fun channelPlansAreIndependent() {
        sink.reset(mapOf("discord" to listOf(failure(429))))

        assertEquals(429, discord("rate limited").statusCode())
        assertEquals(200, telegram("unaffected").statusCode())

        assertTrue(sink.discordTexts().isEmpty(), "a 429'd post is an attempt, never a delivery")
        assertEquals(listOf("unaffected"), sink.telegramTexts())
    }

    @Test
    @DisplayName("a post to a wrong-but-plausible path is recorded as unknown, not accepted as delivery")
    fun wrongCredentialsLandInUnknown() {
        sink.reset()

        assertEquals(200, post("/api/v10/channels/not-the-channel/messages", """{"content":"stray"}""").statusCode())

        assertTrue(sink.discordTexts().isEmpty())
        assertEquals(listOf("/api/v10/channels/not-the-channel/messages"), sink.unknownPaths())
    }

    @Test
    @DisplayName("reset clears observations between scenarios")
    fun resetIsolatesScenarios() {
        sink.reset()
        discord("from the previous scenario")
        assertEquals(1, sink.attempts("discord").size)

        sink.reset()

        assertTrue(sink.received.isEmpty(), "reset must isolate observations between scenarios")
    }
}
