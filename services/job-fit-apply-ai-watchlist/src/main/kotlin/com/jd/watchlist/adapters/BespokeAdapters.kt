package com.jd.watchlist.adapters

import com.fasterxml.jackson.databind.JsonNode
import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import java.net.URLEncoder
import org.jsoup.Jsoup

/**
 * One-employer APIs worth an adapter because the employer is a medium/high QE fit with real
 * Seattle-area hiring. Each is searched per term and paged; titles and locations are still matched
 * by the pre-filter.
 */

/**
 * jobs.apple.com. The search page embeds its results as React Router hydration JSON
 * (`window.__staticRouterHydrationData = JSON.parse("…")`); the detail page embeds the full
 * description the same way. [Employer.location] is Apple's location slug (default `seattle-SEA`).
 */
class AppleAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 5,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val location = employer.location ?: "seattle-SEA"
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            var readForTerm = 0
            for (page in 1..maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val search = hydration(http.get("$BASE/en-us/search?location=${enc(location)}&search=${enc(term)}&page=$page"))
                    ?.path("loaderData")?.path("search") ?: break
                val results = search.path("searchResults")
                readForTerm += results.size()
                for (r in results) {
                    val id = r.path("positionId").asText("").ifBlank { r.path("id").asText("") }
                    if (id.isBlank() || id in seen) continue
                    seen[id] = Posting(
                        company = employer.company,
                        externalId = id,
                        title = r.path("postingTitle").asText(""),
                        location = (r.path("locations").map { it.path("name").asText("") } +
                            listOfNotNull("Remote".takeIf { r.path("homeOffice").asBoolean(false) }))
                            .filter { it.isNotBlank() }.distinct().joinToString(" | "),
                        url = "$BASE/en-us/details/$id/${r.path("transformedPostingTitle").asText("")}",
                        description = r.path("jobSummary").asText("").ifBlank { null },
                    )
                }
                if (results.size() == 0 || readForTerm >= search.path("totalRecords").asInt(Int.MAX_VALUE)) break
            }
        }
        return seen.values.toList()
    }

    /** Full posting (summary, description, minimum + preferred qualifications); falls back to the search summary. */
    override fun describe(employer: Employer, posting: Posting): String? {
        val job = hydration(http.get(posting.url))?.path("loaderData")?.path("jobDetails")?.path("jobsData")
            ?: return posting.description
        val parts = listOf(
            job.path("jobSummary").asText(""),
            job.path("description").asText(""),
            job.path("minimumQualifications").asText("").takeIf { it.isNotBlank() }?.let { "Minimum Qualifications\n$it" }.orEmpty(),
            job.path("preferredQualifications").asText("").takeIf { it.isNotBlank() }?.let { "Preferred Qualifications\n$it" }.orEmpty(),
        ).mapNotNull { htmlToText(it) }
        return parts.joinToString("\n\n").ifBlank { posting.description }
    }

    companion object {
        private const val BASE = "https://jobs.apple.com"
        private val HYDRATION = Regex("""window\.__staticRouterHydrationData\s*=\s*JSON\.parse\("(.*?)"\);""", RegexOption.DOT_MATCHES_ALL)

        /** The hydration JSON: a JS string literal (JSON-escaped) holding the JSON document. */
        internal fun hydration(html: String): JsonNode? {
            val literal = HYDRATION.find(html)?.groupValues?.get(1) ?: return null
            val text = json.readValue("\"$literal\"", String::class.java)
            return json.readTree(text)
        }
    }
}

/** Capgemini's job-stream API (US site), searched per term; HTML descriptions inline. */
class CapgeminiAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 5,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val country = employer.location ?: "us-en"
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            for (page in 1..maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val root = json.readTree(http.get("$API?country_code=${enc(country)}&search=${enc(term)}&page=$page&size=$PAGE"))
                val data = root.path("data")
                for (j in data) {
                    val id = j.path("id").asText("").ifBlank { j.path("ref").asText("") }
                    if (id.isBlank() || id in seen) continue
                    seen[id] = Posting(
                        company = employer.company,
                        externalId = id,
                        title = j.path("title").asText(""),
                        location = j.path("location").asText(""),
                        url = j.path("apply_job_url").asText("").ifBlank { j.path("wp_url").asText("") },
                        description = htmlToText(j.path("description").asText(null)) ?: j.path("description_stripped").asText("").ifBlank { null },
                    )
                }
                if (data.size() < PAGE || page * PAGE >= root.path("total").asInt(0)) break
            }
        }
        return seen.values.toList()
    }

    companion object {
        private const val API = "https://cg-jobstream-api.azurewebsites.net/api/job-search"
        private const val PAGE = 50
    }
}

