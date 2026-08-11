package com.jd.notifier.notify

import com.jd.notifier.bridge.CompletedEvent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Notifier (message formatting + gating)")
class NotifierTest {

    // Both channels accept by default; a test that cares about refusal stubs its own result.
    private fun client(discord: Boolean = true, telegram: Boolean = true) = mock<NotificationClient> {
        on { discordConfigured } doReturn discord
        on { telegramConfigured } doReturn telegram
        on { postDiscord(any()) } doReturn DeliveryResult.DELIVERED
        on { postTelegramHtml(any()) } doReturn DeliveryResult.DELIVERED
    }

    private fun job(
        company: String? = "Acme", role: String? = "Staff SDET", fit: Int? = 30,
        action: String? = "tailor", artifact: String? = null, jobUrl: String? = null, error: String? = null,
    ) = CompletedEvent(
        jobId = "j", completedSeq = 1, status = if (error != null) "error" else "done",
        company = company, roleTitle = role, fitScore = fit, pipelineAction = action,
        jobUrl = jobUrl, artifactUrl = artifact, error = error,
    )

    @Test
    @DisplayName("no channels configured → nothing sent")
    fun noChannels() {
        val c = client(discord = false, telegram = false)
        assertFalse(Notifier(c, fitThreshold = 50).notify(job()).sentAnything)
        verify(c, never()).postDiscord(any())
        verify(c, never()).postTelegramHtml(any())
    }

    @Test
    @DisplayName("non-job event (no company, no error) is skipped")
    fun nonJobSkipped() {
        val c = client()
        val outcome = Notifier(c, 50).notify(job(company = null, role = null, error = null))
        assertFalse(outcome.sentAnything)
        assertTrue(outcome.acked, "a non-job event must not hold the cursor")
        verify(c, never()).postDiscord(any())
    }

    @Test
    @DisplayName("scored job below threshold → Discord only, no Telegram")
    fun belowThreshold() {
        val c = client()
        assertTrue(Notifier(c, fitThreshold = 50).notify(job(fit = 30, action = "tailor")).sentAnything)
        verify(c).postDiscord(argThat { contains("Acme") && contains("Staff SDET") && contains("30") && contains("tailor") })
        verify(c, never()).postTelegramHtml(any())
    }

    @Test
    @DisplayName("high-fit job (>= threshold) → Discord + Telegram")
    fun highFit() {
        val c = client()
        Notifier(c, fitThreshold = 50).notify(job(fit = 80))
        verify(c).postDiscord(any())
        verify(c).postTelegramHtml(argThat { contains("High-fit") && contains("80") })
    }

    @Test
    @DisplayName("error event → Discord error line, no Telegram")
    fun errorEvent() {
        val c = client()
        Notifier(c, 50).notify(job(fit = null, error = "scrape failed"))
        verify(c).postDiscord(argThat { contains("error: scrape failed") })
        verify(c, never()).postTelegramHtml(any())
    }

    @Test
    @DisplayName("Discord label links the title to the report when artifactUrl present")
    fun discordLinkedTitle() {
        val c = client()
        Notifier(c, 50).notify(job(fit = 30, artifact = "http://markserv/x"))
        verify(c).postDiscord(argThat { contains("[Staff SDET](http://markserv/x)") })
    }

    @Test
    @DisplayName("Telegram high-fit uses HTML links (company→jobUrl, title→report.md) and escapes")
    fun telegramHtmlLinks() {
        val c = client()
        Notifier(c, 50).notify(job(fit = 90, artifact = "http://markserv/x", jobUrl = "https://acme.co/j"))
        verify(c).postTelegramHtml(argThat {
            contains("<a href=\"https://acme.co/j\">Acme</a>") &&
                contains("<a href=\"http://markserv/x/report.md\">Staff SDET</a>")
        })
    }

    @Test
    @DisplayName("a retryable Discord failure is not acked, and the delivered Telegram is not re-sent on retry")
    fun partialSuccessIsNotAckedAndDoesNotDuplicate() {
        val c = client()
        whenever(c.postDiscord(any())).doReturn(DeliveryResult.RETRYABLE)
        val notifier = Notifier(c, fitThreshold = 50)

        val first = notifier.notify(job(fit = 80))
        assertFalse(first.acked, "a retryable channel must hold the cursor")
        assertEquals(setOf("telegram"), first.deliveredChannels())

        // The retry: Discord recovers, and Telegram must not be posted a second time.
        whenever(c.postDiscord(any())).doReturn(DeliveryResult.DELIVERED)
        val second = notifier.notify(job(fit = 80), first.deliveredChannels()).merge(first)

        assertTrue(second.acked)
        verify(c, times(2)).postDiscord(any())
        verify(c, times(1)).postTelegramHtml(any())
    }

    @Test
    @DisplayName("a permanent failure is acked — one bad channel must not block every later event")
    fun permanentFailureIsAcked() {
        val c = client()
        whenever(c.postDiscord(any())).doReturn(DeliveryResult.PERMANENT)

        val outcome = Notifier(c, fitThreshold = 50).notify(job(fit = 30))

        assertFalse(outcome.sentAnything)
        assertTrue(outcome.acked, "retrying a 4xx forever would turn one misconfiguration into an outage")
    }
}
