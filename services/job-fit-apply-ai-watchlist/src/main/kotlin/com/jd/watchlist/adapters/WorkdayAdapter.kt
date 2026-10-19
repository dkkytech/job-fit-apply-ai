package com.jd.watchlist.adapters

import com.fasterxml.jackson.databind.JsonNode
import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting

/**
 * Workday candidate-experience ("cxs") API. Large tenants list thousands of jobs, and keyword
 * search is fuzzy, so: (1) read the tenant's location facets and select the Seattle-area ones,
 * (2) search each of [searchTerms] within those locations, (3) fetch the description per posting
 * only after the pre-filter (see [describe]). Title matching happens client-side afterwards.
 */
class WorkdayAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val maxPagesPerTerm: Int = 5,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val api = apiBase(employer)
        val facets = areaFacets(json.readTree(http.postJson("$api/jobs", body(0, 1, "", emptyMap()))))
        val seen = LinkedHashMap<String, Posting>()
        for (term in searchTerms) {
            for (page in 0 until maxPagesPerTerm) {
                val root = json.readTree(http.postJson("$api/jobs", body(page * PAGE, PAGE, term, facets)))
                val rows = root.path("jobPostings")
                for (j in rows) {
                    val path = j.path("externalPath").asText("")
                    if (path.isBlank() || path in seen) continue
                    seen[path] = Posting(
                        company = employer.company,
                        externalId = j.path("bulletFields").firstOrNull()?.asText()?.ifBlank { null } ?: path.substringAfterLast("_"),
                        title = j.path("title").asText(""),
                        location = locationOf(j.path("locationsText").asText(""), areaFiltered = facets.isNotEmpty()),
                        url = publicUrl(employer, path),
                        detailRef = path,
                    )
                }
                if (rows.size() < PAGE) break
            }
        }
        return seen.values.toList()
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val path = posting.detailRef ?: return posting.description
        val info = json.readTree(http.get(apiBase(employer) + path)).path("jobPostingInfo")
        return htmlToText(info.path("jobDescription").asText(null))
    }

    private fun apiBase(e: Employer): String {
        requireNotNull(e.host) { "workday employer ${e.company} has no host" }
        requireNotNull(e.tenant) { "workday employer ${e.company} has no tenant" }
        requireNotNull(e.site) { "workday employer ${e.company} has no site" }
        return "https://${e.host}/wday/cxs/${e.tenant}/${e.site}"
    }

    private fun body(offset: Int, limit: Int, term: String, facets: Map<String, List<String>>): String =
        json.writeValueAsString(mapOf("limit" to limit, "offset" to offset, "searchText" to term, "appliedFacets" to facets))

    companion object {
        private const val PAGE = 20
        private val AREA = Regex(
            """seattle|bellevue|redmond|kirkland|bothell|issaquah|everett|renton|\bkent\b|tacoma|tukwila|lynnwood|""" +
                """mukilteo|woodinville|sammamish|auburn|federal way|seatac|mountlake|snoqualmie|\bWA\b|washington""",
            RegexOption.IGNORE_CASE,
        )
        private val DC = Regex("""\bD\.?C\b|district of columbia""", RegexOption.IGNORE_CASE)
        private val MULTI = Regex("""^\d+\s+locations?$""", RegexOption.IGNORE_CASE)

        /**
         * Workday collapses multi-location postings to "3 Locations". When the search was already
         * restricted to Seattle-area facets, that still means "in the area"; otherwise it's unknown
         * (blank), which the pre-filter passes and the Processor's location gate decides.
         */
        /** Candidate-facing link. Shared myworkdaysite.com hosts nest the tenant under /recruiting. */
        internal fun publicUrl(e: Employer, path: String): String =
            if (e.host.orEmpty().endsWith("myworkdaysite.com")) "https://${e.host}/recruiting/${e.tenant}/${e.site}$path"
            else "https://${e.host}/${e.site}$path"

        internal fun locationOf(text: String, areaFiltered: Boolean): String = when {
            !MULTI.matches(text.trim()) -> text
            areaFiltered -> "Washington ($text)"
            else -> ""
        }

        /**
         * Seattle-area location facet ids, grouped by facet parameter. Workday nests location
         * facets (e.g. locationMainGroup → locations); country-level facets are skipped.
         */
        internal fun areaFacets(root: JsonNode): Map<String, List<String>> {
            val out = LinkedHashMap<String, MutableList<String>>()
            fun walk(values: JsonNode, param: String?) {
                for (v in values) {
                    val p = v.path("facetParameter").asText(null) ?: param
                    if (v.path("values").size() > 0) { walk(v.path("values"), p); continue }
                    val id = v.path("id").asText(null) ?: continue
                    val label = v.path("descriptor").asText("")
                    if (p != null && p.contains("location", true) && !p.contains("country", true) &&
                        AREA.containsMatchIn(label) && !DC.containsMatchIn(label)
                    ) out.getOrPut(p) { mutableListOf() }.add(id)
                }
            }
            for (f in root.path("facets")) walk(f.path("values"), f.path("facetParameter").asText(null))
            return out
        }
    }
}
