package com.jd.notifier.notify

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.notifier.config.Config
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.entity.StringEntity
import org.slf4j.LoggerFactory

/**
 * The outcome of one channel's delivery. Before this existed the client logged a non-2xx and
 * returned normally, so a refused post was indistinguishable from a delivered one: the loop
 * advanced its cursor over the event and the notification was lost for good.
 */
enum class DeliveryResult {
    /** Accepted by the platform. */
    DELIVERED,

    /** 5xx, 429, or a transport failure — worth another attempt. */
    RETRYABLE,

    /** A 4xx that says *this message* will never be accepted (bad token, unknown channel).
     *  Retrying is pointless and would block every later event behind it. */
    PERMANENT,

    /** The channel has no credentials configured — nothing was attempted. */
    SKIPPED,
}

/**
 * HTTP transport for Discord + Telegram (moved from the pipeline). No-op when the relevant
 * credentials are blank. Chunks on line boundaries within each platform's char limit.
 *
 * Chunked messages are all-or-nothing from the caller's point of view: the channel's result is
 * the worst of its chunks, so a failure mid-message re-sends the whole message on retry. That is
 * at-least-once for the earlier chunks, and preferable to silently dropping the tail.
 */
open class NotificationClient(
    private val discordToken: String     = Config.DISCORD_BOT_TOKEN,
    private val discordChannelId: String = Config.DISCORD_CHANNEL_ID,
    private val telegramToken: String    = Config.TELEGRAM_BOT_TOKEN,
    private val telegramChatId: String   = Config.TELEGRAM_CHAT_ID,
    private val discordApiBase: String   = Config.DISCORD_API_BASE,
    private val telegramApiBase: String  = Config.TELEGRAM_API_BASE,
) {
    private val log    = LoggerFactory.getLogger(NotificationClient::class.java)
    private val http   = HttpClients.createDefault()
    private val mapper = ObjectMapper()

    open val discordConfigured  get() = discordToken.isNotBlank()  && discordChannelId.isNotBlank()
    open val telegramConfigured get() = telegramToken.isNotBlank() && telegramChatId.isNotBlank()

    internal fun discordMessagesUrl() =
        "${discordApiBase.trimEnd('/')}/api/v10/channels/$discordChannelId/messages"

    internal fun telegramSendMessageUrl() =
        "${telegramApiBase.trimEnd('/')}/bot$telegramToken/sendMessage"

    open fun postDiscord(text: String): DeliveryResult {
        if (!discordConfigured) return DeliveryResult.SKIPPED
        return chunkLines(text, 2000).map { chunk ->
            val body = mapper.writeValueAsString(mapOf("content" to chunk))
            send("Discord", discordMessagesUrl(), body) {
                addHeader("Authorization", "Bot $discordToken")
                addHeader("User-Agent", "DiscordBot (https://github.com/openclaw, 1.0)")
            }
        }.worst()
    }

    open fun postTelegramHtml(text: String): DeliveryResult {
        if (!telegramConfigured) return DeliveryResult.SKIPPED
        return chunkLines(text, 4096).map { chunk ->
            val body = mapper.writeValueAsString(
                mapOf("chat_id" to telegramChatId, "text" to chunk, "parse_mode" to "HTML")
            )
            send("Telegram", telegramSendMessageUrl(), body) {}
        }.worst()
    }

    /** One POST, classified. A thrown transport failure is retryable, not fatal to the loop. */
    private fun send(channel: String, url: String, body: String, headers: HttpPost.() -> Unit): DeliveryResult =
        try {
            val req = HttpPost(url).apply {
                entity = StringEntity(body, ContentType.APPLICATION_JSON)
                headers()
            }
            http.execute(req) { resp ->
                when {
                    resp.code in 200..204 -> DeliveryResult.DELIVERED
                    resp.code == 429 || resp.code >= 500 -> {
                        log.warn("$channel post → ${resp.code} (retryable): ${EntityUtils.toString(resp.entity, Charsets.UTF_8)}")
                        DeliveryResult.RETRYABLE
                    }
                    else -> {
                        log.error("$channel post → ${resp.code} (permanent): ${EntityUtils.toString(resp.entity, Charsets.UTF_8)}")
                        DeliveryResult.PERMANENT
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("$channel post failed (retryable): ${e.message}")
            DeliveryResult.RETRYABLE
        }

    /** A chunked message is only as delivered as its worst chunk. */
    private fun List<DeliveryResult>.worst(): DeliveryResult = when {
        isEmpty() -> DeliveryResult.DELIVERED
        contains(DeliveryResult.RETRYABLE) -> DeliveryResult.RETRYABLE
        contains(DeliveryResult.PERMANENT) -> DeliveryResult.PERMANENT
        else -> DeliveryResult.DELIVERED
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
