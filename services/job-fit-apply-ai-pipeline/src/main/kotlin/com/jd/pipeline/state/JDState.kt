package com.jd.pipeline.state

import com.jd.pipeline.config.Config
import com.jd.pipeline.models.EvidenceItem
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.ProfileLoader
import com.jd.pipeline.models.JdStructured
import com.jd.pipeline.source.IntakeContext

/**
 * JDState — typed, immutable pipeline state.
 *
 * Every node receives a JDState and returns a new one via copy().
 * No mutable HashMap, no unchecked casts.
 *
 * User profile is loaded once per JVM lifetime from [Config.CANDIDATE_PROFILE_PATH]
 * and threaded through the state so every node can access it without reloading.
 */
data class JDState(
    // ── Input ────────────────────────────────────────────────────────────────
    val intake: IntakeContext? = null,
    val expectedScanData: String = "",
    val scanTuningOutputDir: String = "",
    val scanComparisonReport: String = "",
    val scrapeJdTuningOutputDir: String = "",
    val scrapeJdComparisonReport: String = "",
    val jobUrl: String = "",
    // Pre-rendered page text supplied by the browser extension. When present, ScrapeJdNode
    // skips fetching (no server-side auth needed) and LLM-extracts directly from this text.
    val capturedText: String = "",

    // ── Scan node output ──────────────────────────────────────────────────────
    val isJobPosting: Boolean = false,
    val scrapedContent: String = "",
    val rawPageContent: String = "",
    val scrapePath: String = "",
    val jdText: String = "",
    val company: String = "",
    val roleTitle: String = "",
    val location: String = "",
    val salaryRange: String = "",
    val remotePolicy: String = "unknown",
    val employmentType: String = "",
    val seniorityLevel: String = "",
    val yoeRequired: Int? = null,
    val techStack: List<String> = emptyList(),
    val benefits: List<String> = emptyList(),
    val companyDescription: String = "",
    val jobBoard: String = "",

    // ── Score node output ─────────────────────────────────────────────────────
    val fitScore: Float? = null,
    val fitReasoning: String = "",
    val strengths: List<String> = emptyList(),
    val gaps: List<String> = emptyList(),
    val redFlags: List<String> = emptyList(),
    // Evidence-backed versions of strengths/gaps; claim strings mirror strengths/gaps above
    val strengthsWithEvidence: List<EvidenceItem> = emptyList(),
    val gapsWithEvidence: List<EvidenceItem> = emptyList(),
    // Per-dimension subscores keyed by dimension name (mobile, cicd, web_api, seniority, stack_overlap, location, domain)
    val dimensionScores: Map<String, Int> = emptyMap(),
    // Hard-gate violations — if non-empty, pipelineAction is forced to "skip" regardless of fit_score
    val hardGateViolations: List<String> = emptyList(),
    // Extracted JD compensation range and work arrangement for deterministic preference comparison
    val postedCompMin: Int? = null,
    val postedCompMax: Int? = null,
    // Unit of postedCompMin/Max: "year" (annual USD) or "hour" (USD per hour); blank when the model didn't say
    val postedCompPeriod: String = "",
    val workArrangement: String = "unknown",
    val officeLocation: String = "",
    // LLM confidence in the scoring (0.0–1.0); low values flag vague JDs for human review
    val scoreConfidence: Float? = null,
    // JD structure extracted alongside scoring — passed into the tailor subgraph
    val jdStructured: JdStructured? = null,

    // ── Routing ───────────────────────────────────────────────────────────────
    val pipelineAction: PipelineAction = PipelineAction.SKIP,

    // ── Tailor node output ────────────────────────────────────────────────────
    val outputPath: String = "",
    /** Where score_fit.txt was written: a skipped high fit's scoring notes go beside it. */
    val scoreOutputPath: String = "",
    val artifactUrl: String = "",
    val metadataUrl: String = "",
    // Tailoring nodes that failed and fell back to base content (empty = fully generated).
    val tailoringDegradedNodes: List<String> = emptyList(),
    val coverLetter: String = "",

    // ── HTML pipeline output ──────────────────────────────────────────────────
    val resumeHtmlPdf: String = "",

    // ── Supabase tracking output ──────────────────────────────────────────────
    val trackId: Int? = null,
    /**
     * Set on an on-demand tailor run (a TAILOR_REQUEST): the `tracks` row the original OFFER wrote.
     * Tracking then updates that row instead of inserting a second one for the same job.
     */
    val existingTrackId: Int? = null,
    val isSupabaseTracked: Boolean = false,
    val isDuplicate: Boolean = false,
    val duplicateId: Int? = null,

    // ── Digest expansion ──────────────────────────────────────────────────────
    val digestJobs: List<JDState> = emptyList(),
    /**
     * A digest child known only by its link (an employer job-alert email): the processor submits it
     * to the bridge's /api/urls under [postingKey] instead of scraping it here, so the bridge can
     * drop a posting it already has before anything is scraped.
     */
    val submitAsUrl: Boolean = false,
    /** The posting's dedupe identity for [submitAsUrl] children (see PostingLinks). */
    val postingKey: String = "",

    // ── User Profile ────────────────────────���─────────────────────────────────
    // Loaded once from config/candidate_profile.json; available to all nodes.
    val candidateProfile: CandidateProfile? = null,

    // ── Pipeline metadata ─────────────────────────────────────────────────────
    val error: String = "",
    val skippedReason: String = "",
    val isChromeSessionExpired: Boolean = false,
    val isRecruiterResponseRequired: Boolean = false,
    // Interview scheduling / assessment / application-status mail about a role already in progress.
    // Not a new posting even when the JD is attached: labelled JD_Application_Update and skipped.
    val isApplicationUpdate: Boolean = false,
    val draftId: String = "",
    // Recruiter reply body, generated Gmail-free by the Processor. The Poller delivers it
    // (creates the Gmail draft) via the bridge completed feed. Empty when not a recruiter reply.
    val draftText: String = ""
) {
    companion object {

        /**
         * Lazily load + merge the résumé (`resume.yaml`) and pipeline config
         * (`candidate_profile.yaml`) into one [CandidateProfile], once per JVM invocation.
         * Returns null if the résumé is missing or malformed (nodes handle null gracefully).
         */
        fun loadCandidateProfile(): CandidateProfile? = ProfileLoader.loadMergedProfile()

        // Singleton, loaded once per JVM — shared across all JDState instances
        private val SINGLETON_CANDIDATE_PROFILE: CandidateProfile? by lazy { loadCandidateProfile() }

        fun fromEmail(emailId: String, subject: String, from: String, body: String, htmlBody: String = ""): JDState =
            JDState(
                intake = IntakeContext.Email(
                    emailId = emailId,
                    from = from,
                    subject = subject,
                    rawBody = body,
                    htmlBody = htmlBody,
                    isRecruiter = false,
                    isDigest = false,
                    isInlineDigest = false,
                ),
                candidateProfile = SINGLETON_CANDIDATE_PROFILE,
            )
    }
}

val JDState.emailIntake: IntakeContext.Email? get() = intake as? IntakeContext.Email
val JDState.isFromEmail: Boolean get() = intake is IntakeContext.Email
val JDState.isDigest: Boolean get() = (intake as? IntakeContext.Email)?.isDigest == true
val JDState.isInlineDigest: Boolean get() = (intake as? IntakeContext.Email)?.isInlineDigest == true
val JDState.isRecruiterEmail: Boolean get() = (intake as? IntakeContext.Email)?.isRecruiter == true
