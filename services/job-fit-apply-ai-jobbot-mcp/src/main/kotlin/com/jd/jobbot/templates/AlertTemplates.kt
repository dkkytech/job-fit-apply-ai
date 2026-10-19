package com.jd.jobbot.templates

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The notifier's Telegram ping template, editable from the chat (Phase 2 template tools).
 * Validation mirrors the notifier's AlertTemplate exactly — both are pinned by
 * docker/jobbot/contract/alert_template_vectors.json — so a template JobBot accepts is one the
 * notifier will use. The notifier mounts the file read-only and re-reads it per event.
 */
class AlertTemplates(private val file: Path) {
    data class Values(
        val company: String, val title: String, val score: String, val action: String, val ref: String, val jobUrl: String?, val reportUrl: String?,
        val location: String? = null, val remotePolicy: String? = null, val salary: String? = null, val source: String? = null,
        val strengths: List<String> = emptyList(), val gaps: List<String> = emptyList(),
    )

    private val prev: Path get() = file.resolveSibling(file.fileName.toString() + ".prev")

    /** The current template, or null when the built-in format is in use. */
    fun current(): String? = file.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it).trimEnd() }

    /** Validate and write; the replaced version is kept for revert. Returns the error, or null. */
    fun update(template: String): String? {
        val t = template.trimEnd()
        validate(t)?.let { return it }
        Files.createDirectories(file.toAbsolutePath().parent)
        if (Files.exists(file)) Files.copy(file, prev, StandardCopyOption.REPLACE_EXISTING) else Files.deleteIfExists(prev)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, t + "\n")
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return null
    }

    /** Back to the previous template (or the built-in format if there was none). */
    fun revert(): String {
        return if (Files.exists(prev)) {
            Files.move(prev, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            "Reverted to the previous template."
        } else {
            Files.deleteIfExists(file)
            "Reverted to the built-in format."
        }
    }

    companion object {
        val PLACEHOLDERS = listOf("company", "title", "company_link", "title_link", "score", "action", "ref", "details", "strengths", "gap")
        val ALLOWED_TAGS = setOf("b", "i", "u", "s", "code", "pre", "blockquote")
        const val MAX_LENGTH = 1000
        /** The notifier's built-in card, as a template (lines with only empty placeholders drop out). */
        const val BUILT_IN = "{company_link}: {title_link} ({score})\n{details}\n\nFit:\n{strengths}\n\nGap: {gap}\n{ref}"
        private val PLACEHOLDER = Regex("""\{([a-z_]+)\}""")
        private val TAG = Regex("""<\s*(/?)\s*([a-zA-Z0-9]+)[^>]*>""")

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

        /** Same rules as the notifier's AlertTemplate.renderTemplate, pinned by the shared vectors. */
        fun render(template: String, v: Values): String {
            val company = esc(v.company)
            val title = esc(v.title)
            val values = mapOf(
                "company_link" to (v.jobUrl?.takeIf { it.isNotBlank() }?.let { "<a href=\"${esc(it)}\">$company</a>" } ?: company),
                "title_link" to (v.reportUrl?.takeIf { it.isNotBlank() }?.let { "<a href=\"${esc(it)}\">$title</a>" } ?: title),
                "company" to company, "title" to title, "score" to esc(v.score), "action" to esc(v.action), "ref" to esc(v.ref),
                "details" to esc(details(v)), "strengths" to esc(strengths(v)), "gap" to esc(gap(v)),
            )
            // A line whose placeholders all render empty is dropped (no bare "Gap:" label).
            return template.split("\n").mapNotNull { line ->
                val names = PLACEHOLDER.findAll(line).map { it.groupValues[1] }.toList()
                if (names.isNotEmpty() && names.all { values[it].isNullOrEmpty() }) return@mapNotNull null
                PLACEHOLDER.replace(line) { m -> values[m.groupValues[1]] ?: m.value }
            }.joinToString("\n")
        }

        fun details(v: Values): String = listOfNotNull(
            v.location.clean(), v.remotePolicy.clean()?.takeUnless { it.equals("unknown", ignoreCase = true) },
            v.salary.clean(), v.source.clean()?.let { "via $it" },
        ).joinToString(" · ")

        fun strengths(v: Values): String = v.strengths.mapNotNull { it.clean() }.take(3).joinToString("\n") { "• " + clip(it, 120) }

        fun gap(v: Values): String = v.gaps.firstNotNullOfOrNull { it.clean() }?.let { clip(it, 160) }.orEmpty()

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
        private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
