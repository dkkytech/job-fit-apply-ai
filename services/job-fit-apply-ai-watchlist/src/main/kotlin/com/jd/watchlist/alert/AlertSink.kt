package com.jd.watchlist.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.watchlist.config.Config
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Where operational alerts go (stale feeds). Implementations must never throw. */
fun interface AlertSink {
    fun send(text: String)
}

/**
 * Telegram + Discord, with the same bot settings as the processor's AlertService (root .env:
 * TELEGRAM_BOT_TOKEN/CHAT_ID, DISCORD_BOT_TOKEN/CHANNEL_ID). A channel without settings is skipped,
 * so an unconfigured instance is a silent no-op.
 */
class ChatAlertSink(
    private val telegramToken: String = Config.TELEGRAM_BOT_TOKEN,
    private val telegramChatId: String = Config.TELEGRAM_CHAT_ID,
    private val discordToken: String = Config.DISCORD_BOT_TOKEN,
    private val discordChannelId: String = Config.DISCORD_CHANNEL_ID,
    private val telegramApiBase: String = Config.TELEGRAM_API_BASE,
    private val discordApiBase: String = Config.DISCORD_API_BASE,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : AlertSink {
    private val mapper = ObjectMapper()

    override fun send(text: String) {
        if (telegramToken.isNotBlank() && telegramChatId.isNotBlank()) {
            post(
                "${telegramApiBase.trimEnd('/')}/bot$telegramToken/sendMessage",
                mapper.writeValueAsString(mapOf("chat_id" to telegramChatId, "text" to text, "disable_web_page_preview" to true)),
            )
        }
        if (discordToken.isNotBlank() && discordChannelId.isNotBlank()) {
            post(
                "${discordApiBase.trimEnd('/')}/api/v10/channels/$discordChannelId/messages",
                mapper.writeValueAsString(mapOf("content" to text.take(DISCORD_MAX))),
                authorization = "Bot $discordToken",
            )
        }
    }

    private fun post(url: String, body: String, authorization: String? = null) {
        runCatching {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .also { b -> authorization?.let { b.header("Authorization", it) } }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            // Never log the URL: the Telegram one carries the bot token.
            if (resp.statusCode() !in 200..204) System.err.println("[alert] ${if ("/bot" in url) "telegram" else "discord"} → HTTP ${resp.statusCode()}")
        }.onFailure { System.err.println("[alert] send failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val DISCORD_MAX = 2000
    }
}
