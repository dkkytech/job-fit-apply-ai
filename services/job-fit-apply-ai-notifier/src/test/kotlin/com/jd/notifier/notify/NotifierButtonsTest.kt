package com.jd.notifier.notify

import com.jd.notifier.bridge.ArtifactUrls
import com.jd.notifier.bridge.CompletedEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The button feature as the Notifier actually uses it: which buttons a high-fit event gets, what
 * their callback data says, and the job reference line the agent resolves replies against.
 */
@DisplayName("Notifier (Telegram buttons)")
class NotifierButtonsTest {

    private fun client() = mock<NotificationClient> {
        on { discordConfigured } doReturn false
        on { telegramConfigured } doReturn true
        // The Notifier calls postDiscord unconditionally; the real client returns SKIPPED itself
        // when unconfigured, so an unstubbed mock would hand back null and trip NotifyOutcome.
        on { postDiscord(any()) } doReturn DeliveryResult.SKIPPED
        on { postTelegramHtml(any()) } doReturn DeliveryResult.DELIVERED
        on { postTelegramHtmlWithButtons(any(), any()) } doReturn DeliveryResult.DELIVERED
    }

    private fun event(
        seq: Long = 7663,
        jobUrl: String? = "https://acme.co/j",
        messageId: String? = "gmail-1",
        recruiter: Boolean = true,
        terminalLabel: String? = "Recruiter_Response_Required",
    ) = CompletedEvent(
        jobId = "j", completedSeq = seq, status = "done",
        company = "Acme", roleTitle = "Staff SDET", fitScore = 80,
        pipelineAction = "tailor", jobUrl = jobUrl,
        artifactUrl = "http://host:8081/20260901_acme_staff_sdet/",
        artifacts = ArtifactUrls(resumePdf = "/api/jobs/abc/resume.pdf"),
        messageId = messageId, isRecruiter = recruiter, terminalLabel = terminalLabel,
    )

    private fun notifier(
        c: NotificationClient,
        buttons: Boolean = true,
        actions: String = "apply,reply,archive",
    ) = Notifier(
        client = c,
        fitThreshold = 50,
        buttonsEnabled = buttons,
        linkButtonsEnabled = buttons,
        actions = TelegramButtons.parseActions(actions),
        links = ArtifactLinks(enabled = false, timeoutMs = 100, bridgeBase = "http://bridge:8765"),
    )

    private fun sent(c: NotificationClient): Pair<String, List<List<TelegramButtons.Button>>> {
        val text = argumentCaptor<String>()
        val rows = argumentCaptor<List<List<TelegramButtons.Button>>>()
        verify(c).postTelegramHtmlWithButtons(text.capture(), rows.capture())
        return text.firstValue to rows.firstValue
    }

    /** A pay-gated high fit: scored 63, skipped by the pay gate, scoring notes at artifact_url. */
    private fun gated(reason: String? = "Posted pay (max \$85K) is below the \$145K target") = CompletedEvent(
        jobId = "j", completedSeq = 7708, status = "done",
        company = "Sparksoft", roleTitle = "Automation Engineer", fitScore = 63,
        pipelineAction = "SKIP", jobUrl = "https://jobright.ai/jobs/info/6abf",
        artifactUrl = "http://host:8081/20261002_132538_sparksoft_automation_engineer/",
        messageId = "m-digest", isRecruiter = false, terminalLabel = "JD_Processed_Digest",
        skipReason = reason,
    )

    /** Fails the test if the resume lookup runs: a skipped job has no resume to find. */
    private val noLookup = object : ArtifactLinks(enabled = true, timeoutMs = 100, bridgeBase = "http://bridge:8765") {
        override fun resolve(artifactUrl: String?, resumePdf: String?): Links = error("no resume lookup for a skipped job")
    }

    /** A fit below AUTO_TAILOR_MIN_SCORE: scored 68, offered, scoring notes at artifact_url, no resume. */
    private fun offered() = CompletedEvent(
        jobId = "j", completedSeq = 7720, status = "done",
        company = "Acme", roleTitle = "Senior SDET", fitScore = 68,
        pipelineAction = "OFFER", jobUrl = "https://acme.co/jobs/7720",
        artifactUrl = "http://host:8081/20261014_acme_senior_sdet/",
        messageId = "m-digest", isRecruiter = false, terminalLabel = "JD_Processed_Digest",
        skipReason = "Fit 68 is below the auto-tailor score (75)",
    )

