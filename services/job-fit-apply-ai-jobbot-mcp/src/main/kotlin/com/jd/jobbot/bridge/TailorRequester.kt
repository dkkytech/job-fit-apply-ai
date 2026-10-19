package com.jd.jobbot.bridge

import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The one queue write JobBot may make: ask the bridge to tailor an offered job
 * (`POST /api/jobs/{id}/tailor`). The bridge owns the rules — only a finished OFFER qualifies, and
 * a job is tailored once — so this only reports what it decided.
 */
open class TailorRequester(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) {
    sealed interface Outcome {
        /** A TAILOR_REQUEST was queued as [jobId]. */
        data class Queued(val jobId: String) : Outcome
        /** The job was already requested: [jobId] is that request, [status] its bridge status. */
        data class Already(val jobId: String, val status: String) : Outcome
        /** The bridge refused (not an offer, not finished, unknown job). */
        data class Refused(val detail: String) : Outcome
    }

    open fun request(jobId: String): Outcome {
        require(JOB_ID.matches(jobId)) { "not a bridge job id: $jobId" }
        val path = "/api/jobs/$jobId/tailor"
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        val body = runCatching { BridgeReadClient.JSON.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
        // Success must name the request: a 2xx without a job_id is a broken bridge, not a tailor run.
        fun requestId(): String = body?.str("job_id")?.takeIf { it.isNotBlank() }
            ?: throw BridgeReadClient.BridgeException(resp.statusCode(), "POST $path → ${resp.statusCode()} without a job_id: ${resp.body().take(200)}")
        return when (resp.statusCode()) {
            202 -> Outcome.Queued(requestId())
            200 -> Outcome.Already(requestId(), body?.str("status") ?: "")
            404, 409 -> Outcome.Refused(body?.str("detail") ?: "the bridge refused (${resp.statusCode()})")
            else -> throw BridgeReadClient.BridgeException(resp.statusCode(), "POST $path → ${resp.statusCode()}: ${resp.body().take(200)}")
        }
    }

    companion object {
        /** Bridge job ids are UUIDs; nothing else may be spliced into the path. */
        private val JOB_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
