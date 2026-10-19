package com.jd.pipeline.source

import com.fasterxml.jackson.annotation.JsonIgnore

enum class IngestionSource { EMAIL, JSEARCH, MANUAL, EXTENSION, CAREER_SITE }

data class JdRecord(
    val jdText: String,
    val company: String?,
    val roleTitle: String?,
    val location: String?,
    val jobUrl: String?,
    val source: IngestionSource,
    val idempotencyKey: String? = null,
    val intakeMeta: IntakeContext? = null,
    // Scrape-extracted fields carried across the ingestion → processing queue boundary.
    // Nullable so the bridge JSON round-trip stays backward compatible with older records.
    val salaryRange: String? = null,
    val remotePolicy: String? = null,
    val employmentType: String? = null,
    val seniorityLevel: String? = null,
    val yoeRequired: Int? = null,
    val techStack: List<String>? = null,
    /**
     * How this record's JD text was obtained, carried from ingestion (where the scrape actually
     * happens) into processing so it reaches the run_log. Without it the analyzer sees "" for every
     * job and browser-scraping health is invisible — see [ProcessingResult.scrapePath].
     *
     * `@get:JsonIgnore` because the EMAIL_RAW path hands this record straight to
     * [com.jd.pipeline.pipeline.ProcessingPipeline] in-process, so it needs no serialization; keeping
     * it out of the JSON leaves the bridge's queue payload contract untouched. The consequence is
     * that digest children, which DO round-trip through the bridge, arrive with "" — correct anyway,
     * since a JD_SCRAPED child carries the digest's text and never scrapes at claim time.
     */
    @get:JsonIgnore val scrapePath: String = "",
)

data class ProcessingResult(
    val pipelineAction: String,
    val fitScore: Int,
    val strengths: List<String>,
    val isDuplicate: Boolean,
    val outputPath: String?,
    val hasCoverLetter: Boolean,
    val error: String? = null,
    /** A transient provider failure; the bridge must defer it instead of completing the job. */
    val retryable: Boolean = false,
    val artifactUrl: String? = null,
    // Processed-posting identity — persisted by the bridge for completed-feed consumers (Notifier).
    // (serialized snake_case → company / role_title / job_url / artifact_url)
    val company: String? = null,
    val roleTitle: String? = null,
    val jobUrl: String? = null,
    // The `tracks` row this job wrote (serialized → track_id), so feed consumers can record
    // lifecycle events against it. Null when tracking failed or never ran.
    val trackId: Int? = null,
    // Gmail write-back — the Poller acts on these via the bridge completed feed.
    // (serialized snake_case → terminal_label / draft_text / is_recruiter / message_id)
    val terminalLabel: String? = null,
    val draftText: String? = null,
    val isRecruiter: Boolean = false,
    val messageId: String? = null,
    // How the JD text was obtained ("http", "cdp_profile", "cdp_forced", "cdp_fallback",
    // "captured", "blocked", "empty"). Internal-only: carried to the run_log for the analyzer,
    // never posted to the bridge feed (@get:JsonIgnore keeps it out of the completed-event DTO).
    @get:JsonIgnore val scrapePath: String = "",
    /** Why a scored job was not tailored (serialized → skip_reason); null when tailored. */
    val skipReason: String? = null,
    // Card details for the Telegram high-fit ping (serialized → location / remote_policy /
    // salary_range / source / gaps; strengths above). Null or empty when unknown.
    val location: String? = null,
    val remotePolicy: String? = null,
    val salaryRange: String? = null,
    /** Where the job came from: the job board ("jobright.ai") or "recruiter email". */
    val source: String? = null,
    val gaps: List<String> = emptyList(),
    /**
     * Only on an OFFER (serialized → tailor_record): the record as processed, so a later Tailor tap
     * can re-run it as a TAILOR_REQUEST. The bridge keeps it on the job — for an email job it is the
     * only copy of the resolved JD, which never round-trips through the bridge otherwise.
     */
    val tailorRecord: JdRecord? = null,
)

/**
 * An on-demand tailor run, from a TAILOR_REQUEST work item: [parentJobId] is the bridge job that was
 * OFFERed, [trackId] the `tracks` row it wrote (updated instead of inserting a second), and
 * [jobBoard] the board the card showed, which the record alone can't always recover.
 */
data class TailorRequest(
    val parentJobId: String,
    val trackId: Int? = null,
    val jobBoard: String? = null,
)
