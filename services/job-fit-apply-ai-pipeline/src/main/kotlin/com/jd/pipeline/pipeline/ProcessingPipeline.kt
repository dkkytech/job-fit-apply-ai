package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.Node
import com.jd.pipeline.nodes.AddArtifactUrlNode
import com.jd.pipeline.nodes.CheckDuplicateNode
import com.jd.pipeline.nodes.GenerateCoverLetterNode
import com.jd.pipeline.nodes.DraftReplyComposer
import com.jd.pipeline.nodes.RenderResumePdfNode
import com.jd.pipeline.nodes.ScoreFitNode
import com.jd.pipeline.nodes.SupabaseTrackNode
import com.jd.pipeline.nodes.tailor.ResumeTailoringSubgraph
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isRecruiterEmail
import com.jd.pipeline.utils.MetadataUtils
import java.io.File

/**
 * Processing half of the pipeline: check_duplicate → score_fit → tailor →
 * generate_cover_letter → render_pdf → add_artifact_url → supabase_track.
 *
 * No Gmail calls. Consumed by the worker via [WorkerCommandHandler].
 */
class ProcessingPipeline(
    private val checkDuplicate: Node<JDState>      = CheckDuplicateNode(),
    private val scoreFit: Node<JDState>            = ScoreFitNode(),
    private val tailorSubgraph: Node<JDState>      = ResumeTailoringSubgraph(),
    private val generateCoverLetter: Node<JDState> = GenerateCoverLetterNode(),
    private val renderResumePdf: Node<JDState>     = RenderResumePdfNode(),
    private val addArtifactUrl: Node<JDState>      = AddArtifactUrlNode(),
    private val supabaseTrack: Node<JDState>       = SupabaseTrackNode(),
    private val draftComposer: DraftReplyComposer  = DraftReplyComposer(),
) {

    fun invoke(record: JdRecord): ProcessingResult {
        return try {
            invokeInternal(record)
        } catch (e: Exception) {
            System.err.println("[processing_pipeline] ERROR: ${e.message}")
            ProcessingResult(
                pipelineAction = PipelineAction.SKIP.name,
                fitScore       = 0,
                strengths      = emptyList(),
                isDuplicate    = false,
                outputPath     = null,
                hasCoverLetter = false,
                error          = e.message ?: "ProcessingPipeline failed",
                company        = record.company,
                roleTitle      = record.roleTitle,
                jobUrl         = record.jobUrl,
                // Carry it on the failure path too. A job that died in processing is precisely when
                // you want to know how its JD was fetched, and this fallback bypasses the normal
                // state → result mapping that would otherwise supply it.
                scrapePath     = record.scrapePath,
            )
        }
    }

    private fun invokeInternal(record: JdRecord): ProcessingResult {
        val profile = JDState.loadCandidateProfile()
        val intakeMeta = record.intakeMeta ?: IntakeContext.Synthetic("worker")

        var state = JDState(
            intake           = intakeMeta,
            isJobPosting     = true,
            jdText           = record.jdText,
            company          = record.company ?: "",
            roleTitle        = record.roleTitle ?: "",
            location         = record.location ?: "",
            jobUrl           = record.jobUrl ?: "",
            salaryRange      = record.salaryRange ?: "",
            remotePolicy     = record.remotePolicy ?: "unknown",
            employmentType   = record.employmentType ?: "",
            seniorityLevel   = record.seniorityLevel ?: "",
            yoeRequired      = record.yoeRequired,
            techStack        = record.techStack ?: emptyList(),
            candidateProfile = profile,
            // Seed from the record so the ingestion-side scrape path survives into the result, and
            // from there into the run_log. Processing never scrapes, so nothing overwrites this.
            scrapePath       = record.scrapePath,
        )

        val isRecruiter = state.isRecruiterEmail

        // 1. Duplicate check
        state = checkDuplicate.process(state)

        if (state.isDuplicate && !isRecruiter) {
            state = supabaseTrack.process(state)
            return toResult(state)
        }

        // 2. Score fit
        state = scoreFit.process(state)

        // Recruiter emails always get the full tailor treatment.
        if (isRecruiter && state.pipelineAction != PipelineAction.TAILOR) {
            state = state.copy(pipelineAction = PipelineAction.TAILOR, skippedReason = "")
        }

        if (state.pipelineAction != PipelineAction.TAILOR) {
            state = supabaseTrack.process(state)
            return toResult(state)
        }

        // 3. Tailor
        state = tailorSubgraph.process(state)
        if (state.error.isNotEmpty()) {
            System.err.println("[processing_pipeline] tailor failed: ${state.error}")
            state = supabaseTrack.process(state)
            return toResult(state)
        }

        // 4. Cover letter, PDF, artifact URL
        state = generateCoverLetter.process(state)
        state = renderResumePdf.process(state)
        state = addArtifactUrl.process(state)

        // 5. Recruiter reply: compose the draft body Gmail-free (the Poller delivers it).
        if (isRecruiter) {
            draftComposer.compose(state)?.let { body ->
                state = state.copy(draftText = body, isRecruiterResponseRequired = true)
            }
        }

        MetadataUtils.writeMetadata(state)
        state = supabaseTrack.process(state)

        return toResult(state)
    }

    private fun toResult(state: JDState): ProcessingResult {
        val outputPath = state.outputPath.takeIf { it.isNotBlank() }
        val hasCoverLetter = outputPath != null &&
            (state.coverLetter.isNotBlank() || File(outputPath, "cover_letter.txt").exists())

        return ProcessingResult(
            pipelineAction = state.pipelineAction.name,
            fitScore       = state.fitScore?.toInt() ?: 0,
            strengths      = state.strengths,
            isDuplicate    = state.isDuplicate,
            outputPath     = outputPath,
            hasCoverLetter = hasCoverLetter,
            error          = state.error.takeIf { it.isNotBlank() },
            artifactUrl    = state.artifactUrl.takeIf { it.isNotBlank() },
            // Processed-posting identity — for completed-feed consumers (Notifier).
            company        = state.company.takeIf { it.isNotBlank() },
            roleTitle      = state.roleTitle.takeIf { it.isNotBlank() },
            jobUrl         = state.jobUrl.takeIf { it.isNotBlank() },
            // Gmail write-back — the Poller labels the email and delivers any recruiter draft.
            terminalLabel  = TerminalLabel.forState(state),
            draftText      = state.draftText.takeIf { it.isNotBlank() },
            isRecruiter    = state.isRecruiterEmail,
            messageId      = state.emailIntake?.emailId,
            scrapePath     = state.scrapePath,
        )
    }
}
