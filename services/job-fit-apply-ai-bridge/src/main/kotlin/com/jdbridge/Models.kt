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

// ── Inbound ───────────────────────────────────────────────────────────────────

@Serializable
data class SubmitJobRequest(
    val jd_text: String,
    val role_title: String?      = null,
    val company: String?         = null,
    val location: String?        = null,
    val job_url: String?         = null,
    val source: String?          = null,          // "EMAIL" | "JSEARCH" | "MANUAL"
    val idempotency_key: String? = null,
    val intake_meta: JsonElement? = null,          // opaque — stored verbatim
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
    val jd_record: JsonElement,   // raw stored jd_json object
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
    val jdJson: String?,
    val jobUrl: String?,
    val fitScore: Int?,
    val pipelineAction: String?,
    val artifacts: ArtifactUrls?,
    val error: String?,
    val claimedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Returned by claimNext() — enough for the worker to act on. */
data class ClaimedJob(val id: String, val jdJson: String)
