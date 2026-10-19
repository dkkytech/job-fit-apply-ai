package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Server-rendered job boards with no JSON feed (the "scraping" tier). Selectors are per platform,
 * not per employer, so an employer still needs only a URL in employers.yaml. List and detail pages
 * are plain HTML — no browser — and descriptions are read only for pre-filter survivors.
 */

/**
 * Classic iCIMS portals (`careers-<tenant>.icims.com/jobs/search`) — Fred Hutch, WaFd, Verathon,
 * FUJIFILM Sonosite, Highspot/Seismic. [Employer.feed] is the search URL (with any keyword the
 * site needs); `in_iframe=1` returns the bare list and `pr=N` pages it.
 */
class IcimsClassicAdapter(private val http: Http, private val pause: () -> Unit = {}, private val maxPages: Int = 10) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val feed = requireNotNull(employer.feed) { "icims employer ${employer.company} has no feed" }
        val base = withParam(feed, "in_iframe", "1")
        val seen = LinkedHashMap<String, Posting>()
        for (page in 0 until maxPages) {
            if (page > 0) pause()
            val doc = Jsoup.parse(http.get(withParam(base, "pr", page.toString())), base)
            val before = seen.size
            for (card in doc.select("li.iCIMS_JobCardItem")) {
                val a = card.selectFirst(".title a[href]") ?: continue
                val href = a.absUrl("href")
                val id = ICIMS_ID.find(href)?.groupValues?.get(1) ?: continue
                if (id in seen) continue
                seen[id] = Posting(
                    company = employer.company,
                    externalId = id,
                    title = (a.selectFirst("h3")?.text() ?: a.text()).trim(),
                    location = locationOf(card),
                    url = withoutParam(href, "in_iframe"),
                    detailRef = withParam(href, "in_iframe", "1"),
                )
            }
            if (seen.size == before) break   // an empty or repeated page: past the last one
        }
        return seen.values.toList()
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val doc = Jsoup.parse(http.get(posting.detailRef ?: posting.url))
        return doc.selectFirst(".iCIMS_JobContent")?.let { htmlToText(it.html()) }
    }

    /** Portals put the location either in the left card header or in a "Location" header tag. */
    private fun locationOf(card: org.jsoup.nodes.Element): String =
        card.select(".header.left span").lastOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: card.select(".iCIMS_JobHeaderTag").firstOrNull { it.selectFirst("dt")?.text()?.contains("Location", true) == true }
                ?.selectFirst("dd")?.text()?.trim().orEmpty()

    companion object {
        private val ICIMS_ID = Regex("""/jobs/(\d+)/""")
    }
}

/** Jobvite career sites (`jobs.jobvite.com/<company>/jobs`) — Kymeta, Ookla, Probably Monsters. */
class JobviteAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "jobvite employer ${employer.company} has no board" }
        val url = "https://jobs.jobvite.com/$board/jobs"
        val doc: Document = Jsoup.parse(http.get(url), url)
        return doc.select("table.jv-job-list tr").mapNotNull { row ->
            val a = row.selectFirst("td.jv-job-list-name a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href")
            // Only a real posting link has a stable id; anything else (layout change) is skipped, not keyed on the URL.
            val id = JOB_ID.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            Posting(
                company = employer.company,
                externalId = id,
                title = a.text().trim(),
                // "2 Locations" is unknown, not a place: blank lets the pre-filter pass it on.
                location = row.selectFirst("td.jv-job-list-location")?.text()?.trim()
                    ?.takeUnless { MULTI.matches(it) }.orEmpty(),
                url = href,
            )
        }
    }

    override fun describe(employer: Employer, posting: Posting): String? =
        Jsoup.parse(http.get(posting.url)).selectFirst(".jv-job-detail-description")?.let { htmlToText(it.html()) }

    companion object {
        private val MULTI = Regex("""^\d+\s+locations?$""", RegexOption.IGNORE_CASE)
        private val JOB_ID = Regex("""/job/([A-Za-z0-9]+)""")
    }
}

/** Set (or replace) one query parameter on [url]. */
internal fun withParam(url: String, key: String, value: String): String {
    val (path, params) = splitQuery(url, key)
    return path + "?" + (params + "$key=$value").joinToString("&")
}

/** Remove one query parameter from [url], keeping the others (and dropping a now-empty `?`). */
internal fun withoutParam(url: String, key: String): String {
    val (path, params) = splitQuery(url, key)
    return if (params.isEmpty()) path else path + "?" + params.joinToString("&")
}

/** [url] without its fragment, as (path, query params other than [key]). */
private fun splitQuery(url: String, key: String): Pair<String, List<String>> {
    val base = url.substringBefore('#')
    val params = base.substringAfter('?', "").split('&').filter { it.isNotBlank() && it.substringBefore('=') != key }
    return base.substringBefore('?') to params
}
