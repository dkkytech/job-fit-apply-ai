package com.jd.notifier.bridge

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.notifier.config.Config
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.io.entity.EntityUtils

/**
 * A completed-job event from the bridge stream (the fields a Notifier needs).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletedEvent(
    val jobId: String,
    val completedSeq: Long = 0,
    val status: String = "",
    val company: String? = null,
    val roleTitle: String? = null,
    val fitScore: Int? = null,
    val pipelineAction: String? = null,
    val jobUrl: String? = null,
    val artifactUrl: String? = null,
    val artifacts: ArtifactUrls? = null,
    val error: String? = null,
    /** The source Gmail message (null for jobs that did not arrive by email). */
    val messageId: String? = null,
    /** Explicit name: Jackson would otherwise read the Kotlin `is` getter as `recruiter`. */
    @JsonProperty("is_recruiter")
    val isRecruiter: Boolean = false,
    /** The Gmail label the poller writes back — tells whether the email is still in the inbox. */
    val terminalLabel: String? = null,
    /** Why a scored job was not tailored (e.g. "Pay $85K is below your $145K target"); null when tailored. */
    val skipReason: String? = null,
    // Card details (absent on events completed before the pipeline sent them).
    val location: String? = null,
    val remotePolicy: String? = null,
    val salaryRange: String? = null,
    val source: String? = null,
    val strengths: List<String>? = null,
    val gaps: List<String>? = null,
) {
    /** Scored but not tailored (e.g. pay-gated): no resume exists, so no resume button and no Apply. */
    fun skipped(): Boolean = pipelineAction.equals("SKIP", ignoreCase = true)

    /** Scored as a fit but below AUTO_TAILOR_MIN_SCORE: no resume until the Tailor button is tapped. */
    fun offered(): Boolean = pipelineAction.equals("OFFER", ignoreCase = true)

    /** Would have been tailored, but the cloud LLM quota is out: tailored automatically once it is back. */
    fun deferred(): Boolean = pipelineAction.equals("DEFERRED", ignoreCase = true)

    /**
     * The pipeline output directory name, taken from the tail of [artifactUrl].
     *
     * The event carries no dirname field, and the agent's Apply records are keyed by it, so it
     * is derived here. Null when the pipeline produced no artifacts.
     */
    fun dirName(): String? =
        artifactUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
}

/** Artifact URLs the bridge exposes for one job (relative to the bridge base). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtifactUrls(
    val resumePdf: String? = null,
    val coverLetterTxt: String? = null,
)

/**
 * Reads the bridge's completed-feed as an event stream (`?all=true`, cursor by `completed_seq`).
 */
open class NotifierBridgeClient(
    private val baseUrl: String = Config.JD_BRIDGE_URL,
) {
    private val http = HttpClients.createDefault()
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** GET /api/jobs/completed?since=&limit=&all=true — completed events with seq > since, ASC. */
    open fun fetchEvents(since: Long, limit: Int = 50): List<CompletedEvent> {
        val req = HttpGet("$baseUrl/api/jobs/completed?since=$since&limit=$limit&all=true")
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "GET /api/jobs/completed → ${resp.code}: $body" }
            mapper.readValue(body, Array<CompletedEvent>::class.java).toList()
        }
    }

    /** GET /api/jobs/completed/head — current max completed_seq (for cold-start cursor seeding). */
    open fun headSeq(): Long {
        val req = HttpGet("$baseUrl/api/jobs/completed/head")
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "GET /api/jobs/completed/head → ${resp.code}: $body" }
            mapper.readTree(body).get("max_seq").asLong(0L)
        }
    }
}
