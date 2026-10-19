package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup

/**
 * Job-alert emails from employers and their ATSes (Microsoft Careers, iCIMS, SuccessFactors'
 * jobs2web, Workday, Greenhouse…): one child per posting the email links to.
 *
 * Children are URL-only ([JDState.submitAsUrl]). The processor hands each to the bridge's
 * `/api/urls` under its [PostingLink.key] — the watchlist's key for ATSes it polls — so a posting
 * the watchlist already found, or an earlier alert already sent, is dropped by the bridge before
 * anything is scraped or scored.
 *
 * Only an email that reads as an alert counts: these senders also send "thanks for applying" mail
 * that links the job, and that must stay an application update, not become a digest.
 */
object EmployerAlertDigestStrategy : BoardDigestStrategy {

    private val ALERT_WORDING = Regex(
        "new jobs?|job alert|jobs? (?:that )?match|match(?:es)? your|opportunit|recommended jobs|jobs for you|new openings",
        RegexOption.IGNORE_CASE,
    )

    /** Anchor text that says nothing about the job. */
    private val GENERIC_ANCHOR = Regex("^(apply( now)?|view( job| details| all.*)?|see (job|details|more)|learn more|details|more|here|click here|read more)$", RegexOption.IGNORE_CASE)

    /** "Microsoft Careers <donotreply@…>" → "Microsoft". */
    private val SENDER_NOISE = Regex("\\b(job alerts?|talent acquisition|careers?|jobs?|talent|recruiting|alerts?|notifications?|no-?reply|do-?not-?reply)\\b", RegexOption.IGNORE_CASE)

    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val opening = email.subject + " " + email.rawBody.take(600)
        if (!ALERT_WORDING.containsMatchIn(opening)) return emptyList()

        val company = companyFrom(email.from)
        val links = linkedPostings(email)
        return links.take(MAX_JOBS_PER_EMAIL).map { (link, title) ->
            parent.withEmailFlags(isDigest = true).copy(
                isJobPosting = true,
                jobUrl = link.url,
                postingKey = link.key,
                submitAsUrl = true,
                company = company,
                roleTitle = title,
            )
        }
    }

    /** Postings linked from the HTML (with their anchor text) or, failing that, the text body. */
    internal fun linkedPostings(email: IntakeContext.Email): List<Pair<PostingLink, String>> {
        val found = LinkedHashMap<String, Pair<PostingLink, String>>()
        if (email.htmlBody.isNotBlank()) {
            for (a in Jsoup.parse(email.htmlBody).select("a[href]")) {
                val link = PostingLinks.identify(a.attr("href")) ?: continue
                val text = a.text().replace(Regex("\\s+"), " ").trim()
                val title = text.takeIf { it.length in 3..120 && !GENERIC_ANCHOR.matches(it) }.orEmpty()
                val seen = found[link.key]
                if (seen == null || (seen.second.isBlank() && title.isNotBlank())) found[link.key] = link to title
            }
        }
        Regex("https?://[^\\s<>\"')\\]]+").findAll(email.rawBody).forEach { m ->
            val link = PostingLinks.identify(m.value) ?: return@forEach
            found.putIfAbsent(link.key, link to "")
        }
        return found.values.toList()
    }

    internal fun companyFrom(from: String): String {
        val display = from.substringBefore('<').trim().trim('"').trim()
        if (display.isBlank() || '@' in display) return ""
        return display.replace(SENDER_NOISE, " ").replace(Regex("\\s+"), " ").trim().trim('-', '|', ',').trim()
    }
}
