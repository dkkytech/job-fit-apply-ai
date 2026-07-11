package com.jd.pipeline.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Unit tests for LLM HTTP 429 retry logic in [LlmClient].
 */
@DisplayName("LlmClientRetryTest")
class LlmClientRetryTest {

    @Test
    @DisplayName("post retries on 429 and succeeds when the next call returns 200")
    fun retry429ThenSuccess() {
        val client = LlmClient(
            LlmConfig(
                model = "test-model",
                backend = LlmBackend.OLLAMA_LOCAL,
                timeoutSeconds = 5
            )
        )

        val mockHttp = mock<HttpClient>()
        val response429 = mock<HttpResponse<String>> {
            on { statusCode() } doReturn 429
            on { body() } doReturn "rate limited"
            on { headers() } doReturn HttpResponse.BodyHandlers.ofString().javaClass.let {
                // Minimal headers stub; Retry-After not present
                java.net.http.HttpHeaders.of(
                    emptyMap()
                ) { _, _ -> true }
            }
        }
        val response200 = mock<HttpResponse<String>> {
            on { statusCode() } doReturn 200
            on { body() } doReturn "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}"
            on { headers() } doReturn java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
        }

        whenever(mockHttp.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(response429)
            .thenReturn(response200)

        // Inject mockHttp via reflection
        val httpField = LlmClient::class.java.getDeclaredField("http")
        httpField.isAccessible = true
        httpField.set(client, mockHttp)

        val postMethod = LlmClient::class.java.getDeclaredMethod(
            "post",
            String::class.java,
            Map::class.java,
            Map::class.java,
            Long::class.javaPrimitiveType
        )
        postMethod.isAccessible = true

        val result = postMethod.invoke(
            client,
            "http://localhost/api/chat",
            mapOf("model" to "test", "messages" to emptyList<String>()),
            emptyMap<String, String>(),
            5L
        ) as String

        assertTrue(result.contains("ok"))
        verify(mockHttp, times(2)).send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    @DisplayName("post throws after exhausting 429 retries")
    fun retry429Exhausted() {
        val client = LlmClient(
            LlmConfig(
                model = "test-model",
                backend = LlmBackend.OLLAMA_LOCAL,
                timeoutSeconds = 5
            )
        )

        val mockHttp = mock<HttpClient>()
        val response429 = mock<HttpResponse<String>> {
            on { statusCode() } doReturn 429
            on { body() } doReturn "rate limited"
            on { headers() } doReturn java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
        }

        whenever(mockHttp.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(response429)

        val httpField = LlmClient::class.java.getDeclaredField("http")
        httpField.isAccessible = true
        httpField.set(client, mockHttp)

        val postMethod = LlmClient::class.java.getDeclaredMethod(
            "post",
            String::class.java,
            Map::class.java,
            Map::class.java,
            Long::class.javaPrimitiveType
        )
        postMethod.isAccessible = true

        try {
            postMethod.invoke(
                client,
                "http://localhost/api/chat",
                mapOf("model" to "test", "messages" to emptyList<String>()),
                emptyMap<String, String>(),
                5L
            )
            fail("Expected RuntimeException after exhausting retries")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            assertTrue(cause is RuntimeException)
            assertTrue(cause!!.message!!.contains("429"))
        }

        // initial + 3 retries = 4 calls
        verify(mockHttp, times(4)).send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    @DisplayName("post respects Retry-After header on 429")
    fun retry429WithRetryAfter() {
        val client = LlmClient(
            LlmConfig(
                model = "test-model",
                backend = LlmBackend.OLLAMA_LOCAL,
                timeoutSeconds = 5
            )
        )

        val mockHttp = mock<HttpClient>()
        val response429 = mock<HttpResponse<String>> {
            on { statusCode() } doReturn 429
            on { body() } doReturn "rate limited"
            on { headers() } doReturn java.net.http.HttpHeaders.of(
                mapOf("Retry-After" to listOf("2"))
            ) { _, _ -> true }
        }
        val response200 = mock<HttpResponse<String>> {
            on { statusCode() } doReturn 200
            on { body() } doReturn "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}"
            on { headers() } doReturn java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
        }

        whenever(mockHttp.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(response429)
            .thenReturn(response200)

        val httpField = LlmClient::class.java.getDeclaredField("http")
        httpField.isAccessible = true
        httpField.set(client, mockHttp)

        val postMethod = LlmClient::class.java.getDeclaredMethod(
            "post",
            String::class.java,
            Map::class.java,
            Map::class.java,
            Long::class.javaPrimitiveType
        )
        postMethod.isAccessible = true

        val start = System.currentTimeMillis()
        val result = postMethod.invoke(
            client,
            "http://localhost/api/chat",
            mapOf("model" to "test", "messages" to emptyList<String>()),
            emptyMap<String, String>(),
            5L
        ) as String
        val elapsed = System.currentTimeMillis() - start

        assertTrue(result.contains("ok"))
        assertTrue(elapsed >= 1500, "Expected at least ~2s delay, got ${elapsed}ms")
        verify(mockHttp, times(2)).send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
    }
}
