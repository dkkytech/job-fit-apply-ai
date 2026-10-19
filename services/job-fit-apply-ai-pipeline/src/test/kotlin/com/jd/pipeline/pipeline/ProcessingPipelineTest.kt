package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.CheckDuplicateNode
import com.jd.pipeline.nodes.DraftReplyComposer
import com.jd.pipeline.nodes.Node
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("ProcessingPipelineTest")
class ProcessingPipelineTest {

    @BeforeEach
    fun setup() {
        CheckDuplicateNode.resetFallback()
    }

    private fun minimalRecord(jdText: String = "x".repeat(200)) = JdRecord(
        jdText     = jdText,
        company    = "Acme Corp",
        roleTitle  = "Staff SDET",
        location   = "Seattle, WA",
        jobUrl     = null,
        source     = IngestionSource.EMAIL,
    )

    private fun recruiterRecord() = minimalRecord().copy(
        intakeMeta = com.jd.pipeline.source.IntakeContext.Email(
            emailId = "e1", from = "rec@firm.com", subject = "Great role for you",
            rawBody = "body", htmlBody = "",
            isRecruiter = true, isDigest = false, isInlineDigest = false,
        ),
    )

    private fun injectNode(pipeline: ProcessingPipeline, fieldName: String, node: Node<JDState>) {
        val field = ProcessingPipeline::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(pipeline, node)
    }

