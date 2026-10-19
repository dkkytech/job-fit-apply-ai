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
import com.jd.pipeline.source.TailorRequest
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isRecruiterEmail
import com.jd.pipeline.client.CloudQuota
import com.jd.pipeline.client.TransientFailureClassifier
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.MetadataUtils
import com.jd.pipeline.utils.OutputUtils
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
    private val autoTailorMinScore: Float          = Config.AUTO_TAILOR_MIN_SCORE,
    /** Tailoring needs the cloud models: while their quota is used up, defer instead of degrading. */
    private val tailoringDeferred: () -> Boolean   = { CloudQuota.shared.isExhausted() },
) {

    /**
     * Process [record]. With [tailor] set (a Tailor tap on an OFFERed job) it is re-scored for the
     * tailoring inputs and then tailored whatever the score — the tap is the decision — and the
     * original `tracks` row is updated rather than duplicated.
     */
    fun invoke(record: JdRecord, tailor: TailorRequest? = null): ProcessingResult {
        return try {
            invokeInternal(record, tailor)
        } catch (e: Exception) {
            System.err.println("[processing_pipeline] ERROR: ${e.message}")
            // A transient provider failure (ScoreFitNode's RetryableLlmError, an LLM timeout) goes
            // back to the bridge's retry queue. Catching it here without this check is what turned
            // every one of them into a terminal JD_Error — the processor's own retry classification
            // never saw the exception.
            val retryable = TransientFailureClassifier.isRetryable(e)
            ProcessingResult(
                pipelineAction = PipelineAction.SKIP.name,
                fitScore       = 0,
                strengths      = emptyList(),
                isDuplicate    = false,
                outputPath     = null,
                hasCoverLetter = false,
                error          = e.message ?: "ProcessingPipeline failed",
                retryable      = retryable,
                // A processing failure is never evidence that the email is not a job. Without an
                // explicit label the Poller's compatibility fallback converts this into JD_Not_Found.
                // A retryable one carries no label: the bridge defers it, or applies JD_Error itself
                // once the retry budget is spent.
                terminalLabel  = if (retryable) null else TerminalLabel.JD_ERROR,
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

    private fun invokeInternal(record: JdRecord, tailor: TailorRequest?): ProcessingResult {
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
            jobBoard         = tailor?.jobBoard.orEmpty(),
            existingTrackId  = tailor?.trackId,
        )

        val isRecruiter = state.isRecruiterEmail

        // 1. Duplicate check — not for a tailor request: its own OFFER is the "duplicate".
        if (tailor == null) state = checkDuplicate.process(state)

        if (state.isDuplicate && !isRecruiter) {
            state = supabaseTrack.process(state)
            return toResult(state)
        }

        // 2. Score fit
        state = scoreFit.process(state)

        // Recruiter emails always get the full tailor treatment, and so does a job tapped for it.
        if ((isRecruiter || tailor != null) && state.pipelineAction != PipelineAction.TAILOR) {
            state = state.copy(pipelineAction = PipelineAction.TAILOR, skippedReason = "")
        }

        // A fit below the auto-tailor score is offered rather than tailored up front.
        if (tailor == null && !isRecruiter && state.pipelineAction == PipelineAction.TAILOR &&
            (state.fitScore ?: 0f) < autoTailorMinScore
        ) {
            val score = state.fitScore?.toInt() ?: 0
            state = state.copy(
                pipelineAction = PipelineAction.OFFER,
                skippedReason = "Fit $score is below the auto-tailor score (${autoTailorMinScore.toInt()})",
            )
        }

        // The cloud quota is out: every tailoring node would fail and ship the base resume. Defer.
        if (state.pipelineAction == PipelineAction.TAILOR && tailoringDeferred()) state = deferred(state, isRecruiter)

        if (state.pipelineAction != PipelineAction.TAILOR) return untailored(state, record)

        // 3. Tailor
        state = tailorSubgraph.process(state)
        // The quota ran out part-way: the nodes that failed fell back to the base resume. Defer
        // rather than publish that.
        if (state.tailoringDegradedNodes.isNotEmpty() && tailoringDeferred()) {
            System.err.println("[processing_pipeline] cloud quota ran out while tailoring (${state.tailoringDegradedNodes}) — deferring")
            return untailored(deferred(state.copy(outputPath = "", tailoringDegradedNodes = emptyList()), isRecruiter), record)
        }
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
        if (isRecruiter) state = composeDraft(state)

        MetadataUtils.writeMetadata(state)
        state = supabaseTrack.process(state)

        return toResult(state)
    }

    /**
     * A scored job that is not tailored now: a skip, an OFFER (Tailor on a tap) or a DEFERRED one
     * (tailored when the quota is back). The last two hand the record back for that later run.
     */
    private fun untailored(scored: JDState, record: JdRecord): ProcessingResult {
        var state = scored
        val later = state.pipelineAction == PipelineAction.OFFER || state.pipelineAction == PipelineAction.DEFERRED
        if (later || isGatedHighFit(state)) state = scoringNotes(state)
        state = supabaseTrack.process(state)
        val result = toResult(state)
        return if (later) result.copy(tailorRecord = record.copy(intakeMeta = null)) else result
    }

    /** A job that should be tailored now, deferred until the cloud quota is back. */
    private fun deferred(state: JDState, isRecruiter: Boolean): JDState = state.copy(
        pipelineAction = PipelineAction.DEFERRED,
        skippedReason = "the cloud LLM quota is used up, so it is tailored when the quota resets",
        // A recruiter email stays in the inbox (Recruiter_Response_Required) until it is answered.
        isRecruiterResponseRequired = state.isRecruiterResponseRequired || isRecruiter,
    )

    /**
     * The reply is the last, cheapest step of a recruiter job, after the résumé and cover letter
     * are already built. A draft-model failure must not throw those away: before this, a slow
     * DRAFT_REPLY_MODEL call that timed out failed the whole job as JD_Error. Without a draft the
     * email is still labelled Recruiter_Response_Required, so it stays flagged for a reply.
     */
    private fun composeDraft(state: JDState): JDState {
        val body = try {
            draftComposer.compose(state) ?: return state
        } catch (e: Exception) {
            System.err.println("[processing_pipeline] WARN: draft reply failed, labelling without a draft: ${e.message}")
            return state.copy(isRecruiterResponseRequired = true)
        }
        return state.copy(draftText = body, isRecruiterResponseRequired = true)
    }

    /**
     * Scored at or above the threshold, but a hard gate (pay below target, location) skipped it.
     * Richard still gets the Telegram card, so it needs a report to link to.
     */
    private fun isGatedHighFit(state: JDState): Boolean =
        state.pipelineAction == PipelineAction.SKIP && state.hardGateViolations.isNotEmpty() &&
            (state.fitScore?.toDouble() ?: 0.0) >= Config.FIT_THRESHOLD

    /**
     * The skipped job's scoring notes: report.md and metadata.json beside its score_fit.txt, with
     * an artifact URL so the card's View Report and the backlog can link to them. No resume.
     */
    private fun scoringNotes(state: JDState): JDState = try {
        val dir = state.scoreOutputPath.ifBlank { OutputUtils.getOutputDirectory(state).toString() }
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir))
        val withDir = addArtifactUrl.process(state.copy(outputPath = dir))
        MetadataUtils.writeMetadata(withDir)
        withDir
    } catch (e: Exception) {
        // The card goes out without a report link; the job itself is unaffected.
        System.err.println("[processing_pipeline] WARN: scoring notes for a skipped high fit failed: ${e.message}")
        state
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
            trackId        = state.trackId,
            // Gmail write-back — the Poller labels the email and delivers any recruiter draft.
            terminalLabel  = TerminalLabel.forState(state),
            draftText      = state.draftText.takeIf { it.isNotBlank() },
            isRecruiter    = state.isRecruiterEmail,
            messageId      = state.emailIntake?.emailId,
            scrapePath     = state.scrapePath,
            // Why a scored job was not tailored — the card says it (all gates, not just the first).
            skipReason     = skipReasonOf(state),
            location       = state.location.takeIf { it.isNotBlank() },
            remotePolicy   = state.remotePolicy.takeIf { it.isNotBlank() && it != "unknown" },
            salaryRange    = state.salaryRange.takeIf { it.isNotBlank() },
            source         = sourceOf(state),
            gaps           = state.gaps,
        )
    }

    /** The job board it came from, or "recruiter email"; null when neither is known. */
    private fun sourceOf(state: JDState): String? =
        if (state.isRecruiterEmail) "recruiter email" else MetadataUtils.jobBoard(state).takeIf { it.isNotBlank() }

    private fun skipReasonOf(state: JDState): String? = when {
        state.pipelineAction == PipelineAction.TAILOR -> null
        state.hardGateViolations.isNotEmpty() -> state.hardGateViolations.joinToString("; ")
        else -> state.skippedReason.takeIf { it.isNotBlank() }
    }
}
