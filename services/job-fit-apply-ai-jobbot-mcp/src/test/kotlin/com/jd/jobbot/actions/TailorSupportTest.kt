package com.jd.jobbot.actions

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.bridge.TailorRequester
import com.jd.jobbot.jobs.JobIdentity
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.FakeBridge
import com.jd.jobbot.support.FakeHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Tailor button: an offered job's tap asks the bridge to queue its resume build. */
class TailorSupportTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val user = "8679792351"
    private val bridge = FakeHttp()
    @AfterTest fun stop() = bridge.close()

    private class StubLookup(private val events: Map<Long, JsonObject>) : JobLookup(BridgeReadClient("http://unused")) {
        override fun event(seq: Long): JsonObject? = events[seq]
    }

    private val offer = FakeBridge.event(7700, fit = 68, action = "OFFER", skipReason = "Fit 68 is below the auto-tailor score (75)")
    private val store = ActionStore(":memory:", clock)

    private fun service(events: Map<Long, JsonObject> = mapOf(7700L to offer), handled: Set<String> = setOf("apply", "tailor")) = TapService(
        StubLookup(events), store,
        mapOf("tailor" to TailorSupport(TailorRequester(bridge.url), handled).tailor),
        setOf(user), handled, dryRun = false, cardTtl = Duration.ofDays(7), clock = clock,
    )

    private fun tap(seq: Long = 7700) = TapRequest("tailor", seq, user, user, messageId = 99, messageDate = now.minusSeconds(60).epochSecond)

    @Test
    fun `a tap on an offer queues the tailor run, records it and drops the button`() {
        bridge.on("POST /api/jobs/job-7700/tailor") { FakeHttp.Resp(202, """{"job_id":"tailor-1","status":"pending","deduped":false}""") }

        val r = service().tap(tap())

        assertEquals(Outcomes.DONE, r.outcome)
        assertEquals("Tailoring #J7700. The resume card follows in a few minutes.", r.toast)
        assertEquals(emptyList(), r.actionRow, "an offer has no other verbs, so the row goes away")
        assertEquals(listOf("POST /api/jobs/job-7700/tailor"), bridge.requests.map { "${it.method} ${it.path}" })
        val action = store.latest("tailor", JobIdentity.of(offer))!!
        assertEquals(Outcomes.DONE, action.status)
        assertEquals("tailor-1", Json.parseToJsonElement(action.details!!).jsonObject["tailor_job_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a second tap says it is already on its way, or done`() {
        bridge.on("POST /api/jobs/") { FakeHttp.Resp(200, """{"job_id":"tailor-1","status":"pending","deduped":true}""") }
        assertEquals("Already tailoring. The resume card is on its way.", service().tap(tap()).toast)
        bridge.on("POST /api/jobs/") { FakeHttp.Resp(200, """{"job_id":"tailor-1","status":"done","deduped":true}""") }
        val r = service().tap(tap())
        assertEquals(Outcomes.ALREADY, r.outcome)
        assertEquals("Already tailored. Look for its resume card.", r.toast)
    }

    @Test
    fun `the bridge's refusal is passed on`() {
        bridge.on("POST /api/jobs/") { FakeHttp.Resp(409, """{"detail":"Job job-7700 was not offered for tailoring"}""") }
        val r = service().tap(tap())
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals("Can't tailor #J7700: Job job-7700 was not offered for tailoring", r.toast)
        assertNull(store.latest("tailor", JobIdentity.of(offer)))
    }

    @Test
    fun `a 202 without a job id is an error, not a recorded tailor run`() {
        bridge.on("POST /api/jobs/job-7700/tailor") { FakeHttp.Resp(202, "{}") }
        val r = service().tap(tap())
        assertEquals(Outcomes.ERROR, r.outcome)
        assertNull(store.latest("tailor", JobIdentity.of(offer)))
    }

    @Test
    fun `a job that was not offered never reaches the bridge`() {
        val tailored = FakeBridge.event(7701)   // TAILOR
        val r = service(events = mapOf(7701L to tailored)).tap(tap(7701))
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertTrue(bridge.requests.isEmpty())
    }

    @Test
    fun `tailor is off unless JOBBOT_ACTIONS lists it`() {
        val r = service(handled = setOf("apply")).tap(tap())
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertTrue(bridge.requests.isEmpty())
    }

    // ── TailorRequester ─────────────────────────────────────────────────────

    @Test
    fun `the requester maps the bridge's answers and refuses anything but a job id`() {
        val req = TailorRequester(bridge.url)
        bridge.on("POST /api/jobs/") { FakeHttp.Resp(202, """{"job_id":"t-2"}""") }
        assertEquals(TailorRequester.Outcome.Queued("t-2"), req.request("job-1"))
        bridge.on("POST /api/jobs/") { FakeHttp.Resp(404, """{"detail":"Job not found"}""") }
        assertEquals(TailorRequester.Outcome.Refused("Job not found"), req.request("job-1"))
        bridge.on("POST /api/jobs/") { FakeHttp.Resp(500, "boom") }
        assertEquals(500, assertFailsWith<BridgeReadClient.BridgeException> { req.request("job-1") }.status)
        // A success that names no request is a broken bridge, not a tailor run.
        bridge.on("POST /api/jobs/") { FakeHttp.Resp(202, "{}") }
        assertEquals(202, assertFailsWith<BridgeReadClient.BridgeException> { req.request("job-1") }.status)
        bridge.on("POST /api/jobs/") { FakeHttp.Resp(200, """{"job_id":"  ","status":"pending"}""") }
        assertFailsWith<BridgeReadClient.BridgeException> { req.request("job-1") }

        val before = bridge.requests.size
        assertFailsWith<IllegalArgumentException> { req.request("../queue/claim") }
        assertFailsWith<IllegalArgumentException> { req.request("job-1/result") }
        assertEquals(before, bridge.requests.size, "a bad id never becomes a request")
    }
}
