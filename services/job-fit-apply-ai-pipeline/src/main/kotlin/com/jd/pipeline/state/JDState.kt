package com.jd.pipeline.state

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.config.Config
import com.jd.pipeline.models.EvidenceItem
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.nodes.tailor.JdStructured
import com.jd.pipeline.source.IntakeContext
import java.nio.file.Files

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

    // ── Scan node output ──────────────────────────────────────────────────────
    val isJobPosting: Boolean = false,
    val scrapedContent: String = "",
    val rawPageContent: String = "",
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
    val artifactUrl: String = "",
    val metadataUrl: String = "",
    val coverLetter: String = "",

    // ── HTML pipeline output ──────────────────────────────────────────────────
    val resumeHtmlPdf: String = "",

    // ── Supabase tracking output ──────────────────────────────────────────────
    val trackId: Int? = null,
    val trackUrl: String = "",
    val isSupabaseTracked: Boolean = false,
    val isDuplicate: Boolean = false,
    val duplicateId: Int? = null,

    // ── Digest expansion ──────────────────────────────────────────────────────
    val digestJobs: List<JDState> = emptyList(),

    // ── User Profile ────────────────────────���─────────────────────────────────
    // Loaded once from config/candidate_profile.json; available to all nodes.
    val candidateProfile: CandidateProfile? = null,

    // ── Pipeline metadata ─────────────────────────────────────────────────────
    val error: String = "",
    val skippedReason: String = "",
    val isChromeSessionExpired: Boolean = false,
    val isRecruiterResponseRequired: Boolean = false,
    val draftId: String = ""
) {
    companion object {
        private val MAPPER = ObjectMapper().registerKotlinModule()

        /**
         * Lazily load the user profile once per JVM invocation.
         * Returns null if the file is missing or malformed (nodes handle null gracefully).
         */
        fun loadCandidateProfile(): CandidateProfile? {
            val path = Config.CANDIDATE_PROFILE_PATH
            if (!Files.exists(path)) {
                System.err.println("[JDState] CANDIDATE_PROFILE_PATH not found: $path — create config/candidate_profile.json to enable structured user data")
                return null
            }
            return try {
                val json = Files.readString(path)
                MAPPER.readValue(json, CandidateProfile::class.java)
            } catch (e: Exception) {
                System.err.println("[JDState] Failed to parse candidate_profile.json: ${e.message}")
                null
            }
        }

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
