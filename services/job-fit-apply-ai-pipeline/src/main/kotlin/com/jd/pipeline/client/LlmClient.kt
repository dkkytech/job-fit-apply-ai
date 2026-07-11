package com.jd.pipeline.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.NodeTimer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.pow

enum class LlmBackend { OLLAMA_LOCAL, OLLAMA_CLOUD, DEEPSEEK_CLOUD, MINIMAX_CLOUD }

fun interface LlmCaller {
    fun call(prompt: String): String
}

/**
 * Configuration for a single LLM call target.
 *
 * @param thinkingEnabled  When false, "/no_think\n" is prepended to the user message so the
 *                         Ollama Modelfile TEMPLATE can suppress chain-of-thought tokens.
 * @param temperature      Passed as options.temperature to Ollama when non-null.
 *                         null = model default.
 */
data class LlmConfig(
    val model: String,
    val backend: LlmBackend,
    val thinkingEnabled: Boolean = false,
    val temperature: Double? = null,
    val timeoutSeconds: Long = 120,
    val jsonMode: Boolean = true,
    val nodeKey: String = ""
)

/**
 * Shared LLM HTTP client.  All nodes obtain one via the companion factory helpers or
 * [fromModelString] so HTTP code is never duplicated in individual node classes.
 *
 * Uses /api/chat (not /api/generate) for Ollama so Modelfile TEMPLATEs apply —
 * required for /no_think token injection to disable thinking on qwen3-family models.
 */
class LlmClient(private val config: LlmConfig) : LlmCaller {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val mapper = ObjectMapper()

    /** Call the configured LLM and return the raw assistant content string. */
    override fun call(prompt: String): String {
        val t0 = System.currentTimeMillis()
        try {
            return when (config.backend) {
                LlmBackend.OLLAMA_LOCAL       -> callOllama(prompt, Config.OLLAMA_LOCAL_BASE_URL)
                LlmBackend.OLLAMA_CLOUD -> callOllama(prompt, Config.OLLAMA_CLOUD_BASE_URL, Config.OLLAMA_API_KEY)
                LlmBackend.DEEPSEEK_CLOUD -> callDeepSeekCloud(prompt)
                LlmBackend.MINIMAX_CLOUD  -> callMinimaxCloud(prompt)
            }
        } finally {
            if (config.nodeKey.isNotEmpty()) NodeTimer.record(config.nodeKey, System.currentTimeMillis() - t0)
        }
    }

    // ── Ollama (local and cloud share the same /api/chat wire format) ─────────

    private fun callOllama(prompt: String, baseUrl: String, apiKey: String = ""): String {
        val isQwen3 = config.model.startsWith("qwen3", ignoreCase = true)
        val content = if (!config.thinkingEnabled && isQwen3) "/no_think\n$prompt" else prompt

        val messages = listOf(mapOf("role" to "user", "content" to content))
        val body = buildMap<String, Any> {
            put("model", config.model)
            put("messages", messages)
            put("stream", false)
            if (config.jsonMode) put("format", "json")
            config.temperature?.let { put("options", mapOf("temperature" to it)) }
        }

        val headers = if (apiKey.isNotEmpty())
            mapOf("Authorization" to "Bearer $apiKey")
        else emptyMap()

        val responseBody = post(
            url = "$baseUrl/api/chat",
            bodyMap = body,
            headers = headers,
            timeoutSeconds = config.timeoutSeconds
        )

        // /api/chat response shape: {"message":{"role":"assistant","content":"..."}, ...}
        return mapper.readTree(responseBody)
            .path("message").path("content").asText()
            .also { if (it.isBlank()) throw RuntimeException("Empty content in Ollama response") }
    }

    // ── Cloud APIs ────────────────────────────────────────────────────────────

    private fun callDeepSeekCloud(prompt: String): String {
        val body = buildMap<String, Any> {
            put("model", config.model)
            put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
            if (config.jsonMode) put("response_format", mapOf("type" to "json_object"))
            config.temperature?.let { put("temperature", it) }
        }
        val responseBody = post(
            url = "${Config.DEEPSEEK_BASE_URL}/v1/chat/completions",
            bodyMap = body,
            headers = mapOf("Authorization" to "Bearer ${Config.DEEPSEEK_API_KEY}"),
            timeoutSeconds = config.timeoutSeconds
        )
        return extractChatContent(responseBody)
    }

    private fun callMinimaxCloud(prompt: String): String {
        val body = buildMap<String, Any> {
            put("model", config.model)
            put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
            if (config.jsonMode) put("response_format", mapOf("type" to "json_object"))
            config.temperature?.let { put("temperature", it) }
        }
        val responseBody = post(
            url = "${Config.MINIMAX_BASE_URL}/chat/completions",
            bodyMap = body,
            headers = mapOf("Authorization" to "Bearer ${Config.MINIMAX_API_KEY}"),
            timeoutSeconds = config.timeoutSeconds
        )
        return extractChatContent(responseBody)
    }

