package com.jd.pipeline.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [BridgeClient] against an in-process stub HTTP server (mirrors [SteelClientTest]).
 * Covers the request shapes, response parsing, the 204-empty-queue branch, and the non-2xx
 * `check { … }` failure paths for the pipeline↔bridge queue/DB endpoints.
 */
@DisplayName("BridgeClient (HTTP)")
class BridgeClientHttpTest {

    private val mapper = ObjectMapper()
    private lateinit var server: HttpServer
    private lateinit var client: BridgeClient

    // Stub-controlled behaviour / captured state.
    @Volatile private var submitBody: String = ""
    @Volatile private var resultBody: String = ""
    @Volatile private var claimResponse: Pair<Int, String> = 200 to
        """{"job_id":"j1","type":"JD_SCRAPED","jd_record":{"jd_text":"x","company":"Acme","role_title":"SDET","location":null,"job_url":null,"source":"EMAIL"}}"""
    @Volatile private var submitStatus: Int = 200
    @Volatile private var statusResponses: ArrayDeque<String> = ArrayDeque()
    @Volatile private var artifactHit: Boolean = false

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api") { ex ->
            val path = ex.requestURI.path
            when {
                path == "/api/jobs" && ex.requestMethod == "POST" -> {
                    submitBody = ex.requestBody.readBytes().decodeToString()
                    respond(ex, submitStatus, """{"job_id":"job-123"}""")
                }
                path == "/api/queue/claim" && ex.requestMethod == "GET" ->
                    respond(ex, claimResponse.first, claimResponse.second)
                path.endsWith("/result") && ex.requestMethod == "POST" -> {
                    resultBody = ex.requestBody.readBytes().decodeToString()
                    respond(ex, 200, "{}")
                }
                path.endsWith("/artifacts") && ex.requestMethod == "POST" -> {
                    artifactHit = true
                    ex.requestBody.readBytes()
                    respond(ex, 200, "{}")
                }
                path.startsWith("/api/jobs/") && ex.requestMethod == "GET" -> {
                    val body = statusResponses.removeFirstOrNull()
                        ?: """{"job_id":"job-123","status":"running"}"""
                    respond(ex, 200, body)
                }
                else -> respond(ex, 404, """{"error":"not found"}""")
            }
        }
        server.start()
        client = BridgeClient("http://127.0.0.1:${server.address.port}")
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sampleRecord() = JdRecord(
        jdText = "Test everything", company = "Acme", roleTitle = "SDET",
        location = "Remote", jobUrl = "https://acme.com/j/1", source = IngestionSource.EMAIL,
    )

    private fun sampleResult() = ProcessingResult(
        pipelineAction = "DRAFT", fitScore = 88, strengths = listOf("Kotlin"),
        isDuplicate = false, outputPath = "/tmp/out", hasCoverLetter = true,
    )

    @Test
    @DisplayName("submit posts the record as JSON and returns the job_id")
    fun submitReturnsJobId() {
        val id = client.submit(sampleRecord())

        assertEquals("job-123", id)
        val sent = mapper.readTree(submitBody)
        assertEquals("Acme", sent.get("company").asText())
        assertEquals("EMAIL", sent.get("source").asText())
    }

    @Test
    @DisplayName("submit throws with the status code and body on a non-2xx response")
    fun submitThrowsOnError() {
        submitStatus = 500
        val ex = assertThrows<IllegalStateException> { client.submit(sampleRecord()) }
        assertTrue(ex.message!!.contains("500"), "message: ${ex.message}")
    }

    @Test
    @DisplayName("claim parses a JD_SCRAPED work item")
    fun claimParsesRecord() {
        val dto = client.claim()!!
        assertEquals("j1", dto.jobId)
        assertEquals(WorkItemType.JD_SCRAPED, dto.type)
        assertEquals("Acme", dto.jdRecord!!.company)
    }

    @Test
    @DisplayName("claim returns null when the queue is empty (204)")
    fun claimEmptyQueue() {
        claimResponse = 204 to ""
        assertNull(client.claim())
    }

    @Test
    @DisplayName("claim throws on an unexpected status code")
    fun claimThrowsOnError() {
        claimResponse = 503 to """{"error":"unavailable"}"""
        val ex = assertThrows<IllegalStateException> { client.claim() }
        assertTrue(ex.message!!.contains("503"), "message: ${ex.message}")
    }

    @Test
    @DisplayName("postResult sends the result body and the snake_case fields")
    fun postResultSendsBody() {
        client.postResult("job-123", sampleResult())

        val sent = mapper.readTree(resultBody)
        assertEquals("DRAFT", sent.get("pipeline_action").asText())
        assertEquals(88, sent.get("fit_score").asInt())
    }

    @Test
    @DisplayName("getStatus deserializes the status DTO")
    fun getStatusParses() {
        statusResponses.addLast("""{"job_id":"job-123","status":"done","fit_score":91,"pipeline_action":"DRAFT"}""")
        val status = client.getStatus("job-123")

        assertEquals("done", status.status)
        assertEquals(91, status.fit_score)
        assertEquals("DRAFT", status.pipeline_action)
    }

    @Test
    @DisplayName("pollUntilTerminal returns as soon as the job reaches a terminal status")
    fun pollUntilTerminalStopsOnDone() {
        statusResponses.addLast("""{"job_id":"job-123","status":"running"}""")
        statusResponses.addLast("""{"job_id":"job-123","status":"done","fit_score":70}""")

        val status = client.pollUntilTerminal("job-123", timeoutMs = 5_000, intervalMs = 1)
        assertEquals("done", status.status)
    }

    @Test
    @DisplayName("uploadArtifacts is a no-op when the file list is empty")
    fun uploadArtifactsNoop() {
        client.uploadArtifacts("job-123", emptyList())
        assertTrue(!artifactHit, "no request should be made for an empty file list")
    }

    @Test
    @DisplayName("uploadArtifacts posts a multipart request when files are present")
    fun uploadArtifactsPosts(@org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path) {
        val file = tempDir.resolve("cover_letter.txt")
        Files.writeString(file, "Dear hiring manager")
        client.uploadArtifacts("job-123", listOf(file.toFile()))
        assertTrue(artifactHit)
    }

    @Test
    @DisplayName("downloadArtifact streams the response body to the destination file")
    fun downloadArtifactWritesFile(@org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path) {
        // Reuse the status GET context, which echoes a JSON body, as a stand-in artifact payload.
        statusResponses.addLast("""ARTIFACT-BYTES""")
        val dest = tempDir.resolve("downloaded.txt")
        client.downloadArtifact("job-123", "resume.pdf", dest.toFile())
        assertEquals("ARTIFACT-BYTES", dest.readText())
    }
}
