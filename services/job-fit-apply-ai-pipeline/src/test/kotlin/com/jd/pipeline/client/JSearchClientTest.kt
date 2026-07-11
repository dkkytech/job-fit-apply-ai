package com.jd.pipeline.client

import com.jd.pipeline.config.Config
import com.jd.pipeline.config.JSearchConfig
import com.jd.pipeline.models.JobListing
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [JSearchClient].
 *
 * Tests the client in isolation with mocked HTTP responses.
 */
@DisplayName("JSearchClientTest")
class JSearchClientTest {

    // ── toJDState conversion ─────────────────────────────────────────────────

    @Test
    @DisplayName("toJDState should map all JobListing fields to JDState")
    fun testToJDStateBasicConversion() {
        // Given
        val listing = JobListing(
            jobId = "abc123",
            jobTitle = "Senior SDET",
            employerName = "Acme Corp",
            jobCity = "Seattle",
            jobState = "WA",
            jobIsRemote = false,
            jobDescription = "We need a senior SDET...",
            jobApplyLink = "https://acme.com/jobs/123",
            jobPostedAtDatetimeUtc = "2024-01-15T10:00:00Z",
            jobMinSalary = 120000.0,
            jobMaxSalary = 160000.0,
            jobSalary = null,
            jobSalaryString = null,
            jobPublisher = "LinkedIn"
        )

        // When
        val state: JDState = JSearchClient.toJDState(listing)

        // Then
        assertTrue(state.intake is IntakeContext.Api)
        assertEquals(true, state.isJobPosting)
        assertEquals("Acme Corp", state.company)
        assertEquals("Senior SDET", state.roleTitle)
        assertEquals("Seattle, WA", state.location)
        assertEquals("\$120000 - \$160000", state.salaryRange)
        assertEquals("unknown", state.remotePolicy)
        assertEquals("We need a senior SDET...", state.jdText)
        assertEquals("https://acme.com/jobs/123", state.jobUrl)
    }

    @Test
    @DisplayName("toJDState should handle remote job with null city/state")
    fun testToJDStateRemoteJob() {
        // Given
        val listing = JobListing(
            jobId = "remote456",
            jobTitle = "Remote QA Engineer",
            employerName = "RemoteCo",
            jobCity = null,
            jobState = null,
            jobIsRemote = true,
            jobDescription = "Fully remote position...",
            jobApplyLink = null,
            jobPostedAtDatetimeUtc = null,
            jobMinSalary = null,
            jobMaxSalary = null,
            jobSalary = null,
            jobSalaryString = null,
            jobPublisher = null
        )

        // When
        val state: JDState = JSearchClient.toJDState(listing)

        // Then
        assertEquals("Remote", state.location)
        assertEquals("Remote", state.remotePolicy)
        assertEquals("Fully remote position...", state.jdText)
        assertEquals("", state.jobUrl)
        assertEquals("", state.salaryRange)
        assertTrue(state.intake is IntakeContext.Api)
    }

    @Test
    @DisplayName("toJDState should handle missing city but present state")
    fun testToJDStateMissingCity() {
        // Given
        val listing = JobListing(
            jobId = "xyz789",
            jobTitle = "Test Engineer",
            employerName = "TestCo",
            jobCity = null,
            jobState = "CA",
            jobIsRemote = false,
            jobDescription = null,
            jobApplyLink = null,
            jobPostedAtDatetimeUtc = null,
            jobMinSalary = null,
            jobMaxSalary = null,
            jobSalary = null,
            jobSalaryString = null,
            jobPublisher = null
        )

        // When
        val state: JDState = JSearchClient.toJDState(listing)

        // Then
        assertEquals("CA", state.location)
        assertEquals("unknown", state.remotePolicy)
    }

    // ── formatSalary ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("formatSalary should prefer jobSalaryString when present")
    fun testFormatSalaryPrefersString() {
        // Given
        val listing = JobListing(
            jobId = "1",
            jobTitle = "Dev",
            employerName = "Co",
            jobSalaryString = "\$150k per year",
            jobSalary = 150000.0,
            jobMinSalary = 140000.0,
            jobMaxSalary = 160000.0
        )

        // When / Then
        assertEquals("\$150k per year", JSearchClient.formatSalary(listing))
    }

