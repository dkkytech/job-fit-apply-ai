package com.jd.jobbot.jobs

import com.jd.jobbot.bridge.str
import kotlinx.serialization.json.JsonObject
import java.net.URI

/**
 * What makes two completions "the same job" for idempotency. The pipeline often processes one
 * posting twice (a digest and a direct alert, a rescore), so the completion seq is not enough:
 * the posting URL is, with tracking parameters stripped. Without a URL, the source email; without
 * either, company + title.
 */
object JobIdentity {
    private val TRACKING_PARAMS = Regex("^(utm_[a-z]+|gclid|fbclid|ref|src|source|trk|trackingId)$", RegexOption.IGNORE_CASE)

    fun of(event: JsonObject): String {
        event.str("job_url")?.let { normalizeUrl(it) }?.let { return "url:$it" }
        event.str("message_id")?.let { return "msg:$it" }
        val company = event.str("company").orEmpty().lowercase().trim()
        val title = event.str("role_title").orEmpty().lowercase().trim()
        return "co:$company|$title"
    }

    internal fun normalizeUrl(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val query = uri.rawQuery?.split('&')
            ?.filter { it.isNotBlank() && !TRACKING_PARAMS.matches(it.substringBefore('=')) }
            ?.sorted()
            ?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }
        val path = (uri.rawPath ?: "").trimEnd('/')
        host + path + (query?.let { "?$it" } ?: "")
    }.getOrNull()
}
