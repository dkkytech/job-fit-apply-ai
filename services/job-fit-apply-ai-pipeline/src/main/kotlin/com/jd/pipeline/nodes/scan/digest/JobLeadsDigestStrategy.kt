package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup

object JobLeadsDigestStrategy : BoardDigestStrategy {
    /**
     * Anchor text JobLeads used before their ~2026-09 template change.
     * e.g. `View job: https://www.jobleads.com/job/<id>?...`
     */
    private val ANCHORED_VIEW_JOB = Regex(
        """View job:\s*(https://www\.jobleads\.com/[^\s]+)""",
    )

    /**
     * Anchor text used from ~2026-09 onward.
     * e.g. `...- full_timeView full details and applyhttps://www.jobleads.com/job/<id>?...`
     *
     * Note there is no whitespace between the anchor and the URL, so the separator is optional
     * rather than `\s+`.
     */
    private val ANCHORED_VIEW_DETAILS = Regex(
        """View full details and apply\s*(https://www\.jobleads\.com/[^\s]+)""",
    )

    /**
     * Fallback: any JobLeads *posting* URL, anchored or not.
     *
     * The `/job/` path segment is the discriminator. JobLeads newsletters are full of other
     * `jobleads.com` links — feedback thumbs, resume-upload banners, help centre, unsubscribe —
     * and those all live under `/us/jobs?email-action=...` or `/manage-email-notifications`,
     * so a `/job/` path filter excludes them without needing a denylist.
     *
     * Anchors are tried first because they encode intent; this only runs when the templates
     * have moved on again.
     */
    private val UNANCHORED_JOB_URL = Regex(
        """https://www\.jobleads\.com/job/[^\s"'<>)\]]+""",
    )

    /**
     * Strips JobLeads' plain-text section divider from the end of a URL.
     *
     * Their text part separates sections with a run of hyphens (`---`), and when a URL is the
     * last thing before the divider the two are glued together: `...&t=abc---`. The hyphens are
     * a separator, not part of the URL, and `cleanUrl` does not remove them (it strips
     * `.,;:!?` only). A doubled hyphen is also not how real slugs end, so the run is safe to
     * drop — and leaving it in would send a URL with a bogus trailing token to the scraper.
     *
     * Scoped to this strategy rather than `cleanUrl`, which ten other board strategies share.
     */
    internal fun stripSectionDivider(url: String): String = url.replace(Regex("-{2,}$"), "")

    /**
     * All job-posting URLs in preference order: anchored templates first, then the structural
     * fallback. Each entry is cleaned so trailing punctuation or the `&amp;` a plain-text body
     * carries does not end up inside the URL.
     *
     * Anchored matches are merged **in document order**, not grouped per anchor. The caller
     * zips this list against the HTML cards by index, so grouping would attach one card's URL to
     * another card as soon as a digest mixes templates.
     */
    internal fun extractJobUrls(emailBody: String): List<String> {
        val anchored = (ANCHORED_VIEW_JOB.findAll(emailBody) + ANCHORED_VIEW_DETAILS.findAll(emailBody))
            .sortedBy { it.range.first }
            .map { it.groupValues[1] }
            .toList()
        if (anchored.isNotEmpty()) {
            return anchored.map { stripSectionDivider(cleanUrl(it)) }.filter { it.isNotBlank() }
        }

        return UNANCHORED_JOB_URL.findAll(emailBody)
            .map { it.value }
            .map { stripSectionDivider(cleanUrl(it)) }
            .filter { it.isNotBlank() }
            .toList()
    }

    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val emailBody = email.rawBody
        val emailHtml = email.htmlBody
        if (emailHtml.isBlank()) return emptyList()
        val urls = extractJobUrls(emailBody)
        val document = Jsoup.parse(emailHtml)
        val cards = document.select("td[style*=padding: 16px]")
        val jobs = mutableListOf<JDState>()

        for (card in cards) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val divTexts = card.select("div").map { it.text().replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
            val title = divTexts.firstOrNull() ?: continue
            val company = divTexts.drop(1).firstOrNull() ?: continue
            val location = divTexts.drop(2).firstOrNull { looksLikeLocation(it) } ?: continue
            val salary = divTexts.firstOrNull { it.startsWith("USD ") }.orEmpty()
            val url = urls.getOrNull(jobs.size).orEmpty()
            jobs.add(createParsedDigestJob(parent, company, title, location, salary, url))
        }
        return jobs
    }
}
