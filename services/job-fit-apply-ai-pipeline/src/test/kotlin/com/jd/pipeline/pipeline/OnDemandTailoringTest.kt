package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.CheckDuplicateNode
import com.jd.pipeline.nodes.Node
import com.jd.pipeline.nodes.SupabaseTrackNode
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.TailorRequest
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.testutils.MockSupabaseClient
import com.jd.pipeline.utils.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AUTO_TAILOR_MIN_SCORE: fits below it are OFFERed (scored, tracked, pinged with a Tailor button)
 * instead of tailored, and a Tailor tap (a TAILOR_REQUEST) tailors the stored record whatever it
 * scores, updating the OFFER's tracks row.
 */
@DisplayName("On-demand tailoring (OFFER + TAILOR_REQUEST)")
class OnDemandTailoringTest {

    @BeforeEach
    fun setup() = CheckDuplicateNode.resetFallback()

    private val record = JdRecord(
        jdText = "x".repeat(200), company = "Acme Corp", roleTitle = "Staff SDET", location = "Seattle, WA",
        jobUrl = "https://jobs.example.com/qa/1", source = IngestionSource.EMAIL, salaryRange = "$150k-$180k",
        intakeMeta = IntakeContext.Api(board = "jsearch"),
    )

    private val recruiter = record.copy(
        intakeMeta = IntakeContext.Email(
            emailId = "e1", from = "rec@firm.com", subject = "Role for you", rawBody = "body", htmlBody = "",
            isRecruiter = true, isDigest = false, isInlineDigest = false,
        ),
    )

    private fun inject(pipeline: ProcessingPipeline, field: String, node: Node<JDState>) =
        ProcessingPipeline::class.java.getDeclaredField(field).apply { isAccessible = true }.set(pipeline, node)

    /** A pipeline whose scorer answers [score] (TAILOR at ≥55, like FIT_THRESHOLD) and whose other nodes record. */
    private class Rig(score: Float, tempDir: Path, autoTailor: Float = 75f) {
        val ran = mutableListOf<String>()
        var tracked: JDState? = null
        // A canned draft: the recruiter path must never reach a real LLM from a unit test.
        val pipeline = ProcessingPipeline(
            draftComposer = com.jd.pipeline.nodes.DraftReplyComposer { "Thanks — happy to talk." },
            autoTailorMinScore = autoTailor,
        )

        init {
            fun set(field: String, node: Node<JDState>) =
                ProcessingPipeline::class.java.getDeclaredField(field).apply { isAccessible = true }.set(pipeline, node)
            set("checkDuplicate", Node { ran += "dup"; it.copy(isDuplicate = true) })
            set("scoreFit", Node {
                ran += "score"
                it.copy(
                    fitScore = score,
                    pipelineAction = if (score >= 55f) PipelineAction.TAILOR else PipelineAction.SKIP,
                    skippedReason = if (score >= 55f) "" else "Fit score below threshold",
                    scoreOutputPath = tempDir.toString(),
                )
            })
            set("tailorSubgraph", Node { ran += "tailor"; it.copy(outputPath = tempDir.toString()) })
            set("generateCoverLetter", Node { ran += "cover"; it })
            set("renderResumePdf", Node { it })
            set("addArtifactUrl", Node { it.copy(artifactUrl = "https://markserv.example/report/") })
            set("supabaseTrack", Node { tracked = it; it.copy(trackId = it.existingTrackId ?: 101) })
        }
    }

    @Test
    fun `a fit below the auto-tailor score is offered — scored, noted and tracked, not tailored`(@TempDir tmp: Path) {
        val rig = Rig(score = 68f, tempDir = tmp).also { it.pipeline.let { p -> inject(p, "checkDuplicate", Node { s -> s }) } }

        val result = rig.pipeline.invoke(record)

        assertEquals("OFFER", result.pipelineAction)
        assertEquals(68, result.fitScore)
        assertEquals("Fit 68 is below the auto-tailor score (75)", result.skipReason)
        assertTrue("tailor" !in rig.ran && "cover" !in rig.ran, "an offer is not tailored: ${rig.ran}")
        assertEquals("https://markserv.example/report/", result.artifactUrl, "the card links the scoring notes")
        assertTrue(Files.exists(tmp.resolve("report.md")))
        assertEquals(PipelineAction.OFFER, rig.tracked?.pipelineAction)
        assertEquals(101, result.trackId)
        // The record comes back for the Tailor tap — minus the intake, which has no JSON form.
        assertEquals(record.copy(intakeMeta = null), result.tailorRecord)
    }

    @Test
    fun `at or above the auto-tailor score it is tailored as before, with no record handed back`(@TempDir tmp: Path) {
        val rig = Rig(score = 75f, tempDir = tmp).also { inject(it.pipeline, "checkDuplicate", Node { s -> s }) }

        val result = rig.pipeline.invoke(record)

        assertEquals("TAILOR", result.pipelineAction)
        assertTrue("tailor" in rig.ran)
        assertNull(result.tailorRecord)
    }

