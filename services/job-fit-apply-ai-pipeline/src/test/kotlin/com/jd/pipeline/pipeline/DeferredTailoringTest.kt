package com.jd.pipeline.pipeline

import com.jd.pipeline.client.Alert
import com.jd.pipeline.client.CloudQuota
import com.jd.pipeline.client.DeferredJob
import com.jd.pipeline.client.DeferredRelease
import com.jd.pipeline.nodes.CheckDuplicateNode
import com.jd.pipeline.nodes.DraftReplyComposer
import com.jd.pipeline.nodes.Node
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.TailorRequest
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tailoring needs the cloud models. While their quota is used up a job that should be tailored is
 * DEFERRED — scored, noted, tracked and pinged, its record handed back — instead of shipping the
 * base resume every failed node falls back to; the release sweep queues it once the quota is back.
 */
@DisplayName("Deferred tailoring while the cloud quota is out")
class DeferredTailoringTest {

    @BeforeEach
    fun setup() = CheckDuplicateNode.resetFallback()

    private val record = JdRecord(
        jdText = "x".repeat(200), company = "Acme Corp", roleTitle = "Staff SDET", location = "Seattle, WA",
        jobUrl = "https://jobs.example.com/qa/1", source = IngestionSource.EMAIL,
    )
    private val recruiter = record.copy(
        intakeMeta = IntakeContext.Email(
            emailId = "e1", from = "rec@firm.com", subject = "Role for you", rawBody = "body", htmlBody = "",
            isRecruiter = true, isDigest = false, isInlineDigest = false,
        ),
    )

    /** A pipeline scoring [score]; [quotaOut] answers the deferral check each time it is asked. */
    private class Rig(score: Float, tempDir: Path, quotaOut: () -> Boolean, degradedTailoring: Boolean = false) {
        val ran = mutableListOf<String>()
        var tracked: JDState? = null
        val pipeline = ProcessingPipeline(
            draftComposer = DraftReplyComposer { "Thanks — happy to talk." },
            autoTailorMinScore = 75f,
            tailoringDeferred = quotaOut,
        )

        init {
            fun set(field: String, node: Node<JDState>) =
                ProcessingPipeline::class.java.getDeclaredField(field).apply { isAccessible = true }.set(pipeline, node)
            set("checkDuplicate", Node { it })
            set("scoreFit", Node {
                ran += "score"
                it.copy(fitScore = score, pipelineAction = if (score >= 55f) PipelineAction.TAILOR else PipelineAction.SKIP, scoreOutputPath = tempDir.toString())
            })
            set("tailorSubgraph", Node {
                ran += "tailor"
                it.copy(outputPath = tempDir.resolve("tailored").toString(), tailoringDegradedNodes = if (degradedTailoring) listOf("summary_rewrite", "bullet_rewrite") else emptyList())
            })
            set("generateCoverLetter", Node { ran += "cover"; it })
            set("renderResumePdf", Node { ran += "pdf"; it })
            set("addArtifactUrl", Node { it.copy(artifactUrl = "https://markserv.example/${Path.of(it.outputPath).fileName}/") })
            set("supabaseTrack", Node { tracked = it; it.copy(trackId = it.existingTrackId ?: 101) })
        }
    }

    @Test
    fun `a job to tailor is deferred while the quota is out — noted, tracked, record handed back`(@TempDir tmp: Path) {
        val rig = Rig(score = 88f, tempDir = tmp, quotaOut = { true })

        val result = rig.pipeline.invoke(record)

        assertEquals("DEFERRED", result.pipelineAction)
        assertEquals(88, result.fitScore)
        assertEquals(listOf("score"), rig.ran, "no tailoring call is spent on a quota that is out")
        assertEquals("the cloud LLM quota is used up, so it is tailored when the quota resets", result.skipReason)
        assertTrue(Files.exists(tmp.resolve("report.md")), "the card links the scoring notes")
        assertEquals(PipelineAction.DEFERRED, rig.tracked?.pipelineAction)
        assertEquals(record.copy(intakeMeta = null), result.tailorRecord)
    }

    @Test
    fun `with the quota fine it is tailored as before`(@TempDir tmp: Path) {
        val rig = Rig(score = 88f, tempDir = tmp, quotaOut = { false })
        val result = rig.pipeline.invoke(record)
        assertEquals("TAILOR", result.pipelineAction)
        assertEquals(listOf("score", "tailor", "cover", "pdf"), rig.ran)
        assertNull(result.tailorRecord)
    }

    @Test
    fun `a deferred recruiter email stays in the inbox`(@TempDir tmp: Path) {
        val result = Rig(score = 40f, tempDir = tmp, quotaOut = { true }).pipeline.invoke(recruiter)
        assertEquals("DEFERRED", result.pipelineAction)
        assertEquals(TerminalLabel.RECRUITER, result.terminalLabel, "never archived without an answer")
        assertNull(result.draftText)
    }

    @Test
    fun `an offer is still an offer — the offer band is never tailored up front anyway`(@TempDir tmp: Path) {
        val result = Rig(score = 68f, tempDir = tmp, quotaOut = { true }).pipeline.invoke(record)
        assertEquals("OFFER", result.pipelineAction)
    }