    @Test
    @DisplayName("an offered fit: the card says it is not tailored yet, View Report and a Tailor button, no Apply")
    fun offeredCardHasTailor() {
        val c = client()
        Notifier(client = c, fitThreshold = 55, buttonsEnabled = true, linkButtonsEnabled = true,
            actions = TelegramButtons.parseActions("apply,reply,archive,tailor"), links = noLookup).notify(offered())
        val (text, rows) = sent(c)
        assertTrue(text.contains("Not tailored yet: Fit 68 is below the auto-tailor score (75). Tap Tailor for a resume."), text)
        assertFalse(text.contains("Skipped:"), text)
        assertEquals(
            listOf(
                listOf(TelegramButtons.Button("View Report", url = "http://host:8081/20261014_acme_senior_sdet/report.md")),
                listOf(TelegramButtons.Button("Tailor", callbackData = "tailor:7720")),
            ),
            rows,
        )
    }

    @Test
    @DisplayName("with Tailor not enabled the offer card does not promise a button")
    fun offeredCardWithoutTailorAction() {
        val c = client()
        Notifier(client = c, fitThreshold = 55, buttonsEnabled = true, linkButtonsEnabled = true,
            actions = TelegramButtons.parseActions("apply,reply,archive"), links = noLookup).notify(offered())
        val (text, rows) = sent(c)
        assertTrue(text.contains("Not tailored yet: Fit 68 is below the auto-tailor score (75)."), text)
        assertFalse(text.contains("Tap Tailor"), text)
        assertTrue(rows.flatten().none { it.callbackData != null }, "no action buttons: $rows")
    }

    @Test
    @DisplayName("a deferred fit: the card says it waits on the quota, View Report only, no actions")
    fun deferredCardSaysWhy() {
        val c = client()
        Notifier(client = c, fitThreshold = 55, buttonsEnabled = true, linkButtonsEnabled = true,
            actions = TelegramButtons.parseActions("apply,reply,archive,tailor"), links = noLookup)
            .notify(offered().copy(pipelineAction = "DEFERRED", fitScore = 88, skipReason = "the cloud LLM quota is used up"))
        val (text, rows) = sent(c)
        assertTrue(text.contains("Tailoring deferred: the cloud LLM quota is used up. It is tailored automatically when the quota resets, best fits first."), text)
        assertFalse(text.contains("Skipped:") || text.contains("Not tailored yet"), text)
        assertEquals(listOf(listOf(TelegramButtons.Button("View Report", url = "http://host:8081/20261014_acme_senior_sdet/report.md"))), rows)
    }

    @Test
    @DisplayName("a tailored job never gets a Tailor button")
    fun tailoredHasNoTailorButton() {
        val c = client()
        notifier(c, actions = "apply,reply,archive,tailor").notify(event())
        val (_, rows) = sent(c)
        assertTrue(rows.flatten().none { it.callbackData?.startsWith("tailor:") == true }, "$rows")
    }

    @Test
    @DisplayName("a pay-gated high fit: the card says why, View Report only, no Apply")
    fun gatedCardSaysWhy() {
        val c = client()
        Notifier(client = c, fitThreshold = 55, buttonsEnabled = true, linkButtonsEnabled = true,
            actions = TelegramButtons.parseActions("apply,reply,archive"), links = noLookup).notify(gated())
        val (text, rows) = sent(c)
        val lines = text.lines()
        assertEquals("<a href=\"https://jobright.ai/jobs/info/6abf\">Sparksoft</a>: <a href=\"http://host:8081/20261002_132538_sparksoft_automation_engineer/report.md\">Automation Engineer</a> (63)", lines[0])
        assertEquals("Skipped: Posted pay (max \$85K) is below the \$145K target", lines[1])
        assertEquals("#J7708", lines.last())
        assertEquals(listOf(listOf("View Report")), rows.map { r -> r.map { it.text } }, "no resume, no Apply: $rows")
        assertEquals("http://host:8081/20261002_132538_sparksoft_automation_engineer/report.md", rows[0][0].url)
    }

