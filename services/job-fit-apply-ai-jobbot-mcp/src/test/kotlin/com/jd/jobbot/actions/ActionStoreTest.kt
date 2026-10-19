package com.jd.jobbot.actions

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActionStoreTest {
    private fun store() = ActionStore(":memory:", Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC))

    @Test
    fun `latest returns the newest action for a verb and job`() {
        store().use { s ->
            s.insert("archive", "url:a", 1, "done", "c", "10")
            val second = s.insert("archive", "url:a", 2, "started", "c", "11")
            s.insert("archive", "url:b", 3, "done", "c", "12")
            assertEquals(second.id, s.latest("archive", "url:a")!!.id)
            assertNull(s.latest("reply", "url:a"))
        }
    }

    @Test
    fun `update changes status and keeps details unless given`() {
        store().use { s ->
            val a = s.insert("archive", "url:a", 1, "started", "c", "10", details = """{"removed":["INBOX"]}""")
            val updated = s.update(a.id, "done")!!
            assertEquals("done", updated.status)
            assertEquals("""{"removed":["INBOX"]}""", updated.details)
        }
    }

    @Test
    fun `pending counts only open actions`() {
        store().use { s ->
            s.insert("reply", "a", 1, "awaiting_approval", null, null)
            s.insert("reply", "b", 2, "awaiting_approval", null, null)
            s.insert("apply", "c", 3, "started", null, null)
            s.insert("archive", "d", 4, "done", null, null)
            assertEquals(mapOf("awaiting_approval" to 2, "started" to 1), s.pendingCounts())
        }
    }

    @Test
    fun `errors are kept newest first and truncated`() {
        store().use { s ->
            s.recordError("one", "first")
            s.recordError("two", "x".repeat(900))
            val errors = s.recentErrors()
            assertEquals(listOf("two", "one"), errors.map { it.context })
            assertEquals(500, errors.first().message.length)
        }
    }
}