    /** Extract choices[0].message.content from an OpenAI-compatible chat response. */
    private fun extractChatContent(responseBody: String): String {
        val root = mapper.readTree(responseBody)
        var content = root.path("choices").path(0).path("message").path("content").asText()
        if (content.isBlank()) {
            val snippet = responseBody.take(400)
            throw RuntimeException("Empty content in cloud API response: $snippet")
        }
        // Strip <think>...</think> reasoning blocks emitted by some models (MiniMax, DeepSeek R1)
        // before the actual JSON response.
        content = content.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()
        return content
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun post(
        url: String,
        bodyMap: Map<String, Any>,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = config.timeoutSeconds
    ): String {
        val bodyStr = mapper.writeValueAsString(bodyMap)

        var lastException: RuntimeException? = null
        for (attempt in 0..MAX_RETRIES) {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))

            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() < 400) {
                return response.body()
            }
            if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
                val retryAfter = response.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()
                val delayMs = if (retryAfter != null && retryAfter > 0) {
                    retryAfter * 1000
                } else {
                    (2.0.pow(attempt) * 1000).toLong()
                }
                System.err.println("[llm_client] HTTP 429 from $url -- retrying in ${delayMs}ms (attempt ${attempt + 1}/${MAX_RETRIES})")
                Thread.sleep(delayMs)
                continue
            }
            lastException = RuntimeException("LLM HTTP ${response.statusCode()} from $url: ${response.body().take(300)}")
            break
        }
        throw lastException ?: RuntimeException("LLM HTTP request failed after retries")
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    companion object {
        /** Maximum retry attempts on HTTP 429 rate-limit responses. */
        private const val MAX_RETRIES = 3

        /**
         * Orchestration executor: deterministic extraction/analysis nodes (temp=0, thinking disabled).
         * Uses SCORE_MODEL. Respects all backend suffixes (:ollama-cloud, :cloud).
         */
        fun orchestrationClient(nodeKey: String = ""): LlmClient {
            val model = Config.SCORE_MODEL
            return LlmClient(
                LlmConfig(
                    model = stripBackendSuffix(model),
                    backend = backendFor(model),
                    thinkingEnabled = false,
                    temperature = 0.0,
                    timeoutSeconds = 180,
                    nodeKey = nodeKey
                )
            )
        }

        /**
         * Reasoning executor: creative rewriting nodes (temp=0.4, thinking enabled for both
         * Ollama backends). Thinking is injected via /no_think suppression on qwen3 models;
         * cloud models (DeepSeek, MiniMax) manage their own reasoning internally.
         */
        fun reasoningClient(nodeKey: String = ""): LlmClient {
            val model = Config.RESUME_REASONING_MODEL
            val backend = backendFor(model)
            return LlmClient(
                LlmConfig(
                    model = stripBackendSuffix(model),
                    backend = backend,
                    thinkingEnabled = backend == LlmBackend.OLLAMA_LOCAL || backend == LlmBackend.OLLAMA_CLOUD,
                    temperature = 0.4,
                    timeoutSeconds = 300,
                    nodeKey = nodeKey
                )
            )
        }

        /**
         * Skills restructure executor: judgment-heavy but factually grounded (temp=0.2, thinking disabled).
         * Uses SKILLS_MODEL — defaults to RESUME_REASONING_MODEL if not set.
         */
        fun skillsClient(nodeKey: String = ""): LlmClient {
            val model = Config.SKILLS_MODEL
            return LlmClient(
                LlmConfig(
                    model = stripBackendSuffix(model),
                    backend = backendFor(model),
                    thinkingEnabled = false,
                    temperature = 0.2,
                    timeoutSeconds = 180,
                    nodeKey = nodeKey
                )
            )
        }

        /**
         * Build a client from a model string. Suffix conventions:
         *   "qwen3:14b"                  → local Ollama (OLLAMA_BASE_URL)
         *   "glm-5.1:ollama-cloud"       → Ollama Cloud (OLLAMA_CLOUD_BASE_URL + OLLAMA_API_KEY)
         *   "deepseek-v4-pro:cloud"      → DeepSeek API
         *   "MiniMax-M2.7:cloud"         → MiniMax API
         */
        fun fromModelString(
            model: String,
            jsonMode: Boolean = true,
            temperature: Double? = null,
            timeoutSeconds: Long = 120,
            nodeKey: String = ""
        ): LlmClient {
            return LlmClient(
                LlmConfig(
                    model = stripBackendSuffix(model),
                    backend = backendFor(model),
                    thinkingEnabled = false,
                    temperature = temperature,
                    timeoutSeconds = timeoutSeconds,
                    jsonMode = jsonMode,
                    nodeKey = nodeKey
                )
            )
        }

        /**
         * Determine the LLM backend from a model string.
         *   No suffix           → OLLAMA  (local, OLLAMA_BASE_URL)
         *   ":ollama-cloud"     → OLLAMA_CLOUD (OLLAMA_CLOUD_BASE_URL + OLLAMA_API_KEY)
         *   "minimax*:cloud"    → MINIMAX_CLOUD
         *   "<other>:cloud"     → DEEPSEEK_CLOUD
         */
        private fun backendFor(model: String): LlmBackend = when {
            model.endsWith(":ollama-cloud") -> LlmBackend.OLLAMA_CLOUD
            !model.endsWith(":cloud")       -> LlmBackend.OLLAMA_LOCAL
            else -> {
                val name = model.removeSuffix(":cloud")
                if (name.startsWith("minimax", ignoreCase = true)) LlmBackend.MINIMAX_CLOUD
                else LlmBackend.DEEPSEEK_CLOUD
            }
        }

        /** Strip any backend routing suffix to get the bare model name sent to the API. */
        private fun stripBackendSuffix(model: String): String = when {
            model.endsWith(":ollama-cloud") -> model.removeSuffix(":ollama-cloud")
            model.endsWith(":cloud")        -> model.removeSuffix(":cloud")
            else                            -> model
        }
    }
}
