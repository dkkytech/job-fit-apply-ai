package com.jd.notifier.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Config is an object (loaded once at class-init) that layers dotenv → System.getenv → defaults.
 * With no .env file present in this service dir and no relevant env vars exported in the test
 * environment, every property should resolve to its documented default.
 */
@DisplayName("Config (defaults, no .env / env overrides present)")
class ConfigTest {

    @Test
    @DisplayName("JD_BRIDGE_URL defaults to the local bridge")
    fun bridgeUrlDefault() {
        assertEquals("http://127.0.0.1:8765", Config.JD_BRIDGE_URL)
    }

    @Test
    @DisplayName("messaging credentials default to blank (channels silently disabled)")
    fun credentialsDefaultBlank() {
        assertEquals("", Config.DISCORD_BOT_TOKEN)
        assertEquals("", Config.DISCORD_CHANNEL_ID)
        assertEquals("", Config.TELEGRAM_BOT_TOKEN)
        assertEquals("", Config.TELEGRAM_CHAT_ID)
    }

    @Test
    @DisplayName("NOTIFICATION_FIT_THRESHOLD defaults to 50")
    fun fitThresholdDefault() {
        assertEquals(50, Config.NOTIFICATION_FIT_THRESHOLD)
    }

    @Test
    @DisplayName("POLL_INTERVAL_MS defaults to 20000")
    fun pollIntervalDefault() {
        assertEquals(20000L, Config.POLL_INTERVAL_MS)
    }

    @Test
    @DisplayName("CURSOR_FILE defaults to the mounted state path")
    fun cursorFileDefault() {
        assertEquals("/state/notifier-cursor.txt", Config.CURSOR_FILE)
    }

    @Test
    @DisplayName("HEARTBEAT_FILE defaults to the tmp liveness marker")
    fun heartbeatFileDefault() {
        assertEquals("/tmp/notifier-heartbeat", Config.HEARTBEAT_FILE)
    }

    @Test
    @DisplayName("HEALTH_MAX_AGE_MS defaults to 120000")
    fun healthMaxAgeDefault() {
        assertEquals(120000L, Config.HEALTH_MAX_AGE_MS)
    }

    @Test
    @DisplayName("numeric configs parse as positive longs/ints (sanity)")
    fun numericConfigsArePositive() {
        assertTrue(Config.NOTIFICATION_FIT_THRESHOLD > 0)
        assertTrue(Config.POLL_INTERVAL_MS > 0)
        assertTrue(Config.HEALTH_MAX_AGE_MS > 0)
    }

    @Test
    @DisplayName("namespaced notifier credential wins over the legacy value")
    fun namespacedCredentialWins() {
        assertEquals("notifier-bot", resolveCredential("notifier-bot", "legacy-bot"))
    }

    @Test
    @DisplayName("blank notifier credential falls back to the legacy value")
    fun blankNamespacedCredentialFallsBack() {
        assertEquals("legacy-bot", resolveCredential("   ", "legacy-bot"))
    }

    @Test
    @DisplayName("missing credentials resolve to blank")
    fun missingCredentialsResolveBlank() {
        assertEquals("", resolveCredential(null, null))
    }
}
