package com.jd.jobbot.gmail

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
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The few Gmail calls JobBot makes, on the poller's account. Deliberately narrow: read one
 * message or thread, and move one message in or out of the inbox. No delete, no trash, no
 * filters, no label creation.
 */
open class GmailClient(
    private val auth: GmailAuth,
    private val baseUrl: String = "https://gmail.googleapis.com/gmail/v1/users/me",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    class GmailException(val status: Int, message: String) : RuntimeException(message)

    data class Message(
        val id: String,
        val threadId: String?,
        val labels: List<String>,
        val from: String?,
        val to: String?,
        val cc: String? = null,
        val subject: String?,
        val date: String?,
        val snippet: String?,
        val body: String,
        /** Gmail's receive time, epoch millis. */
        val internalDate: Long? = null,
    )

    open fun labels(messageId: String): List<String> =
        call("GET", "/messages/${enc(messageId)}?format=minimal")["labelIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    /** Add and/or remove label ids on one message; returns the labels after the change. */
    open fun modify(messageId: String, add: List<String> = emptyList(), remove: List<String> = emptyList()): List<String> {
        require(add.none { it in FORBIDDEN_LABELS } && remove.none { it in FORBIDDEN_LABELS }) { "label not allowed" }
        val body = buildJsonObject {
            put("addLabelIds", JsonArray(add.map { JsonPrimitive(it) }))
            put("removeLabelIds", JsonArray(remove.map { JsonPrimitive(it) }))
        }
        return call("POST", "/messages/${enc(messageId)}/modify", body.toString())["labelIds"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: emptyList()
    }

    open fun message(messageId: String): Message = parse(call("GET", "/messages/${enc(messageId)}?format=full"))

    open fun thread(threadId: String): List<Message> =
        call("GET", "/threads/${enc(threadId)}?format=full")["messages"]?.jsonArray?.map { parse(it.jsonObject) } ?: emptyList()

    /** Message ids matching a Gmail search, newest first. */
    open fun search(query: String, max: Int = 10): List<String> =
        call("GET", "/messages?q=${enc(query)}&maxResults=$max")["messages"]?.jsonArray
            ?.map { it.jsonObject["id"]!!.jsonPrimitive.content } ?: emptyList()

    // ── Drafts (reply workflow) ────────────────────────────────────────────────

    /** The account's own address, to tell its messages from the recruiter's. */
    open fun selfAddress(): String = call("GET", "/profile")["emailAddress"]!!.jsonPrimitive.content.lowercase()

    /** (draftId, threadId) of every draft. */
    open fun drafts(): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        var page: String? = null
        do {
            val resp = call("GET", "/drafts?maxResults=100" + (page?.let { "&pageToken=${enc(it)}" } ?: ""))
            resp["drafts"]?.jsonArray?.forEach { d ->
                val o = d.jsonObject
                out += o["id"]!!.jsonPrimitive.content to o["message"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content
            }
            page = resp["nextPageToken"]?.jsonPrimitive?.content
        } while (page != null && out.size < 500)
        return out
    }

    open fun draftRaw(draftId: String): String =
        call("GET", "/drafts/${enc(draftId)}?format=raw")["message"]!!.jsonObject["raw"]!!.jsonPrimitive.content

    open fun createDraft(raw: String, threadId: String?): String =
        call("POST", "/drafts", draftBody(null, raw, threadId))["id"]!!.jsonPrimitive.content

    open fun updateDraft(draftId: String, raw: String, threadId: String?): String =
        call("PUT", "/drafts/${enc(draftId)}", draftBody(draftId, raw, threadId))["id"]!!.jsonPrimitive.content

    /** Sends a draft; returns the sent message's id. */
    open fun sendDraft(draftId: String): String =
        call("POST", "/drafts/send", buildJsonObject { put("id", draftId) }.toString())["id"]!!.jsonPrimitive.content

    /** Raw RFC 822 headers of one message (for In-Reply-To / References). */
    open fun headers(messageId: String): Map<String, String> =
        call("GET", "/messages/${enc(messageId)}?format=metadata&metadataHeaders=Message-ID&metadataHeaders=References&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Reply-To")
            .get("payload")?.jsonObject?.get("headers")?.jsonArray?.associate {
                val h = it.jsonObject
                h["name"]!!.jsonPrimitive.content.lowercase() to h["value"]!!.jsonPrimitive.content
            } ?: emptyMap()

    private fun draftBody(id: String?, raw: String, threadId: String?) = buildJsonObject {
        id?.let { put("id", it) }
        put("message", buildJsonObject {
            put("raw", raw)
            threadId?.let { put("threadId", it) }
        })
    }.toString()

    internal fun call(method: String, path: String, body: String? = null, retried: Boolean = false): JsonObject {
        val token = auth.token(forceRefresh = retried)
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
        val req = if (body != null) {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build()
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        }
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 401 && !retried) return call(method, path, body, retried = true)
        if (resp.statusCode() !in 200..299) {
            throw GmailException(resp.statusCode(), "Gmail $method $path → ${resp.statusCode()}: ${resp.body().take(200)}")
        }
        return JSON.parseToJsonElement(resp.body().ifBlank { "{}" }).jsonObject
    }

    private fun parse(m: JsonObject): Message {
        val payload = m["payload"]?.jsonObject
        val headers = payload?.get("headers")?.jsonArray?.associate {
            val h = it.jsonObject
            (h["name"]?.jsonPrimitive?.content ?: "").lowercase() to (h["value"]?.jsonPrimitive?.content ?: "")
        } ?: emptyMap()
        return Message(
            id = m["id"]?.jsonPrimitive?.content ?: "",
            threadId = m["threadId"]?.jsonPrimitive?.content,
            labels = m["labelIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            from = headers["from"], to = headers["to"], cc = headers["cc"], subject = headers["subject"], date = headers["date"],
            snippet = m["snippet"]?.jsonPrimitive?.content,
            body = payload?.let { textOf(it) }.orEmpty(),
            internalDate = m["internalDate"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }

    /** The message's plain-text body: the first text/plain part, else the first text/html part, stripped. */
    internal fun textOf(part: JsonObject): String? =
        findPart(part, "text/plain")?.let { decode(it) } ?: findPart(part, "text/html")?.let { stripHtml(decode(it)) }

    private fun findPart(part: JsonObject, mime: String): String? {
        val data = part["body"]?.jsonObject?.get("data")?.jsonPrimitive?.content
        if (part["mimeType"]?.jsonPrimitive?.content == mime && data != null) return data
        return part["parts"]?.jsonArray?.firstNotNullOfOrNull { findPart(it.jsonObject, mime) }
    }

    private fun decode(data: String) = String(decodeBase64Url(data), Charsets.UTF_8)

    private fun stripHtml(html: String) = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Gmail's base64url, tolerantly: padded or not, possibly wrapped with whitespace. The JDK
         * decoder is strict about both, and one odd payload must not break reading the message.
         */
        fun decodeBase64Url(data: String): ByteArray {
            val clean = data.filterNot { it.isWhitespace() }.trimEnd('=').replace('+', '-').replace('/', '_')
            return Base64.getUrlDecoder().decode(clean)
        }

        /** System labels JobBot must never touch: deletion and spam are not archive. */
        val FORBIDDEN_LABELS = setOf("TRASH", "SPAM")
    }
}
