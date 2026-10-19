package com.jd.pipeline.nodes.scan.digest

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import java.nio.file.Files

object LlmDigestStrategy : BoardDigestStrategy {
    private val mapper = ObjectMapper()

    private val llm by lazy {
        LlmClient.forIngest(Config.SCAN_MODEL, jsonMode = true, temperature = 0.0, nodeKey = "digest_llm")
    }

    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val skill = runCatching { Files.readString(Config.DIGEST_SKILL) }.getOrElse { return emptyList() }
        val senderDomain = Regex("@([\\w.-]+)").find(email.from)?.groupValues?.get(1).orEmpty()
        val subject = email.subject
        val body = (email.rawBody.ifBlank { email.htmlBody }).take(8000)

        val prompt = buildString {
            appendLine(skill)
            appendLine()
            appendLine("SENDER_DOMAIN: $senderDomain")
            appendLine("SUBJECT: $subject")
            appendLine("EMAIL_BODY:")
            append(body)
        }

        val raw = runCatching { llm.call(prompt) }.getOrElse { return emptyList() }
        val jsonText = extractJsonArray(raw) ?: return emptyList()

        return runCatching {
            val array = mapper.readTree(jsonText)
            if (!array.isArray) return emptyList()
            val jobs = mutableListOf<JDState>()
            for (node in array) {
                if (jobs.size >= MAX_JOBS_PER_EMAIL) break
                val title = node.path("title").asText("").trim()
                val company = node.path("company").asText("").trim()
                val location = node.path("location").asText("").trim()
                val url = node.path("url").asText("").trim()
                if (title.isBlank()) continue
                jobs.add(createParsedDigestJob(parent, company, title, location, "", url))
            }
            jobs
        }.getOrElse { emptyList() }
    }

    private fun extractJsonArray(raw: String): String? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }
}
