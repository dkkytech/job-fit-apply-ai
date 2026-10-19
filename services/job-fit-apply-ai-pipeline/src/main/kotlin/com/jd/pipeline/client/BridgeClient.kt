package com.jd.pipeline.client

import com.fasterxml.jackson.databind.JsonNode
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.utils.Json
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.entity.StringEntity

/**
 * HTTP client that the pipeline side (ingestion + worker) uses to talk to the bridge.
 *
 * Base URL defaults to JD_BRIDGE_URL env var (default http://127.0.0.1:8765).
 * Uses Apache HttpClient 5 (already on the classpath).
 * Serializes / deserializes via [Json.mapper] (snake_case + IntakeContext polymorphism).
 */
class BridgeClient(
    private val baseUrl: String = System.getenv("JD_BRIDGE_URL") ?: "http://127.0.0.1:8765",
) {

    private val http = HttpClients.createDefault()
    private val mapper = Json.mapper

    // ── Submission ────────────────────────────────────────────────────────────

    /**
     * POST /api/jobs — submit a record; returns the job_id.
     * Returns the existing job_id if the bridge deduped the submission.
     */
    fun submit(record: JdRecord): String = submitDetailed(record).jobId

    /**
     * As [submit], but keeps the bridge's `deduped` flag. Digest fan-out needs it: "the child was
     * already submitted by an earlier attempt" and "the child was newly queued" are both success,
     * and telling them apart is what makes a retried fan-out verifiably non-duplicating.
     */
    fun submitDetailed(record: JdRecord): SubmitOutcome {
        val json = mapper.writeValueAsString(record)
        val req = HttpPost("$baseUrl/api/jobs").apply {
            entity = StringEntity(json, ContentType.APPLICATION_JSON)
        }
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code in 200..202) { "POST /api/jobs → ${resp.code}: $body" }
            val tree = mapper.readTree(body)
            SubmitOutcome(
                jobId = tree.get("job_id").asText(),
                deduped = tree.get("deduped")?.asBoolean(false) ?: false,
            )
        }
    }

    /**
     * POST /api/urls — a posting known only by its link; the bridge dedupes it on [key] / [url]
     * within [source]'s window and the processor scrapes it at claim time (JD_URL).
     */
    fun submitUrl(
        url: String,
        key: String,
        source: String,
        company: String? = null,
        roleTitle: String? = null,
        location: String? = null,
    ): SubmitOutcome {
        val json = mapper.writeValueAsString(
            mapOf(
                "url" to url, "idempotency_key" to key, "source" to source,
                "company" to company?.ifBlank { null }, "role_title" to roleTitle?.ifBlank { null },
                "location" to location?.ifBlank { null },
            ),
        )
        val req = HttpPost("$baseUrl/api/urls").apply { entity = StringEntity(json, ContentType.APPLICATION_JSON) }
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code in 200..202) { "POST /api/urls → ${resp.code}: $body" }
            val tree = mapper.readTree(body)
            SubmitOutcome(jobId = tree.get("job_id").asText(), deduped = tree.get("deduped")?.asBoolean(false) ?: false)
        }
    }

    // ── Status polling ────────────────────────────────────────────────────────

    fun getStatus(jobId: String): JobStatusDto {
        val req = HttpGet("$baseUrl/api/jobs/$jobId")
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "GET /api/jobs/$jobId → ${resp.code}: $body" }
            mapper.readValue(body, JobStatusDto::class.java)
        }
    }

    fun pollUntilTerminal(
        jobId: String,
        timeoutMs: Long = 600_000L,
        intervalMs: Long = 3_000L,
    ): JobStatusDto {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = getStatus(jobId)
            if (status.status in listOf("done", "error")) return status
            Thread.sleep(intervalMs)
        }
        throw RuntimeException("Timed out waiting for job $jobId after ${timeoutMs}ms")
    }

    // ── Deferred tailoring ────────────────────────────────────────────────────

    /**
     * POST /api/tailoring/release-deferred — expire DEFERRED jobs older than [maxAgeDays], then queue
     * the best-fit rest as TAILOR_REQUESTs until [limit] are in flight. `limit = 0` only expires.
     */
    fun releaseDeferred(limit: Int, maxAgeDays: Int): DeferredRelease {
        val json = mapper.writeValueAsString(mapOf("limit" to limit, "max_age_days" to maxAgeDays))
        val req = HttpPost("$baseUrl/api/tailoring/release-deferred").apply {
            entity = StringEntity(json, ContentType.APPLICATION_JSON)
        }
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "POST /api/tailoring/release-deferred → ${resp.code}: $body" }
            mapper.readValue(body, DeferredRelease::class.java)
        }
    }

    // ── Worker endpoints ──────────────────────────────────────────────────────

    /**
     * GET /api/queue/claim — returns null when the queue is empty (204 No Content).
     */
    fun claim(): ClaimDto? {
        val req = HttpGet("$baseUrl/api/queue/claim")
        return http.execute(req) { resp ->
            if (resp.code == 204) return@execute null
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "GET /api/queue/claim → ${resp.code}: $body" }
            parseClaimTree(mapper.readTree(body))
        }
    }

    /**
     * POST a result. A retryable result is accepted with 202 after the bridge durably defers it.
     * [claimToken] is the fence the bridge handed out with the claim:
     * if this claim was requeued and re-claimed while we were working, the bridge refuses the
     * write (409) rather than letting us overwrite the attempt that replaced us.
     */
    fun postResult(jobId: String, result: ProcessingResult, claimToken: String? = null) {
        val json = if (claimToken == null) {
            mapper.writeValueAsString(result)
        } else {
            mapper.writeValueAsString(
                mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(result)
                    .put("claim_token", claimToken),
            )
        }
        val req = HttpPost("$baseUrl/api/jobs/$jobId/result").apply {
            entity = StringEntity(json, ContentType.APPLICATION_JSON)
        }
        http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200 || resp.code == 202) { "POST /api/jobs/$jobId/result → ${resp.code}: $body" }
        }
    }

    fun uploadArtifacts(jobId: String, files: List<java.io.File>) {
        if (files.isEmpty()) return
        val builder = MultipartEntityBuilder.create()
        for (file in files) {
            val ct = if (file.name.endsWith(".pdf")) ContentType.APPLICATION_OCTET_STREAM
                     else ContentType.TEXT_PLAIN
            builder.addBinaryBody(file.name, file, ct, file.name)
        }
        val req = HttpPost("$baseUrl/api/jobs/$jobId/artifacts").apply {
            entity = builder.build()
        }
        http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "POST /api/jobs/$jobId/artifacts → ${resp.code}: $body" }
        }
    }

    fun downloadArtifact(jobId: String, name: String, dest: java.io.File) {
        val req = HttpGet("$baseUrl/api/jobs/$jobId/$name")
        http.execute(req) { resp ->
            check(resp.code == 200) { "GET /api/jobs/$jobId/$name → ${resp.code}" }
            dest.outputStream().use { out -> resp.entity.writeTo(out) }
        }
    }
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

