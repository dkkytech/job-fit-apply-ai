package com.jd.watchlist.alert

import com.jd.watchlist.WatchlistRunner
import com.jd.watchlist.adapters.FeedAdapter
import com.jd.watchlist.bridge.SubmitJobRequest
import com.jd.watchlist.bridge.SubmitJobResponse
import com.jd.watchlist.bridge.WatchlistBridgeClient
import com.jd.watchlist.config.Config
import com.jd.watchlist.filter.PostingFilter
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import com.jd.watchlist.state.HealthReport
import com.jd.watchlist.state.RunState
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaleFeedAlertTest {
    @TempDir lateinit var dir: Path
    private val day = 86_400_000L

    private class Switchable(var fail: Boolean = false, var empty: Boolean = false) : FeedAdapter {
        override fun list(employer: Employer): List<Posting> = when {
            fail -> error("HTTP 404 from https://boards.example/${employer.board}")
            empty -> emptyList()
            else -> listOf(Posting(employer.company, "1", "Accountant", "Seattle, WA", "https://x/1", "x".repeat(200)))
        }
    }

    private val bridge = object : WatchlistBridgeClient(baseUrl = "http://unused") {
        override fun submit(req: SubmitJobRequest) = SubmitJobResponse("j", "PENDING")
    }

    private fun runner(adapter: FeedAdapter, alerts: MutableList<String>, employers: List<Employer> = listOf(Employer(company = "Acme", ats = "greenhouse", board = "acme"))) =
        WatchlistRunner(
            employers = employers, adapters = mapOf("greenhouse" to adapter),
            filter = PostingFilter(Config.TITLE_INCLUDE, Config.TITLE_EXCLUDE), bridge = bridge,
            state = RunState(dir.resolve("state.json")), health = HealthReport(dir.resolve("health.json")),
            schedule = com.jd.watchlist.state.RunSchedule(day), interEmployerDelayMs = 0, alerts = AlertSink { alerts += it }, alertAfterRuns = 2,
        )

    @Test
    fun `alerts once when a feed reaches two failing runs, never again until it recovers`() {
        val adapter = Switchable(fail = true)
        val alerts = mutableListOf<String>()
        runner(adapter, alerts).runOnce(1 * day)
        assertEquals(0, alerts.size, "one bad run is noise")
        runner(adapter, alerts).runOnce(2 * day)
        assertEquals(1, alerts.size)
        assertTrue("Acme (greenhouse): HTTP 404" in alerts.single(), alerts.single())
        runner(adapter, alerts).runOnce(3 * day)
        assertEquals(1, alerts.size, "still failing: doctor.sh shows it, no repeat ping")
        assertEquals(3, HealthReport(dir.resolve("health.json")).previousSuspectRuns()["Acme"])

        adapter.fail = false
        runner(adapter, alerts).runOnce(4 * day)
        assertEquals(0, HealthReport(dir.resolve("health.json")).previousSuspectRuns()["Acme"], "recovery resets the streak")
        adapter.empty = true
        runner(adapter, alerts).runOnce(5 * day)
        runner(adapter, alerts).runOnce(6 * day)
        assertEquals(2, alerts.size, "an empty feed counts too, and a new streak alerts again")
        assertTrue("listed 0 jobs" in alerts.last())
    }

    @Test
    fun `an employer that may be empty is not suspect for listing nothing - but still is for failing`() {
        val quiet = Employer(company = "HCLTech", ats = "greenhouse", board = "hcl", mayBeEmpty = true)
        val adapter = Switchable(empty = true)
        val alerts = mutableListOf<String>()
        repeat(3) { runner(adapter, alerts, listOf(quiet)).runOnce((it + 1) * day) }
        assertEquals(0, alerts.size, "zero openings is its normal state")
        val last = HealthReport(dir.resolve("health.json"))
        assertEquals(0, last.previousSuspectRuns()["HCLTech"])

        adapter.empty = false; adapter.fail = true
        repeat(2) { runner(adapter, alerts, listOf(quiet)).runOnce((it + 4) * day) }
        assertEquals(1, alerts.size, "a broken feed is still a broken feed")
    }

    @Test
    fun `a mass failure is summarised, not one line per employer`() {
        val employers = (1..20).map { Employer(company = "Co$it", ats = "greenhouse", board = "co$it") }
        val alerts = mutableListOf<String>()
        repeat(2) { runner(Switchable(fail = true), alerts, employers).runOnce((it + 1) * day) }
        val msg = alerts.single()
        assertTrue(msg.startsWith("⚠️ Watchlist: 20 employer feed(s)"), msg)
        assertTrue("…and 5 more" in msg)
        assertEquals(15, msg.lines().count { it.startsWith("• ") })
    }

    @Test
    fun `no previous health file means no prior streaks`() {
        assertEquals(emptyMap(), HealthReport(dir.resolve("missing.json")).previousSuspectRuns())
    }
}

class ChatAlertSinkTest {
    @Test
    fun `posts to Telegram and Discord with the configured channels, and is a no-op when unconfigured`() {
        val seen = mutableListOf<Triple<String, String?, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += Triple(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readAllBytes().decodeToString())
                ex.sendResponseHeaders(if ("/bot" in ex.requestURI.path) 200 else 500, -1); ex.close()
            }
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            ChatAlertSink("tok", "42", "dtok", "chan", telegramApiBase = base, discordApiBase = base).send("⚠️ hello")
            assertEquals(listOf("/bottok/sendMessage", "/api/v10/channels/chan/messages"), seen.map { it.first })
            assertTrue("\"chat_id\":\"42\"" in seen[0].third && "⚠️ hello" in seen[0].third)
            assertEquals("Bot dtok", seen[1].second, "Discord bot auth")
            // A Discord 500 is logged, never thrown (above). Unconfigured channels send nothing:
            seen.clear()
            ChatAlertSink("", "", "", "", telegramApiBase = base, discordApiBase = base).send("x")
            assertEquals(emptyList(), seen)
        } finally {
            server.stop(0)
        }
    }
}
