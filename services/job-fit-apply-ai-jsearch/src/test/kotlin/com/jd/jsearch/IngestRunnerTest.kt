package com.jd.jsearch

import com.jd.jsearch.bridge.JsearchBridgeClient
import com.jd.jsearch.bridge.SubmitJobRequest
import com.jd.jsearch.bridge.SubmitJobResponse
import com.jd.jsearch.client.JSearchClient
import com.jd.jsearch.model.JobListing
import com.jd.jsearch.state.RunState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("IngestRunner")
class IngestRunnerTest {

    private val NOW = 1_700_000_000_000L
    private val DAY = 86_400_000L

    private fun listing(id: String, jd: String, remote: Boolean = false) = JobListing(
        jobId = id, jobTitle = "SDET", employerName = "Acme",
        jobCity = "Seattle", jobState = "WA", jobIsRemote = remote,
        jobDescription = jd, jobApplyLink = "https://acme.co/$id",
    )

    private fun longJd() = "x".repeat(200)

    private fun runner(
        factoryCalls: AtomicInteger,
        client: JSearchClient,
        bridge: JsearchBridgeClient,
        state: RunState,
    ) = IngestRunner(
        clientFactory = { factoryCalls.incrementAndGet(); client },
        bridge = bridge,
        state = state,
        intervalMs = DAY,
    )

    @Test
    @DisplayName("skips (no API calls, no client built) when the last run is within the window")
    fun skipsWhenRecent(@TempDir dir: Path) {
        val state = RunState(dir.resolve("s.json")).apply { recordRun(NOW - 3_600_000L) } // 1h ago
        val calls = AtomicInteger(0)
        val client = mock<JSearchClient>()
        val bridge = mock<JsearchBridgeClient>()

        val outcome = runner(calls, client, bridge, state).runOnce(NOW)

        assertTrue(outcome is IngestRunner.Skipped)
        assertEquals(0, calls.get(), "client must not be built on a skip")
        verify(client, never()).search(any())
        verify(bridge, never()).submit(any())
    }

    @Test
    @DisplayName("fires on first run (no state) and submits only listings with jd_text >= 150")
    fun firesFirstRunAndFiltersShortJd(@TempDir dir: Path) {
        val state = RunState(dir.resolve("s.json"))
        val client = mock<JSearchClient> {
            on { search(any()) } doReturn listOf(listing("a", longJd()), listing("b", "too short"))
            on { callCount } doReturn 4
        }
        val bridge = mock<JsearchBridgeClient> {
            on { submit(any()) } doReturn SubmitJobResponse("job-a", "pending", deduped = false)
        }

        val outcome = runner(AtomicInteger(0), client, bridge, state).runOnce(NOW)

        assertTrue(outcome is IngestRunner.Ran)
        val ran = outcome as IngestRunner.Ran
        assertEquals(2, ran.fetched)
        assertEquals(1, ran.queued)     // only the long-jd listing submitted
        assertEquals(1, ran.skipped)    // the short one filtered before submit
        assertEquals(4, ran.calls)
        verify(bridge, org.mockito.kotlin.times(1)).submit(any())  // short jd never submitted
        assertNotNull(state.lastRun())  // run recorded
        assertEquals(NOW, state.lastRun())
    }

    @Test
    @DisplayName("fires when the last run is older than the window")
    fun firesWhenStale(@TempDir dir: Path) {
        val state = RunState(dir.resolve("s.json")).apply { recordRun(NOW - 25 * 3_600_000L) } // 25h ago
        val client = mock<JSearchClient> {
            on { search(any()) } doReturn listOf(listing("a", longJd()))
            on { callCount } doReturn 4
        }
        val bridge = mock<JsearchBridgeClient> { on { submit(any()) } doReturn SubmitJobResponse("j", "pending") }

        assertTrue(runner(AtomicInteger(0), client, bridge, state).runOnce(NOW) is IngestRunner.Ran)
    }

    @Test
    @DisplayName("counts deduped and failed submissions distinctly")
    fun countsDedupedAndFailed(@TempDir dir: Path) {
        val state = RunState(dir.resolve("s.json"))
        val client = mock<JSearchClient> {
            on { search(any()) } doReturn listOf(listing("a", longJd()), listing("b", longJd()), listing("c", longJd()))
            on { callCount } doReturn 4
        }
        val bridge = mock<JsearchBridgeClient>()
        whenever(bridge.submit(any()))
            .thenReturn(SubmitJobResponse("a", "pending", deduped = false))  // queued
            .thenReturn(SubmitJobResponse("b", "pending", deduped = true))   // deduped
            .thenReturn(null)                                                // failed (422/error)

        val ran = runner(AtomicInteger(0), client, bridge, state).runOnce(NOW) as IngestRunner.Ran
        assertEquals(1, ran.queued)
        assertEquals(1, ran.deduped)
        assertEquals(1, ran.skipped)   // the null/failed one
    }

    @Test
    @DisplayName("submits JSearch's structured fields so the report isn't blank")
    fun submitsStructuredFields(@TempDir dir: Path) {
        val l = listing("a", longJd(), remote = true).copy(
            jobEmploymentType = "FULLTIME", jobMinSalary = 101_000.0, jobMaxSalary = 127_000.0,
            jobSalaryPeriod = "YEAR", jobRequiredExperience = JobListing.RequiredExperience(72),
            jobRequiredSkills = listOf("Appium"),
        )
        val client = mock<JSearchClient> {
            on { search(any()) } doReturn listOf(l)
            on { callCount } doReturn 1
        }
        val bridge = mock<JsearchBridgeClient> { on { submit(any()) } doReturn SubmitJobResponse("j", "pending") }

        runner(AtomicInteger(0), client, bridge, RunState(dir.resolve("s.json"))).runOnce(NOW)

        val sent = argumentCaptor<SubmitJobRequest>().also { verify(bridge).submit(it.capture()) }.firstValue
        assertEquals("\$101K - \$127K/yr", sent.salaryRange)
        assertEquals("remote", sent.remotePolicy)
        assertEquals("Full-time", sent.employmentType)
        assertEquals(6, sent.yoeRequired)
        assertEquals(listOf("Appium"), sent.techStack)
    }
}