    @Test
    @DisplayName("formatSalary should fall back to jobSalary when string is absent")
    fun testFormatSalaryFallsBackToJobSalary() {
        // Given
        val listing = JobListing(
            jobId = "2",
            jobTitle = "Dev",
            employerName = "Co",
            jobSalary = 95000.0,
            jobMinSalary = 90000.0,
            jobMaxSalary = 100000.0
        )

        // When / Then
        assertEquals("\$95000", JSearchClient.formatSalary(listing))
    }

    @Test
    @DisplayName("formatSalary should use min-max range when other fields are absent")
    fun testFormatSalaryMinMaxRange() {
        // Given
        val listing = JobListing(
            jobId = "3",
            jobTitle = "Dev",
            employerName = "Co",
            jobMinSalary = 80000.0,
            jobMaxSalary = 120000.0
        )

        // When / Then
        assertEquals("\$80000 - \$120000", JSearchClient.formatSalary(listing))
    }

    @Test
    @DisplayName("formatSalary should return only min when max is null")
    fun testFormatSalaryOnlyMin() {
        // Given
        val listing = JobListing(
            jobId = "4",
            jobTitle = "Dev",
            employerName = "Co",
            jobMinSalary = 70000.0
        )

        // When / Then
        assertEquals("\$70000", JSearchClient.formatSalary(listing))
    }

    @Test
    @DisplayName("formatSalary should return only max when min is null")
    fun testFormatSalaryOnlyMax() {
        // Given
        val listing = JobListing(
            jobId = "5",
            jobTitle = "Dev",
            employerName = "Co",
            jobMaxSalary = 200000.0
        )

        // When / Then
        assertEquals("\$200000", JSearchClient.formatSalary(listing))
    }

    @Test
    @DisplayName("formatSalary should return empty string when no salary data")
    fun testFormatSalaryNoData() {
        // Given
        val listing = JobListing(
            jobId = "6",
            jobTitle = "Dev",
            employerName = "Co"
        )

        // When / Then
        assertEquals("", JSearchClient.formatSalary(listing))
    }

    // ── search with mocked HttpClient ────────────────────────────────────────