    @Test
    @DisplayName("a skip with no reason still says it was skipped; the reason is HTML-escaped")
    fun gatedCardFallbackAndEscaping() {
        val c1 = client()
        notifier(c1).notify(gated(reason = null))
        assertEquals("Skipped: not tailored, so there's no resume", sent(c1).first.lines()[1])
        val c2 = client()
        notifier(c2).notify(gated(reason = "Pay <\$85K> & no equity"))
        assertEquals("Skipped: Pay &lt;\$85K&gt; &amp; no equity", sent(c2).first.lines()[1])
    }

    @Test
    @DisplayName("an edited card template still gets the Skipped line")
    fun gatedCardUnderTemplate(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val file = dir.resolve("high-fit.html").also { java.nio.file.Files.writeString(it, "<b>{score}</b> {company}\n{ref}") }
        val c = client()
        Notifier(client = c, fitThreshold = 55, buttonsEnabled = true, linkButtonsEnabled = true,
            actions = TelegramButtons.parseActions("apply"), links = noLookup, template = AlertTemplate(file)).notify(gated())
        assertEquals(listOf("<b>63</b> Sparksoft", "#J7708", "Skipped: Posted pay (max \$85K) is below the \$145K target"), sent(c).first.lines())
    }

    @Test
    @DisplayName("the card carries location · salary · source, the top three strengths and the main gap")
    fun cardCarriesDetails() {
        val c = client()
        notifier(c).notify(event().copy(
            location = "Seattle, WA", remotePolicy = "hybrid", salaryRange = "\$150K–\$180K", source = "jobright.ai",
            strengths = listOf("Kotlin & CI ownership", "Espresso", "XCUITest", "Fourth"), gaps = listOf("No Pact experience", "Other"),
        ))
        val lines = sent(c).first.lines()
        assertTrue(lines[0].startsWith("<a href=") && lines[0].endsWith("</a> (80)"), lines.toString())
        assertEquals(
            listOf(
                "Seattle, WA · hybrid · \$150K–\$180K · via jobright.ai",
                "", "Fit:", "• Kotlin &amp; CI ownership", "• Espresso", "• XCUITest", "",
                "Gap: No Pact experience",
                "#J7663",
            ),
            lines.drop(1),
        )
    }

    @Test
    @DisplayName("an event from before the details existed keeps the two-line card")
    fun oldEventKeepsShortCard() {
        val c = client()
        notifier(c).notify(event())
        assertEquals(2, sent(c).first.lines().size)
    }

    @Test
    @DisplayName("a skipped high fit shows its details, then why it was skipped, then the ref")
    fun gatedCardWithDetails() {
        val c = client()
        notifier(c).notify(gated().copy(location = "Remote", strengths = listOf("API automation"), gaps = listOf("Mid-level scope")))
        assertEquals(
            listOf("Remote", "", "Fit:", "• API automation", "", "Gap: Mid-level scope",
                "Skipped: Posted pay (max \$85K) is below the \$145K target", "#J7708"),
            sent(c).first.lines().drop(1),
        )
    }

    @Test
    @DisplayName("Richard's layout exactly, with no strengths the card stays compact, and a missing company or title is Unknown")
    fun layoutAndUnknown() {
        val c1 = client()
        notifier(c1).notify(gated().copy(
            jobUrl = null, artifactUrl = null, location = "Remote", salaryRange = "\$80K–\$85K", source = "jobright.ai",
            strengths = listOf("Web/API automation tools match", "Healthcare domain expertise overlap", "Core tech stack overlap"),
            gaps = listOf("Seniority level is a step down"),
        ))
        assertEquals(
            """
            Sparksoft: Automation Engineer (63)
            Remote · ${'$'}80K–${'$'}85K · via jobright.ai

            Fit:
            • Web/API automation tools match
            • Healthcare domain expertise overlap
            • Core tech stack overlap

            Gap: Seniority level is a step down
            Skipped: Posted pay (max ${'$'}85K) is below the ${'$'}145K target
            #J7708
            """.trimIndent(),
            sent(c1).first,
        )

        val c2 = client()
        notifier(c2).notify(gated().copy(jobUrl = null, artifactUrl = null, company = "  ", roleTitle = null, location = "Remote", gaps = listOf("Mid-level")))
        assertEquals(listOf("Unknown: Unknown (63)", "Remote", "Gap: Mid-level"), sent(c2).first.lines().take(3), "no blank lines without a Fit block")
    }

