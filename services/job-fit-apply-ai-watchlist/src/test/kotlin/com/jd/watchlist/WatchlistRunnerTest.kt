package com.jd.watchlist

import com.jd.watchlist.adapters.FeedAdapter
import com.jd.watchlist.bridge.SubmitJobRequest
import com.jd.watchlist.bridge.SubmitJobResponse
import com.jd.watchlist.bridge.WatchlistBridgeClient
import com.jd.watchlist.config.Config
import com.jd.watchlist.filter.PostingFilter
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import com.jd.watchlist.state.HealthReport
import com.jd.watchlist.state.RunSchedule
import com.jd.watchlist.state.RunState
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.jd.watchlist.adapters.PostingDetail

class WatchlistRunnerTest {
    @TempDir lateinit var dir: Path

    private val longJd = "Own the test strategy for our platform. ".repeat(10)
    private val day = 86_400_000L

    private class RecordingBridge(private val dedupedKeys: Set<String> = emptySet()) : WatchlistBridgeClient(baseUrl = "http://unused") {
        val sent = mutableListOf<SubmitJobRequest>()
        override fun submit(req: SubmitJobRequest): SubmitJobResponse {
            sent += req
            return SubmitJobResponse(jobId = "j${sent.size}", status = "PENDING", deduped = req.idempotencyKey in dedupedKeys)
        }
    }

    private class StubAdapter(private val postings: List<Posting>, private val fail: Boolean = false) : FeedAdapter {
        var described = 0
        override fun list(employer: Employer): List<Posting> = if (fail) error("HTTP 404") else postings
        override fun describe(employer: Employer, posting: Posting): String? { described++; return posting.description }
    }

    private fun posting(id: String, title: String, location: String = "Seattle, WA", jd: String? = longJd) =
        Posting(company = "Acme", externalId = id, title = title, location = location, url = "https://acme.example/$id", description = jd)

    private fun runner(employers: List<Employer>, adapters: Map<String, FeedAdapter>, bridge: WatchlistBridgeClient) = WatchlistRunner(
        employers = employers,
        adapters = adapters,
        filter = PostingFilter(Config.TITLE_INCLUDE, Config.TITLE_EXCLUDE),
        bridge = bridge,
        state = RunState(dir.resolve("state.json")),
        health = HealthReport(dir.resolve("health.json")),
        schedule = RunSchedule(day),
        nextDueFile = dir.resolve("next-due"),
        interEmployerDelayMs = 0,
    )

    @Test
    fun `a list without locations - the posting page's location is filtered before anything is sent`() {
        val pageLocation = mapOf("1" to "Seattle, Washington (Onsite)", "2" to "Auburn Hills, MI (Onsite)", "3" to null)
        val adapter = object : FeedAdapter {
            override fun list(employer: Employer) = listOf(
                posting("1", "API QA Lead", location = ""), posting("2", "Vehicle Quality Engineer", location = ""), posting("3", "QA Manager", location = ""),
            )
            override fun detail(employer: Employer, posting: Posting) = PostingDetail(longJd, pageLocation[posting.externalId])
        }
        val bridge = RecordingBridge()
        val out = runner(listOf(Employer(company = "Experis", ats = "html")), mapOf("html" to adapter), bridge).runOnce(10 * day)

        assertEquals(listOf("watchlist:html:experis:1", "watchlist:html:experis:3"), bridge.sent.map { it.idempotencyKey },
            "out of state on the page → never sent; no location anywhere → sent (the processor's WA gate decides)")
        assertEquals("Seattle, Washington (Onsite)", bridge.sent[0].location, "the page's location travels with it")
        assertEquals(null, bridge.sent[1].location)
        assertEquals(1, assertIs<WatchlistRunner.Ran>(out).health.employers.single().skipped)
    }

    @Test
    fun `pre-filters, describes only survivors, and submits as CAREER_SITE with a stable key`() {
        val adapter = StubAdapter(listOf(
            posting("1", "Senior SDET"),
            posting("2", "Software Engineer"),                 // title filter
            posting("3", "QA Engineer", location = "Austin, TX"), // location filter
            posting("4", "Quality Engineer", jd = "too short"),   // bridge minimum
        ))
        val bridge = RecordingBridge()
        val acme = Employer(company = "Acme", ats = "greenhouse", board = "acme")
        val out = runner(listOf(acme), mapOf("greenhouse" to adapter), bridge).runOnce(10 * day)

        assertEquals(2, adapter.described, "only title+location survivors are described")
        val sent = bridge.sent.single()
        assertEquals("CAREER_SITE", sent.source)
        assertEquals("watchlist:greenhouse:acme:1", sent.idempotencyKey)
        assertEquals("Acme", sent.company)
        val h = assertIs<WatchlistRunner.Ran>(out).health.employers.single()
        assertEquals(listOf(4, 2, 1, 0, 1), listOf(h.listed, h.matched, h.queued, h.deduped, h.skipped))
        assertTrue(Files.readString(dir.resolve("health.json")).contains("\"company\" : \"Acme\""))
    }

