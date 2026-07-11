package com.jd.pipeline.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.nodes.scan.digest.BoardRegistry
import com.jd.pipeline.nodes.scan.digest.GlassdoorDigestStrategy
import com.jd.pipeline.nodes.scan.digest.MAX_JOBS_PER_EMAIL
import com.jd.pipeline.nodes.scan.digest.applyDigestSummary
import com.jd.pipeline.nodes.scan.digest.cleanUrl
import com.jd.pipeline.nodes.scan.digest.isEligibleJobUrl
import com.jd.pipeline.nodes.scan.digest.withEmailFlags
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import org.jsoup.Jsoup
import java.net.URI
import java.nio.file.Files
import java.util.regex.Pattern

/**
 * Node: scan_email
 *
 * Stage 1: Classify an incoming email and extract job description data.
 * LLM calls go through LlmClient (temperature=0, structured JSON output).
 * All JSON parsing uses Jackson — no regex field extraction.
 */
class ScanEmailNode(
    private val llm: LlmCaller = LlmClient.fromModelString(Config.SCAN_MODEL, jsonMode = true, temperature = 0.0, nodeKey = "scan_email"),
    private val mapper: ObjectMapper = ObjectMapper()
) : Node<JDState> {

    private val scrapeJdNode = ScrapeJdNode()

    companion object {
        val MAX_JOBS_PER_EMAIL = com.jd.pipeline.nodes.scan.digest.MAX_JOBS_PER_EMAIL
        val EMAIL_BODY_MAX_CHARS = 4_000

        val JOB_SIGNAL_PATTERN: Pattern = Pattern.compile(
            "\\b(engineer|developer|dev|position|role|apply|compensation|salary|\\btc\\b|" +
            "remote|onsite|on-site|hybrid|qualifications|responsibilities|requirements|" +
            "qa|qe|sdet|swe|test|manager|lead|senior|staff|principal)\\b",
            Pattern.CASE_INSENSITIVE
        )

        val DEFAULT_SKILL_PROMPT = """
            |You are a job description parser. Extract structured job information from recruiter emails.
            |Return a JSON object with these fields:
            |- is_job_posting: boolean
            |- job_url: string or null
            |- jd_text: string
            |- company: string
            |- role_title: string
            |- location: string
            |- remote_policy: "remote" | "hybrid" | "onsite" | "unknown"
            |- yoe_required: integer or null
            |- tech_stack: array of strings
            |If not a job posting, set is_job_posting to false.
        """.trimMargin()
    }

    override fun process(input: JDState): JDState {
        val email = input.emailIntake ?: return input
        val emailFrom = email.from.lowercase()
        val sender = extractEmailAddress(emailFrom)
        val senderDomain = extractDomain(sender)

        if (input.isJobPosting) return input

        if (isJobBoardDomain(senderDomain)) {
            println("[scan_email] Job board email from domain: $senderDomain")
            return processJobBoardEmail(input, senderDomain, email.rawBody)
        }

        println("[scan_email] Direct email from: $sender — running LLM extraction")
        return processRecruiterEmail(input, email.subject, email.rawBody, email.htmlBody)
    }

    private fun processJobBoardEmail(input: JDState, senderDomain: String, emailBody: String): JDState {
        val group = BoardRegistry.groupFor(senderDomain)
        val parsedDigestJobs = parseBoardDigestJobs(input, group?.key)
        if (parsedDigestJobs.isNotEmpty()) {
            val groupLabel = group?.key?.replace('_', ' ') ?: "job board"
            val base = input.withEmailFlags(isDigest = true, isInlineDigest = false).copy(
                isJobPosting = true,
                digestJobs = parsedDigestJobs,
                skippedReason = "Job board digest — ${parsedDigestJobs.size} $groupLabel job(s) extracted from email"
            )
            return applyDigestSummary(base, parsedDigestJobs)
        }

        val jobUrls = extractJobUrls(emailBody, senderDomain)
        println("[scan_email] ${jobUrls.size} job URL(s) found in $senderDomain email")
        val digestJobs = jobUrls.map { url ->
            input.withEmailFlags(isDigest = true).copy(jobUrl = url, isJobPosting = true)
        }

        return if (digestJobs.isNotEmpty()) {
            val base = input.withEmailFlags(isDigest = true, isInlineDigest = false).copy(digestJobs = digestJobs, isJobPosting = true)
            val withSummary = applyDigestSummary(base, digestJobs)
            withSummary.copy(skippedReason = "Job board digest — ${digestJobs.size} job(s) extracted")
        } else {
            input.withEmailFlags(isDigest = true, isInlineDigest = false).copy(digestJobs = emptyList(),
                isJobPosting = false, skippedReason = "Job board email — no job URLs found")
        }
    }

    private fun parseBoardDigestJobs(input: JDState, boardKey: String?): List<JDState> {
        val email = input.emailIntake ?: return emptyList()
        val strategy = BoardRegistry.STRATEGIES[boardKey] ?: return emptyList()
        return strategy.expand(input, email)
    }

    private fun processRecruiterEmail(input: JDState, subject: String, emailBody: String, emailHtml: String): JDState {
        val combined = "$subject $emailBody"
        if (!JOB_SIGNAL_PATTERN.matcher(combined).find()) {
            println("[scan_email] No job-signal keywords — skipping LLM")
            return input.copy(isJobPosting = false, skippedReason = "Not a job posting")
        }

        val skillPrompt = loadSkillPrompt()
        val cleanedBody = preprocessEmailBody(emailBody)
        val hiddenContent = extractHiddenEmailContent(emailBody, emailHtml)
        val prompt = buildString {
            append(skillPrompt)
            append("\n\nSUBJECT: ").append(subject)
            append("\n\nVISIBLE_BODY:\n").append(cleanedBody)
            if (hiddenContent.isNotBlank()) append("\n\nHIDDEN_OR_NONVISIBLE_EMAIL_CONTENT:\n").append(hiddenContent)
        }

        return try {
            parseLlmResponse(input, llm.call(prompt))
        } catch (e: Exception) {
            println("[scan_email] LLM error: ${e.message}")
            input.copy(isJobPosting = false, error = "scan_email: ${e.message}")
        }
    }

    private fun extractJobUrls(emailBody: String, senderDomain: String): List<String> {
        val group = BoardRegistry.groupFor(senderDomain)
        if (group?.key == "glassdoor") return GlassdoorDigestStrategy.extractGlassdoorJobUrls(emailBody)

        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()

        val hrefMatcher = Pattern.compile("href=[\"']([^\"'\\s]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(emailBody)
        while (hrefMatcher.find() && seen.size < MAX_JOBS_PER_EMAIL) {
            val url = cleanUrl(hrefMatcher.group(1))
            if (isEligibleJobUrl(url, senderDomain) && url !in seen) { seen.add(url); result.add(url) }
        }

        val textMatcher = Pattern.compile("https?://[^\\s<>\"'\\]\\)]+").matcher(emailBody)
        while (textMatcher.find() && seen.size < MAX_JOBS_PER_EMAIL) {
            val url = cleanUrl(textMatcher.group())
            if (isEligibleJobUrl(url, senderDomain) && url !in seen) { seen.add(url); result.add(url) }
        }

        return result
    }

    private fun preprocessEmailBody(body: String?): String {
        if (body == null) return ""
        var text = body.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("(\\r?\\n){3,}"), "\n\n")
            .trim()
        if (text.length > EMAIL_BODY_MAX_CHARS) text = text.substring(0, EMAIL_BODY_MAX_CHARS)
        return text
    }

    private fun extractHiddenEmailContent(visibleBody: String, emailHtml: String): String {
        if (emailHtml.isBlank()) return ""
        val document = Jsoup.parse(emailHtml)
        document.select("script,style,noscript").remove()
        val htmlText = document.text().replace(Regex("\\s+"), " ").trim()
        if (htmlText.isBlank()) return ""

        val visibleNormalized = preprocessEmailBody(visibleBody).replace(Regex("\\s+"), " ").trim()
        val hiddenCandidates = buildList {
            extractJsonScriptBlocks(emailHtml).forEach { add(it) }
            if (htmlText.length > visibleNormalized.length + 50 || !htmlText.contains(visibleNormalized)) add(htmlText)
        }
        return hiddenCandidates.map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }.distinct().joinToString("\n\n").take(EMAIL_BODY_MAX_CHARS)
    }

    private fun extractJsonScriptBlocks(emailHtml: String): List<String> {
        val matcher = Pattern.compile(
            "<script[^>]*type=[\"']application/(?:ld\\+json|json)[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        ).matcher(emailHtml)
        val results = mutableListOf<String>()
        while (matcher.find()) {
            val content = matcher.group(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (content.isNotBlank()) results.add(content)
        }
        return results
    }

    private fun loadSkillPrompt(): String = try {
        if (Files.exists(Config.SCAN_SKILL)) Files.readString(Config.SCAN_SKILL) else DEFAULT_SKILL_PROMPT
    } catch (e: Exception) {
        DEFAULT_SKILL_PROMPT
    }

    private fun parseLlmResponse(input: JDState, responseText: String): JDState {
        val cleaned = responseText.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }
        return try {
            val node = mapper.readTree(cleaned)
            val isJobPosting = node.path("is_job_posting").asBoolean(false)
            val jobUrl = node.path("job_url").asText("").let { if (it == "null") "" else it }
            input.withEmailFlags(isRecruiter = isJobPosting).copy(
                isJobPosting = isJobPosting,
                jobUrl = jobUrl.ifBlank { input.jobUrl },
                jdText = node.path("jd_text").asText(""),
                company = node.path("company").asText("Unknown"),
                roleTitle = node.path("role_title").asText("Unknown"),
                location = node.path("location").asText("Unknown"),
                remotePolicy = node.path("remote_policy").asText("unknown"),
                yoeRequired = node.path("yoe_required").takeIf { !it.isNull && !it.isMissingNode }?.asInt(),
                techStack = node.path("tech_stack").map { it.asText() },
                skippedReason = if (!isJobPosting) "Not a job posting" else ""
            )
        } catch (e: Exception) {
            input.copy(isJobPosting = false, error = "scan_email: JSON parse failed — ${cleaned.take(200)}")
        }
    }

    private fun extractEmailAddress(from: String): String {
        val m = Pattern.compile("<([^>]+)>").matcher(from)
        return if (m.find()) m.group(1) else from
    }

    private fun extractDomain(email: String): String {
        val at = email.lastIndexOf('@')
        return if (at >= 0) email.substring(at + 1) else ""
    }

    private fun isJobBoardDomain(domain: String?): Boolean {
        if (domain.isNullOrEmpty()) return false
        return BoardRegistry.groupFor(domain) != null
    }
}
