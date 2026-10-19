package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting

/**
 * Phenom career sites' widget API (TEKsystems, Actalent — Allegis staffing). `refineSearch` is
 * searched per term with a state filter ([Employer.location], e.g. "Washington"); the full
 * description comes from the `jobDetail` widget.
 */
class PhenomAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 5,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val host = requireNotNull(employer.host) { "phenom employer ${employer.company} has no host" }
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            for (page in 0 until maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val body = mutableMapOf<String, Any>(
                    "lang" to "en_us", "deviceType" to "desktop", "country" to "us", "pageName" to "search-results",
                    "ddoKey" to "refineSearch", "from" to page * PAGE, "size" to PAGE, "jobs" to true, "counts" to false,
                    "keywords" to term, "global" to true, "siteType" to "external", "jdsource" to "facets",
                )
                employer.location?.let { body["selected_fields"] = mapOf("state" to listOf(it)) }
                val search = json.readTree(http.postJson("https://$host/widgets", json.writeValueAsString(body))).path("refineSearch")
                val jobs = search.path("data").path("jobs")
                for (j in jobs) {
                    val id = j.path("jobId").asText("")
                    if (id.isBlank() || id in seen) continue
                    seen[id] = Posting(
                        company = employer.company,
                        externalId = id,
                        title = j.path("title").asText(""),
                        location = j.path("location").asText("").ifBlank { j.path("cityStateCountry").asText("") },
                        url = "https://$host/us/en/job/$id",
                    )
                }
                if (jobs.size() < PAGE || (page + 1) * PAGE >= search.path("totalHits").asInt(0)) break
            }
        }
        return seen.values.toList()
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val body = mapOf(
            "lang" to "en_us", "deviceType" to "desktop", "country" to "us", "pageName" to "job",
            "ddoKey" to "jobDetail", "jobId" to posting.externalId, "siteType" to "external",
        )
        val job = json.readTree(http.postJson("https://${employer.host}/widgets", json.writeValueAsString(body)))
            .path("jobDetail").path("data").path("job")
        return htmlToText(job.path("description").asText(null))
    }

    companion object {
        private const val PAGE = 50
    }
}