    @Test
    fun `one failing feed does not stop the sweep and is reported`() {
        val bridge = RecordingBridge()
        val employers = listOf(Employer(company = "Broken", ats = "lever", board = "x"), Employer(company = "Acme", ats = "greenhouse", board = "acme"))
        val out = runner(employers, mapOf("lever" to StubAdapter(emptyList(), fail = true), "greenhouse" to StubAdapter(listOf(posting("1", "SDET")))), bridge)
            .runOnce(10 * day)
        val health = assertIs<WatchlistRunner.Ran>(out).health
        assertEquals(listOf("Broken"), health.failing)
        assertEquals(1, bridge.sent.size)
        assertEquals(10 * day, RunState(dir.resolve("state.json")).lastRun(), "a partial failure still counts as a run")
    }

    @Test
    fun `an all-failed sweep does not advance the gate, so the next tick retries`() {
        Files.writeString(dir.resolve("next-due"), "1")
        val employers = listOf(Employer(company = "Broken", ats = "lever", board = "x"))
        runner(employers, mapOf("lever" to StubAdapter(emptyList(), fail = true)), RecordingBridge()).runOnce(10 * day)
        assertNull(RunState(dir.resolve("state.json")).lastRun())
        assertTrue(!Files.exists(dir.resolve("next-due")), "no due time: the shell loop falls back to its regular tick")
    }

    @Test
    fun `fixed run-at times replace the interval`() {
        val la = java.time.ZoneId.of("America/Los_Angeles")
        fun at(t: String) = java.time.ZonedDateTime.of(java.time.LocalDateTime.parse(t), la).toInstant().toEpochMilli()
        RunState(dir.resolve("state.json")).recordRun(at("2026-10-13T05:31"))
        val r = WatchlistRunner(
            employers = listOf(Employer(company = "Acme", ats = "greenhouse", board = "acme")),
            adapters = mapOf("greenhouse" to StubAdapter(listOf(posting("1", "SDET")))),
            filter = PostingFilter(Config.TITLE_INCLUDE, Config.TITLE_EXCLUDE),
            bridge = RecordingBridge(),
            state = RunState(dir.resolve("state.json")),
            health = HealthReport(dir.resolve("health.json")),
            schedule = RunSchedule(day, listOf(java.time.LocalTime.of(5, 30), java.time.LocalTime.of(17, 30)), la),
            interEmployerDelayMs = 0,
        )
        assertIs<WatchlistRunner.Skipped>(r.runOnce(at("2026-10-13T17:29")))
        assertIs<WatchlistRunner.Ran>(r.runOnce(at("2026-10-13T17:30")), "12h after the last sweep, not 24h")
    }

    @Test
    fun `tells the shell loop when the next sweep is due, rounded up to the second`() {
        val acme = listOf(Employer(company = "Acme", ats = "greenhouse", board = "acme"))
        val adapters = mapOf("greenhouse" to StubAdapter(listOf(posting("1", "SDET"))))
        runner(acme, adapters, RecordingBridge()).runOnce(10 * day + 1)
        assertEquals(((11 * day + 1 + 999) / 1000).toString(), Files.readString(dir.resolve("next-due")), "after a sweep: one interval on")

        Files.delete(dir.resolve("next-due"))
        assertIs<WatchlistRunner.Skipped>(runner(acme, adapters, RecordingBridge()).runOnce(10 * day + 7_200_000))
        assertEquals(((11 * day + 1 + 999) / 1000).toString(), Files.readString(dir.resolve("next-due")), "a skipped tick rewrites it too")
    }

    @Test
    fun `skips when the last run is inside the interval, and ignores employers without an adapter`() {
        RunState(dir.resolve("state.json")).recordRun(10 * day)
        val bridge = RecordingBridge()
        val r = runner(listOf(Employer(company = "Acme", ats = "greenhouse", board = "acme")), mapOf("greenhouse" to StubAdapter(listOf(posting("1", "SDET")))), bridge)
        assertIs<WatchlistRunner.Skipped>(r.runOnce(10 * day + 3_600_000))

        val out = runner(
            listOf(Employer(company = "Acme", ats = "greenhouse", board = "acme"), Employer(company = "Custom Co", ats = "custom"),
                Employer(company = "Off", ats = "greenhouse", board = "off", enabled = false)),
            mapOf("greenhouse" to StubAdapter(listOf(posting("1", "SDET")))), bridge,
        ).runOnce(12 * day)
        val health = assertIs<WatchlistRunner.Ran>(out).health
        assertEquals(1, health.polled)
        assertEquals(1, health.unsupported)
    }

    @Test
    fun `deduped postings are counted, not re-queued`() {
        val bridge = RecordingBridge(dedupedKeys = setOf("watchlist:greenhouse:acme:1"))
        val out = runner(listOf(Employer(company = "Acme", ats = "greenhouse", board = "acme")), mapOf("greenhouse" to StubAdapter(listOf(posting("1", "SDET")))), bridge)
            .runOnce(10 * day)
        val h = assertIs<WatchlistRunner.Ran>(out).health.employers.single()
        assertEquals(1, h.deduped)
        assertEquals(0, h.queued)
    }
}
