package com.jd.jsearch.bridge

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.jsearch.config.Config
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Snake_case wire DTOs for POST /api/jobs (mirror the bridge's SubmitJobRequest/Response). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SubmitJobRequest(
    val jdText: String,
    val roleTitle: String? = null,
    val company: String? = null,
    val location: String? = null,
    val jobUrl: String? = null,
    val source: String? = "JSEARCH",
    val idempotencyKey: String? = null,
    val salaryRange: String? = null,
    val remotePolicy: String? = null,
    val employmentType: String? = null,
    val yoeRequired: Int? = null,
    val techStack: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubmitJobResponse(
    val jobId: String = "",
    val status: String = "",
    val deduped: Boolean = false,
)

/**
 * Submits JD_SCRAPED work items to the bridge. The bridge rejects jd_text < 150 chars (422) and
 * dedups by idempotency_key (the JSearch jobId), so re-fetches never create duplicate jobs.
 */
open class JsearchBridgeClient(
    private val baseUrl: String = Config.JD_BRIDGE_URL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
) {
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** POST /api/jobs. Returns the response (200 deduped / 202 queued), or null on 422/other. */
    open fun submit(req: SubmitJobRequest): SubmitJobResponse? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/jobs"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req)))
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..202) {
            System.err.println("[bridge] POST /api/jobs -> ${resp.statusCode()}: ${resp.body()}")
            return null
        }
        return mapper.readValue(resp.body(), SubmitJobResponse::class.java)
    }
}
