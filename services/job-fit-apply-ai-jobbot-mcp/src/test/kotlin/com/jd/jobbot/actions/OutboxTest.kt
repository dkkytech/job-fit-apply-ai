package com.jd.jobbot.actions

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `items are delivered in order, once, with buttons and photos`() {
        Outbox(dir.resolve("o.db").toString(), dir.resolve("shots")).use { o ->
            val a = o.post("first")
            val b = o.post("second", listOf(InlineButton("✅ Submit", "submit:3")), byteArrayOf(1, 2))
            assertEquals(listOf(a, b), o.pending().map { it.id })
            assertEquals("submit:3", o.pending()[1].buttons.single().callbackData)
            assertTrue(o.pending()[1].hasPhoto)
            assertContentEquals(byteArrayOf(1, 2), o.photo(b))
            assertNull(o.photo(a))
            o.delivered(a)
            assertEquals(listOf(b), o.pending().map { it.id })
        }
    }

    @Test
    fun `long texts are capped so Telegram accepts them`() {
        Outbox(dir.resolve("o.db").toString(), dir.resolve("shots")).use { o ->
            o.post("x".repeat(10_000))
            assertEquals(Outbox.MAX_TEXT, o.pending().single().text.length)
        }
    }
}
