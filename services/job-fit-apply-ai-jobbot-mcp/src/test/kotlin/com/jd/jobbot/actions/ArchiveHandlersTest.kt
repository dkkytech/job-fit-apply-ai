package com.jd.jobbot.actions

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.gmail.GmailAuth
import com.jd.jobbot.gmail.GmailClient
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.FakeBridge
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchiveHandlersTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val user = "8679792351"

    /** In-memory Gmail: one message's labels. */
    private open class FakeGmail(var labels: MutableList<String>) : GmailClient(object : GmailAuth(Paths.get("/x"), Paths.get("/y")) {
        override fun token(forceRefresh: Boolean) = "t"
    }) {
        val modifies = mutableListOf<Pair<List<String>, List<String>>>()
        override fun labels(messageId: String) = labels.toList()
        override fun modify(messageId: String, add: List<String>, remove: List<String>): List<String> {
            modifies += add to remove
            labels.removeAll(remove); labels.addAll(add.filter { it !in labels })
            return labels.toList()
        }
    }

    private class FakeTracks(var fail: Boolean = false) : TrackWriter("http://unused") {
        val events = mutableListOf<Pair<Long, String>>()
        override fun addEvent(trackId: Long, kind: String, summary: String?, details: JsonElement?) {
            if (fail) error("bridge down")
            events += trackId to kind
        }
    }

    private class StubLookup(val event: JsonObject, val track: JsonObject?, val match: String?) : JobLookup(BridgeReadClient("http://unused")) {
        override fun event(seq: Long) = event.takeIf { seq == 9L }
        override fun find(seq: Long) = event(seq)?.let { Found(it, track, match) }
    }

    private val recruiterEvent = FakeBridge.event(9, messageId = "m9", recruiter = true, terminalLabel = "Recruiter_Response_Required")

    private fun setup(
        labels: MutableList<String> = mutableListOf("INBOX", "STARRED", "Recruiter_Response_Required"),
        match: String? = "track_id",
        tracks: FakeTracks = FakeTracks(),
    ): Triple<TapService, FakeGmail, ActionStore> {
        val gmail = FakeGmail(labels)
        val store = ActionStore(":memory:", clock)
        val lookup = StubLookup(recruiterEvent, FakeBridge.track(41, "Acme", "Staff SDET"), match)
        val support = ArchiveSupport(gmail, lookup, tracks, setOf("apply", "reply", "archive"), store)
        val taps = TapService(lookup, store, mapOf("archive" to support.archive, "undo" to support.undo),
            setOf(user), setOf("apply", "reply", "archive"), false, Duration.ofDays(7), clock)
        return Triple(taps, gmail, store)
    }

    private fun tap(verb: String) = TapRequest(verb, 9, user, user, 5, now.epochSecond)

    @Test
    fun `archive removes INBOX only and swaps the button for Undo`() {
        val tracks = FakeTracks()
        val (taps, gmail, store) = setup(tracks = tracks)
        val r = taps.tap(tap("archive"))
        assertEquals(Outcomes.DONE, r.outcome)
        assertEquals("Archived.", r.toast)
        assertEquals(listOf(emptyList<String>() to listOf("INBOX")), gmail.modifies)
        assertEquals(listOf("STARRED", "Recruiter_Response_Required"), gmail.labels)
        assertEquals(listOf("apply:9", "reply:9", "undo:9"), r.actionRow!!.map { it.callbackData })
        assertEquals(Outcomes.DONE, store.latest("archive", "url:acme.co/jobs/9")!!.status)
        assertEquals(listOf(41L to "archived"), tracks.events)
    }

    @Test
    fun `undo restores exactly what archive removed`() {
        val tracks = FakeTracks()
        val (taps, gmail, store) = setup(tracks = tracks)
        taps.tap(tap("archive"))
        val r = taps.tap(tap("undo"))
        assertEquals("Back in the inbox.", r.toast)
        assertEquals(listOf("INBOX") to emptyList<String>(), gmail.modifies.last())
        assertTrue("INBOX" in gmail.labels)
        assertEquals(listOf("apply:9", "reply:9", "archive:9"), r.actionRow!!.map { it.callbackData })
        assertEquals(ArchiveSupport.UNDONE, store.latest("archive", "url:acme.co/jobs/9")!!.status)
        assertEquals(listOf("archived", "unarchived"), tracks.events.map { it.second })
    }

    @Test
    fun `a second undo has nothing to undo`() {
        val (taps, _, _) = setup()
        taps.tap(tap("archive")); taps.tap(tap("undo"))
        val r = taps.tap(tap("undo"))
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals("Nothing to undo.", r.toast)
    }

    @Test
    fun `archive on mail already out of the inbox changes nothing (Muse may have archived it)`() {
        val (taps, gmail, _) = setup(labels = mutableListOf("STARRED"))
        val r = taps.tap(tap("archive"))
        assertEquals(Outcomes.ALREADY, r.outcome)
        assertTrue(gmail.modifies.isEmpty())
        assertEquals(listOf("apply:9", "reply:9", "archive:9"), r.actionRow!!.map { it.callbackData }, "not ours: no Undo")
    }

    @Test
    fun `a double tap archives once`() {
        val (taps, gmail, _) = setup()
        taps.tap(tap("archive"))
        val second = taps.tap(tap("archive"))
        assertEquals(Outcomes.ALREADY, second.outcome)
        assertEquals(1, gmail.modifies.size)
        assertEquals("undo:9", second.actionRow!!.last().callbackData, "ours: keep offering Undo")
    }

    @Test
    fun `history goes only to a reliably matched track`() {
        val tracks = FakeTracks()
        val (taps, _, _) = setup(match = "company+title (fuzzy)", tracks = tracks)
        assertEquals(Outcomes.DONE, taps.tap(tap("archive")).outcome)
        assertTrue(tracks.events.isEmpty())
    }

    @Test
    fun `a failed history write never fails the archive`() {
        val (taps, gmail, store) = setup(tracks = FakeTracks(fail = true))
        assertEquals(Outcomes.DONE, taps.tap(tap("archive")).outcome)
        assertEquals(1, gmail.modifies.size)
        assertTrue(store.recentErrors().single().context.startsWith("track event archived"))
    }

    @Test
    fun `a Gmail failure marks the archive failed, never stuck started`() {
        val gmail = object : FakeGmail(mutableListOf("INBOX")) {
            override fun modify(messageId: String, add: List<String>, remove: List<String>): List<String> = error("Gmail 503")
        }
        val store = ActionStore(":memory:", clock)
        val lookup = StubLookup(recruiterEvent, null, null)
        val support = ArchiveSupport(gmail, lookup, null, setOf("archive"), store)
        val taps = TapService(lookup, store, mapOf("archive" to support.archive, "undo" to support.undo), setOf(user), setOf("archive"), false, Duration.ofDays(7), clock)
        assertEquals(Outcomes.ERROR, taps.tap(tap("archive")).outcome)
        assertEquals(ArchiveSupport.FAILED, store.latest("archive", "url:acme.co/jobs/9")!!.status)
        assertEquals(emptyMap(), store.pendingCounts())
        assertEquals("Nothing to undo.", taps.tap(tap("undo")).toast)
    }
}
