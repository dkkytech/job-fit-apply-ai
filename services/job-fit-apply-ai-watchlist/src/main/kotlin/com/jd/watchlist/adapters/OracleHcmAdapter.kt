package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import java.net.URLEncoder

/**
 * Oracle Recruiting Cloud ("candidate experience") REST API. Its location facets only list a
 * tenant's top ten locations, so a Seattle-area site is usually missing there; but keyword search
 * matches location text. So we search each of the employer's area city names ([Employer.search],
 * e.g. ["Redmond"]) and leave title matching to the pre-filter. Description = detail fetch.
 */
class OracleHcmAdapter(
    private val http: Http,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 8,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val (host, site) = hostSite(employer)
        val terms = employer.search?.takeIf { it.isNotEmpty() } ?: DEFAULT_AREA_TERMS
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in terms) {
            for (page in 0 until maxPagesPerTerm) {
                if (calls++ > 0) pause()
                // Finder syntax (`;` `,` `=`) stays literal; only the quoted keyword value is encoded.
                val finder = "findReqs;siteNumber=$site,limit=$PAGE,offset=${page * PAGE},keyword=%22${enc(term)}%22,sortBy=POSTING_DATES_DESC"
                val url = "https://$host/hcmRestApi/resources/latest/recruitingCEJobRequisitions" +
                    "?onlyData=true&expand=requisitionList.secondaryLocations&finder=$finder"
                val reqs = json.readTree(http.get(url)).path("items").firstOrNull()?.path("requisitionList") ?: break
                for (r in reqs) {
                    val id = r.path("Id").asText("")
                    if (id.isBlank() || id in seen) continue
                    val locations = listOf(r.path("PrimaryLocation").asText("")) +
                        r.path("secondaryLocations").map { it.path("Name").asText("") } +
                        listOfNotNull(r.path("WorkplaceType").asText(null)?.takeIf { it.contains("remote", true) })
                    seen[id] = Posting(
                        company = employer.company,
                        externalId = id,
                        title = r.path("Title").asText(""),
                        location = locations.filter { it.isNotBlank() }.distinct().joinToString(" | "),
                        url = "https://$host/hcmUI/CandidateExperience/en/sites/$site/job/$id",
                        detailRef = id,
                    )
                }
                if (reqs.size() < PAGE) break
            }
        }
        return seen.values.toList()
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val (host, site) = hostSite(employer)
        val id = posting.detailRef ?: return posting.description
        val item = json.readTree(
            http.get("https://$host/hcmRestApi/resources/latest/recruitingCEJobRequisitionDetails?expand=all&onlyData=true&finder=ById;Id=%22${enc(id)}%22,siteNumber=$site")
        ).path("items").firstOrNull() ?: return null
        val parts = listOf("ExternalDescriptionStr", "ExternalResponsibilitiesStr", "ExternalQualificationsStr")
            .mapNotNull { htmlToText(item.path(it).asText(null)) }
        return parts.joinToString("\n\n").ifBlank { null }
    }

    private fun hostSite(e: Employer): Pair<String, String> =
        requireNotNull(e.host) { "oracle_hcm employer ${e.company} has no host" } to
            requireNotNull(e.site) { "oracle_hcm employer ${e.company} has no site" }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        private const val PAGE = 25
        val DEFAULT_AREA_TERMS = listOf("Seattle", "Bellevue", "Redmond", "Kirkland", "Bothell", "Everett", "Issaquah", "Renton", "Tacoma")
    }
}
