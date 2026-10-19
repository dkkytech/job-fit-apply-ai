package com.jd.pipeline.client

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.lang.reflect.InvocationTargetException
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@DisplayName("Cloud quota exhaustion → local fallback for the ingest/score lane")
class CloudQuotaTest {

    @AfterEach fun resetShared() = CloudQuota.shared.markHealthy()

    @Test
    fun `quota wording is recognised, concurrency and other errors are not`() {
        assertTrue(CloudQuota.isQuotaResponse(429, """{"error":"you have reached your weekly usage limit"}"""))
        assertTrue(CloudQuota.isQuotaResponse(429, """{"error":"session limit exceeded, upgrade your plan"}"""))
        assertTrue(CloudQuota.isQuotaResponse(402, """{"error":"insufficient credits"}"""))
        assertTrue(CloudQuota.isQuotaResponse(402, ""), "402 Payment Required is a quota refusal whatever it says")
        assertTrue(CloudQuota.isQuotaResponse(403, """{"error":"subscription quota exhausted"}"""))
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"too many concurrent requests"}"""), "the ordinary concurrency 429 keeps its retry")
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"concurrency limit exceeded for your plan"}"""), "concurrency wins over quota words")
        assertFalse(CloudQuota.isQuotaResponse(429, ""), "a bare 429 is a rate limit, not a quota")
        assertFalse(CloudQuota.isQuotaResponse(500, """{"error":"usage limit"}"""), "only 402/403/429 mean quota")
        assertFalse(CloudQuota.isQuotaResponse(400, """{"error":"context length limit exceeded"}"""))
        // An ordinary rate limit reads like "limit exceeded" too, and it is worth retrying.
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"rate limit exceeded, slow down"}"""))
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"Too Many Requests"}"""))
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"tokens per minute limit exceeded"}"""))
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"context length limit exceeded"}"""))
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"invalid plan parameter"}"""))
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"request limit exceeded"}"""), "generic limit wording is not a quota")
        // Throttling that also mentions the plan is still throttling.
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"rate limit exceeded for your subscription tier, retry in 2s"}"""))
        assertFalse(CloudQuota.isQuotaResponse(429, """{"error":"too many requests: upgrade for higher limits"}"""))
    }

    @Test
    fun `exhaustion skips the cloud until the recheck time, and alerts once per transition`() {
        var now = 1_000L
        val events = mutableListOf<String>()
        val q = CloudQuota(recheckMs = 30 * 60_000L, clock = { now }, onExhausted = { events += "down" }, onRecovered = { events += "up" })
        assertFalse(q.skipCloud())
        q.markExhausted("weekly limit")
        q.markExhausted("weekly limit again")
        assertTrue(q.skipCloud())
        assertEquals(listOf("down"), events, "one alert for the outage, not one per call")
        now += 30 * 60_000L
        assertFalse(q.skipCloud(), "after the recheck window the next call probes the cloud")
        q.markHealthy(); q.markHealthy()
        assertEquals(listOf("down", "up"), events)
    }

    @Test
    fun `the fallback caller switches to local on quota exhaustion and back after recovery`() {
        var now = 0L
        val q = CloudQuota(recheckMs = 60_000L, clock = { now })
        var cloudCalls = 0
        var cloudFails = true
        val cloud = LlmCaller { cloudCalls++; if (cloudFails) throw CloudQuotaExhausted("LLM HTTP 429: weekly usage limit") else "cloud" }
        val local = LlmCaller { "local" }
        val caller = FallbackLlmCaller(cloud, local, q)

        assertEquals("local", caller.call("p"), "quota refusal → same call answered locally")
        assertEquals("local", caller.call("p"))
        assertEquals(1, cloudCalls, "while exhausted the cloud is not even tried")

        now += 60_000L
        cloudFails = false
        assertEquals("cloud", caller.call("p"), "after the recheck window the cloud is tried again")
    }

    @Test
    fun `other cloud errors are not swallowed by the fallback`() {
        val caller = FallbackLlmCaller(LlmCaller { throw TransientLlmFailure("LLM HTTP 503: overloaded") }, LlmCaller { "local" }, CloudQuota(clock = { 0L }))
        assertFailsWith<TransientLlmFailure> { caller.call("p") }
    }

    // ── HTTP layer ──────────────────────────────────────────────────────────

    private fun client(backend: LlmBackend) = LlmClient(LlmConfig(model = "m", backend = backend, timeoutSeconds = 5))

    private fun withHttp(c: LlmClient, http: HttpClient) = c.also {
        LlmClient::class.java.getDeclaredField("http").apply { isAccessible = true }.set(it, http)
    }

    private fun post(c: LlmClient): String = LlmClient::class.java
        .getDeclaredMethod("post", String::class.java, Map::class.java, Map::class.java, Long::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .invoke(c, "http://localhost/api/chat", mapOf("model" to "m"), emptyMap<String, String>(), 5L) as String

    private fun resp(code: Int, body: String) = mock<HttpResponse<String>> {
        on { statusCode() } doReturn code
        on { body() } doReturn body
        on { headers() } doReturn java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
    }

    @Test
    fun `a cloud quota 429 throws CloudQuotaExhausted at once and marks the shared quota`() {
        val quota429 = resp(429, """{"error":"you have reached your weekly usage limit"}""")
        val http = mock<HttpClient>()
        whenever(http.sendAsync(any(), any<HttpResponse.BodyHandler<String>>()))
            .doReturn(CompletableFuture.completedFuture(quota429))
        val e = assertFailsWith<InvocationTargetException> { post(withHttp(client(LlmBackend.OLLAMA_CLOUD), http)) }
        assertIs<CloudQuotaExhausted>(e.targetException)
        verify(http, times(1)).sendAsync(any(), any<HttpResponse.BodyHandler<String>>())
        assertTrue(CloudQuota.shared.skipCloud(), "the ingest/score lane now goes straight to local")
    }

    @Test
    fun `a local backend never marks the cloud quota, and the concurrency 429 still retries`() {
        val usage429 = resp(429, """{"error":"usage limit"}""")
        val concurrency429 = resp(429, """{"error":"concurrent request limit exceeded"}""")
        val ok = resp(200, "ok")
        val local = mock<HttpClient>()
        whenever(local.sendAsync(any(), any<HttpResponse.BodyHandler<String>>()))
            .doReturn(CompletableFuture.completedFuture(usage429))
            .doReturn(CompletableFuture.completedFuture(ok))
        assertEquals("ok", post(withHttp(client(LlmBackend.MLX_LOCAL), local)))
        assertFalse(CloudQuota.shared.skipCloud())

        val cloud = mock<HttpClient>()
        whenever(cloud.sendAsync(any(), any<HttpResponse.BodyHandler<String>>()))
            .doReturn(CompletableFuture.completedFuture(concurrency429))
            .doReturn(CompletableFuture.completedFuture(ok))
        assertEquals("ok", post(withHttp(client(LlmBackend.OLLAMA_CLOUD), cloud)))
        assertFalse(CloudQuota.shared.skipCloud())
    }

    @Test
    fun `forIngest wraps cloud models only, and only when a local fallback is configured`() {
        assertIs<FallbackLlmCaller>(LlmClient.forIngest("glm-5.2:ollama-cloud", nodeKey = "score_fit", localFallbackModel = "Qwen3.6-35B-A3B-OptiQ-4bit"))
        assertIs<LlmClient>(LlmClient.forIngest("glm-5.2:ollama-cloud", nodeKey = "score_fit", localFallbackModel = ""), "no fallback configured")
        assertIs<LlmClient>(LlmClient.forIngest("Qwen3.5-9B-OptiQ-4bit", nodeKey = "score_fit", localFallbackModel = "Qwen3.6-35B-A3B-OptiQ-4bit"), "already local")
        assertIs<LlmClient>(LlmClient.forIngest("glm-5.2:ollama-cloud", nodeKey = "score_fit", localFallbackModel = "kimi-k2.6:ollama-cloud"), "a cloud 'fallback' is not a fallback")
    }
}
