package com.jd.notifier.notify

import com.jd.notifier.bridge.ArtifactUrls
import com.jd.notifier.bridge.CompletedEvent
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The link buttons and the action buttons are gated separately, because they carry different
 * risk: links are inert, an action starts agent work.
 */
@DisplayName("Notifier (independent link/action gating)")
class NotifierGatingTest {

    private fun client() = mock<NotificationClient> {
        on { discordConfigured } doReturn false
        on { telegramConfigured } doReturn true
        on { postDiscord(any()) } doReturn DeliveryResult.SKIPPED
        on { postTelegramHtml(any()) } doReturn DeliveryResult.DELIVERED
        on { postTelegramHtmlWithButtons(any(), any()) } doReturn DeliveryResult.DELIVERED
    }

    private fun event() = CompletedEvent(
        jobId = "j", completedSeq = 42, status = "done",
        company = "Acme", roleTitle = "Staff SDET", fitScore = 80,
        pipelineAction = "tailor", jobUrl = "https://acme.co/j",
        artifactUrl = "http://host:8081/20260901_acme_staff_sdet/",
        artifacts = ArtifactUrls(resumePdf = "/api/jobs/abc/resume.pdf"),
    )

    private fun notifier(
        c: NotificationClient,
        buttons: Boolean = true,
        linkButtons: Boolean,
        actions: String,
    ) = Notifier(
        client = c,
        fitThreshold = 50,
        buttonsEnabled = buttons,
        linkButtonsEnabled = linkButtons,
        actions = TelegramButtons.parseActions(actions),
        links = ArtifactLinks(enabled = false, timeoutMs = 100, bridgeBase = "http://bridge:8765"),
    )

    private fun labelsSent(c: NotificationClient): List<String> {
        val rows = argumentCaptor<List<List<TelegramButtons.Button>>>()
        verify(c).postTelegramHtmlWithButtons(any(), rows.capture())
        return rows.firstValue.flatten().map { it.text }
    }

    @Test
    @DisplayName("links on + actions off: Report and Resume only")
    fun linksOnly() {
        val c = client()
        notifier(c, linkButtons = true, actions = "").notify(event())
        assertEquals(listOf("View Report", "View Resume"), labelsSent(c))
    }

    @Test
    @DisplayName("actions on + links off: Apply only")
    fun actionsOnly() {
        val c = client()
        notifier(c, linkButtons = false, actions = "apply").notify(event())
        assertEquals(listOf("Apply"), labelsSent(c))
    }

    @Test
    @DisplayName("both on: links then Apply")
    fun bothOn() {
        val c = client()
        notifier(c, linkButtons = true, actions = "apply").notify(event())
        assertEquals(listOf("View Report", "View Resume", "Apply"), labelsSent(c))
    }

    @Test
    @DisplayName("buttons off: no keyboard at all, plain text ping")
    fun buttonsOff() {
        val c = client()
        notifier(c, buttons = false, linkButtons = false, actions = "apply").notify(event())
        verify(c, never()).postTelegramHtmlWithButtons(any(), any())
        verify(c).postTelegramHtml(any())
    }

    @Test
    @DisplayName("links survive an event with no artifacts; Apply survives with no links")
    fun independentDegradation() {
        val c = client()
        notifier(c, linkButtons = true, actions = "apply")
            .notify(event().copy(artifactUrl = null, artifacts = null))
        assertEquals(listOf("Apply"), labelsSent(c))
    }
}
