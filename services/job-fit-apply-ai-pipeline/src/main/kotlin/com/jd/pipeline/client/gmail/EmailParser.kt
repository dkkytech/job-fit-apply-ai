package com.jd.pipeline.client.gmail

import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.regex.Pattern

data class ParsedEmail(
    val plainText: String,
    val htmlBodies: List<String>,
    val inlineScripts: List<String>,
    val scriptUrls: List<String>,
    val partSummary: String,
)

object EmailParser {

    fun parse(msg: Message): ParsedEmail {
        val payload = msg.payload
        val plainText = decodeBody(payload)
        val htmlBodies = mutableListOf<String>()
        collectHtmlBodies(payload, htmlBodies)

        val inlineScripts = mutableListOf<String>()
        val scriptUrls = mutableListOf<String>()
        for (html in htmlBodies) {
            collectScripts(html, inlineScripts, scriptUrls)
        }

        val partSummary = buildPartSummary(payload)

        return ParsedEmail(
            plainText = plainText,
            htmlBodies = htmlBodies,
            inlineScripts = inlineScripts,
            scriptUrls = scriptUrls,
            partSummary = partSummary
        )
    }

    private fun decodeBody(payload: MessagePart?): String {
        if (payload == null) return ""

        val mimeType = payload.mimeType

        if (mimeType == "text/plain") {
            if (payload.body != null && payload.body.data != null) {
                val bytes = Base64.getUrlDecoder().decode(payload.body.data)
                return String(bytes, StandardCharsets.UTF_8)
            }
        }

        if (mimeType == "text/html") {
            if (payload.body != null && payload.body.data != null) {
                val bytes = Base64.getUrlDecoder().decode(payload.body.data)
                val html = String(bytes, StandardCharsets.UTF_8)
                val cleanedHtml = html.replace(Regex("<a\\b[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"), "$2 $1")
                val text = Jsoup.clean(cleanedHtml, Safelist.none())
                return text.replace(Regex("\\s+"), " ").trim()
            }
        }

        if (mimeType == "multipart/alternative" || mimeType == "multipart/mixed" || mimeType == "multipart/related") {
            if (payload.parts != null) {
                for (part in payload.parts) {
                    if (part.mimeType == "text/plain") {
                        val body = decodeBody(part)
                        if (body.isNotEmpty()) return body
                    }
                }
                for (part in payload.parts) {
                    val body = decodeBody(part)
                    if (body.isNotEmpty()) return body
                }
            }
        }

        return ""
    }

    private fun collectHtmlBodies(part: MessagePart?, htmlBodies: MutableList<String>) {
        if (part == null) return

        if (part.mimeType == "text/html" && part.body?.data != null) {
            htmlBodies.add(decodePartData(part.body.data))
        }

        part.parts?.forEach { child ->
            collectHtmlBodies(child, htmlBodies)
        }
    }

    private fun collectScripts(html: String, inlineScripts: MutableList<String>, scriptUrls: MutableList<String>) {
        val scriptTagPattern = Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script>")
        val matcher = scriptTagPattern.matcher(html)
        while (matcher.find()) {
            val attrs = matcher.group(1) ?: ""
            val body = matcher.group(2)?.trim().orEmpty()
            val srcMatcher = Pattern.compile("src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(attrs)
            if (srcMatcher.find()) {
                scriptUrls.add(srcMatcher.group(1))
            }
            if (body.isNotEmpty()) {
                inlineScripts.add(body)
            }
        }
    }

    private fun buildPartSummary(part: MessagePart?, depth: Int = 0): String {
        if (part == null) return ""

        val indent = "  ".repeat(depth)
        val current = buildString {
            append(indent)
            append("- mimeType=")
            append(part.mimeType ?: "unknown")
            val filename = part.filename ?: ""
            if (filename.isNotBlank()) {
                append(" filename=")
                append(filename)
            }
            val size = part.body?.size ?: 0
            append(" size=")
            append(size)
            append('\n')
        }

        val children = part.parts?.joinToString("") { buildPartSummary(it, depth + 1) }.orEmpty()
        return current + children
    }

    private fun decodePartData(data: String): String {
        val bytes = Base64.getUrlDecoder().decode(data)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
