package com.jd.pipeline.client

import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.Json
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.entity.StringEntity
import org.slf4j.LoggerFactory

/**
 * HTTP transport for Discord and Telegram notifications.
 *
 * All methods are no-ops when the relevant credentials are not configured.
 * Chunking respects each platform's character limits (2000 for Discord, 4096 for Telegram)
 * and always splits on line boundaries to preserve message formatting.
 */
class NotificationClient(
    private val discordToken: String     = Config.DISCORD_BOT_TOKEN,
    private val discordChannelId: String = Config.DISCORD_CHANNEL_ID,
    private val telegramToken: String    = Config.TELEGRAM_BOT_TOKEN,
    private val telegramChatId: String   = Config.TELEGRAM_CHAT_ID,
    private val discordApiBase: String   = Config.DISCORD_API_BASE,
    private val telegramApiBase: String  = Config.TELEGRAM_API_BASE,
) {
    private val log  = LoggerFactory.getLogger(NotificationClient::class.java)
    private val http = HttpClients.createDefault()

    val discordConfigured  get() = discordToken.isNotBlank()  && discordChannelId.isNotBlank()
    val telegramConfigured get() = telegramToken.isNotBlank() && telegramChatId.isNotBlank()

    internal fun discordMessagesUrl() =
        "${discordApiBase.trimEnd('/')}/api/v10/channels/$discordChannelId/messages"

    internal fun telegramSendMessageUrl() =
        "${telegramApiBase.trimEnd('/')}/bot$telegramToken/sendMessage"

    fun postDiscord(text: String) {
        if (!discordConfigured) return
        chunkLines(text, 2000).forEach { chunk ->
            try {
                val body = Json.mapper.writeValueAsString(mapOf("content" to chunk))
                val req  = HttpPost(discordMessagesUrl()).apply {
                    addHeader("Authorization", "Bot $discordToken")
                    addHeader("User-Agent", "DiscordBot (https://github.com/openclaw, 1.0)")
                    entity = StringEntity(body, ContentType.APPLICATION_JSON)
                }
                http.execute(req) { resp ->
                    if (resp.code !in 200..204) {
                        log.warn("Discord post → ${resp.code}: ${EntityUtils.toString(resp.entity, Charsets.UTF_8)}")
                    }
                }
            } catch (e: Exception) {
                log.warn("Discord post failed: ${e.message}")
            }
        }
    }

    /** Send a plain-text Telegram message. */
    fun postTelegram(text: String) = postTelegram(text, parseMode = null)

    /**
     * Send a Telegram message rendered as HTML (`parse_mode=HTML`), so `<a href>` links are
     * clickable. Callers must HTML-escape any interpolated user text.
     */
    fun postTelegramHtml(text: String) = postTelegram(text, parseMode = "HTML")

    private fun postTelegram(text: String, parseMode: String?) {
        if (!telegramConfigured) return
        chunkLines(text, 4096).forEach { chunk ->
            try {
                val payload = buildMap<String, Any> {
                    put("chat_id", telegramChatId)
                    put("text", chunk)
                    if (!parseMode.isNullOrBlank()) put("parse_mode", parseMode)
                }
                val body = Json.mapper.writeValueAsString(payload)
                val req  = HttpPost(telegramSendMessageUrl()).apply {
                    entity = StringEntity(body, ContentType.APPLICATION_JSON)
                }
                http.execute(req) { resp ->
                    if (resp.code !in 200..204) {
                        log.warn("Telegram post → ${resp.code}: ${EntityUtils.toString(resp.entity, Charsets.UTF_8)}")
                    }
                }
            } catch (e: Exception) {
                log.warn("Telegram post failed: ${e.message}")
            }
        }
    }

    private fun chunkLines(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks  = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.lines()) {
            val candidate = if (current.isEmpty()) line else "$current\n$line"
            if (candidate.length > maxLen) {
                if (current.isNotEmpty()) chunks += current.toString()
                current.clear().append(line)
            } else {
                current.clear().append(candidate)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