/**
 * DirectEmployers (jobsyn) career sites — Alaska Airlines. The Solr search API only answers with
 * `X-Origin: <career-site host>` ([Employer.host]); descriptions come inline, and `/<guid>/job/`
 * on the career site redirects to the canonical posting page.
 */
class JobsynAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 5,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val host = requireNotNull(employer.host) { "jobsyn employer ${employer.company} has no host" }
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            for (page in 1..maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val root = json.readTree(http.get("$API?q=${enc(term)}&page=$page&num_items=$PAGE", mapOf("X-Origin" to host)))
                val jobs = root.path("jobs")
                for (j in jobs) {
                    val guid = j.path("guid").asText("")
                    if (guid.isBlank() || guid in seen) continue
                    seen[guid] = Posting(
                        company = employer.company,
                        externalId = j.path("reqid").asText("").ifBlank { guid },
                        title = j.path("title_exact").asText(""),
                        location = j.path("location_exact").asText(""),
                        url = "https://$host/$guid/job/",
                        description = htmlToText(j.path("description").asText(null)),
                    )
                }
                if (!root.path("pagination").path("has_more_pages").asBoolean(false)) break
            }
        }
        return seen.values.toList()
    }

    companion object {
        private const val API = "https://prod-search-api.jobsyn.org/api/v1/solr/search"
        private const val PAGE = 50
    }
}

/** EPAM's careers search API; location = city + countries; page links from the SEO path. */
class EpamAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 4,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            for (page in 0 until maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val root = json.readTree(http.get("$BASE/api/jobs/v2/search/careers?lang=en&q=${enc(term)}&size=$PAGE&from=${page * PAGE}"))
                val jobs = root.path("data").path("jobs")
                for (j in jobs) {
                    val id = j.path("uid").asText("").ifBlank { j.path("permalink_key").asText("") }
                    if (id.isBlank() || id in seen) continue
                    val city = j.path("city").let { c -> if (c.isArray) c.map { it.path("name").asText(it.asText("")) } else listOf(c.asText("")) }
                    val countries = j.path("country").map { it.path("name").asText("") }
                    seen[id] = Posting(
                        company = employer.company,
                        externalId = id,
                        title = j.path("name").asText(""),
                        location = (city + countries).filter { it.isNotBlank() && it != "null" }.distinct().joinToString(", "),
                        url = BASE + j.path("seo").path("url").asText(""),
                        description = htmlToText(j.path("description").asText(null)),
                    )
                }
                if (jobs.size() < PAGE) break
            }
        }
        return seen.values.toList()
    }

    companion object {
        private const val BASE = "https://careers.epam.com"
        private const val PAGE = 50
    }
}

private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

/**
 * Grid Dynamics: the careers page ([Employer.feed]) embeds every opening as JSON in a
 * `data-vacancies` attribute. Most are outside the US, including "Turkey Remote"-style roles the
 * remote filter would let through, so only openings with a US location are listed. [describe]
 * reads the vacancy page's content block.
 */
class GridDynamicsAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val feed = requireNotNull(employer.feed) { "griddynamics employer ${employer.company} has no feed" }
        val raw = Jsoup.parse(http.get(feed)).selectFirst("[data-vacancies]")?.attr("data-vacancies")
            ?: error("griddynamics: no data-vacancies on $feed")
        return json.readTree(raw)
            .filter { v -> v.path("countryLocations").any { it.path("country").asText("") in US } }
            .map { v ->
                val id = v.path("id").asText()
                Posting(
                    company = employer.company,
                    externalId = id,
                    title = v.path("title").asText("").trim(),
                    location = v.path("relatedLocations").joinToString(" | ") { it.asText() },
                    url = "https://www.griddynamics.com/careers/vacancy/$id",
                )
            }
    }

    override fun describe(employer: Employer, posting: Posting): String? =
        Jsoup.parse(http.get(posting.url)).selectFirst(".careers-vacancy__content-block")?.let { htmlToText(it.html()) }

    private companion object {
        val US = setOf("US", "USA", "United States")
    }
}