    @Test
    fun `below the fit threshold it is still a plain skip`(@TempDir tmp: Path) {
        val rig = Rig(score = 40f, tempDir = tmp).also { inject(it.pipeline, "checkDuplicate", Node { s -> s }) }
        val result = rig.pipeline.invoke(record)
        assertEquals("SKIP", result.pipelineAction)
        assertNull(result.tailorRecord)
    }

    @Test
    fun `recruiter emails are always tailored, whatever the auto-tailor score`(@TempDir tmp: Path) {
        val rig = Rig(score = 60f, tempDir = tmp).also { inject(it.pipeline, "checkDuplicate", Node { s -> s }) }
        val result = rig.pipeline.invoke(recruiter)
        assertEquals("TAILOR", result.pipelineAction)
        assertTrue("tailor" in rig.ran)
    }

    @Test
    fun `the default auto-tailor score equals FIT_THRESHOLD, so nothing is offered until it is set`(@TempDir tmp: Path) {
        val rig = Rig(score = 56f, tempDir = tmp, autoTailor = com.jd.pipeline.config.Config.FIT_THRESHOLD)
            .also { inject(it.pipeline, "checkDuplicate", Node { s -> s }) }
        // Config.FIT_THRESHOLD defaults to 50 in tests; a 56 that fits is tailored outright.
        assertEquals("TAILOR", rig.pipeline.invoke(record).pipelineAction)
    }

    @Test
    fun `a tailor request skips the duplicate check, tailors even a low re-score, and reuses the track`(@TempDir tmp: Path) {
        val rig = Rig(score = 40f, tempDir = tmp)   // re-scored low AND checkDuplicate would say duplicate

        val result = rig.pipeline.invoke(record.copy(intakeMeta = null), TailorRequest(parentJobId = "job-1", trackId = 7, jobBoard = "jobright.ai"))

        assertEquals("TAILOR", result.pipelineAction)
        assertEquals(listOf("score", "tailor", "cover"), rig.ran, "no duplicate check; the tap is the decision")
        assertEquals(7, rig.tracked?.existingTrackId)
        assertEquals(7, result.trackId)
        assertEquals("jobright.ai", result.source, "the card keeps the board the offer showed")
        assertNull(result.tailorRecord)
        assertNull(result.messageId, "no Gmail write-back for a tap")
    }

    // ── Tracking: update the OFFER's row instead of inserting a second ───────

    private fun trackState(existing: Int?) = JDState(
        company = "Acme Corp", roleTitle = "Staff SDET", fitScore = 70f,
        pipelineAction = PipelineAction.TAILOR, existingTrackId = existing,
    )

    @Test
    fun `tracking updates the existing row on a tailor run, and inserts otherwise`() {
        val db = MockSupabaseClient()
        val offered = db.insert("tracks", mapOf("company" to "Acme Corp", "pipeline_action" to "offer")).path("id").asInt()

        val tailored = SupabaseTrackNode(db).process(trackState(existing = offered))
        assertEquals(offered, tailored.trackId)
        assertEquals(1, db.getTrackCount(), "one row per job")
        assertEquals("tailor", db.query("tracks", mapOf("id" to "eq.$offered")).single().path("pipeline_action").asText())

        val fresh = SupabaseTrackNode(db).process(trackState(existing = null))
        assertNotNull(fresh.trackId)
        assertEquals(2, db.getTrackCount())
    }

    @Test
    fun `a tailor run whose offer row is gone inserts a fresh one`() {
        val db = MockSupabaseClient()
        val tracked = SupabaseTrackNode(db).process(trackState(existing = 999_999))
        assertNotNull(tracked.trackId)
        assertEquals(1, db.getTrackCount())
    }

    // ── The bridge payload ───────────────────────────────────────────────────

    @Test
    fun `an OFFER result serializes tailor_record, and a TAILOR_REQUEST claim parses it back`() {
        val result = com.jd.pipeline.source.ProcessingResult(
            pipelineAction = "OFFER", fitScore = 68, strengths = emptyList(), isDuplicate = false,
            outputPath = null, hasCoverLetter = false, tailorRecord = record.copy(intakeMeta = null),
        )
        val posted = Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(result)
        val stored = posted.path("tailor_record")
        assertEquals("Staff SDET", stored.path("role_title").asText())
        assertEquals("$150k-$180k", stored.path("salary_range").asText())

        // What the bridge's /tailor endpoint queues: the stored record plus the job's links.
        val claim = Json.mapper.readTree(
            """{"job_id":"t-1","type":"TAILOR_REQUEST","claim_token":"tok","jd_record":
               {"record":$stored,"parent_job_id":"job-1","track_id":7,"job_board":"jobright.ai"}}""",
        )
        val parsed = com.jd.pipeline.client.parseClaimTree(claim)
        assertEquals(record.copy(intakeMeta = null), parsed.tailor?.record)
        assertEquals("job-1", parsed.tailor?.parentJobId)
        assertEquals(7, parsed.tailor?.trackId)
        assertEquals("jobright.ai", parsed.tailor?.jobBoard)
        assertEquals("tok", parsed.claimToken)
    }
}