data class JobStatusDto(
    val job_id: String = "",
    val status: String = "",
    val fit_score: Int? = null,
    val pipeline_action: String? = null,
    val error: String? = null,
)

/** Work-item type discriminator (mirrors the bridge's WorkItemType — DTOs duplicated per service). */
object WorkItemType {
    const val EMAIL_RAW   = "EMAIL_RAW"    // raw email — the Processor scans/scrapes it
    const val JD_SCRAPED  = "JD_SCRAPED"   // pre-structured JdRecord (JSearch / digest child)
    const val JD_PAGE_RAW = "JD_PAGE_RAW"  // raw captured web-page content — the Processor LLM-extracts it
    const val JD_URL      = "JD_URL"       // a posting known only by its link — the Processor scrapes it
    const val TAILOR_REQUEST = "TAILOR_REQUEST" // a Tailor tap on an OFFERed job — the Processor tailors it
}

/** A raw email claimed from the queue (payload of an EMAIL_RAW item). */
data class ClaimedEmail(
    val messageId: String,
    val subject: String = "",
    val body: String = "",
    val htmlBody: String? = null,
    val from: String = "",
    val isRecruiterHint: Boolean = false,
)

/** Raw captured page content claimed from the queue (payload of a JD_PAGE_RAW item). */
data class ClaimedPageCapture(
    val url: String,
    val text: String,
    val title: String = "",
)

