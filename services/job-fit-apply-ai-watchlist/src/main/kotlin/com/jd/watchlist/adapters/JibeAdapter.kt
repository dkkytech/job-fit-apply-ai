package com.jd.watchlist.adapters

import com.fasterxml.jackson.databind.JsonNode
import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import java.net.URLEncoder

/**
 * Jibe (Google Cloud Talent) career sites in front of iCIMS — AMD, DocuSign, Avalara, REI,
 * Spacelabs (OSI Systems), Publicis. [Employer.feed] is the site's `/api/jobs` URL with its
 * location filter (e.g. `?location=Seattle, WA&stretch=30`); each search term is added as
 * `keywords=` and paged. Descriptions come inline with the listing.
 */
class JibeAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 5,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val feed = requireNotNull(employer.feed) { "jibe employer ${employer.company} has no feed" }
        val sep = if ('?' in feed) "&" else "?"
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            for (page in 1..maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val root = json.readTree(http.get("$feed${sep}keywords=${enc(term)}&limit=$PAGE&page=$page"))
                val jobs = root.path("jobs")
                for (j in jobs) {
                    val d = j.path("data")
                    val id = d.path("req_id").asText("").ifBlank { d.path("slug").asText("") }
                    if (id.isBlank() || id in seen) continue
                    seen[id] = posting(employer, id, d)
                }
                if (jobs.size() < PAGE) break
            }
        }
        return seen.values.toList()
    }

    private fun posting(e: Employer, id: String, d: JsonNode): Posting {
        val location = listOf(d.path("full_location").asText(""), d.path("location_name").asText(""), d.path("short_location").asText("")) +
            listOfNotNull("Remote".takeIf { d.path("location_type").asText("").contains("remote", true) })
        val description = listOf("description", "responsibilities", "qualifications")
            .mapNotNull { htmlToText(d.path(it).asText(null)) }.joinToString("\n\n").ifBlank { null }
        return Posting(
            company = e.company,
            externalId = id,
            title = d.path("title").asText(""),
            location = location.filter { it.isNotBlank() }.distinct().joinToString(" | "),
            url = d.path("meta_data").path("canonical_url").asText("").ifBlank { d.path("apply_url").asText("") },
            description = description,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        private const val PAGE = 100
    }
}
