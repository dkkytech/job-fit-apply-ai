package com.jd.jobbot.actions

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.FakeBridge
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapServiceTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val user = "8679792351"

    private class StubLookup(private val events: Map<Long, JsonObject>, private val fail: Boolean = false) :
        JobLookup(BridgeReadClient("http://unused")) {
        override fun event(seq: Long): JsonObject? = if (fail) throw java.io.IOException("down") else events[seq]
    }

    private val calls = AtomicInteger()
    private val counting = VerbHandler { ctx, _ -> calls.incrementAndGet(); TapResponse(Outcomes.DONE, toast = "ok ${ctx.ref}") }

    private fun service(
        events: Map<Long, JsonObject> = mapOf(7663L to FakeBridge.event(7663)),
        handled: Set<String> = setOf("apply"),
        dryRun: Boolean = false,
        handlers: Map<String, VerbHandler> = mapOf("apply" to counting),
        lookupFails: Boolean = false,
        store: ActionStore = ActionStore(":memory:", clock),
    ) = TapService(StubLookup(events, lookupFails), store, handlers, setOf(user), handled, dryRun, Duration.ofDays(7), clock)

    private fun tap(verb: String = "apply", seq: Long = 7663, from: String = user, sentAt: Instant? = now.minusSeconds(60)) =
        TapRequest(verb, seq, from, user, messageId = 99, messageDate = sentAt?.epochSecond)

    @Test
    fun `a valid tap reaches the verb handler`() {
        val r = service().tap(tap())
        assertEquals(Outcomes.DONE, r.outcome)
        assertEquals("ok #J7663", r.toast)
        assertEquals(1, calls.get())
    }

    @Test
    fun `unknown verbs are refused before anything else`() {
        assertEquals(Outcomes.REFUSED, service().tap(tap(verb = "delete")).outcome)
        assertEquals(0, calls.get())
    }

    @Test
    fun `taps from users off the allowlist are refused`() {
        val r = service().tap(tap(from = "123"))
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals("Not authorized.", r.toast)
        assertEquals(0, calls.get())
    }

    @Test
    fun `an empty allowlist refuses everyone`() {
        val s = TapService(StubLookup(emptyMap()), ActionStore(":memory:"), emptyMap(), emptySet(), setOf("apply"), false, Duration.ofDays(7), clock)
        assertEquals(Outcomes.REFUSED, s.tap(tap()).outcome)
    }

    @Test
    fun `cards older than the TTL expire and lose their action row`() {
        val r = service().tap(tap(sentAt = now.minus(Duration.ofDays(7)).minusSeconds(1)))
        assertEquals(Outcomes.EXPIRED, r.outcome)
        assertEquals(emptyList(), r.actionRow)
        assertEquals(0, calls.get())
    }

    @Test
    fun `a card exactly at the TTL still works`() {
        assertEquals(Outcomes.DONE, service().tap(tap(sentAt = now.minus(Duration.ofDays(7)))).outcome)
    }

    @Test
    fun `verbs this deployment does not handle are refused`() {
        val r = service(events = mapOf(7663L to FakeBridge.event(7663, messageId = "m", recruiter = true, terminalLabel = "Recruiter_Response_Required"))).tap(tap(verb = "reply"))
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals("Reply isn't available yet.", r.toast)
    }

    @Test
    fun `undo is handled exactly when archive is`() {
        val event = FakeBridge.event(7663, messageId = "m", terminalLabel = "Recruiter_Response_Required")
        assertEquals(Outcomes.REFUSED, service(events = mapOf(7663L to event)).tap(tap(verb = "undo")).outcome)
        val undo = VerbHandler { _, _ -> TapResponse(Outcomes.DONE) }
        val r = service(events = mapOf(7663L to event), handled = setOf("archive"), handlers = mapOf("undo" to undo)).tap(tap(verb = "undo"))
        assertEquals(Outcomes.DONE, r.outcome)
    }

    @Test
    fun `an unknown job is refused`() {
        assertEquals("Unknown job #J1.", service().tap(tap(seq = 1)).toast)
    }

    @Test
    fun `Apply on an older card for a skipped job says why and does nothing`() {
        val gated = FakeBridge.event(7708, action = "SKIP", skipReason = "Posted pay (max \$85K) is below the \$145K target")
        val r = service(events = mapOf(7708L to gated)).tap(tap(seq = 7708))
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals("Not applying: this job was skipped (Posted pay (max \$85K) is below the \$145K target), so there's no tailored resume.", r.toast)
        assertEquals(0, calls.get())
    }

    @Test
    fun `the refusal fits Telegram's 200-character toast`() {
        val gated = FakeBridge.event(7708, action = "SKIP", skipReason = "x".repeat(400))
        assertTrue(service(events = mapOf(7708L to gated)).tap(tap(seq = 7708)).toast!!.length <= 200)
    }

    @Test
    fun `an ineligible job is refused (the dead-button guard on the receiving side)`() {
        val r = service(events = mapOf(7663L to FakeBridge.event(7663, jobUrl = null))).tap(tap())
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals("Apply isn't available for this job.", r.toast)
        assertEquals(0, calls.get())
    }

    @Test
    fun `bridge failures are reported and logged, not thrown`() {
        val store = ActionStore(":memory:", clock)
        val r = service(lookupFails = true, store = store).tap(tap())
        assertEquals(Outcomes.ERROR, r.outcome)
        assertTrue(store.recentErrors().single().message.contains("bridge lookup failed"))
    }

    @Test
    fun `dry run validates and records, but never runs the handler`() {
        val store = ActionStore(":memory:", clock)
        val r = service(dryRun = true, store = store).tap(tap())
        assertEquals(Outcomes.DRY_RUN, r.outcome)
        assertEquals("[dry run] Would apply #J7663.", r.toast)
        assertEquals(0, calls.get())
        assertEquals(Outcomes.DRY_RUN, store.recent().single().status)
    }

    @Test
    fun `dry run still refuses what live mode would refuse`() {
        assertEquals(Outcomes.REFUSED, service(dryRun = true).tap(tap(from = "123")).outcome)
    }

    @Test
    fun `a handler exception becomes an error reply and an error record`() {
        val store = ActionStore(":memory:", clock)
        val boom = VerbHandler { _, _ -> error("kaboom") }
        val r = service(handlers = mapOf("apply" to boom), store = store).tap(tap())
        assertEquals(Outcomes.ERROR, r.outcome)
        assertEquals("kaboom", store.recentErrors().single().message)
    }

    @Test
    fun `apply replies exactly Not implemented yet and records the tap`() {
        val store = ActionStore(":memory:", clock)
        val r = service(handlers = mapOf("apply" to ApplyNotImplemented), store = store).tap(tap())
        assertEquals(Outcomes.NOT_IMPLEMENTED, r.outcome)
        assertEquals("Not implemented yet", r.reply)
        assertNull(r.actionRow, "Apply must stay on the card")
        assertEquals("url:acme.co/jobs/7663", store.recent().single().jobKey)
    }

    @Test
    fun `a tap without the card's send time is refused, not exempt from expiry`() {
        val r = service().tap(tap(sentAt = null))
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals(0, calls.get())
    }
}
