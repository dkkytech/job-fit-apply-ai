package com.jd.pipeline.utils

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("ExpiringHostSet")
class ExpiringHostSetTest {
    private var now = 0L
    private val minute = 60_000L * 1_000_000
    private val set = ExpiringHostSet(ttlMs = 30 * 60_000L, nanoTime = { now })

    @Test
    @DisplayName("an entry lapses after the TTL")
    fun lapses() {
        set.add("www.dice.com")
        now += 29 * minute
        assertTrue("www.dice.com" in set)
        now += 2 * minute
        assertFalse("www.dice.com" in set)
        assertTrue(set.isEmpty())
    }

    @Test
    @DisplayName("re-adding restarts the timer, and clear empties it")
    fun reAddRestartsTimer() {
        set.add("www.dice.com")
        now += 20 * minute
        set.add("www.dice.com")
        now += 20 * minute
        assertTrue("www.dice.com" in set, "second add should restart the 30-min window")
        set.clear()
        assertTrue(set.isEmpty())
    }
}
