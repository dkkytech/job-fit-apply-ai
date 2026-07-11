package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup
import java.util.regex.Pattern

object JobrightDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val emailBody = email.rawBody
        val emailHtml = email.htmlBody
        val htmlJobs = parseJobRightDigestJobsFromHtml(parent, emailHtml)
        if (htmlJobs.isNotEmpty()) return htmlJobs
        return parseJobRightDigestJobsFromPlainText(parent, emailBody)
    }

    private fun parseJobRightDigestJobsFromHtml(input: JDState, emailHtml: String): List<JDState> {
        if (emailHtml.isBlank()) return emptyList()
        val doc = Jsoup.parse(emailHtml)
        val anchors = doc.select("a[href*=jobright.ai]")
        val jobs = mutableListOf<JDState>()

        for (anchor in anchors) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val href = cleanUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
            if (href.isBlank() || !href.contains("jobright.ai")) continue
            val fullText = anchor.text().replace(Regex("\\s+"), " ").trim()
            if (fullText.isBlank()) continue
            val (company, roleTitle) = parseJobRightTitleLine(fullText)
            if (company.isBlank() || roleTitle.isBlank()) continue
            val parentText = anchor.parent()?.text()?.replace(Regex("\\s+"), " ")?.trim() ?: ""
            val salary = extractJobRightSalary(fullText + " " + parentText)
            val location = extractJobRightLocation(fullText + " " + parentText)
            jobs.add(createParsedDigestJob(input, company, roleTitle, location, salary, href))
        }
        return jobs
    }

    private fun parseJobRightDigestJobsFromPlainText(input: JDState, emailBody: String): List<JDState> {
        if (emailBody.isBlank()) return emptyList()
        val jobs = mutableListOf<JDState>()
        val urlPattern = Pattern.compile("https://jobright\\.ai/jobs/info/[^\\s&]+")
        val urlMatcher = urlPattern.matcher(emailBody)
        val urls = mutableListOf<String>()
        while (urlMatcher.find()) {
            val url = cleanUrl(urlMatcher.group())
            if (url.isNotBlank() && url !in urls) urls.add(url)
        }
        if (urls.isEmpty()) return emptyList()

        for ((index, url) in urls.withIndex()) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val urlStartIdx = emailBody.indexOf(url)
            val prevUrlEnd = if (index == 0) 0 else emailBody.indexOf(urls[index - 1]) + urls[index - 1].length
            val blockText = if (urlStartIdx > prevUrlEnd) emailBody.substring(prevUrlEnd, urlStartIdx) else ""
            val percentMatch = Regex("""(\d{1,3}(?:\.\d+)?%)""").find(blockText) ?: continue
            val titleSectionStart = percentMatch.range.first
            val titleSection = blockText.substring(titleSectionStart)
            val (company, roleTitle) = parseJobRightTitleLine(titleSection)
            if (company.isBlank() || roleTitle.isBlank()) continue
            val salaryLocationSection = blockText.substring(0, titleSectionStart)
            val salary = if (salaryLocationSection.contains("no salary", ignoreCase = true)) "" else extractJobRightSalary(salaryLocationSection)
            val location = extractJobRightLocation(salaryLocationSection + " " + titleSection)
            jobs.add(createParsedDigestJob(input, company, roleTitle, location, salary, url))
        }
        return jobs
    }

    private fun parseJobRightTitleLine(line: String): Pair<String, String> {
        val dotSeparator = " · "
        val dotIdx = line.indexOf(dotSeparator)
        if (dotIdx > 0) {
            val beforeDot = line.substring(0, dotIdx).trim()
            val afterDot = line.substring(dotIdx + dotSeparator.length).trim()
            val company = extractJobRightCompany(beforeDot)
            val percentMatch = Regex("""(\d{1,3}(?:\.\d+)?%)""").find(afterDot)
            if (percentMatch != null) {
                val roleTitle = cleanJobRightRoleTitle(afterDot.substring(percentMatch.range.last + 1).trim())
                return company to roleTitle
            }
        }
        val match = Regex("""(\d{1,3}(?:\.\d+)?%)""").find(line)
        if (match != null) {
            val company = extractJobRightCompany(line.substring(0, match.range.first).trim())
            val roleTitle = cleanJobRightRoleTitle(line.substring(match.range.last + 1).trim())
            return company to roleTitle
        }
        for (sep in listOf(" - ", " @ ", " : ", " | ")) {
            val idx = line.indexOf(sep)
            if (idx > 0) return line.substring(0, idx).trim() to line.substring(idx + sep.length).trim()
        }
        return "" to line
    }

    private fun extractJobRightCompany(text: String): String {
        val words = text.split(Regex("\\s+"))
        if (words.size >= 2) {
            val industrySuffixes = listOf("Biotechnology","Technology","Biotech","Tech","Healthcare","Finance","Media","Consulting","Solutions","Services","Inc","Corp","Company","Group","Systems","AI","Software","Electronics","Enterprise","Information","Artificial Intelligence","Technologies")
            for (i in words.size - 1 downTo 1) {
                val potentialSuffix = words.drop(i).joinToString(" ")
                val match = industrySuffixes.firstOrNull { potentialSuffix.equals(it, ignoreCase = true) || potentialSuffix.startsWith(it, ignoreCase = true) }
                if (match != null) return words.take(i).joinToString(" ")
            }
        }
        return text
    }

    private fun cleanJobRightRoleTitle(roleTitle: String): String {
        val noisePatterns = listOf(
            Regex("""\d+\+\s*referrals?""", RegexOption.IGNORE_CASE),
            Regex("""\d+\s*(minutes?|hours?|days?)\s*ago""", RegexOption.IGNORE_CASE),
            Regex("""Be an early applicant""", RegexOption.IGNORE_CASE),
            Regex("""APPLY\s*NOW""", RegexOption.IGNORE_CASE)
        )
        var result = roleTitle
        for (pattern in noisePatterns) result = pattern.replace(result, "")
        val salaryStop = Regex("""\$[\d,K]+(?:\s*-\s*\$[\d,K]+)?""")
        val salaryMatch = salaryStop.find(result)
        if (salaryMatch != null) result = result.substring(0, salaryMatch.range.first).trim()
        return result.trim()
    }

    private fun extractJobRightSalary(text: String): String {
        val patterns = listOf(
            Regex("""\$[\d,]+(?:\.\d+)?K?\s*-\s*\$[\d,]+(?:\.\d+)?K?\s*/\s*(?:yr|year)""", RegexOption.IGNORE_CASE),
            Regex("""\$[\d,]+(?:\.\d+)?K?\s*/\s*(?:yr|year)""", RegexOption.IGNORE_CASE),
            Regex("""USD\s*\$[\d,]+(?:\.\d+)?K?(?:\s*-\s*\$[\d,]+(?:\.\d+)?K?)""", RegexOption.IGNORE_CASE),
            Regex("""[\d,]+(?:\.\d+)?K\s*-\s*[\d,]+(?:\.\d+)?K""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val salary = match.value.trim()
                if (salary.contains("no salary", ignoreCase = true)) return ""
                return salary
            }
        }
        return ""
    }

    private fun extractJobRightLocation(text: String): String {
        val patterns = listOf(
            Regex("""Remote(?:\s+\([^)]+\))?(?:\s+US)?(?:\s+within\s+the\s+US)?""", RegexOption.IGNORE_CASE),
            Regex("""[A-Z][A-Za-z\s.'-]+,\s*[A-Z]{2}(?:\s*\([^)]+\))?""", RegexOption.IGNORE_CASE),
            Regex("""\bRemote\b""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.value.trim()
        }
        return ""
    }
}
