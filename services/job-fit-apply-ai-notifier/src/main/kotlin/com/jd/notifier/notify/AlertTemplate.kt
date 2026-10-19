package com.jd.notifier.notify

import java.nio.file.Files
import java.nio.file.Path

/**
 * The Telegram high-fit ping's text, from a template JobBot can edit (`update_alert_template`).
 *
 * Deliberately small: `{placeholder}` substitution only, every value HTML-escaped, links built in
 * code (`{company_link}`, `{title_link}`) so a template can never point anywhere, and a handful of
 * formatting tags. The file is re-read for each event, so an edit applies to the next ping. Any
 * problem — missing file, invalid template — returns null and the caller uses the built-in format;
 * an alert is never lost to a template. Rules are pinned by
 * docker/jobbot/contract/alert_template_vectors.json (shared with jobbot-mcp).
 */
class AlertTemplate(private val file: Path?) {
    data class Values(
        val company: String,
        val title: String,
        val score: String,
        val action: String,
        val ref: String,
        val jobUrl: String?,
        val reportUrl: String?,
        // Card details (null or empty when unknown — e.g. jobs completed before they existed).
        val location: String? = null,
        val remotePolicy: String? = null,
        val salary: String? = null,
        val source: String? = null,
        val strengths: List<String> = emptyList(),
        val gaps: List<String> = emptyList(),
    )

    /** The rendered text, or null to fall back to the built-in format. */
    fun render(values: Values): String? {
        val path = file ?: return null
        val template = runCatching { Files.readString(path) }.getOrNull()?.trimEnd() ?: return null
        if (validate(template) != null) return null
        return renderTemplate(template, values)
    }

    companion object {
        val PLACEHOLDERS = listOf("company", "title", "company_link", "title_link", "score", "action", "ref", "details", "strengths", "gap")
        val ALLOWED_TAGS = setOf("b", "i", "u", "s", "code", "pre", "blockquote")
        const val MAX_LENGTH = 1000
        private val PLACEHOLDER = Regex("""\{([a-z_]+)\}""")
        private val TAG = Regex("""<\s*(/?)\s*([a-zA-Z0-9]+)[^>]*>""")

        /** Null when valid, otherwise the reason. */
        fun validate(template: String): String? {
            if (template.isBlank()) return "empty"
            if (template.length > MAX_LENGTH) return "longer than $MAX_LENGTH characters"
            if ("{ref}" !in template) return "must contain {ref} (the job reference replies depend on)"
            PLACEHOLDER.findAll(template).map { it.groupValues[1] }.firstOrNull { it !in PLACEHOLDERS }
                ?.let { return "unknown placeholder {$it}; allowed: ${PLACEHOLDERS.joinToString { p -> "{$p}" }}" }
            if (Regex("""<\s*a[\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(template)) {
                return "links are built in code — use {company_link} / {title_link}"
            }
            // A stack, not per-tag counts: <b><i></b></i> is mis-nested and Telegram rejects it.
            val open = ArrayDeque<String>()
            for (m in TAG.findAll(template)) {
                val (closing, name) = m.destructured
                val tag = name.lowercase()
                if (tag !in ALLOWED_TAGS) return "tag <$tag> not allowed; allowed: ${ALLOWED_TAGS.joinToString()}"
                if (closing != "/") {
                    open.addLast(tag)
                } else {
                    val top = open.removeLastOrNull() ?: return "unbalanced </$tag>"
                    if (top != tag) return "mis-nested </$tag> (expected </$top>)"
                }
            }
            open.lastOrNull()?.let { return "unbalanced <$it>" }
            return null
        }

        /**
         * A line whose placeholders all render empty is dropped, so `<b>Gap:</b> {gap}` disappears
         * for a job with no gap instead of leaving a bare label.
         */
        fun renderTemplate(template: String, v: Values): String {
            val company = esc(v.company)
            val title = esc(v.title)
            val values = mapOf(
                "company_link" to (v.jobUrl?.takeIf { it.isNotBlank() }?.let { "<a href=\"${esc(it)}\">$company</a>" } ?: company),
                "title_link" to (v.reportUrl?.takeIf { it.isNotBlank() }?.let { "<a href=\"${esc(it)}\">$title</a>" } ?: title),
                "company" to company, "title" to title, "score" to esc(v.score), "action" to esc(v.action), "ref" to esc(v.ref),
                "details" to esc(details(v)), "strengths" to esc(strengths(v)), "gap" to esc(gap(v)),
            )
            return template.split("\n").mapNotNull { line ->
                val names = PLACEHOLDER.findAll(line).map { it.groupValues[1] }.toList()
                if (names.isNotEmpty() && names.all { values[it].isNullOrEmpty() }) return@mapNotNull null
                PLACEHOLDER.replace(line) { m -> values[m.groupValues[1]] ?: m.value }
            }.joinToString("\n")
        }

        /** "Seattle, WA · hybrid · $150K–$180K · via jobright.ai" — whatever is known, in that order. */
        fun details(v: Values): String = listOfNotNull(
            v.location.clean(), v.remotePolicy.clean()?.takeUnless { it.equals("unknown", ignoreCase = true) },
            v.salary.clean(), v.source.clean()?.let { "via $it" },
        ).joinToString(" · ")

        /** The top three strengths as bullet lines. */
        fun strengths(v: Values): String =
            v.strengths.mapNotNull { it.clean() }.take(MAX_STRENGTHS).joinToString("\n") { "• " + clip(it, 120) }

        /** The main gap (the first one the scorer listed). */
        fun gap(v: Values): String = v.gaps.firstNotNullOfOrNull { it.clean() }?.let { clip(it, 160) }.orEmpty()

        const val MAX_STRENGTHS = 3

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
        private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
