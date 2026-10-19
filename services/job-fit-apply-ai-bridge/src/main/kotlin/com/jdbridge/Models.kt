package com.jdbridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

// ── Job status enum ───────────────────────────────────────────────────────────

object JobStatusSerializer : KSerializer<JobStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("JobStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JobStatus) =
        encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): JobStatus =
        JobStatus.fromValue(decoder.decodeString())
}

@Serializable(with = JobStatusSerializer::class)
enum class JobStatus(val value: String) {
    PENDING("pending"),
    CLAIMED("claimed"),
    DONE("done"),
    ERROR("error");

    companion object {
        fun fromValue(value: String): JobStatus =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown JobStatus: $value")
    }
}

// ── Error response ────────────────────────────────────────────────────────────

@Serializable
data class ErrorResponse(val detail: String)

// ── Work-item type ──────────────────────────────────────────────────────────────

/** Discriminates queued items: a raw email needing scan/scrape vs a pre-scraped JD. */
object WorkItemType {
    const val EMAIL_RAW   = "EMAIL_RAW"    // raw email from the Poller — Processor scans/scrapes it
    const val JD_SCRAPED  = "JD_SCRAPED"   // pre-structured JdRecord (JSearch / digest child)
    const val JD_PAGE_RAW = "JD_PAGE_RAW"  // raw captured web-page content from the extension — Processor LLM-extracts it
    const val JD_URL      = "JD_URL"       // a posting known only by its link (email alert rules, HTML boards) — Processor scrapes it
    const val TAILOR_REQUEST = "TAILOR_REQUEST" // a Tailor tap on an OFFERed job — Processor tailors the stored record
}

// ── Inbound ───────────────────────────────────────────────────────────────────

@Serializable
data class SubmitJobRequest(
    val jd_text: String,
    val role_title: String?      = null,
    val company: String?         = null,
    val location: String?        = null,
    val job_url: String?         = null,
    val source: String?          = null,          // "EMAIL" | "JSEARCH" | "MANUAL" | "CAREER_SITE"
    val idempotency_key: String? = null,
    val intake_meta: JsonElement? = null,          // opaque — stored verbatim
    // Scrape/API-extracted JD fields, passed through to the Processor's JdRecord. The request is
    // re-encoded from this class on enqueue, so a field missing here is silently dropped (the
    // server ignores unknown keys) and the report shows "—" for it.
    val salary_range: String?    = null,
    val remote_policy: String?   = null,
    val employment_type: String? = null,
    val seniority_level: String? = null,
    val yoe_required: Int?       = null,
    val tech_stack: List<String>? = null,
)

/**
 * Raw captured web-page content submitted by the browser extension. The user is already
 * authenticated in their browser, so this sidesteps the auth walls that block server-side
 * scraping; the Processor LLM-extracts the JD from [text] server-side (see JD_PAGE_RAW).
 */
@Serializable
data class SubmitPageCaptureRequest(
    val url: String,
    val text: String,
    val title: String           = "",
    val idempotency_key: String? = null,          // defaults to url
)

/**
 * A job posting known only by its URL — a job-alert email rule or a server-rendered careers page
 * found the link but not the description. The Processor scrapes [url] (the same path digest-email
 * children take) and scores it; [company]/[role_title]/[location] are hints used when the scrape
 * can't extract them. [source] picks the dedup window, as on /api/jobs.
 */
@Serializable
data class SubmitUrlRequest(
    val url: String,
    val source: String?          = null,          // "EMAIL" | "CAREER_SITE" | "MANUAL"
    val company: String?         = null,
    val role_title: String?      = null,
    val location: String?        = null,
    val idempotency_key: String? = null,          // defaults to url
)

/** Raw email submitted by the Poller; the Processor does scan + scrape server-side. */
@Serializable
data class SubmitEmailRequest(
    val message_id: String,
    val body: String,
    val subject: String          = "",
    val html_body: String?       = null,
    val from: String             = "",
    val is_recruiter_hint: Boolean = false,
    val idempotency_key: String? = null,          // defaults to message_id
)

