package com.jd.notifier

import com.jd.notifier.bridge.NotifierBridgeClient
import com.jd.notifier.config.Config
import com.jd.notifier.health.Heartbeat
import com.jd.notifier.notify.DeliveryResult
import com.jd.notifier.notify.Notifier
import com.jd.notifier.notify.NotifyOutcome
import com.jd.notifier.state.Cursor

/**
 * Drains the bridge's completed-event stream and notifies. Tracks a client-side cursor; on cold
 * start it seeds the cursor at the bridge head so it doesn't re-notify job history.
 *
 * **The cursor moves on acknowledgement, not on attempt.** It used to advance after every event
 * unconditionally — and since [NotificationClient] swallowed non-2xx responses, a Discord 500
 * silently dropped that notification for good. Now a retryable failure holds the cursor at the
 * failed event and stops the drain, so the events behind it keep their order, and the next pass
 * retries after a bounded exponential backoff.
 *
 * At-least-once, deliberately: a crash mid-batch, or an in-flight retry, re-sends a few messages
 * on restart (harmless for chat pings). Channels that already landed for the *pending* event are
 * remembered in memory so an in-process retry does not duplicate them; that memory is lost on
 * restart, which is the at-least-once part.
 */
class NotifierLoop(
    private val bridge: NotifierBridgeClient,
    private val notifier: Notifier,
    private val cursor: Cursor,
    private val heartbeat: Heartbeat? = null,
    private val maxAttempts: Int = Config.MAX_DELIVERY_ATTEMPTS,
    private val backoffBaseMs: Long = Config.DELIVERY_BACKOFF_BASE_MS,
    private val backoffCapMs: Long = Config.DELIVERY_BACKOFF_CAP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The event the cursor is parked on, if a retryable failure is outstanding. */
    private var pendingSeq: Long? = null
    private var pendingOutcome: NotifyOutcome = NotifyOutcome()
    private var attempts: Int = 0
    private var nextAttemptAt: Long = 0

    /** Exponential, capped — and evaluated between drains rather than slept through. */
    private fun backoffMs(attempt: Int): Long =
        minOf(backoffCapMs, backoffBaseMs * (1L shl (attempt - 1).coerceIn(0, 20)))
    /** Return the current cursor, seeding it at the bridge head on cold start (skip history). */
    fun ensureCursor(): Long {
        cursor.read()?.let { return it }
        val head = bridge.headSeq()
        cursor.write(head)
        println("[notifier] cold start — seeding cursor at head seq $head (skipping history)")
        return head
    }

    /**
     * One drain pass: page through events > cursor, notifying each and advancing the cursor only
     * past events that were acknowledged. Returns the number of events that actually delivered
     * something.
     */
    fun drainOnce(pageSize: Int = 50): Int {
        var cur = ensureCursor()
        var sent = 0
        if (pendingSeq != null && clock() < nextAttemptAt) return 0   // still backing off
        while (true) {
            val events = bridge.fetchEvents(cur, limit = pageSize)
            if (events.isEmpty()) break
            for (e in events) {
                val carried = if (e.completedSeq == pendingSeq) pendingOutcome else NotifyOutcome()
                val outcome = runCatching { notifier.notify(e, carried.deliveredChannels()).merge(carried) }
                    .onFailure { System.err.println("[notifier] notify failed for ${e.jobId}: ${it.message}") }
                    // A throw from the notifier itself is retryable — same treatment as a 500.
                    .getOrElse { NotifyOutcome(discord = DeliveryResult.RETRYABLE).merge(carried) }

                if (!outcome.acked) {
                    attempts = if (e.completedSeq == pendingSeq) attempts + 1 else 1
                    pendingSeq = e.completedSeq
                    pendingOutcome = outcome
                    if (attempts >= maxAttempts) {
                        // Dead-letter: keep one poisoned event from blocking every later one
                        // forever. Loud, because this is a dropped notification.
                        System.err.println(
                            "[notifier] GIVING UP on ${e.jobId} (seq ${e.completedSeq}) after $attempts attempts " +
                                "— discord=${outcome.discord} telegram=${outcome.telegram}; advancing past it",
                        )
                        clearPending()
                        cur = e.completedSeq
                        cursor.write(cur)
                        continue
                    }
                    nextAttemptAt = clock() + backoffMs(attempts)
                    println(
                        "[notifier] holding cursor at ${e.completedSeq} for ${e.jobId} " +
                            "(attempt $attempts/$maxAttempts, retry in ${backoffMs(attempts)}ms)",
                    )
                    return sent   // stop the drain: later events must not overtake this one
                }

                if (outcome.sentAnything) sent++
                clearPending()
                cur = e.completedSeq
                cursor.write(cur)   // advance only past an acknowledged event
            }
            if (events.size < pageSize) break
        }
        return sent
    }

    private fun clearPending() {
        pendingSeq = null
        pendingOutcome = NotifyOutcome()
        attempts = 0
        nextAttemptAt = 0
    }

    fun runForever(intervalMs: Long = Config.POLL_INTERVAL_MS) {
        while (!Thread.currentThread().isInterrupted) {
            runCatching { drainOnce() }.onFailure { System.err.println("[notifier] drain error: ${it.message}") }
            heartbeat?.beat(System.currentTimeMillis())
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
