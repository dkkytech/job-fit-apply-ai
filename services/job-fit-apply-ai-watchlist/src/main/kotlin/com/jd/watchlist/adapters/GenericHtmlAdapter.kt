package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Server-rendered career sites with no feed (staffing firms, custom sites), configured per employer
 * instead of coded per site: [Employer.feed] is the list page (`{term}` / `{page}` filled from the
 * search terms and 1..[Employer.pages]), [Employer.linkPattern] says which links are postings, and
 * [Employer.cardSelector] which surrounding element carries a posting's location. The posting
 * page's schema.org JobPosting is used when it has one, else [Employer.detailSelector];
 * [Employer.locationLabel] reads the location from a page whose list shows none.
 */
class GenericHtmlAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val feed = requireNotNull(employer.feed) { "html employer ${employer.company} has no feed" }
        val pattern = Regex(requireNotNull(employer.linkPattern) { "html employer ${employer.company} has no link_pattern" })
        val terms = if ("{term}" in feed) (employer.search ?: searchTerms) else listOf("")
        val pages = if ("{page}" in feed) (1..(employer.pages ?: 1)) else (1..1)
        val found = LinkedHashMap<String, Posting>()
        var first = true
        for (term in terms) for (page in pages) {
            if (!first) pause()
            first = false
            val url = feed.replace("{term}", java.net.URLEncoder.encode(term, Charsets.UTF_8)).replace("{page}", page.toString())
            val doc = Jsoup.parse(http.get(url), url)
            val before = found.size
            for (a in doc.select("a[href]")) {
                val href = a.absUrl("href").substringBefore('#')
                val m = pattern.find(href) ?: continue
                val id = m.groupValues.getOrNull(1)?.ifBlank { null } ?: m.value
                val card = employer.cardSelector?.let { a.closest(it) }
                val title = (employer.titleSelector?.let { sel -> (card ?: a).selectFirst(sel)?.text() } ?: a.text()).trim()
                if (title.isBlank()) continue
                val location = card?.text()?.replace(title, " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val seen = found[id]
                if (seen == null || (seen.location.isBlank() && location.isNotBlank())) {
                    found[id] = Posting(company = employer.company, externalId = id, title = title, location = location, url = href)
                }
            }
            if (found.size == before) break   // an empty or repeated page: past the last one
        }
        return found.values.toList()
    }

    override fun describe(employer: Employer, posting: Posting): String? = detail(employer, posting).text

    override fun detail(employer: Employer, posting: Posting): PostingDetail {
        val doc = Jsoup.parse(http.get(posting.url), posting.url)
        val ld = jobPosting(doc)
        val text = ld?.path("description")?.asText(null)?.let { htmlToText(it, escaped = true) }
            ?: employer.detailSelector?.let { sel -> doc.selectFirst(sel)?.let { htmlToText(it.html()) } }
        // The street address is a last resort: Experis fills it with its recruiting office (Nashville
        // for a Seattle job) and puts the real place in a "Location:" label when there is one.
        val location = ld?.let { ldLocation(it, streetFallback = false) }
            ?: labelledLocation(doc, employer.locationLabel)
            ?: ld?.let { ldLocation(it, streetFallback = true) }
        return PostingDetail(text, location)
    }

    /** The page's schema.org JobPosting, if it publishes one. */
    private fun jobPosting(doc: Document) = doc.select("script[type=application/ld+json]").asSequence()
        .mapNotNull { runCatching { json.readTree(it.data()) }.getOrNull() }
        .flatMap { if (it.isArray) it.asSequence() else sequenceOf(it) }
        .firstOrNull { it.path("@type").asText() == "JobPosting" }

    /**
     * "City, Region, Country" per place. Unfilled template values ("[addressLocality]") are dropped;
     * with [streetFallback], a street address stands in when city and region both are (Experis's
     * language-specialist roles carry their place, "Redmond, WA", only there). A place that is only
     * a country says nothing about the city, so it counts as unknown.
     */
    private fun ldLocation(ld: com.fasterxml.jackson.databind.JsonNode, streetFallback: Boolean): String? {
        val places = ld.path("jobLocation").let { if (it.isArray) it.toList() else listOf(it) }
        val names = places.mapNotNull { p ->
            val a = p.path("address")
            val (city, region, street) = listOf("addressLocality", "addressRegion", "streetAddress")
                .map { a.path(it).asText("").trim().takeUnless { v -> PLACEHOLDER.matches(v) }.orEmpty() }
            val place = listOf(city, region).filter { it.isNotBlank() }.joinToString(", ").ifBlank { if (streetFallback) street else "" }
            if (place.isBlank()) return@mapNotNull null
            listOf(place, a.path("addressCountry").asText("")).filter { it.isNotBlank() && !PLACEHOLDER.matches(it) }.joinToString(", ")
        } + listOfNotNull("Remote".takeIf { ld.path("jobLocationType").asText("").contains("TELECOMMUTE", true) })
        return names.joinToString(" | ").ifBlank { null }
    }

    private companion object {
        val PLACEHOLDER = Regex("""^\s*\[.*]\s*$""")
    }

    /**
     * "Location:" then the value: in the label's own element ("Location: Seattle"), after it in the
     * parent ("<p><strong>Location:</strong> Seattle</p>"), or in the next element.
     */
    private fun labelledLocation(doc: Document, label: String?): String? {
        if (label.isNullOrBlank()) return null
        val el = doc.body()?.getElementsContainingOwnText(label)?.firstOrNull() ?: return null
        return listOfNotNull(
            el.ownText().substringAfter(label, ""),
            el.parent()?.text()?.substringAfter(label, ""),
            el.nextElementSibling()?.text(),
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }
    }
}
