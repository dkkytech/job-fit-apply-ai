package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import org.jsoup.Jsoup

/**
 * SAP SuccessFactors career sites (Recruiting Marketing "Unify": Wipro, HCLTech). The site's own
 * search API takes keywords and a free-text location, so each search term is searched within
 * [Employer.location]; the posting page carries the description in schema.org microdata.
 */
class SuccessFactorsAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val host = requireNotNull(employer.host) { "successfactors employer ${employer.company} has no host" }
        val found = LinkedHashMap<String, Posting>()
        for (term in searchTerms.ifEmpty { listOf("") }) {
            for (page in 0 until MAX_PAGES) {
                if (page > 0 || found.isNotEmpty()) pause()
                val body = json.writeValueAsString(
                    mapOf(
                        "locale" to "en_US", "pageNumber" to page, "sortBy" to "", "keywords" to term,
                        "location" to (employer.location ?: ""), "facetFilters" to emptyMap<String, Any>(),
                        "brand" to "", "skills" to emptyList<String>(), "categoryId" to 0, "alertId" to "", "rcmCandidateId" to "",
                    ),
                )
                val results = json.readTree(http.postJson("https://$host/services/recruiting/v1/jobs", body, mapOf("Accept" to "application/json")))
                    .path("jobSearchResult")
                if (results.isEmpty) break
                for (r in results) {
                    val j = r.path("response")
                    val id = j.path("id").asText("")
                    if (id.isBlank()) continue
                    val locale = j.path("supportedLocales").firstOrNull()?.asText()?.ifBlank { null } ?: "en_US"
                    found.putIfAbsent(id, Posting(
                        company = employer.company,
                        externalId = id,
                        title = j.path("unifiedStandardTitle").asText(""),
                        location = j.path("jobLocationShort").map { it.asText("").replace(Regex("<[^>]+>"), "").trim().trimEnd(',') }
                            .filter { it.isNotBlank() }.joinToString(" | "),
                        url = "https://$host/job/${j.path("urlTitle").asText(j.path("unifiedUrlTitle").asText("job"))}/$id-$locale/",
                    ))
                }
            }
        }
        return found.values.toList()
    }

    override fun describe(employer: Employer, posting: Posting): String? =
        htmlToText(Jsoup.parse(http.get(posting.url)).selectFirst("[itemprop=description]")?.html())

    private companion object {
        /** Search pages per term; a Seattle-area search fits in one or two. */
        const val MAX_PAGES = 10
    }
}
