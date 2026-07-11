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
    fun submit(record: JdRecord): String {
        val json = mapper.writeValueAsString(record)
        val req = HttpPost("$baseUrl/api/jobs").apply {
            entity = StringEntity(json, ContentType.APPLICATION_JSON)
        }
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code in 200..202) { "POST /api/jobs → ${resp.code}: $body" }
            mapper.readTree(body).get("job_id").asText()
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
            val tree = mapper.readTree(body)
            val jobId = tree.get("job_id").asText()
            val jdNode = tree.get("jd_record")
                ?: throw RuntimeException("claim response missing jd_record")
            val record = mapper.treeToValue(jdNode, JdRecord::class.java)
            ClaimDto(jobId = jobId, jdRecord = record)
        }
    }

    fun postResult(jobId: String, result: ProcessingResult) {
        val json = mapper.writeValueAsString(result)
        val req = HttpPost("$baseUrl/api/jobs/$jobId/result").apply {
            entity = StringEntity(json, ContentType.APPLICATION_JSON)
        }
        http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "POST /api/jobs/$jobId/result → ${resp.code}: $body" }
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

data class ClaimDto(
    val jobId: String,
    val jdRecord: JdRecord,
)
