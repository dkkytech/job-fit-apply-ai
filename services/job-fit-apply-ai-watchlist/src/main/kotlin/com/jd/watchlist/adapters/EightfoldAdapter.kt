package com.jd.watchlist.adapters

import com.fasterxml.jackson.databind.JsonNode
import com.jd.watchlist.http.Http
import com.jd.watchlist.http.HttpStatusException
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import java.net.URLEncoder

/**
 * Eightfold talent sites (Microsoft, Starbucks, Qualcomm, Fortive, Symetra, NetApp, Liberty Mutual).
 * Two API flavours: the newer `pcsx` search (`api: pcsx`) and the older `apply/v2` (`api: v2`).
 * Both are searched per term within [Employer.location] (relevance-sorted, fuzzy), and both
 * need a per-posting detail call for the description.
 *
 * Eightfold rate-limits per IP over a window longer than [com.jd.watchlist.http.JdkHttp]'s 429
 * backoff, so the sweep paces it more slowly ([pause]) and, when a later search stays limited,
 * keeps the postings the earlier searches already returned instead of failing the employer.
 */
class EightfoldAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 3,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val host = requireNotNull(employer.host) { "eightfold employer ${employer.company} has no host" }
        val domain = requireNotNull(employer.domain) { "eightfold employer ${employer.company} has no domain" }
        val v2 = employer.api.equals("v2", ignoreCase = true)
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            var start = 0
            for (page in 0 until maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val q = "domain=${enc(domain)}&query=${enc(term)}&start=$start" +
                    (employer.location?.let { "&location=${enc(it)}" } ?: "")
                val positions: JsonNode = try {
                    if (v2) {
                        json.readTree(http.get("https://$host/api/apply/v2/jobs?$q&num=$V2_PAGE")).path("positions")
                    } else {
                        json.readTree(http.get("https://$host/api/pcsx/search?$q")).path("data").path("positions")
                    }
                } catch (e: HttpStatusException) {
                    // Still rate-limited after the HTTP layer's backoff: keep what earlier searches found.
                    if (e.status != 429 || seen.isEmpty()) throw e
                    System.err.println("[watchlist] ${employer.company}: rate-limited at '$term' — keeping ${seen.size} postings found so far")
                    return seen.values.toList()
                }
                for (p in positions) {
                    val id = p.path("id").asText("")
                    if (id.isBlank() || id in seen) continue
                    seen[id] = if (v2) fromV2(employer, host, p) else fromPcsx(employer, host, p)
                }
                if (positions.size() == 0) break
                start += positions.size()
            }
        }
        return seen.values.toList()
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val host = requireNotNull(employer.host)
        val domain = enc(requireNotNull(employer.domain))
        return if (employer.api.equals("v2", ignoreCase = true)) {
            htmlToText(json.readTree(http.get("https://$host/api/apply/v2/jobs/${posting.externalId}?domain=$domain")).path("job_description").asText(null))
        } else {
            val d = json.readTree(http.get("https://$host/api/pcsx/position_details?position_id=${posting.externalId}&domain=$domain&hl=en")).path("data")
            htmlToText(d.path("jobDescription").asText(null))
        }
    }

    private fun fromPcsx(e: Employer, host: String, p: JsonNode) = Posting(
        company = e.company,
        externalId = p.path("id").asText(),
        title = p.path("name").asText(""),
        location = (p.path("locations").map { it.asText("") } +
            listOfNotNull("Remote".takeIf { p.path("workLocationOption").asText("").contains("remote", true) }))
            .filter { it.isNotBlank() }.joinToString(" | "),
        url = p.path("positionUrl").asText("").let { if (it.startsWith("http")) it else "https://$host$it" },
    )

    private fun fromV2(e: Employer, host: String, p: JsonNode) = Posting(
        company = e.company,
        externalId = p.path("id").asText(),
        title = p.path("name").asText(""),
        location = (listOf(p.path("location").asText("")) + p.path("locations").map { it.asText("") })
            .filter { it.isNotBlank() }.distinct().joinToString(" | "),
        url = p.path("canonicalPositionUrl").asText("").ifBlank { "https://$host/careers/job/${p.path("id").asText()}" },
    )

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        private const val V2_PAGE = 50
    }
}