@Serializable
data class ResultRequest(
    val pipeline_action: String,
    val fit_score: Int,
    val strengths: List<String>   = emptyList(),
    val is_duplicate: Boolean     = false,
    val output_path: String?      = null,
    val has_cover_letter: Boolean = false,
    val error: String?            = null,
    /** True only for a transient provider failure that should return to the queue. */
    val retryable: Boolean         = false,
    // Processed-posting identity + report URL — persisted for completed-feed consumers (e.g. Notifier):
    val company: String?          = null,
    val role_title: String?       = null,
    val job_url: String?          = null,
    val artifact_url: String?     = null,          // markserv report URL (was previously dropped)
    val track_id: Int?            = null,          // the `tracks` row the Processor wrote (Postgres id)
    val skip_reason: String?      = null,          // why a scored job was not tailored (e.g. pay below target)
    // Card details for the Telegram ping (strengths is above; it used to be accepted but dropped):
    val location: String?         = null,
    val remote_policy: String?    = null,
    val salary_range: String?     = null,
    val source: String?           = null,          // job board ("jobright.ai") or "recruiter email"
    val gaps: List<String>        = emptyList(),
    // Gmail write-back payload (Poller acts on these via the completed feed):
    val terminal_label: String?   = null,          // label the Poller should apply to message_id
    val draft_text: String?       = null,          // LLM-generated recruiter reply (Processor makes it)
    val is_recruiter: Boolean     = false,
    val message_id: String?       = null,          // present only for EMAIL_RAW jobs
    // Only on an OFFER: the processed record, kept so POST /api/jobs/{id}/tailor can queue it.
    val tailor_record: JsonElement? = null,
    // Fencing token handed out by /api/queue/claim. Null from a worker built before fencing
    // existed; a *stale* one is refused (see Store.recordResult).
    val claim_token: String?      = null,
)

// ── Outbound ──────────────────────────────────────────────────────────────────

@Serializable
data class SubmitJobResponse(
    val job_id: String,
    val status: String,
    val deduped: Boolean = false,
)

@Serializable
data class ClaimResponse(
    val job_id: String,
    val type: String = WorkItemType.JD_SCRAPED,   // Processor branches on this
    val jd_record: JsonElement,                   // raw stored payload (JdRecord or email)
    /** Present it on POST /result. Rotated per claim, so a requeued claim invalidates it. */
    val claim_token: String? = null,
)

/**
 * One item from GET /api/jobs/completed — a completed-job event. Carries the Gmail write-back
 * payload (for the Poller) plus the processed-posting fields (for event-stream consumers like the
 * Notifier): company / role_title / fit_score / pipeline_action / job_url / artifact_url.
 */
@Serializable
data class CompletedJob(
    val job_id: String,
    val completed_seq: Long,
    val status: String,
    val message_id: String?      = null,
    val terminal_label: String?  = null,
    val draft_text: String?      = null,
    val is_recruiter: Boolean    = false,
    val artifacts: ArtifactUrls? = null,
    val error: String?           = null,
    // Processed-posting fields (for Notifier / analytics / other completed-feed consumers):
    val company: String?         = null,
    val role_title: String?      = null,
    val fit_score: Int?          = null,
    val pipeline_action: String? = null,
    val job_url: String?         = null,
    val artifact_url: String?    = null,
    // The job's `tracks` row — key for /api/tracks/{id}/events (JobBot records lifecycle events).
    val track_id: Int?           = null,
    // Why a scored job was not tailored — the Telegram card says it ("Skipped: …"). Null when tailored.
    val skip_reason: String?     = null,
    // Card details for the Telegram ping. Null or empty for jobs completed before they existed.
    val location: String?        = null,
    val remote_policy: String?   = null,
    val salary_range: String?    = null,
    val source: String?          = null,
    val strengths: List<String>  = emptyList(),
    val gaps: List<String>       = emptyList(),
)

@Serializable
data class ArtifactUrls(
    val resume_pdf: String,
    val cover_letter_txt: String,
)

@Serializable
data class JobStatusResponse(
    val job_id: String,
    val status: String,
    val title: String?            = null,
    val company: String?          = null,
    val fit_score: Int?           = null,
    val pipeline_action: String?  = null,
    val artifacts: ArtifactUrls?  = null,
    val error: String?            = null,
    val track_id: Int?            = null,
)