    @Test
    @DisplayName("a tailored job's card has no Skipped line")
    fun tailoredCardHasNoSkipLine() {
        val c = client()
        notifier(c).notify(event())
        assertTrue(sent(c).first.lines().none { it.startsWith("Skipped:") })
    }

    @Test
    @DisplayName("every enabled, eligible action becomes a <verb>:<completed_seq> callback")
    fun actionsCarryVerbAndSeq() {
        val c = client()
        notifier(c).notify(event())
        val (_, rows) = sent(c)

        assertEquals(listOf("View Report", "View Resume"), rows[0].map { it.text })
        assertEquals(listOf("Apply", "Reply", "Archive"), rows[1].map { it.text })
        assertEquals(
            listOf("apply:7663", "reply:7663", "archive:7663"),
            rows[1].map { it.callbackData },
        )
        rows[1].forEach { assertNull(it.url, "${it.text} must be a callback, not a URL") }
    }

    @Test
    @DisplayName("the ping text ends with the #J<completed_seq> job reference line")
    fun textCarriesJobRef() {
        val c = client()
        notifier(c).notify(event())
        val (text, _) = sent(c)
        assertTrue(text.startsWith("<a href=\"https://acme.co/j\">Acme</a>: "), "\"Company: Title (score)\", no prefix: $text")
        assertEquals("#J7663", text.lines().last(), "job ref must be its own last line: $text")
        assertTrue(text.lines().first().endsWith("</a> (80)"), "score stays on the first line: $text")
    }

    @Test
    @DisplayName("the plain (no-buttons) ping also carries the job reference")
    fun plainPingCarriesJobRef() {
        val c = client()
        notifier(c, buttons = false).notify(event())
        val text = argumentCaptor<String>()
        verify(c).postTelegramHtml(text.capture())
        verify(c, never()).postTelegramHtmlWithButtons(any(), any())
        assertEquals("#J7663", text.firstValue.lines().last())
    }

    @Test
    @DisplayName("no completed_seq: no job ref line and no action buttons, links still sent")
    fun noSeqNoActions() {
        val c = client()
        notifier(c).notify(event(seq = 0))
        val (text, rows) = sent(c)
        assertTrue(!text.contains("#J"), text)
        assertEquals(listOf(listOf("View Report", "View Resume")), rows.map { r -> r.map { it.text } })
    }

    @Test
    @DisplayName("a disabled verb is never sent, even when the event qualifies")
    fun disabledVerbsWithheld() {
        val c = client()
        notifier(c, actions = "apply").notify(event())
        val (_, rows) = sent(c)
        assertEquals(listOf("Apply"), rows[1].map { it.text })
    }

    @Test
    @DisplayName("an ineligible verb is never sent, even when enabled (the dead-button guard)")
    fun ineligibleVerbsWithheld() {
        val c = client()
        // Not a recruiter email, already archived by the poller, no posting URL.
        notifier(c).notify(event(jobUrl = null, recruiter = false, terminalLabel = "JD_Processed"))
        val (_, rows) = sent(c)
        assertEquals(listOf(listOf("View Report", "View Resume")), rows.map { r -> r.map { it.text } })
    }

    @Test
    @DisplayName("unknown verbs in the config are dropped, not sent as dead buttons")
    fun unknownVerbsDropped() {
        val c = client()
        notifier(c, actions = "apply, investigate ,frobnicate").notify(event())
        val (_, rows) = sent(c)
        assertEquals(listOf("apply:7663"), rows[1].map { it.callbackData })
    }

    @Test
    @DisplayName("below the threshold nothing goes to Telegram")
    fun belowThresholdSilent() {
        val c = client()
        notifier(c).notify(event().copy(fitScore = 10))
        verify(c, never()).postTelegramHtmlWithButtons(any(), any())
        verify(c, never()).postTelegramHtml(any())
    }
}