    @Test
    fun `a tailor request during the outage is deferred again, on the same track`(@TempDir tmp: Path) {
        val rig = Rig(score = 68f, tempDir = tmp, quotaOut = { true })
        val result = rig.pipeline.invoke(record.copy(intakeMeta = null), TailorRequest(parentJobId = "job-1", trackId = 7))
        assertEquals("DEFERRED", result.pipelineAction)
        assertEquals(7, result.trackId)
        assertEquals(record.copy(intakeMeta = null), result.tailorRecord, "so the sweep can queue it again")
    }

    @Test
    fun `if the quota runs out part-way, the degraded resume is dropped and the job deferred`(@TempDir tmp: Path) {
        var calls = 0
        // Fine when tailoring starts, out by the time it ends.
        val rig = Rig(score = 88f, tempDir = tmp, quotaOut = { calls++ > 0 }, degradedTailoring = true)

        val result = rig.pipeline.invoke(record)

        assertEquals("DEFERRED", result.pipelineAction)
        assertEquals(listOf("score", "tailor"), rig.ran, "no cover letter or PDF for a degraded run")
        assertEquals(tmp.toString(), result.outputPath, "the card links the scoring notes, not the degraded output")
        assertFalse(result.hasCoverLetter)
    }

    @Test
    fun `a degraded run with the quota fine is published as before`(@TempDir tmp: Path) {
        val rig = Rig(score = 88f, tempDir = tmp, quotaOut = { false }, degradedTailoring = true)
        assertEquals("TAILOR", rig.pipeline.invoke(record).pipelineAction)
        assertTrue("pdf" in rig.ran)
    }

    // ── CloudQuota.isExhausted ───────────────────────────────────────────────

    @Test
    fun `the quota counts as exhausted past the recheck window, until a cloud success`() {
        var now = 0L
        val q = CloudQuota(recheckMs = 60_000L, clock = { now })
        assertFalse(q.isExhausted())
        q.markExhausted("weekly limit")
        now += 120_000L
        assertFalse(q.skipCloud(), "the next call may probe")
        assertTrue(q.isExhausted(), "but nothing has shown the quota is back")
        q.markHealthy()
        assertFalse(q.isExhausted())
    }

    // ── The release sweep ────────────────────────────────────────────────────

    private class FakeBridge(var answer: DeferredRelease = DeferredRelease()) {
        val calls = mutableListOf<Pair<Int, Int>>()
        var fail = false
        fun release(limit: Int, maxAgeDays: Int): DeferredRelease {
            calls += limit to maxAgeDays
            if (fail) throw java.io.IOException("bridge down")
            return answer
        }
    }

    @Test
    fun `the sweep releases a batch when the quota is back, and only expires while it is out`() {
        val bridge = FakeBridge()
        var out = true
        var now = 0L
        val sweep = DeferredTailorRelease(bridge::release, quotaExhausted = { out }, batch = 2, intervalMs = 900_000L, maxAgeDays = 5, alert = {}, clock = { now })

        sweep.maybeRun()
        assertEquals(listOf(0 to 5), bridge.calls, "while the quota is out: expire only")
        sweep.maybeRun()
        assertEquals(1, bridge.calls.size, "not again before the interval")

        out = false
        now += 900_000L
        sweep.maybeRun()
        assertEquals(2 to 5, bridge.calls.last(), "quota back: keep 2 in flight")
    }

    @Test
    fun `expired jobs get one tailor-by-hand alert listing them`() {
        val alerts = mutableListOf<Alert>()
        val bridge = FakeBridge(DeferredRelease(expired = listOf(
            DeferredJob("j1", company = "Acme", roleTitle = "Staff SDET", fitScore = 88, artifactUrl = "https://markserv.example/acme/"),
            DeferredJob("j2", company = "Initech", roleTitle = "QA Lead", fitScore = 81, jobUrl = "https://initech.example/jobs/2"),
        )))
        DeferredTailorRelease(bridge::release, quotaExhausted = { true }, maxAgeDays = 5, alert = { alerts += it }, clock = { 0L }).maybeRun()

        val alert = alerts.single()
        assertEquals("Deferred tailoring expired — tailor by hand", alert.title)
        assertTrue(alert.message.contains("• Acme — Staff SDET (88) https://markserv.example/acme/report.md"), alert.message)
        assertTrue(alert.message.contains("• Initech — QA Lead (81) https://initech.example/jobs/2"), alert.message)
        assertTrue(alert.message.startsWith("2 job(s) waited more than 5 days"), alert.message)
    }

    @Test
    fun `a failed sweep is logged, never thrown, and retried next interval`() {
        val bridge = FakeBridge().apply { fail = true }
        var now = 0L
        val sweep = DeferredTailorRelease(bridge::release, quotaExhausted = { false }, intervalMs = 1_000L, alert = { error("no alert") }, clock = { now })
        sweep.maybeRun()
        now += 1_000L
        bridge.fail = false
        sweep.maybeRun()
        assertEquals(2, bridge.calls.size)
    }

    @Test
    fun `a malformed answer or a failing alert never escapes the sweep`() {
        @Suppress("UNCHECKED_CAST")
        val nullAnswer = { _: Int, _: Int -> null as DeferredRelease? } as (Int, Int) -> DeferredRelease
        DeferredTailorRelease(nullAnswer, quotaExhausted = { false }, alert = {}, clock = { 0L }).maybeRun()
        val expiring = FakeBridge(DeferredRelease(expired = listOf(DeferredJob("j1"))))
        DeferredTailorRelease(expiring::release, quotaExhausted = { true }, alert = { error("telegram down") }, clock = { 0L }).maybeRun()
    }
}
