package com.jd.notifier.notify

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the **wire shape** of the buttoned Telegram post.
 *
 * The bug this guards against: serialising `reply_markup` from its JSON *text* makes Jackson emit
 * a quoted string rather than a nested object. Telegram happens to accept that, so nothing
 * complains — but the documented payload is an object, and any consumer reading the body
 * structurally (including the E2E sink used to assert the buttons) sees a text node and finds no
 * keyboard at all. Asserting the label list is not enough; the shape must be asserted.
 */
@DisplayName("NotificationClient (buttoned Telegram wire shape)")
class TelegramButtonsWireShapeTest {

    private lateinit var server: HttpServer
    private lateinit var client: NotificationClient
    private val bodies = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun start() {
        bodies.clear()
        paths.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            bodies += body
            paths += ex.requestURI.path
            val out = """{"ok":true}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        client = NotificationClient(
            discordToken = "",
            discordChannelId = "",
            telegramToken = "t",
            telegramChatId = "c",
            discordApiBase = base,
            telegramApiBase = base,
        )
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun rows() = TelegramButtons.forHighFit(
        reportUrl = "http://host:8081/job/report.md",
        resumeUrl = "http://host:8765/api/jobs/x/resume.pdf",
        actions = listOf(TelegramButtons.Action.APPLY),
        completedSeq = 77,
    )

    @Test
    @DisplayName("reply_markup is a nested JSON object, not a quoted string")
    fun markupIsAnObject() {
        val result = client.postTelegramHtmlWithButtons("High-fit: Acme — 80", rows())
        assertEquals(DeliveryResult.DELIVERED, result)
        assertEquals(1, bodies.size, "expected exactly one post")

        val body = mapper.readTree(bodies.single())
        val markup = body.path("reply_markup")

        assertTrue(!markup.isMissingNode, "no reply_markup on the wire: ${bodies.single()}")
        assertTrue(
            markup.isObject,
            "reply_markup must be a JSON object, got ${markup.nodeType}: ${bodies.single()}",
        )
        // A quoted-string encoding parses as a TextNode; reading it structurally then finds nothing.
        assertTrue(
            markup.path("inline_keyboard").isArray,
            "inline_keyboard missing — reply_markup was probably double-encoded: ${bodies.single()}",
        )
        assertEquals(
            listOf("View Report", "View Resume"),
            markup.path("inline_keyboard").first().map { it.path("text").asText() },
        )
    }

    @Test
    @DisplayName("buttons ride the last chunk only, so a long ping cannot stack keyboards")
    fun buttonsOnlyOnLastChunk() {
        // Two chunks: the body must exceed the 4096 cap.
        val long = (1..500).joinToString("\n") { "line $it" }
        assertEquals(DeliveryResult.DELIVERED, client.postTelegramHtmlWithButtons(long, rows()))

        assertTrue(bodies.size > 1, "expected a chunked send, got ${bodies.size} post(s)")
        val withMarkup = bodies.count { mapper.readTree(it).has("reply_markup") }
        assertEquals(1, withMarkup, "exactly one chunk may carry the keyboard")
        assertTrue(
            mapper.readTree(bodies.last()).has("reply_markup"),
            "the keyboard belongs on the final chunk",
        )
    }

    @Test
    @DisplayName("no buttons degrades to the plain ping with no reply_markup key")
    fun emptyRowsFallBackToPlainText() {
        client.postTelegramHtmlWithButtons("High-fit: Acme — 80", emptyList())
        assertEquals(1, bodies.size)
        assertTrue(
            !mapper.readTree(bodies.single()).has("reply_markup"),
            "an empty keyboard must be omitted, not sent empty (Telegram rejects [[]]): ${bodies.single()}",
        )
    }

    @Test
    @DisplayName("action buttons reach the wire as <verb>:<seq> callback_data")
    fun actionCallbackOnTheWire() {
        client.postTelegramHtmlWithButtons("High-fit: Acme — 80\n#J77", rows())
        val keyboard = mapper.readTree(bodies.single()).path("reply_markup").path("inline_keyboard")
        val apply = keyboard[1][0]
        assertEquals("Apply", apply.path("text").asText())
        assertEquals("apply:77", apply.path("callback_data").asText())
        assertTrue(apply.path("url").isMissingNode, "an action button must not carry a url")
    }

    @Test
    @DisplayName("every Telegram request the client makes is sendMessage — never getUpdates")
    fun onlySendMessageOnTheWire() {
        client.postTelegramHtml("plain")
        client.postTelegramHtmlWithButtons("buttons", rows())
        client.postTelegramHtmlWithButtons((1..500).joinToString("\n") { "l$it" }, rows())
        assertTrue(paths.isNotEmpty())
        paths.forEach { assertTrue(it.endsWith("/sendMessage"), "unexpected Telegram method: $it") }
    }
}
