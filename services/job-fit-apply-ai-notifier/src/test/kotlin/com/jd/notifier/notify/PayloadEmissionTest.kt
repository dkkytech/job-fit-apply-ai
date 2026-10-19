package com.jd.notifier.notify

import com.jd.notifier.bridge.ArtifactUrls
import com.jd.notifier.bridge.CompletedEvent
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Emits the exact Telegram payload the notifier would send for a real high-fit event, so the
 * wire format can be validated against the live API instead of only against our own assertions.
 *
 * Writes to /tmp and never sends.
 */
@DisplayName("Live payload emission (manual verification aid)")
class PayloadEmissionTest {

    @Test
    @DisplayName("emit real reply_markup + text to /tmp for a live API check")
    fun emitPayload() {
        val event = CompletedEvent(
            jobId = "48e9a97d-b7aa-4dd2-af70-f5408a8bea8d",
            completedSeq = 999, status = "done",
            company = "Bright Vision Technologies", roleTitle = "SDET Engineer", fitScore = 80,
            pipelineAction = "tailor",
            jobUrl = "https://jobright.ai/jobs/info/abc",
            artifactUrl = "http://richards-macbook-m1-max.tail02d0e.ts.net:8081/20260706_084739_bright_vision_technologies_sdet_engineer/",
            artifacts = ArtifactUrls(resumePdf = "/api/jobs/add6e3e7-01a9-4261-8c4b-52db5111c079/resume.pdf"),
        )
        val links = ArtifactLinks(
            enabled = false, timeoutMs = 100,
            bridgeBase = "http://richards-macbook-m1-max.tail02d0e.ts.net:8765",
        ).resolve(event.artifactUrl, event.artifacts?.resumePdf)

        val rows = TelegramButtons.forHighFit(
            links.reportUrl, links.resumeUrl, listOf(TelegramButtons.Action.APPLY), event.completedSeq,
        )
        val markup = TelegramButtons.replyMarkupJson(rows)!!
        val text = "<b>High-fit:</b> <a href=\"${event.jobUrl}\">Bright Vision Technologies</a> " +
            "— <a href=\"${links.reportUrl}\">SDET Engineer</a> — 80\n#J${event.completedSeq}"

        val out = mapOf("text" to text, "markup" to markup)
        val p = Paths.get("/tmp/jobbot-payload.json")
        Files.writeString(p, com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(out))
        println("PAYLOAD -> $p")
        println(markup)
        assertTrue(markup.contains("View Report") && markup.contains("View Resume"))
        assertTrue(markup.contains("\"callback_data\":\"apply:999\""), markup)
    }
}
