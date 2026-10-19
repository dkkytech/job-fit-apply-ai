package com.jd.watchlist.state

import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunScheduleTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val twiceDaily = RunSchedule(86_400_000L, listOf(LocalTime.of(17, 30), LocalTime.of(5, 30)), la)

    private fun at(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), la).toInstant().toEpochMilli()

    @Test
    fun `interval mode - due once the interval has passed`() {
        val daily = RunSchedule(86_400_000L)
        val last = at("2026-10-13T19:05")
        assertFalse(daily.isDue(last, at("2026-10-14T19:04")))
        assertTrue(daily.isDue(last, at("2026-10-14T19:05")))
        assertEquals(at("2026-10-14T19:05"), daily.nextDue(last, at("2026-10-14T08:00")))
        assertTrue(daily.isDue(null, at("2026-10-14T08:00")), "never run = due")
        assertNull(daily.nextDue(null, at("2026-10-14T08:00")))
    }

    @Test
    fun `fixed times - due when a slot has passed since the last sweep`() {
        val morning = at("2026-10-14T05:31")
        assertFalse(twiceDaily.isDue(morning, at("2026-10-14T17:29")))
        assertEquals(at("2026-10-14T17:30"), twiceDaily.nextDue(morning, at("2026-10-14T12:00")))
        assertTrue(twiceDaily.isDue(morning, at("2026-10-14T17:30")))
        assertNull(twiceDaily.nextDue(morning, at("2026-10-14T17:30")))
    }

    @Test
    fun `after the evening sweep the next one is tomorrow morning`() {
        assertEquals(at("2026-10-15T05:30"), twiceDaily.nextDue(at("2026-10-14T17:31"), at("2026-10-14T23:59")))
    }

    @Test
    fun `a slot missed while the host slept is caught up once, not once per slot`() {
        val lastEvening = at("2026-10-13T17:31")
        assertTrue(twiceDaily.isDue(lastEvening, at("2026-10-14T09:00")), "05:30 passed while asleep")
        val caughtUp = at("2026-10-14T09:00")
        assertFalse(twiceDaily.isDue(caughtUp, at("2026-10-14T09:01")))
        assertEquals(at("2026-10-14T17:30"), twiceDaily.nextDue(caughtUp, at("2026-10-14T09:01")))
    }

    @Test
    fun `slots are local times in the schedule's zone, across a DST change`() {
        // 05:30 in Los Angeles is 12:30 UTC in October (PDT) and 13:30 UTC in December (PST).
        assertEquals(java.time.Instant.parse("2026-10-15T12:30:00Z").toEpochMilli(), twiceDaily.nextDue(at("2026-10-14T17:31"), at("2026-10-14T20:00")))
        assertEquals(java.time.Instant.parse("2026-12-15T13:30:00Z").toEpochMilli(), twiceDaily.nextDue(at("2026-12-14T17:31"), at("2026-12-14T20:00")))
        // The night clocks fall back (Nov 1): the next morning slot is still 05:30 local.
        assertEquals(at("2026-11-01T05:30"), twiceDaily.nextDue(at("2026-10-31T17:31"), at("2026-11-01T01:30")))
        assertEquals(java.time.Instant.parse("2026-10-15T12:30:00Z").toEpochMilli(),
            RunSchedule(1, listOf(LocalTime.of(5, 30)), la).nextDue(at("2026-10-14T05:31"), at("2026-10-14T20:00")))
    }
}
