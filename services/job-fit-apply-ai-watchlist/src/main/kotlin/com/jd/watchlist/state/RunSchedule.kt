package com.jd.watchlist.state

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a sweep is due. With [runAt] (local times in [zone], e.g. 05:30 and 17:30) a sweep is due
 * once the latest of those times has passed since the last sweep, so a slot missed while the host
 * slept is caught up once, not once per missed slot. Without it, every [intervalMs] since the last
 * sweep.
 */
class RunSchedule(
    private val intervalMs: Long,
    runAt: List<LocalTime> = emptyList(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val times = runAt.distinct().sorted()

    fun isDue(lastRun: Long?, now: Long): Boolean = when {
        lastRun == null -> true
        times.isEmpty() -> now - lastRun >= intervalMs
        else -> lastRun < latestSlotAtOrBefore(now)
    }

    /** Epoch-millis when the next sweep is due, or null when one is due now. */
    fun nextDue(lastRun: Long?, now: Long): Long? = when {
        lastRun == null || isDue(lastRun, now) -> null
        times.isEmpty() -> lastRun + intervalMs
        else -> nextSlotAfter(now)
    }

    /** For log lines: the slot or interval this schedule follows. */
    fun describe(): String =
        if (times.isEmpty()) "every ${intervalMs / 3_600_000}h" else "at ${times.joinToString()} ($zone)"

    private fun slots(now: Long, days: IntRange): Sequence<Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return days.asSequence().flatMap { d ->
            times.asSequence().map { ZonedDateTime.of(today.plusDays(d.toLong()), it, zone).toInstant().toEpochMilli() }
        }
    }

    private fun latestSlotAtOrBefore(now: Long): Long = slots(now, -1..0).filter { it <= now }.max()

    private fun nextSlotAfter(now: Long): Long = slots(now, 0..1).filter { it > now }.min()
}
