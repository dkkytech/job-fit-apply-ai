package com.jd.pipeline.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.NodeTimer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.pow

enum class LlmBackend(val isCloud: Boolean) {
    MLX_LOCAL(false), OLLAMA_LOCAL(false), OLLAMA_CLOUD(true), DEEPSEEK_CLOUD(true), MINIMAX_CLOUD(true)
}

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
    val nodeKey: String = "",
    // Grace added to timeoutSeconds for the hard wall-clock bound on the whole HTTP exchange
    // (headers + body). Guarantees a stalled response body can't hang the worker indefinitely.
    val hardTimeoutGraceSeconds: Long = 15,
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

    /**
     * Call the configured LLM and return the assistant content with any chain-of-thought
     * reasoning stripped. Stripping is applied centrally here so every backend (MLX, Ollama
     * local/cloud, DeepSeek, MiniMax) returns clean text — reasoning models such as Qwen3.5
     * (`<thinking>`) and DeepSeek-R1 (`<think>`) otherwise leak their reasoning into prose
     * outputs like the recruiter draft reply.
     */
    override fun call(prompt: String): String {
        val t0 = System.currentTimeMillis()
        try {
            // Gate on the physical resource: local backends (oMLX + Ollama-local) share one
            // permit so they never thrash the host GPU; cloud backends draw from a larger pool.
            val raw = LlmGate.withPermit(config.backend) {
                when (config.backend) {
                    LlmBackend.MLX_LOCAL          -> callMlxLocal(prompt)
                    LlmBackend.OLLAMA_LOCAL       -> callOllama(prompt, Config.OLLAMA_LOCAL_BASE_URL)
                    LlmBackend.OLLAMA_CLOUD -> callOllama(prompt, Config.OLLAMA_CLOUD_BASE_URL, Config.OLLAMA_API_KEY)
                    LlmBackend.DEEPSEEK_CLOUD -> callDeepSeekCloud(prompt)
                    LlmBackend.MINIMAX_CLOUD  -> callMinimaxCloud(prompt)
                }
            }
            return stripReasoning(raw)
        } finally {
            if (config.nodeKey.isNotEmpty()) NodeTimer.record(config.nodeKey, System.currentTimeMillis() - t0)
        }
    }

    // ── oMLX local (OpenAI-compatible /v1/chat/completions) ────────────────────

    /**
     * Local MLX inference via oMLX. oMLX speaks the OpenAI chat API, so this reuses the same
     * wire format as the cloud backends. The "/no_think" prefix is kept for qwen3-family models
     * (it is prompt-level and works regardless of server) to suppress chain-of-thought.
     */
    private fun callMlxLocal(prompt: String): String {
        // substringAfterLast("--") strips an HF-cache publisher prefix (e.g.
        // "mlx-community--Qwen3.6-27B-4bit") so /no_think still fires for qwen3 models
        // served under their prefixed oMLX id, not just bare-named ones.
        val isQwen3 = config.model.substringAfterLast("--").startsWith("qwen3", ignoreCase = true)
        val content = if (!config.thinkingEnabled && isQwen3) "/no_think\n$prompt" else prompt

        val body = buildMap<String, Any> {
            put("model", config.model)
            put("messages", listOf(mapOf("role" to "user", "content" to content)))
            put("stream", false)
            if (config.jsonMode) put("response_format", mapOf("type" to "json_object"))
            config.temperature?.let { put("temperature", it) }
        }
        val responseBody = post(
            url = "${Config.MLX_LOCAL_BASE_URL}/chat/completions",
            bodyMap = body,
            headers = mapOf("Authorization" to "Bearer ${Config.MLX_API_KEY}"),
            timeoutSeconds = config.timeoutSeconds
        )
        return extractChatContent(responseBody)
    }

    // ── Ollama (local and cloud share the same /api/chat wire format) ─────────

    private fun callOllama(prompt: String, baseUrl: String, apiKey: String = ""): String {
        // substringAfterLast("--") strips an HF-cache publisher prefix (e.g.
        // "mlx-community--Qwen3.6-27B-4bit") so /no_think still fires for qwen3 models
        // served under their prefixed oMLX id, not just bare-named ones.
        val isQwen3 = config.model.substringAfterLast("--").startsWith("qwen3", ignoreCase = true)
        val content = if (!config.thinkingEnabled && isQwen3) "/no_think\n$prompt" else prompt

        val messages = listOf(mapOf("role" to "user", "content" to content))
        val body = buildMap<String, Any> {
            put("model", config.model)
            put("messages", messages)
            put("stream", false)
            put("keep_alive", -1)  // prevent model eviction between pipeline steps
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
        val content = root.path("choices").path(0).path("message").path("content").asText()
        if (content.isBlank()) {
            val snippet = responseBody.take(400)
            throw RuntimeException("Empty content in cloud API response: $snippet")
        }
        // Reasoning (<think>/<thinking>) is stripped centrally in call().
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

            // Hard wall-clock bound. HttpRequest.timeout() only covers time-to-response-headers;
            // a server that returns headers then stalls the body would otherwise hang the (single-
            // threaded) worker forever. sendAsync + Future.get(timeout) bounds the TOTAL exchange,
            // and on timeout we cancel the request so the connection is released.
            val hardTimeoutSeconds = timeoutSeconds + config.hardTimeoutGraceSeconds
            val future = http.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            val response = try {
                future.get(hardTimeoutSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw TransientLlmFailure("LLM call to $url exceeded hard timeout of ${hardTimeoutSeconds}s", e)
            } catch (e: ExecutionException) {
                future.cancel(true)
                val message = "LLM call to $url failed: ${e.cause?.message ?: e.message}"
                // Transport failures — HttpRequest.timeout()'s "request timed out", a reset or
                // refused connection — say nothing about the request itself, so they are as
                // retryable as a 5xx. Left as a plain RuntimeException, nodes that only rethrow
                // TransientLlmFailure recorded them as a terminal JD_Error instead.
                throw if (e.cause is java.io.IOException) TransientLlmFailure(message, e.cause) else RuntimeException(message)
            }
            if (response.statusCode() < 400) {
                if (config.backend.isCloud) CloudQuota.shared.markHealthy()
                return response.body()
            }
            // A used-up quota won't clear in seconds: no in-process retry. Mark it so the ingest/score
            // lane switches to the local fallback, and throw the distinct type the fallback catches.
            if (config.backend.isCloud && CloudQuota.isQuotaResponse(response.statusCode(), response.body())) {
                val message = "LLM HTTP ${response.statusCode()} from $url: ${response.body().take(300)}"
                CloudQuota.shared.markExhausted(message)
                throw CloudQuotaExhausted(message)
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
            val message = "LLM HTTP ${response.statusCode()} from $url: ${response.body().take(300)}"
            lastException = if (response.statusCode() == 429 || response.statusCode() in 500..599) {
                TransientLlmFailure(message)
            } else {
                RuntimeException(message)
            }
            break
        }
        throw lastException ?: RuntimeException("LLM HTTP request failed after retries")
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    companion object {
        /** Maximum retry attempts on HTTP 429 rate-limit responses. */
        private const val MAX_RETRIES = 3

        // Well-formed reasoning block: <think>/<thinking>/<reasoning> … matching close tag.
        // Tolerant of attributes and surrounding whitespace; case-insensitive; DOTALL via [\s\S].
        private val REASONING_BLOCK = Regex(
            "<\\s*(think|thinking|reasoning)\\b[^>]*>[\\s\\S]*?<\\s*/\\s*\\1\\s*>",
            RegexOption.IGNORE_CASE
        )
        // Orphan closing tag — some chat templates emit reasoning, a close tag, then the answer,
        // with no opening tag.
        private val REASONING_CLOSE = Regex(
            "<\\s*/\\s*(?:think|thinking|reasoning)\\s*>",
            RegexOption.IGNORE_CASE
        )

        /**
         * Remove chain-of-thought reasoning that reasoning models emit before their answer:
         *  - well-formed `<think>`/`<thinking>`/`<reasoning>` … `</…>` blocks, and
         *  - an orphan closing tag (reasoning emitted without an opening tag, then the answer).
         *
         * Applied to every LLM response so reasoning never leaks into outputs such as the
         * recruiter draft reply. Safe on clean text (returns it trimmed, unchanged).
         */
        internal fun stripReasoning(content: String): String {
            if (content.isEmpty()) return content
            var out = REASONING_BLOCK.replace(content, "")
            REASONING_CLOSE.findAll(out).lastOrNull()?.let { orphan ->
                out = out.substring(orphan.range.last + 1)
            }
            return out.trim()
        }

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
         * Reasoning executor: creative rewriting nodes (temp=0.4). Thinking is controlled by
         * RESUME_REASONING_THINKING (default false) — qwen3:32b thinking traces routinely exceed
         * the 300s timeout on local hardware, so thinking is off by default.
         */
        fun reasoningClient(nodeKey: String = "", timeoutSeconds: Long = 300): LlmClient {
            val model = Config.RESUME_REASONING_MODEL
            val backend = backendFor(model)
            val isOllama = backend == LlmBackend.OLLAMA_LOCAL || backend == LlmBackend.OLLAMA_CLOUD
            return LlmClient(
                LlmConfig(
                    model = stripBackendSuffix(model),
                    backend = backend,
                    thinkingEnabled = isOllama && Config.RESUME_REASONING_THINKING,
                    // 0.25 (was 0.4): lower drift/fabrication on dense local models (gemma-4-31b)
                    // while keeping bullet/summary prose from going flat.
                    temperature = 0.25,
                    timeoutSeconds = timeoutSeconds,
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
         *   "Qwen3.5-9B-OptiQ-4bit"      → local oMLX (MLX_LOCAL_BASE_URL)
         *   "qwen3:14b:ollama-local"     → local Ollama escape hatch (OLLAMA_LOCAL_BASE_URL)
         *   "glm-5.1:ollama-cloud"       → Ollama Cloud (OLLAMA_CLOUD_BASE_URL + OLLAMA_API_KEY)
         *   "deepseek-v4-pro:cloud"      → DeepSeek API
         *   "MiniMax-M2.7:cloud"         → MiniMax API
         */
        fun fromModelString(
            model: String,
            jsonMode: Boolean = true,
            temperature: Double? = null,
            timeoutSeconds: Long = 180,
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
         * [fromModelString] for the ingest/score lane (scan, digest, scrape, score_fit): when the
         * model is a cloud model and LOCAL_FALLBACK_MODEL is set, calls switch to that local model
         * while the cloud quota is exhausted (see [FallbackLlmCaller]). Tailoring never uses this.
         */
        fun forIngest(
            model: String,
            jsonMode: Boolean = true,
            temperature: Double? = null,
            timeoutSeconds: Long = 180,
            nodeKey: String = "",
            localFallbackModel: String = Config.LOCAL_FALLBACK_MODEL,
        ): LlmCaller {
            val primary = fromModelString(model, jsonMode, temperature, timeoutSeconds, nodeKey)
            if (localFallbackModel.isBlank() || !backendFor(model).isCloud || backendFor(localFallbackModel).isCloud) return primary
            val local = fromModelString(localFallbackModel, jsonMode, temperature, maxOf(timeoutSeconds, Config.LOCAL_FALLBACK_TIMEOUT_SECONDS), nodeKey)
            return FallbackLlmCaller(primary, local, label = nodeKey.ifBlank { model })
        }

        /**
         * Determine the LLM backend from a model string.
         *   No suffix           → MLX_LOCAL (local oMLX, MLX_LOCAL_BASE_URL)
         *   ":ollama-local"     → OLLAMA_LOCAL (escape hatch, OLLAMA_LOCAL_BASE_URL)
         *   ":ollama-cloud"     → OLLAMA_CLOUD (OLLAMA_CLOUD_BASE_URL + OLLAMA_API_KEY)
         *   "minimax*:cloud"    → MINIMAX_CLOUD
         *   "<other>:cloud"     → DEEPSEEK_CLOUD
         */
        internal fun backendFor(model: String): LlmBackend = when {
            model.endsWith(":ollama-local") -> LlmBackend.OLLAMA_LOCAL
            model.endsWith(":ollama-cloud") -> LlmBackend.OLLAMA_CLOUD
            !model.endsWith(":cloud")       -> LlmBackend.MLX_LOCAL
            else -> {
                val name = model.removeSuffix(":cloud")
                if (name.startsWith("minimax", ignoreCase = true)) LlmBackend.MINIMAX_CLOUD
                else LlmBackend.DEEPSEEK_CLOUD
            }
        }

        /** Strip any backend routing suffix to get the bare model name sent to the API. */
        private fun stripBackendSuffix(model: String): String = when {
            model.endsWith(":ollama-local") -> model.removeSuffix(":ollama-local")
            model.endsWith(":ollama-cloud") -> model.removeSuffix(":ollama-cloud")
            model.endsWith(":cloud")        -> model.removeSuffix(":cloud")
            else                            -> model
        }
    }
}