    @Test
    @DisplayName("search should return empty list on HTTP error")
    fun testSearchReturnsEmptyOnHttpError() {
        // Given: mock HttpClient that returns 500
        val mockClient = createMockHttpClient(500, """{"error":"Internal Server Error"}""")
        val client = JSearchClient(mockClient, apiKey = "dummy-key")

        val config = JSearchConfig(queries = listOf("kotlin"), numPages = 1)

        // When
        val result = client.search(config)

        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("search should parse valid JSON response")
    fun testSearchParsesValidResponse() {
        // Given: mock HttpClient that returns 200 with a valid JSearch payload
        val json = """
            {
              "data": [
                {
                  "job_id": "j1",
                  "job_title": "Senior Kotlin Engineer",
                  "employer_name": "Acme",
                  "job_city": "Seattle",
                  "job_state": "WA",
                  "job_is_remote": false,
                  "job_description": "Build cool things",
                  "job_apply_link": "https://acme.com/jobs/1",
                  "job_posted_at_datetime_utc": "2024-01-01T00:00:00Z",
                  "job_min_salary": 120000.0,
                  "job_max_salary": 160000.0,
                  "job_salary": null,
                  "job_salary_string": null,
                  "job_publisher": "LinkedIn"
                }
              ]
            }
        """.trimIndent()
        val mockClient = createMockHttpClient(200, json)
        val client = JSearchClient(mockClient, apiKey = "dummy-key")

        val config = JSearchConfig(queries = listOf("kotlin"), numPages = 1)

        // When
        val result = client.search(config)

        // Then
        assertEquals(1, result.size)
        val listing = result[0]
        assertEquals("j1", listing.jobId)
        assertEquals("Senior Kotlin Engineer", listing.jobTitle)
        assertEquals("Acme", listing.employerName)
        assertEquals("Seattle", listing.jobCity)
        assertEquals("WA", listing.jobState)
        assertEquals(false, listing.jobIsRemote)
    }

    @Test
    @DisplayName("search should return empty list when data array is missing")
    fun testSearchReturnsEmptyWhenDataMissing() {
        // Given: mock HttpClient that returns 200 with no data array
        val mockClient = createMockHttpClient(200, """{"status":"ok"}""")
        val client = JSearchClient(mockClient, apiKey = "dummy-key")

        val config = JSearchConfig(queries = listOf("kotlin"), numPages = 1)

        // When
        val result = client.search(config)

        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("search should deduplicate by jobId across multiple pages")
    fun testSearchDeduplicatesAcrossPages() {
        // Given: mock HttpClient that returns same job on every page request
        val json = """
            {
              "data": [
                {
                  "job_id": "dup1",
                  "job_title": "QA Engineer",
                  "employer_name": "DupCo",
                  "job_city": null,
                  "job_state": null,
                  "job_is_remote": true,
                  "job_description": null,
                  "job_apply_link": null,
                  "job_posted_at_datetime_utc": null,
                  "job_min_salary": null,
                  "job_max_salary": null,
                  "job_salary": null,
                  "job_salary_string": null,
                  "job_publisher": null
                }
              ]
            }
        """.trimIndent()
        val mockClient = createMockHttpClient(200, json)
        val client = JSearchClient(mockClient, apiKey = "dummy-key")

        // Request 2 pages with 1 query → should hit API twice but return 1 unique job
        val config = JSearchConfig(queries = listOf("qa"), numPages = 2)

        // When
        val result = client.search(config)

        // Then
        assertEquals(1, result.size)
        assertEquals("dup1", result[0].jobId)
    }

    @Test
    @DisplayName("search with multiple configs should deduplicate across configs")
    fun testSearchMultipleConfigsDeduplicates() {
        // Given: mock HttpClient that returns same job for every request
        val json = """
            {
              "data": [
                {
                  "job_id": "cfg-dup",
                  "job_title": "SDET",
                  "employer_name": "TestCo",
                  "job_city": null,
                  "job_state": null,
                  "job_is_remote": false,
                  "job_description": null,
                  "job_apply_link": null,
                  "job_posted_at_datetime_utc": null,
                  "job_min_salary": null,
                  "job_max_salary": null,
                  "job_salary": null,
                  "job_salary_string": null,
                  "job_publisher": null
                }
              ]
            }
        """.trimIndent()
        val mockClient = createMockHttpClient(200, json)
        val client = JSearchClient(mockClient, apiKey = "dummy-key")

        val configA = JSearchConfig(queries = listOf("sdet"), numPages = 1)
        val configB = JSearchConfig(queries = listOf("test engineer"), numPages = 1)

        // When
        val result = client.search(listOf(configA, configB))

        // Then
        assertEquals(1, result.size)
        assertEquals("cfg-dup", result[0].jobId)
    }

    // ── constructor behaviour ────────────────────────────────────────────────

    @Test
    @DisplayName("constructor should throw when no API key is provided and Config key is empty")
    fun testConstructorThrowsWithoutApiKey() {
        // Only meaningful when no env key is available; skip in environments with a real key
        assumeTrue(Config.JSEARCH_API_KEY.isBlank(), "Skipped: JSEARCH_API_KEY is set in this environment")
        val exception = assertThrows<IllegalStateException> {
            JSearchClient(apiKey = "")
        }
        assertTrue(exception.message!!.contains("JSEARCH_API_KEY"))
    }
}


/**
 * Creates a mock HttpClient that returns the given status code and body for every synchronous send.
 */
private fun createMockHttpClient(statusCode: Int, body: String): HttpClient {
    return object : HttpClient() {
        override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
            @Suppress("UNCHECKED_CAST")
            return MockHttpResponse<T>(statusCode, body) as HttpResponse<T>
        }
        override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): java.util.concurrent.CompletableFuture<HttpResponse<T>> {
            throw NotImplementedError()
        }
        override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): java.util.concurrent.CompletableFuture<HttpResponse<T>> {
            throw NotImplementedError()
        }
        override fun followRedirects() = throw NotImplementedError()
        override fun cookieHandler() = throw NotImplementedError()
        override fun connectTimeout() = throw NotImplementedError()
        override fun proxy() = throw NotImplementedError()
        override fun sslContext() = throw NotImplementedError()
        override fun sslParameters() = throw NotImplementedError()
        override fun authenticator() = throw NotImplementedError()
        override fun version() = throw NotImplementedError()
        override fun executor() = throw NotImplementedError()
    }
}

/**
 * Minimal mock HttpResponse for testing.
 */
private class MockHttpResponse<T>(private val statusCode: Int, private val body: String) : HttpResponse<T> {
    @Suppress("UNCHECKED_CAST")
    override fun body(): T = body as T
    override fun statusCode(): Int = statusCode
    override fun headers() = throw NotImplementedError()
    override fun request() = throw NotImplementedError()
    override fun previousResponse() = throw NotImplementedError()
    override fun sslSession() = throw NotImplementedError()
    override fun uri() = throw NotImplementedError()
    override fun version() = throw NotImplementedError()
}