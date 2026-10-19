package com.jd.pipeline.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Unit tests for LLM HTTP transport in [LlmClient]: 429 retry logic and the hard
 * wall-clock timeout. The client calls `http.sendAsync(...).get(hardTimeout)` so a
 * server that stalls the response body cannot hang the (single-threaded) worker.
 */
@DisplayName("LlmClientRetryTest")
class LlmClientRetryTest {

    private fun newClient(timeoutSeconds: Long = 5, grace: Long = 15) = LlmClient(
        LlmConfig(
            model = "test-model",
            backend = LlmBackend.OLLAMA_LOCAL,
            timeoutSeconds = timeoutSeconds,
            hardTimeoutGraceSeconds = grace,
        )
    )

    private fun injectHttp(client: LlmClient, http: HttpClient) {
        val f = LlmClient::class.java.getDeclaredField("http")
        f.isAccessible = true
        f.set(client, http)
    }

    private fun invokePost(client: LlmClient, timeoutSeconds: Long = 5L): String {
        val m = LlmClient::class.java.getDeclaredMethod(
            "post", String::class.java, Map::class.java, Map::class.java, Long::class.javaPrimitiveType
        )
        m.isAccessible = true
        return m.invoke(
            client,
            "http://localhost/api/chat",
            mapOf("model" to "test", "messages" to emptyList<String>()),
            emptyMap<String, String>(),
            timeoutSeconds,
        ) as String
    }

    private fun resp(code: Int, body: String, headers: Map<String, List<String>> = emptyMap()) =
        mock<HttpResponse<String>> {
            on { statusCode() } doReturn code
            on { body() } doReturn body
            on { headers() } doReturn java.net.http.HttpHeaders.of(headers) { _, _ -> true }
        }

    private fun future(r: HttpResponse<String>) = CompletableFuture.completedFuture(r)

    @Test
    @DisplayName("post retries on 429 and succeeds when the next call returns 200")
    fun retry429ThenSuccess() {
        val client = newClient()
        val mockHttp = mock<HttpClient>()
        // Build the futures (and their response mocks) BEFORE stubbing — creating a mock inside
        // an unfinished whenever() trips Mockito's UnfinishedStubbingException.
        val f429 = future(resp(429, "rate limited"))
        val f200 = future(resp(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}"))
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(f429)
            .thenReturn(f200)
        injectHttp(client, mockHttp)

        val result = invokePost(client)

        assertTrue(result.contains("ok"))
        verify(mockHttp, times(2)).sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    @DisplayName("post throws after exhausting 429 retries")
    fun retry429Exhausted() {
        val client = newClient()
        val mockHttp = mock<HttpClient>()
        val f429 = future(resp(429, "rate limited"))
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(f429)
        injectHttp(client, mockHttp)

        try {
            invokePost(client)
            fail("Expected RuntimeException after exhausting retries")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is RuntimeException)
            assertTrue(e.cause!!.message!!.contains("429"))
        }
        // initial + 3 retries = 4 calls
        verify(mockHttp, times(4)).sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    @DisplayName("post preserves a temporary 503 as a typed transient failure")
    fun temporary5xxIsTypedTransientFailure() {
        val client = newClient()
        val mockHttp = mock<HttpClient>()
        val f503 = future(resp(503, "temporarily unavailable"))
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(f503)
        injectHttp(client, mockHttp)

        try {
            invokePost(client)
            fail("Expected transient failure for HTTP 503")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is TransientLlmFailure, "5xx must retain the typed retry signal: ${e.cause}")
            assertTrue(e.cause!!.message!!.contains("503"))
        }
        verify(mockHttp, times(1)).sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    @DisplayName("post respects Retry-After header on 429")
    fun retry429WithRetryAfter() {
        val client = newClient()
        val mockHttp = mock<HttpClient>()
        val f429 = future(resp(429, "rate limited", mapOf("Retry-After" to listOf("2"))))
        val f200 = future(resp(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}"))
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(f429)
            .thenReturn(f200)
        injectHttp(client, mockHttp)

        val start = System.currentTimeMillis()
        val result = invokePost(client)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(result.contains("ok"))
        assertTrue(elapsed >= 1500, "Expected at least ~2s delay, got ${elapsed}ms")
        verify(mockHttp, times(2)).sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    @DisplayName("post enforces a hard timeout when the response never completes (stalled body)")
    fun hardTimeoutOnStalledResponse() {
        // grace=0 + timeoutSeconds=1 → hard bound ~1s, so this test is fast.
        val client = newClient(timeoutSeconds = 1, grace = 0)
        val mockHttp = mock<HttpClient>()
        val neverCompletes = CompletableFuture<HttpResponse<String>>() // simulates a stalled server
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(neverCompletes)
        injectHttp(client, mockHttp)

        val start = System.currentTimeMillis()
        try {
            invokePost(client, timeoutSeconds = 1L)
            fail("Expected RuntimeException from hard timeout")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is RuntimeException)
            assertTrue(e.cause!!.message!!.contains("hard timeout"), "message was: ${e.cause!!.message}")
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 10_000, "hard timeout should fire quickly (~1s), took ${elapsed}ms")
    }

    @Test
    @DisplayName("post surfaces an async request failure as a RuntimeException (no hang)")
    fun asyncFailureSurfaced() {
        val client = newClient()
        val mockHttp = mock<HttpClient>()
        val failed = CompletableFuture<HttpResponse<String>>()
        failed.completeExceptionally(java.io.IOException("connection refused"))
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(failed)
        injectHttp(client, mockHttp)

        try {
            invokePost(client)
            fail("Expected RuntimeException from async failure")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is RuntimeException)
            assertTrue(e.cause!!.message!!.contains("connection refused"), "message was: ${e.cause!!.message}")
        }
    }

    @Test
    @DisplayName("post raises a TransientLlmFailure when the request times out")
    fun requestTimeoutIsTransient() {
        // HttpRequest.timeout() fails the future with HttpTimeoutException("request timed out").
        // That was a plain RuntimeException, so scan/score recorded it as a terminal JD_Error.
        val client = newClient()
        val mockHttp = mock<HttpClient>()
        val failed = CompletableFuture<HttpResponse<String>>()
        failed.completeExceptionally(java.net.http.HttpTimeoutException("request timed out"))
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(failed)
        injectHttp(client, mockHttp)

        try {
            invokePost(client)
            fail("Expected TransientLlmFailure from request timeout")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is TransientLlmFailure, "was: ${e.cause}")
            assertTrue(e.cause!!.message!!.contains("request timed out"), "message was: ${e.cause!!.message}")
        }
    }

    @Test
    @DisplayName("post keeps a non-transport async failure non-transient")
    fun nonIoAsyncFailureIsNotTransient() {
        val client = newClient()
        val mockHttp = mock<HttpClient>()
        val failed = CompletableFuture<HttpResponse<String>>()
        failed.completeExceptionally(IllegalArgumentException("bad header"))
        whenever(mockHttp.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(failed)
        injectHttp(client, mockHttp)

        try {
            invokePost(client)
            fail("Expected RuntimeException from async failure")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause !is TransientLlmFailure, "was: ${e.cause}")
        }
    }
}
