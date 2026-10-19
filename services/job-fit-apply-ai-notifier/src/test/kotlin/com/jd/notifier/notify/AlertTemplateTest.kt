package com.jd.notifier.notify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.notifier.bridge.CompletedEvent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DisplayName("AlertTemplate (editable Telegram ping)")
class AlertTemplateTest {
    @TempDir lateinit var dir: Path

    private fun vectors(): JsonNode {
        var d: Path? = Paths.get("").toAbsolutePath()
        while (d != null) {
            d.resolve("docker/jobbot/contract/alert_template_vectors.json").takeIf { Files.exists(it) }
                ?.let { return ObjectMapper().readTree(Files.readString(it)) }
            d = d.parent
        }
        error("alert_template_vectors.json not found")
    }

    @TestFactory
    fun `shared contract`(): List<DynamicTest> {
        val v = vectors()
        assertEquals(AlertTemplate.PLACEHOLDERS, v["placeholders"].map { it.asText() })
        assertEquals(AlertTemplate.MAX_LENGTH, v["max_length"].asInt())
        assertEquals(AlertTemplate.ALLOWED_TAGS, v["allowed_tags"].map { it.asText() }.toSet())
        return v["valid"].map { t -> DynamicTest.dynamicTest("valid: ${t.asText().take(40)}") { assertNull(AlertTemplate.validate(t.asText())) } } +
            v["invalid"].map { c -> DynamicTest.dynamicTest("invalid: ${c["why"].asText()}") { assertNotNull(AlertTemplate.validate(c["template"].asText())) } } +
            v["render"].mapIndexed { i, c ->
                DynamicTest.dynamicTest("render $i") {
                    val e = c["event"]
                    fun s(k: String) = e[k]?.takeIf { !it.isNull }?.asText()
                    fun list(k: String) = e[k]?.takeIf { it.isArray }?.map { it.asText() }.orEmpty()
                    val values = AlertTemplate.Values(s("company")!!, s("title")!!, s("score")!!, s("action")!!, s("ref")!!, s("job_url"), s("report_url"),
                        location = s("location"), remotePolicy = s("remote_policy"), salary = s("salary"), source = s("source"),
                        strengths = list("strengths"), gaps = list("gaps"))
                    assertEquals(c["expected"].asText(), AlertTemplate.renderTemplate(c["template"].asText(), values))
                }
            }
    }

    @Test
    fun `too long is invalid`() {
        assertNotNull(AlertTemplate.validate("{ref}" + "x".repeat(AlertTemplate.MAX_LENGTH)))
    }

    /** The built-in card's first line for [event]: "Company: Title (score)", company → posting, title → report. */
    private val BUILT_IN_FIRST_LINE = "<a href=\"https://acme.co/j\">Acme</a>: <a href=\"http://host:8081/x/report.md\">Staff SDET</a> (72)"

    private val event = CompletedEvent(
        jobId = "j", completedSeq = 9, status = "done", company = "Acme", roleTitle = "Staff SDET", fitScore = 72,
        pipelineAction = "TAILOR", jobUrl = "https://acme.co/j", artifactUrl = "http://host:8081/x/",
    )

    private fun client(result: DeliveryResult = DeliveryResult.DELIVERED) = mock<NotificationClient> {
        on { discordConfigured } doReturn false
        on { telegramConfigured } doReturn true
        on { postDiscord(any()) } doReturn DeliveryResult.SKIPPED
        on { postTelegramHtml(any()) } doReturn result
    }

    private fun notifier(c: NotificationClient, file: Path?) =
        Notifier(c, fitThreshold = 50, buttonsEnabled = false, linkButtonsEnabled = false, template = AlertTemplate(file))

    @Test
    fun `a valid template file shapes the ping`() {
        val f = dir.resolve("high-fit.html").also { Files.writeString(it, "<b>{score}</b> {company} · {title}\n{ref}") }
        val c = client()
        notifier(c, f).notify(event)
        val text = argumentCaptor<String>()
        verify(c).postTelegramHtml(text.capture())
        assertEquals("<b>72</b> Acme · Staff SDET\n#J9", text.firstValue)
    }

    @Test
    fun `a missing or invalid template falls back to the built-in format`() {
        listOf(null, dir.resolve("nope.html"), dir.resolve("bad.html").also { Files.writeString(it, "no ref here") }).forEach { f ->
            val c = client()
            notifier(c, f).notify(event)
            val text = argumentCaptor<String>()
            verify(c).postTelegramHtml(text.capture())
            assertEquals(BUILT_IN_FIRST_LINE, text.firstValue.lines().first(), "fallback for $f: ${text.firstValue}")
        }
    }

    @Test
    fun `a template Telegram refuses is re-sent once in the built-in format`() {
        val f = dir.resolve("high-fit.html").also { Files.writeString(it, "{company} {ref}") }
        val c = mock<NotificationClient> {
            on { discordConfigured } doReturn false
            on { telegramConfigured } doReturn true
            on { postDiscord(any()) } doReturn DeliveryResult.SKIPPED
            on { postTelegramHtml(any()) }.doReturn(DeliveryResult.PERMANENT, DeliveryResult.DELIVERED)
        }
        val outcome = notifier(c, f).notify(event)
        val text = argumentCaptor<String>()
        verify(c, times(2)).postTelegramHtml(text.capture())
        assertEquals("Acme #J9", text.firstValue)
        assertEquals(BUILT_IN_FIRST_LINE, text.secondValue.lines().first())
        assertEquals(DeliveryResult.DELIVERED, outcome.telegram)
    }

    @Test
    fun `edits apply to the next ping without a restart`() {
        val f = dir.resolve("high-fit.html").also { Files.writeString(it, "A {ref}") }
        val c = client()
        val n = notifier(c, f)
        n.notify(event)
        Files.writeString(f, "B {ref}")
        n.notify(event.copy(completedSeq = 10))
        val text = argumentCaptor<String>()
        verify(c, times(2)).postTelegramHtml(text.capture())
        assertEquals(listOf("A #J9", "B #J10"), text.allValues)
    }
}
