package com.jd.notifier.notify

import com.jd.notifier.notify.TelegramButtons.Action
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TelegramButtons (actions, callback data, markup)")
class TelegramButtonsTest {

    @Test
    @DisplayName("callback data is <verb>:<completed_seq>")
    fun callbackDataShape() {
        assertEquals("apply:7663", TelegramButtons.callbackData(Action.APPLY, 7663))
        assertEquals("reply:1", TelegramButtons.callbackData(Action.REPLY, 1))
        assertEquals("archive:42", TelegramButtons.callbackData(Action.ARCHIVE, 42))
    }

    @Test
    @DisplayName("callback data stays far inside the 64-byte cap even at Long.MAX_VALUE")
    fun callbackDataFitsCap() {
        Action.entries.forEach { a ->
            val data = TelegramButtons.callbackData(a, Long.MAX_VALUE)
            assertTrue(data.toByteArray(Charsets.UTF_8).size <= TelegramButtons.CALLBACK_DATA_LIMIT, data)
        }
    }

    @Test
    @DisplayName("a zero or negative completed_seq cannot produce callback data")
    fun callbackDataNeedsRealSeq() {
        assertFailsWith<IllegalArgumentException> { TelegramButtons.callbackData(Action.APPLY, 0) }
        assertFailsWith<IllegalArgumentException> { TelegramButtons.callbackData(Action.APPLY, -3) }
    }

    @Test
    @DisplayName("verbs are stable wire tokens")
    fun verbsAreStable() {
        // Cards already in the chat carry these strings; changing one strands every old card.
        assertEquals(listOf("apply", "reply", "archive", "tailor"), Action.entries.map { it.verb })
    }

    @Test
    @DisplayName("parseActions: trims, ignores case and blanks, drops unknown verbs")
    fun parseActions() {
        assertEquals(setOf(Action.APPLY, Action.ARCHIVE), TelegramButtons.parseActions(" Apply ,archive,, nope "))
        assertEquals(emptySet(), TelegramButtons.parseActions(""))
        assertEquals(emptySet(), TelegramButtons.parseActions("  "))
    }

    @Test
    @DisplayName("links on the first row, actions on the second")
    fun buildsRows() {
        val rows = TelegramButtons.forHighFit(
            reportUrl = "http://host:8081/job/report.md",
            resumeUrl = "http://host:8765/api/jobs/abc/resume.pdf",
            actions = listOf(Action.APPLY, Action.REPLY),
            completedSeq = 9,
        )
        assertEquals(2, rows.size)
        assertEquals(listOf("View Report", "View Resume"), rows[0].map { it.text })
        assertEquals(listOf("Apply", "Reply"), rows[1].map { it.text })
        assertEquals(listOf("apply:9", "reply:9"), rows[1].map { it.callbackData })
        rows[1].forEach { assertNull(it.url) }
    }

    @Test
    @DisplayName("actions are dropped when there is no completed_seq to address")
    fun actionsNeedSeq() {
        val rows = TelegramButtons.forHighFit("http://host/r.md", null, listOf(Action.APPLY), completedSeq = 0)
        assertEquals(listOf(listOf("View Report")), rows.map { r -> r.map { it.text } })
    }

    @Test
    @DisplayName("a non-absolute URL is dropped rather than shipped as a broken button")
    fun relativeUrlDropped() {
        val rows = TelegramButtons.forHighFit(
            reportUrl = "http://host:8081/job/report.md",
            resumeUrl = "/api/jobs/abc/resume.pdf",
            actions = listOf(Action.APPLY),
            completedSeq = 1,
        )
        assertEquals(listOf("View Report"), rows[0].map { it.text })
        assertTrue(rows.flatten().none { it.url?.startsWith("/") == true })
        // One bad link must not remove the action row.
        assertEquals(listOf("Apply"), rows[1].map { it.text })
    }

    @Test
    @DisplayName("no links still yields a usable action keyboard")
    fun blankLinksKeepActions() {
        val rows = TelegramButtons.forHighFit("", "   ", listOf(Action.APPLY), completedSeq = 2)
        assertEquals(listOf(listOf("Apply")), rows.map { r -> r.map { it.text } })
    }

    @Test
    @DisplayName("nothing to render: no rows at all")
    fun blanksAreAbsent() {
        assertTrue(TelegramButtons.forHighFit("   ", "", emptyList(), completedSeq = 5).isEmpty())
    }

    @Test
    @DisplayName("markup JSON has Telegram's inline_keyboard shape and escapes text")
    fun markupShape() {
        val rows = listOf(listOf(TelegramButtons.Button("Say \"hi\" & go", url = "http://host/r.md")))
        val json = TelegramButtons.replyMarkupJson(rows)!!
        assertTrue(json.startsWith("{\"inline_keyboard\":[["), json)
        assertTrue(json.contains("\"url\":\"http://host/r.md\""), json)
        assertTrue(json.contains("\\\"hi\\\""), "quotes must be escaped: $json")
    }

    @Test
    @DisplayName("callback buttons serialise callback_data, not url")
    fun callbackMarkup() {
        val rows = TelegramButtons.forHighFit(null, null, listOf(Action.ARCHIVE), completedSeq = 3)
        val json = TelegramButtons.replyMarkupJson(rows)!!
        assertEquals("""{"inline_keyboard":[[{"text":"Archive","callback_data":"archive:3"}]]}""", json)
    }

    @Test
    @DisplayName("no buttons yields null markup rather than an empty keyboard")
    fun noRowsNoMarkup() {
        assertNull(TelegramButtons.replyMarkupJson(emptyList()))
    }
}