// ── Tracks (backlog UI — Postgres `tracks` table) ──────────────────────────────

/** A row from `tracks` as the backlog UI consumes it (snake_case matches the UI's Track type). */
@Serializable
data class TrackDto(
    val id: Int,
    val company: String,
    val role_title: String?       = null,
    val location: String?         = null,
    val remote_policy: String?    = null,
    val fit_score: Double?        = null,
    val job_url: String?          = null,
    val artifact_url: String?     = null,
    val tech_stack: List<String>? = null,
    val status: String,
    val created_at: String,
    val duplicate: Boolean,
)

@Serializable
data class TrackStatusUpdate(
    val status: String,
    /** Who made the change, recorded on the `status_changed` event. The backlog UI omits it. */
    val source: String = "frontend",
)

/** A row from `track_events` — one entry in a track's application history. */
@Serializable
data class TrackEventDto(
    val id: Long,
    val track_id: Int,
    val occurred_at: String,
    val kind: String,
    val summary: String?     = null,
    val source: String,
    val details: JsonElement? = null,
)

/** Body of POST /api/tracks/{id}/events. [kind] must be in TracksStore.ALLOWED_EVENT_KINDS. */
@Serializable
data class TrackEventCreate(
    val kind: String,
    val summary: String?     = null,
    val source: String,
    val details: JsonElement? = null,             // opaque — stored verbatim as JSONB
)

// ── Store helpers ─────────────────────────────────────────────────────────────

/** Partial update — only non-null fields are written to the DB. */
data class JobUpdate(
    val status: JobStatus?        = null,
    val fitScore: Int?            = null,
    val pipelineAction: String?   = null,
    val artifacts: ArtifactUrls?  = null,
    val error: String?            = null,
)

/** Row returned from the DB after deserialization. */
data class JobRow(
    val id: String,
    val status: String,
    val type: String,
    val jdJson: String?,
    val jobUrl: String?,
    val fitScore: Int?,
    val pipelineAction: String?,
    val artifacts: ArtifactUrls?,
    val error: String?,
    val retryCount: Int,
    val nextAttemptAt: Long?,
    val claimedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    // Gmail write-back fields (set by recordResult)
    val terminalLabel: String?,
    val draftText: String?,
    val isRecruiter: Boolean,
    val messageId: String?,
    val writebackDone: Boolean,
    val completedSeq: Long?,
    // Processed-posting identity (set by recordResult)
    val company: String?          = null,
    val roleTitle: String?        = null,
    val trackId: Int?             = null,
    val jobSource: String?        = null,
    /** The processed record an OFFER handed back (JSON), for an on-demand tailor run. */
    val tailorRecord: String?     = null,
    val tailorState: String?      = null,
    val artifactUrl: String?      = null,
)

/** Values of `jobs.tailor_state` — where a job with a tailor_record stands. */
object TailorState {
    const val OFFERED  = "offered"    // a Tailor button waits on the card
    const val DEFERRED = "deferred"   // waiting for the cloud LLM quota; the release sweep picks it up
    const val RELEASED = "released"   // its TAILOR_REQUEST was queued
    const val EXPIRED  = "expired"    // waited longer than the processor's DEFERRED_MAX_AGE_DAYS
}

/** POST /api/tailoring/release-deferred — the processor's sweep. */
@Serializable
data class ReleaseDeferredRequest(
    /** TAILOR_REQUESTs to keep in flight; 0 = only expire. */
    val limit: Int = 2,
    val max_age_days: Int = 5,
)

@Serializable
data class DeferredJobOut(
    val job_id: String,
    val company: String?      = null,
    val role_title: String?   = null,
    val fit_score: Int?       = null,
    val artifact_url: String? = null,
    val job_url: String?      = null,
    val tailor_job_id: String? = null,
)

@Serializable
data class ReleaseDeferredResponse(
    val released: List<DeferredJobOut>,
    val expired: List<DeferredJobOut>,
    val remaining: Long,
)

/** Returned by claimNext() — enough for the Processor to act on. */
data class ClaimedJob(val id: String, val type: String, val jdJson: String, val claimToken: String? = null)
