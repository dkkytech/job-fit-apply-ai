package com.jd.pipeline.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.config.Config
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Shared Supabase REST client.
 *
 * Replaces the hand-rolled HTTP + toJson() pattern that was duplicated in every node.
 * Jackson handles all serialization/deserialization — no manual escaping.
 */
object SupabaseClient {

    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    fun isConfigured(): Boolean =
        Config.SUPABASE_PROJECT_URL.isNotEmpty() && Config.SUPABASE_SERVICE_ROLE_KEY.isNotEmpty()

    /**
     * POST /rest/v1/{table} — insert a single record.
     * Returns the first row of the "return=representation" response.
     */
    fun insert(table: String, record: Map<String, Any?>): JsonNode {
        val body = mapper.writeValueAsString(record)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.SUPABASE_PROJECT_URL}/rest/v1/$table"))
            .headers(*authHeaders())
            .header("Prefer", "return=representation")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        checkStatus(response, "INSERT $table")
        val node = mapper.readTree(response.body())
        return if (node.isArray && node.size() > 0) node.get(0) else node
    }

    /**
     * PATCH /rest/v1/{table}?{filterCol}=eq.{filterVal} — update matching rows.
     */
    fun patch(
        table: String,
        updates: Map<String, Any?>,
        filterCol: String,
        filterVal: String
    ) {
        val body = mapper.writeValueAsString(updates)
        val encoded = URLEncoder.encode(filterVal, "UTF-8")
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.SUPABASE_PROJECT_URL}/rest/v1/$table?$filterCol=eq.$encoded"))
            .headers(*authHeaders())
            .header("Prefer", "return=minimal")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        checkStatus(response, "PATCH $table")
    }

    /**
     * GET /rest/v1/{table}?{filters}&select={select}&limit={limit}
     *
     * [filters] values must already be Supabase filter expressions, e.g.:
     *   "company" to "eq.Acme Corp"
     *   "created_at" to "gte.2025-01-01T00:00:00Z"
     *
     * Returns an empty list when no rows match.
     */
    fun query(
        table: String,
        filters: Map<String, String>,
        select: String = "*",
        limit: Int = 10
    ): List<JsonNode> {
        val params = buildString {
            append("select=").append(URLEncoder.encode(select, "UTF-8"))
            append("&limit=").append(limit)
            filters.forEach { (col, expr) ->
                // expr is already "eq.value" / "gte.value" etc.
                // We encode the value portion only (after the operator prefix).
                val dotIdx = expr.indexOf('.')
                if (dotIdx >= 0) {
                    val op = expr.substring(0, dotIdx + 1)           // "eq." / "gte." etc.
                    val value = expr.substring(dotIdx + 1)
                    append("&").append(col).append("=").append(op)
                        .append(URLEncoder.encode(value, "UTF-8"))
                } else {
                    append("&").append(col).append("=").append(URLEncoder.encode(expr, "UTF-8"))
                }
            }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.SUPABASE_PROJECT_URL}/rest/v1/$table?$params"))
            .headers(*authHeaders())
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        checkStatus(response, "QUERY $table")
        val node = mapper.readTree(response.body())
        return if (node.isArray) node.toList() else emptyList()
    }

    /**
     * DELETE /rest/v1/{table}?{filterCol}=eq.{filterVal} — delete matching rows.
     */
    fun delete(table: String, filterCol: String, filterVal: String) {
        val encoded = URLEncoder.encode(filterVal, "UTF-8")
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.SUPABASE_PROJECT_URL}/rest/v1/$table?$filterCol=eq.$encoded"))
            .headers(*authHeaders())
            .header("Prefer", "return=minimal")
            .DELETE()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        checkStatus(response, "DELETE $table")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun authHeaders(): Array<String> = arrayOf(
        "Content-Type", "application/json",
        "apikey", Config.SUPABASE_SERVICE_ROLE_KEY,
        "Authorization", "Bearer ${Config.SUPABASE_SERVICE_ROLE_KEY}"
    )

    private fun checkStatus(response: HttpResponse<String>, operation: String) {
        if (response.statusCode() >= 400) {
            throw RuntimeException(
                "Supabase $operation error ${response.statusCode()}: ${response.body().take(300)}"
            )
        }
    }
}
