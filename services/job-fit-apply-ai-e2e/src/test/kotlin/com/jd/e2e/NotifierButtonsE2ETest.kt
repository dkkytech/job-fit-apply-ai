package com.jd.e2e

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the inline buttons on the notifier's high-fit Telegram ping, end to end through the
 * real Bridge → Processor → Notifier path with the mock sink standing in for Telegram.
 *
 * The suite's compose override enables the links and every action verb, so what reaches the wire
 * is decided by the event alone. A manually submitted job has no posting URL and no source email,
 * so it must get the links and **no** action button: an action it cannot qualify for would be a
 * dead button. (Action buttons on qualifying events are asserted in ExistingProductPathsE2ETest.)
 *
 * These assertions are wire-level: the sink receives the notifier's actual HTTP body, so a
 * button that exists in Kotlin but never reaches Telegram fails here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Notifier Telegram buttons (E2E)")
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class NotifierButtonsE2ETest {

    private lateinit var harness: E2eScenarioHarness
    private val expectedRole = "Staff Software Engineer in Test"

    @BeforeAll
    fun startHarness() {
        harness = SharedE2eHarness.start()
    }

    private fun runHighFitScenario(prefix: String): ScenarioResult {
        val nonce = System.currentTimeMillis().toString()
        val company = "$prefix $nonce"
        val jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company))
        return harness.runScenario(company) {
            submitScrapedJob(
                company = company,
                roleTitle = expectedRole,
                jdText = jdText,
                idempotencyKey = "e2e-buttons-$nonce",
            )
        }
    }

    /** GET, not HEAD: the bridge answers HEAD with 404 while GET is 200. */
    private fun statusOf(url: String): Int {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    @Test
    @DisplayName("high-fit ping carries View Report + View Resume, no ineligible action, and its #J ref")
    fun linkButtonsRideThePing() {
        val result = runHighFitScenario("E2E Buttons")

        // The ping must have landed at all before its keyboard means anything.
        assertTrue(
            result.telegramMessages.any { it.contains(result.company) },
            "no Telegram ping for '${result.company}': ${result.telegramMessages}",
        )

        assertEquals(
            listOf(listOf("View Report", "View Resume")),
            result.telegramButtonRows,
            "unexpected keyboard (a job with no posting URL and no source email qualifies for no action)",
        )
        val seq = result.completedEvent.path("completed_seq").asLong()
        val ping = result.telegramMessages.single { it.contains(result.company) }
        assertEquals("#J$seq", ping.lines().last(), "ping must end with its job reference: $ping")
    }

    @Test
    @DisplayName("View Report and View Resume are URL buttons pointing at the job's artifacts")
    fun buttonsAreLinksToRealArtifacts() {
        val result = runHighFitScenario("E2E Button URLs")
        val artifactUrl = assertNotNull(
            result.artifactUrl,
            "no artifact_url on the completed event — the buttons are derived from it",
        )

        val byName = result.telegramButtons.toMap()
        assertEquals(
            setOf("View Report", "View Resume"),
            byName.keys,
            "unexpected buttons: $byName",
        )

        val report = assertNotNull(byName["View Report"], "no View Report button")
        val resume = assertNotNull(byName["View Resume"], "no View Resume button")

        // Both must be URL buttons, not callbacks: a callback here would be a label sent to an
        // agent that never registered it.
        assertTrue(report.startsWith("http"), "View Report is not a URL button: $report")
        assertTrue(resume.startsWith("http"), "View Resume is not a URL button: $resume")

        // Report sits on the artifact directory; the dirname proves it targets *this* job.
        val dirName = URLDecoder.decode(
            artifactUrl.trimEnd('/').substringAfterLast('/'),
            Charsets.UTF_8,
        )
        assertTrue(
            report.contains(dirName),
            "View Report points at the wrong job.\n  url=$report\n  dir=$dirName",
        )
        assertTrue(report.endsWith("/report.md"), "View Report should open report.md, got: $report")
        assertTrue(
            resume.contains("/resume.pdf"),
            "View Resume should open the tailored PDF, got: $resume",
        )
    }

    @Test
    @DisplayName("both buttons resolve over HTTP (no dead links shipped)")
    fun buttonsActuallyResolve() {
        val result = runHighFitScenario("E2E Button Resolve")
        val urls = result.telegramButtons.filter { it.second.startsWith("http") }

        assertTrue(urls.isNotEmpty(), "no URL buttons to resolve: ${result.telegramButtons}")
        urls.forEach { (label, url) ->
            val code = statusOf(url)
            assertTrue(code in 200..299, "$label returned HTTP $code for $url (checked with GET)")
        }
    }
}