/** A posting URL claimed from the queue (payload of a JD_URL item); the other fields are hints. */
data class ClaimedUrl(
    val url: String,
    val source: String? = null,
    val company: String? = null,
    val roleTitle: String? = null,
    val location: String? = null,
    val idempotencyKey: String? = null,
)

/**
 * A Tailor tap on an OFFERed job (payload of a TAILOR_REQUEST item): the bridge copies in the
 * [record] the OFFER handed back, plus the job's `tracks` row and job board.
 */
data class ClaimedTailorRequest(
    val record: JdRecord,
    val parentJobId: String,
    val trackId: Int? = null,
    val jobBoard: String? = null,
)

/** One DEFERRED job the bridge released or expired. [tailorJobId] is set for a released one. */
data class DeferredJob(
    val jobId: String,
    val company: String? = null,
    val roleTitle: String? = null,
    val fitScore: Int? = null,
    val artifactUrl: String? = null,
    val jobUrl: String? = null,
    val tailorJobId: String? = null,
)

/** What one release sweep did: queued [released], gave up on [expired], left [remaining] deferred. */
data class DeferredRelease(
    val released: List<DeferredJob> = emptyList(),
    val expired: List<DeferredJob> = emptyList(),
    val remaining: Int = 0,
)

/** Result of a submission: the job it maps to, and whether the bridge deduped it. */
data class SubmitOutcome(val jobId: String, val deduped: Boolean)

data class ClaimDto(
    val jobId: String,
    val type: String = WorkItemType.JD_SCRAPED,
    val jdRecord: JdRecord? = null,               // set for JD_SCRAPED
    val email: ClaimedEmail? = null,              // set for EMAIL_RAW
    val pageCapture: ClaimedPageCapture? = null,  // set for JD_PAGE_RAW
    val url: ClaimedUrl? = null,                  // set for JD_URL
    val tailor: ClaimedTailorRequest? = null,     // set for TAILOR_REQUEST
    /** Fence for this claim; present it on postResult. Null against a pre-fencing bridge. */
    val claimToken: String? = null,
)

/** Parse a /api/queue/claim response body into a [ClaimDto], branching on the work-item type. */
internal fun parseClaimTree(tree: com.fasterxml.jackson.databind.JsonNode): ClaimDto {
    val mapper = Json.mapper
    val jobId = tree.get("job_id").asText()
    val type = tree.get("type")?.asText() ?: WorkItemType.JD_SCRAPED
    val token = tree.get("claim_token")?.takeIf { !it.isNull }?.asText()
    val payload = tree.get("jd_record")
        ?: throw RuntimeException("claim response missing jd_record")
    return when (type) {
        WorkItemType.EMAIL_RAW ->
            ClaimDto(jobId = jobId, type = type, email = mapper.treeToValue(payload, ClaimedEmail::class.java), claimToken = token)
        WorkItemType.JD_PAGE_RAW ->
            ClaimDto(jobId = jobId, type = type, pageCapture = mapper.treeToValue(payload, ClaimedPageCapture::class.java), claimToken = token)
        WorkItemType.JD_URL ->
            ClaimDto(jobId = jobId, type = type, url = mapper.treeToValue(payload, ClaimedUrl::class.java), claimToken = token)
        WorkItemType.TAILOR_REQUEST ->
            ClaimDto(jobId = jobId, type = type, tailor = mapper.treeToValue(payload, ClaimedTailorRequest::class.java), claimToken = token)
        else ->
            ClaimDto(jobId = jobId, type = type, jdRecord = mapper.treeToValue(payload, JdRecord::class.java), claimToken = token)
    }
}
