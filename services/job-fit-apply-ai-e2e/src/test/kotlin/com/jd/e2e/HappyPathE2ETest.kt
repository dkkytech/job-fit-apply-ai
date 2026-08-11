package com.jd.e2e

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Submit a pre-scraped JD through Bridge, run the full Processor pipeline, and verify
 * Bridge, artifacts, Markserv, Postgres, `/api/tracks`, and Notifier surfaces.
 *
 * Tier A is structural and also holds under `E2E_REAL_LLM=1`. Tier B asserts exact
 * canned values and call ordering so silently degraded fake-LLM runs cannot pass.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Bridge → Processor → Notifier happy path")
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class HappyPathE2ETest {
    private lateinit var harness: E2eScenarioHarness
    private val expectedRole = "Staff Software Engineer in Test"

    // Shared, not owned: the fake LLM and the sink bind fixed ports, so a per-class instance
    // would compete with the other E2E class for them. Teardown belongs to the JVM — see
    // [SharedE2eHarness].
    @BeforeAll
    fun startHarness() {
        harness = SharedE2eHarness.start()
    }

    @Test
    @DisplayName("TAILOR: scraped JD completes through Bridge, Processor, artifacts, tracking, and notification")
    fun tailoredJobCompletesEndToEnd() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Acme $nonce"
        val jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company))
        val result = harness.runScenario(company) {
            submitScrapedJob(
                company = company,
                roleTitle = expectedRole,
                jdText = jdText,
                idempotencyKey = "e2e-$nonce",
            )
        }

        val groups = mutableListOf<Executable>(Executable { assertTierA(result) })
        if (!E2eConfig.realLlm && !java.lang.Boolean.getBoolean("e2e.excludeTierB")) {
            groups += Executable { assertTierB(result) }
        }
        assertAll("Bridge → Processor → Notifier happy path", groups)
    }

    private fun assertTierA(result: ScenarioResult) = assertAll(
        "Tier A — structural",
        listOf(
            Executable { assertEquals("done", result.finalStatus.path("status").asText()) },
            Executable { tailoredWithPassingScore(result) },
            Executable { bridgeServesPdf(result) },
            Executable { bridgeServesCoverLetter(result) },
            Executable { outputDirComplete(outputDir(result)) },
            Executable { markservServesArtifacts(result) },
            Executable { tracksRowInPostgres(result) },
            Executable { assertEquals(result.company, result.apiTrack.path("company").asText()) },
            Executable { discordMessageDelivered(result) },
            Executable { completedFeedHasJob(result) },
        ),
    )

    private fun assertTierB(result: ScenarioResult) = assertAll(
        "Tier B — exact fake-LLM values",
        listOf(
            Executable { assertEquals(72, result.finalStatus.path("fit_score").asInt()) },
            Executable { exactCallSequence(result) },
            Executable { tailoredYamlHasCannedContent(outputDir(result)) },
            Executable { exactCoverLetter(result) },
            Executable { telegramHighFitDelivered(result) },
        ),
    )

    private fun tailoredWithPassingScore(result: ScenarioResult) {
        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertTrue(result.finalStatus.path("fit_score").asInt() >= 50, "fit_score below threshold: ${result.finalStatus}")
    }

    private fun bridgeServesPdf(result: ScenarioResult) {
        val bytes = harness.getBytes("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/resume.pdf")
        assertTrue(bytes.size > 1000, "resume.pdf suspiciously small: ${bytes.size} bytes")
        assertEquals("%PDF-", String(bytes.copyOfRange(0, 5), Charsets.US_ASCII))
    }

    private fun bridgeServesCoverLetter(result: ScenarioResult) {
        assertTrue(harness.getString("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/cover_letter.txt").isNotBlank())
    }

    private fun outputDirComplete(dir: Path) {
        assertTrue(Files.isDirectory(dir), "output dir missing: $dir")
        for (file in listOf("tailored_resume.yaml", "tailored_resume.tex", "tailored_resume.html", "report.md")) {
            assertTrue(Files.exists(dir.resolve(file)), "missing $file in $dir")
        }
        assertTrue(
            Files.list(dir).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".pdf") } },
            "no PDF in $dir",
        )
        assertTrue(!Files.exists(dir.resolve("fonts")), "leftover fonts/ (render cleanup regressed)")
        assertTrue(!Files.exists(dir.resolve("render_pdf.log")), "leftover render_pdf.log (success should remove it)")
    }

    private fun markservServesArtifacts(result: ScenarioResult) {
        val artifactUrl = artifactUrl(result)
        assertTrue(
            artifactUrl.startsWith("${E2eConfig.markservUrl}/"),
            "artifact_url origin is not e2e Markserv (${E2eConfig.markservUrl}): $artifactUrl",
        )
        assertEquals(200, harness.statusOf(artifactUrl.trimEnd('/') + "/report.md"))
        assertEquals(200, harness.statusOf(artifactUrl.trimEnd('/') + "/tailored_resume.pdf"))
    }

    private fun tracksRowInPostgres(result: ScenarioResult) {
        assertEquals(expectedRole, result.track.roleTitle)
        assertEquals("tailor", result.track.pipelineAction)
        assertTrue(!result.track.artifactUrl.isNullOrBlank(), "tracks.artifact_url blank")
        assertTrue(!result.track.outputPath.isNullOrBlank(), "tracks.output_path blank")
    }

    private fun discordMessageDelivered(result: ScenarioResult) {
        assertTrue(harness.sink.unknownPaths().isEmpty(), "unexpected notification path(s): ${harness.sink.unknownPaths()}")
        val message = result.discordMessages.single()
        assertTrue(message.startsWith("• "), "unexpected Discord format: $message")
        assertTrue(message.contains("(TAILOR)"), "Discord message missing action: $message")
    }

    private fun completedFeedHasJob(result: ScenarioResult) {
        assertEquals(1, result.completedEvents.size)
        assertEquals("done", result.completedEvent.path("status").asText())
        assertTrue(result.completedEvent.path("completed_seq").asLong() > result.completedCursor)
    }

    private fun exactCallSequence(result: ScenarioResult) {
        assertEquals(
            listOf(
                "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation", "cover_letter",
            ),
            result.llmCalls,
            "call sequence drifted — a retry/refine fired or a node silently degraded",
        )
    }

    private fun tailoredYamlHasCannedContent(dir: Path) {
        val yaml = Files.readString(dir.resolve("tailored_resume.yaml"))
        assertTrue(yaml.contains("paved road"), "canned summary missing from tailored_resume.yaml")
        assertTrue(yaml.contains("Mobile Test Automation"), "canned skill group missing")
        assertTrue(
            yaml.contains(FakeLlmServer.BULLET_MARKER),
            "no rewritten bullet marker — bullet fold-back join matched nothing",
        )
    }

    private fun exactCoverLetter(result: ScenarioResult) {
        val expected = harness.fixture("llm/cover_letter.txt")
        val actual = harness.getString("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/cover_letter.txt").trim()
        assertEquals(expected, actual)
    }

    private fun telegramHighFitDelivered(result: ScenarioResult) {
        val message = result.telegramMessages.single()
        assertTrue(message.startsWith("High-fit:"), "unexpected Telegram format: $message")
        assertTrue(Regex("""—\s*72\s*$""").containsMatchIn(message), "Telegram message has wrong score: $message")
    }

    private fun artifactUrl(result: ScenarioResult): String =
        assertNotNull(result.artifactUrl, NO_ARTIFACT_URL_HINT)

    /** The output dir is derived from artifact_url, so it goes missing for the same reason. */
    private fun outputDir(result: ScenarioResult): Path =
        assertNotNull(result.outputDir, NO_ARTIFACT_URL_HINT)
}