    @Test
    @DisplayName("invoke returns SKIP with error when checkDuplicate throws")
    fun invokeCatchesCheckDuplicateException() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { _ ->
            throw RuntimeException("simulated checkDuplicate failure")
        })

        val result = pipeline.invoke(minimalRecord())

        assertEquals("SKIP", result.pipelineAction)
        assertEquals(0, result.fitScore)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("simulated checkDuplicate failure"))
    }

    @Test
    @DisplayName("invoke surfaces the record's scrapePath into the result, even on an early SKIP")
    fun invokeCarriesScrapePathIntoResult() {
        // Second half of the ingestion → run_log chain. Processing never scrapes, so it must pass
        // the ingestion-side value straight through; it previously seeded the JDState default ("")
        // and overwrote it, which is what blanked scrapePath for every logged job. Asserted on a
        // failure path too, because that is exactly where a browser problem shows up.
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { _ -> throw RuntimeException("boom") })

        val result = pipeline.invoke(minimalRecord().copy(scrapePath = "cdp_fallback"))

        assertEquals("SKIP", result.pipelineAction)
        assertEquals("cdp_fallback", result.scrapePath)
    }

    @Test
    @DisplayName("invoke returns SKIP with error when scoreFit throws")
    fun invokeCatchesScoreFitException() {
        val pipeline = ProcessingPipeline()
        // checkDuplicate must pass first (return non-duplicate state)
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { _ ->
            throw RuntimeException("simulated scoreFit failure")
        })

        val result = pipeline.invoke(minimalRecord())

        assertEquals("SKIP", result.pipelineAction)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("simulated scoreFit failure"))
        assertEquals(TerminalLabel.JD_ERROR, result.terminalLabel)
    }

    @Test
    @DisplayName("invoke returns SKIP when job is duplicate and not a recruiter email")
    fun invokeSkipsDuplicateNonRecruiter() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state ->
            state.copy(isDuplicate = true)
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        assertTrue(result.isDuplicate)
    }

    @Test
    @DisplayName("result carries the tracks row id that supabaseTrack wrote")
    fun resultCarriesTrackId() {
        // The completed feed hands this id to consumers (JobBot) so they can record lifecycle
        // events against the job's tracks row.
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state -> state.copy(pipelineAction = PipelineAction.SKIP, fitScore = 30f) })
        injectNode(pipeline, "supabaseTrack", Node { state -> state.copy(isSupabaseTracked = true, trackId = 42) })

        val result = pipeline.invoke(minimalRecord())

        assertEquals(42, result.trackId)
    }

    @Test
    @DisplayName("result trackId is null when no tracks row was written")
    fun resultTrackIdNullWithoutTrack() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = true) })
        // Tracking failed: the node records an error and never sets trackId.
        injectNode(pipeline, "supabaseTrack", Node { state -> state.copy(error = "supabase_track: down") })

        val result = pipeline.invoke(minimalRecord())

        assertNull(result.trackId)
    }

    @Test
    @DisplayName("invoke returns error result when tailor subgraph fails")
    fun invokeHandlesTailorSubgraphError() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = com.jd.pipeline.state.PipelineAction.TAILOR,
                fitScore = 90f,
            )
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            state.copy(error = "tailor subgraph failed")
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        // tailor error surfaces in the result
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("tailor subgraph failed"))
    }

    @Test
    @DisplayName("recruiter email is forced to TAILOR even when scoreFit returns SKIP")
    fun invokeForcesTailorForRecruiterLowScore() {
        val pipeline = ProcessingPipeline()
        var tailorCalled = false
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(pipelineAction = com.jd.pipeline.state.PipelineAction.SKIP, fitScore = 20f)
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            tailorCalled = true
            state.copy(error = "stop after tailor") // short-circuit before LLM cover-letter/PDF nodes
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        pipeline.invoke(recruiterRecord())

        // The recruiter override (ProcessingPipeline lines 81-83) must route a low-score
        // recruiter email through the tailor node anyway.
        assertTrue(tailorCalled, "recruiter email must be tailored even on a low fit score")
    }

    @Test
    @DisplayName("recruiter email is scored + tailored even when it is a duplicate")
    fun invokeDoesNotSkipDuplicateRecruiter() {
        val pipeline = ProcessingPipeline()
        var tailorCalled = false
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = true) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(pipelineAction = com.jd.pipeline.state.PipelineAction.SKIP, fitScore = 20f)
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            tailorCalled = true
            state.copy(error = "stop after tailor")
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        pipeline.invoke(recruiterRecord())

        // The duplicate guard (line 72) excludes recruiters, so a duplicate recruiter
        // email still proceeds through score + tailor.
        assertTrue(tailorCalled, "duplicate recruiter email must still be scored and tailored")
    }

    private fun nonRecruiterEmailRecord() = minimalRecord().copy(
        intakeMeta = IntakeContext.Email(
            emailId = "e9", from = "jobs@board.com", subject = "Role", rawBody = "b", htmlBody = "",
            isRecruiter = false, isDigest = false, isInlineDigest = false,
        ),
    )

    /** Wire a TAILOR pipeline that reaches the write-back step, with hermetic (no-LLM) nodes. */
    private fun tailorPipeline(tempDir: Path, composer: DraftReplyComposer): ProcessingPipeline {
        val pipeline = ProcessingPipeline(draftComposer = composer)
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state -> state.copy(pipelineAction = PipelineAction.TAILOR, fitScore = 90f) })
        injectNode(pipeline, "tailorSubgraph", Node { state -> state.copy(outputPath = tempDir.toString(), artifactUrl = "https://a/x") })
        injectNode(pipeline, "generateCoverLetter", Node { state -> state })
        injectNode(pipeline, "renderResumePdf", Node { state -> state })
        injectNode(pipeline, "addArtifactUrl", Node { state -> state.copy(outputPath = tempDir.toString(), artifactUrl = "https://a/x") })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })
        return pipeline
    }

    @Test
    @DisplayName("processed job result carries JD_Processed label + messageId, no draft")
    fun resultCarriesWritebackFieldsForProcessedJob(@TempDir tempDir: Path) {
        // Composer would throw if invoked — proves a non-recruiter job never composes a draft.
        val pipeline = tailorPipeline(tempDir, DraftReplyComposer(generate = { error("must not compose") }))

        val result = pipeline.invoke(nonRecruiterEmailRecord())

        assertEquals(TerminalLabel.JD_PROCESSED, result.terminalLabel)
        assertEquals("e9", result.messageId)
        assertFalse(result.isRecruiter)
        assertNull(result.draftText)
    }

    @Test
    @DisplayName("recruiter job result carries the composed draft + Recruiter label")
    fun resultCarriesDraftAndRecruiterLabel(@TempDir tempDir: Path) {
        val pipeline = tailorPipeline(tempDir, DraftReplyComposer(generate = { "DRAFT BODY" }))

        val result = pipeline.invoke(recruiterRecord())

        assertEquals("DRAFT BODY", result.draftText)
        assertTrue(result.isRecruiter)
        assertEquals(TerminalLabel.RECRUITER, result.terminalLabel)
        assertEquals("e1", result.messageId)
    }

    @Test
    @DisplayName("a failed draft reply keeps the tailored job and still flags it Recruiter_Response_Required")
    fun draftFailureKeepsTheJob(@TempDir tempDir: Path) {
        // The draft is composed after the résumé and cover letter exist. A draft-model timeout
        // used to fail the whole job as JD_Error and throw that work away.
        val pipeline = tailorPipeline(tempDir, DraftReplyComposer(generate = {
            throw com.jd.pipeline.client.TransientLlmFailure("LLM call to https://ollama.com/api/chat failed: request timed out")
        }))

        val result = pipeline.invoke(recruiterRecord())

        assertNull(result.error)
        assertNull(result.draftText)
        assertEquals("TAILOR", result.pipelineAction)
        assertEquals(TerminalLabel.RECRUITER, result.terminalLabel)
        assertEquals(tempDir.toString(), result.outputPath)
    }

    @Test
    @DisplayName("a transient failure is returned retryable with no terminal label, not as JD_Error")
    fun transientFailureIsRetryable() {
        // ScoreFitNode wraps a provider timeout in RetryableLlmError so the bridge can retry it.
        // invoke() used to catch it and label it JD_Error, so it never reached the retry queue.
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { _ ->
            throw com.jd.pipeline.client.RetryableLlmError(
                "score_fit: LLM call to http://llm/v1/chat/completions exceeded hard timeout of 195s",
                com.jd.pipeline.client.TransientLlmFailure("exceeded hard timeout of 195s"),
            )
        })

        val result = pipeline.invoke(minimalRecord())

        assertTrue(result.retryable)
        assertNull(result.terminalLabel)
        assertNotNull(result.error)
    }

    @Test
    @DisplayName("invoke calls MetadataUtils.writeMetadata after addArtifactUrl")
    fun invokeCallsWriteMetadataAfterAddArtifactUrl(@TempDir tempDir: Path) {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = 90f,
            )
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            state.copy(
                outputPath = tempDir.toString(),
                artifactUrl = "https://artifacts.example.com/test-job"
            )
        })
        injectNode(pipeline, "addArtifactUrl", Node { state ->
            state.copy(
                outputPath = tempDir.toString(),
                artifactUrl = "https://artifacts.example.com/test-job"
            )
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        pipeline.invoke(minimalRecord())

        // Verify metadata files were written by MetadataUtils.writeMetadata()
        val mdFile = tempDir.resolve("report.md")
        val jsonFile = tempDir.resolve("metadata.json")
        assertTrue(Files.exists(mdFile), "report.md should be created by writeMetadata")
        assertTrue(Files.exists(jsonFile), "metadata.json should be created by writeMetadata")

        val mdContent = Files.readString(mdFile)
        assertTrue(mdContent.contains("# Staff SDET — Acme Corp"), "report.md should contain job title header")
        assertTrue(mdContent.contains("## Job Details"), "report.md should contain job details section")
        assertTrue(mdContent.contains("Fit Score | 90.0 / 100"), "report.md should contain fit score")

        val jsonContent = Files.readString(jsonFile)
        assertTrue(jsonContent.contains("company"), "metadata.json should contain company field")
        assertTrue(jsonContent.contains("Acme Corp"), "metadata.json should contain company name")
    }

    @Test
    @DisplayName("invoke does not call writeMetadata when addArtifactUrl produces blank outputPath")
    fun invokeSkipsWriteMetadataWhenOutputPathBlank() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = PipelineAction.SKIP,
                fitScore = 30f,
            )
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        // When outputPath is blank, writeMetadata should be a no-op
        assertEquals(null, result.outputPath)
        assertEquals("SKIP", result.pipelineAction)
    }

    @Test
    @DisplayName("a pay-gated high fit gets scoring notes (report.md + artifact URL) beside score_fit.txt, no tailoring")
    fun gatedHighFitGetsScoringNotes(@TempDir tempDir: Path) {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = PipelineAction.SKIP,
                fitScore = 90f,
                company = "Sparksoft",
                roleTitle = "Automation Engineer",
                hardGateViolations = listOf("Posted pay (max \$85K) is below the \$145K target"),
                skippedReason = "Hard gate: Posted pay (max \$85K) is below the \$145K target",
                scoreOutputPath = tempDir.toString(),
            )
        })
        injectNode(pipeline, "tailorSubgraph", Node { _ -> error("a skipped job is never tailored") })
        injectNode(pipeline, "addArtifactUrl", Node { state -> state.copy(artifactUrl = "https://markserv.example/${state.outputPath.substringAfterLast('/')}/") })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        assertEquals("SKIP", result.pipelineAction)
        assertEquals(tempDir.toString(), result.outputPath)
        assertEquals("https://markserv.example/${tempDir.fileName}/", result.artifactUrl)
        assertEquals("Posted pay (max \$85K) is below the \$145K target", result.skipReason)
        val report = Files.readString(tempDir.resolve("report.md"))
        assertTrue(report.contains("Posted pay (max \$85K) is below the \$145K target"), "the report says why it was skipped")
        assertTrue(Files.exists(tempDir.resolve("metadata.json")))
    }

    @Test
    @DisplayName("a gated job below the threshold gets no notes (no card goes out for it)")
    fun gatedLowFitGetsNoNotes(@TempDir tempDir: Path) {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(pipelineAction = PipelineAction.SKIP, fitScore = 20f,
                hardGateViolations = listOf("Pure manual QA"), scoreOutputPath = tempDir.toString())
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        assertEquals(null, result.outputPath)
        assertEquals(null, result.artifactUrl)
        assertEquals("Pure manual QA", result.skipReason)
        assertFalse(Files.exists(tempDir.resolve("report.md")))
    }

    @Test
    @DisplayName("skip_reason: the threshold reason for an ungated skip, nothing for a tailored job")
    fun skipReasonOnlyForSkips() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(pipelineAction = PipelineAction.SKIP, fitScore = 30f, skippedReason = "Fit score below threshold")
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })
        assertEquals("Fit score below threshold", pipeline.invoke(minimalRecord()).skipReason)
    }

    @Test
    @DisplayName("the result carries the card details: location, remote policy, salary, source, strengths, gaps")
    fun resultCarriesCardDetails() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = PipelineAction.SKIP, fitScore = 30f, remotePolicy = "hybrid", salaryRange = "\$150K–\$180K",
                strengths = listOf("Kotlin test infrastructure"), gaps = listOf("No Pact experience", "Mid-level scope"),
            )
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord().copy(jobUrl = "https://www.jobright.ai/jobs/info/abc"))

        assertEquals("Seattle, WA", result.location)
        assertEquals("hybrid", result.remotePolicy)
        assertEquals("\$150K–\$180K", result.salaryRange)
        assertEquals("jobright.ai", result.source)
        assertEquals(listOf("Kotlin test infrastructure"), result.strengths)
        assertEquals(listOf("No Pact experience", "Mid-level scope"), result.gaps)
    }

    @Test
    @DisplayName("card details: an unknown remote policy and a blank salary are null; a recruiter email is the source")
    fun cardDetailsUnknownsAndRecruiter() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state -> state.copy(pipelineAction = PipelineAction.SKIP, fitScore = 30f) })
        injectNode(pipeline, "tailorSubgraph", Node { state -> state.copy(error = "stop here") })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val plain = pipeline.invoke(minimalRecord())
        assertNull(plain.remotePolicy, "\"unknown\" is not shown")
        assertNull(plain.salaryRange)
        assertNull(plain.source, "no job board, no URL")
        assertEquals("recruiter email", pipeline.invoke(recruiterRecord()).source)
    }

    @Test
    @DisplayName("invoke writes metadata with correct pipeline action in result")
    fun invokeWritesMetadataWithCorrectPipelineAction(@TempDir tempDir: Path) {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = 85f,
                company = "Meta",
                roleTitle = "Staff SDET",
            )
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            state.copy(
                outputPath = tempDir.toString(),
                artifactUrl = "https://artifacts.example.com/meta-sdet"
            )
        })
        injectNode(pipeline, "addArtifactUrl", Node { state ->
            state.copy(
                outputPath = tempDir.toString(),
                artifactUrl = "https://artifacts.example.com/meta-sdet"
            )
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        assertEquals("TAILOR", result.pipelineAction)
        assertEquals(85, result.fitScore)

        val jsonFile = tempDir.resolve("metadata.json")
        assertTrue(Files.exists(jsonFile), "metadata.json should exist")
        val jsonContent = Files.readString(jsonFile)
        assertTrue(jsonContent.contains("\"company\" : \"Meta\""), "metadata.json should contain company")
        assertTrue(jsonContent.contains("\"job_title\" : \"Staff SDET\""), "metadata.json should contain job title")
        assertTrue(jsonContent.contains("\"fit_score\" : 85"), "metadata.json should contain fit score")
    }

    @Test
    @DisplayName("MetadataUtils.writeMetadata is called in the correct order — after addArtifactUrl, before supabaseTrack")
    fun writeMetadataCalledInCorrectOrder(@TempDir tempDir: Path) {
        val callOrder = mutableListOf<String>()
        val pipeline = ProcessingPipeline()

        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = 90f,
            )
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            state.copy(
                outputPath = tempDir.toString(),
                artifactUrl = "https://artifacts.example.com/test"
            )
        })
        injectNode(pipeline, "addArtifactUrl", Node { state ->
            callOrder.add("addArtifactUrl")
            state.copy(
                outputPath = tempDir.toString(),
                artifactUrl = "https://artifacts.example.com/test"
            )
        })
        // We can't easily intercept writeMetadata since it's a static utility call,
        // but we can verify the files exist after the pipeline completes,
        // which proves writeMetadata was called after addArtifactUrl populated the state.
        injectNode(pipeline, "supabaseTrack", Node { state ->
            callOrder.add("supabaseTrack")
            state
        })

        pipeline.invoke(minimalRecord())

        // Verify call order: addArtifactUrl must come before supabaseTrack
        assertEquals(listOf("addArtifactUrl", "supabaseTrack"), callOrder,
            "addArtifactUrl must be called before supabaseTrack")

        // And since writeMetadata is between them, the files should exist
        assertTrue(Files.exists(tempDir.resolve("report.md")), "report.md should exist")
        assertTrue(Files.exists(tempDir.resolve("metadata.json")), "metadata.json should exist")
    }
}
