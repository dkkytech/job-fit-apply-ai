package com.jd.notifier.notify

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the configured-flag logic and no-op-when-unconfigured paths only — never constructs this
 * client with real credentials and calls a post method, since that would attempt a live HTTP call
 * to Discord/Telegram.
 */
@DisplayName("NotificationClient (configured flags + no-op guards)")
class NotificationClientTest {

    @Test
    @DisplayName("discordConfigured is false when token and channel are both blank")
    fun discordUnconfiguredWhenBothBlank() {
        val c = NotificationClient(discordToken = "", discordChannelId = "", telegramToken = "", telegramChatId = "")
        assertFalse(c.discordConfigured)
    }

    @Test
    @DisplayName("discordConfigured is false when only the token is set")
    fun discordUnconfiguredWhenChannelBlank() {
        val c = NotificationClient(discordToken = "tok", discordChannelId = "", telegramToken = "", telegramChatId = "")
        assertFalse(c.discordConfigured)
    }

    @Test
    @DisplayName("discordConfigured is false when only the channel is set")
    fun discordUnconfiguredWhenTokenBlank() {
        val c = NotificationClient(discordToken = "", discordChannelId = "chan", telegramToken = "", telegramChatId = "")
        assertFalse(c.discordConfigured)
    }

    @Test
    @DisplayName("discordConfigured is true when both token and channel are set")
    fun discordConfiguredWhenBothSet() {
        val c = NotificationClient(discordToken = "tok", discordChannelId = "chan", telegramToken = "", telegramChatId = "")
        assertTrue(c.discordConfigured)
    }

    @Test
    @DisplayName("telegramConfigured is false when token and chatId are both blank")
    fun telegramUnconfiguredWhenBothBlank() {
        val c = NotificationClient(discordToken = "", discordChannelId = "", telegramToken = "", telegramChatId = "")
        assertFalse(c.telegramConfigured)
    }

    @Test
    @DisplayName("telegramConfigured is false when only the token is set")
    fun telegramUnconfiguredWhenChatIdBlank() {
        val c = NotificationClient(discordToken = "", discordChannelId = "", telegramToken = "tok", telegramChatId = "")
        assertFalse(c.telegramConfigured)
    }

    @Test
    @DisplayName("telegramConfigured is false when only chatId is set")
    fun telegramUnconfiguredWhenTokenBlank() {
        val c = NotificationClient(discordToken = "", discordChannelId = "", telegramToken = "", telegramChatId = "chat")
        assertFalse(c.telegramConfigured)
    }

    @Test
    @DisplayName("telegramConfigured is true when both token and chatId are set")
    fun telegramConfiguredWhenBothSet() {
        val c = NotificationClient(discordToken = "", discordChannelId = "", telegramToken = "tok", telegramChatId = "chat")
        assertTrue(c.telegramConfigured)
    }

    @Test
    @DisplayName("postDiscord is a safe no-op when Discord is unconfigured (no network attempted)")
    fun postDiscordNoOpWhenUnconfigured() {
        val c = NotificationClient(discordToken = "", discordChannelId = "", telegramToken = "", telegramChatId = "")
        // Should return immediately without throwing or attempting any HTTP call.
        c.postDiscord("hello")
    }

    @Test
    @DisplayName("postTelegramHtml is a safe no-op when Telegram is unconfigured (no network attempted)")
    fun postTelegramNoOpWhenUnconfigured() {
        val c = NotificationClient(discordToken = "", discordChannelId = "", telegramToken = "", telegramChatId = "")
        // Should return immediately without throwing or attempting any HTTP call.
        c.postTelegramHtml("hello")
    }

    @Test
    @DisplayName("blank (whitespace-only) credentials also count as unconfigured")
    fun blankWhitespaceCountsAsUnconfigured() {
        val c = NotificationClient(discordToken = "   ", discordChannelId = "  ", telegramToken = " ", telegramChatId = "  ")
        assertFalse(c.discordConfigured)
        assertFalse(c.telegramConfigured)
    }

    @Test
    @DisplayName("default API bases produce the real Discord/Telegram URLs")
    fun defaultBasesProduceRealUrls() {
        val c = NotificationClient(discordToken = "tok", discordChannelId = "chan", telegramToken = "tg", telegramChatId = "chat")
        assertEquals("https://discord.com/api/v10/channels/chan/messages", c.discordMessagesUrl())
        assertEquals("https://api.telegram.org/bottg/sendMessage", c.telegramSendMessageUrl())
    }

    @Test
    @DisplayName("overridden API bases (e.g. an e2e sink) produce the expected URLs, trailing slash tolerated")
    fun overriddenBasesProduceSinkUrls() {
        val c = NotificationClient(
            discordToken = "tok", discordChannelId = "chan", telegramToken = "tg", telegramChatId = "chat",
            discordApiBase = "http://host.docker.internal:18099/",
            telegramApiBase = "http://host.docker.internal:18099",
        )
        assertEquals("http://host.docker.internal:18099/api/v10/channels/chan/messages", c.discordMessagesUrl())
        assertEquals("http://host.docker.internal:18099/bottg/sendMessage", c.telegramSendMessageUrl())
    }
}
