package com.jd.watchlist.config

import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun `malformed numbers fall back to the default instead of crashing startup`() {
        assertEquals(86_400_000L, Config.parseLong("WATCHLIST_INTERVAL_MS", "abc", 86_400_000L))
        assertEquals(750L, Config.parseLong("WATCHLIST_INTER_EMPLOYER_DELAY_MS", "", 750L))
        assertEquals(3_600_000L, Config.parseLong("WATCHLIST_INTERVAL_MS", " 3600000 ", 86_400_000L))
    }

    @Test
    fun `run-at times parse leniently and bad entries are dropped`() {
        assertEquals(listOf(LocalTime.of(5, 30), LocalTime.of(17, 30)), Config.parseRunAt("05:30, 17:30"))
        assertEquals(listOf(LocalTime.of(5, 45)), Config.parseRunAt("5:45,25:00,noon,"))
        assertEquals(emptyList(), Config.parseRunAt(""))
    }

    @Test
    fun `a bad time zone falls back to the JVM default`() {
        assertEquals(ZoneId.of("America/Los_Angeles"), Config.parseZone(" America/Los_Angeles "))
        assertEquals(ZoneId.systemDefault(), Config.parseZone("Mars/Olympus_Mons"))
        assertEquals(ZoneId.systemDefault(), Config.parseZone(""))
    }
}
