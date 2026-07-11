package com.jd.pipeline.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.config.Config
import com.jd.pipeline.config.JSearchConfig
import com.jd.pipeline.models.JobListing
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class JSearchClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    apiKey: String? = null
) {

    private val apiKey = apiKey?.takeIf { it.isNotBlank() }
        ?: Config.JSEARCH_API_KEY.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("JSEARCH_API_KEY is not configured — set it in .env or environment")

    private val mapper = ObjectMapper()

    fun search(config: JSearchConfig): List<JobListing> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<JobListing>()

        val (jobCity, jobState) = config.location?.takeIf { it.isNotBlank() }?.let { loc ->
            val parts = loc.split(",", limit = 2)
            parts.getOrNull(0)?.trim() to parts.getOrNull(1)?.trim()
        } ?: (null to null)

        for (query in config.queries) {
            for (page in 1..config.numPages) {
                val pageResults = fetchPage(query, page, config, jobCity, jobState)
                for (listing in pageResults) {
                    if (seen.add(listing.jobId)) results.add(listing)
                }
            }
        }
        return results
    }

    fun search(configs: List<JSearchConfig>): List<JobListing> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<JobListing>()
        for (config in configs) {
            for (listing in search(config)) {
                if (seen.add(listing.jobId)) results.add(listing)
            }
        }
        return results
    }

    private fun fetchPage(
        query: String,
        page: Int,
        config: JSearchConfig,
        jobCity: String?,
        jobState: String?
    ): List<JobListing> {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val qs = buildString {
            append("query=${enc(query)}")
            append("&page=$page")
            append("&num_pages=1")
            append("&date_posted=${config.datePosted}")
            append("&country=us")
            if (config.remoteJobsOnly) {
                append("&remote_jobs_only=true")
            }
            if (!jobCity.isNullOrBlank()) {
                append("&job_city=${enc(jobCity)}")
            }
            if (!jobState.isNullOrBlank()) {
                append("&job_state=${enc(jobState)}")
            }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://jsearch.p.rapidapi.com/search?$qs"))
            .header("X-RapidAPI-Key", apiKey)
            .header("X-RapidAPI-Host", "jsearch.p.rapidapi.com")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        println("[JSearchClient] HTTP status: ${response.statusCode()}, body length: ${response.body()?.length ?: 0}")
        if (response.statusCode() != 200) {
            println("[JSearchClient] ERROR response: ${response.body()}")
            return emptyList()
        }
        val root = mapper.readTree(response.body())
        val data = root.path("data")
        if (!data.isArray) {
            println("[JSearchClient] 'data' is not an array or missing. Root keys: ${root.fieldNames().asSequence().toList()}")
            return emptyList()
        }
        println("[JSearchClient] Found ${data.size()} items in data array")
        return data.mapNotNull { node ->
            try { mapper.treeToValue(node, JobListing::class.java) } catch (e: Exception) { 
                println("[JSearchClient] Deserialization failed for node: ${node.toString().take(200)}, error: ${e.message}")
                null 
            }
        }
    }

    companion object {
        /**
         * Convert a JSearch [JobListing] into a pipeline [JDState].
         * The resulting state is marked as a job posting sourced from JSearch,
         * so the pipeline can skip scan/scrape/save nodes and go straight to scoring.
         */
        fun toJDState(listing: JobListing): JDState {
            val location = listOfNotNull(listing.jobCity, listing.jobState)
                .joinToString(", ")
                .ifBlank { if (listing.jobIsRemote) "Remote" else "Unknown" }

            val remotePolicy = when {
                listing.jobIsRemote -> "Remote"
                else -> "unknown"
            }

            return JDState(
                intake = IntakeContext.Api(board = "jsearch"),
                isJobPosting = true,
                company = listing.employerName,
                roleTitle = listing.jobTitle,
                location = location,
                salaryRange = formatSalary(listing),
                remotePolicy = remotePolicy,
                jdText = listing.jobDescription ?: "",
                jobUrl = listing.jobApplyLink ?: "",
                jobBoard = listing.jobPublisher ?: "",
            )
        }

        /**
         * Build a human-readable salary string from the various JSearch salary fields.
         */
        fun formatSalary(listing: JobListing): String {
            // Prefer explicit string if present
            if (!listing.jobSalaryString.isNullOrBlank()) {
                return listing.jobSalaryString
            }

            // Next, try the single salary value
            if (listing.jobSalary != null) {
                return "\$${listing.jobSalary.toInt()}"
            }

            // Finally, try min/max range
            val min = listing.jobMinSalary
            val max = listing.jobMaxSalary
            return when {
                min != null && max != null -> "\$${min.toInt()} - \$${max.toInt()}"
                min != null -> "\$${min.toInt()}"
                max != null -> "\$${max.toInt()}"
                else -> ""
            }
        }
    }
}
