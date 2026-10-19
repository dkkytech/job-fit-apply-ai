package com.jd.jobbot.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One JSON-returning chat completion. An interface so the fill loop is tested with scripted replies. */
fun interface Llm {
    fun json(system: String, user: String): JsonObject
}

/**
 * OpenAI-compatible chat completions (the host's Ollama route, the same model family as the
 * agent). Asks for a JSON object and parses the first one in the reply, tolerating a fenced block.
 */
class OpenAiCompatibleLlm(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(120),
) : Llm {
    override fun json(system: String, user: String): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", JsonArray(listOf(
                buildJsonObject { put("role", "system"); put("content", system) },
                buildJsonObject { put("role", "user"); put("content", user) },
            )))
        }
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/chat/completions")).timeout(timeout)
            .header("Content-Type", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() in 200..299) { "LLM ${resp.statusCode()}: ${resp.body().take(200)}" }
        val content = JSON.parseToJsonElement(resp.body()).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        return parseJsonObject(content)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseJsonObject(text: String): JsonObject {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            require(start >= 0 && end > start) { "no JSON object in model reply: ${text.take(120)}" }
            return JSON.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }
    }
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
