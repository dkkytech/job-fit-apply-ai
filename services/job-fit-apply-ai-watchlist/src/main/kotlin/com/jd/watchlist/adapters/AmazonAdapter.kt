package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import java.net.URLEncoder

/**
 * amazon.jobs search JSON (Amazon, AWS, Kuiper/Leo, Twitch-on-amazon.jobs). Filtered server-side
 * to Washington state; the keyword search is fuzzy (thousands of hits), so results are sorted
 * newest-first and capped — a daily sweep only needs the recent head. Description, basic and
 * preferred qualifications come inline.
 */
class AmazonAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 3,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            for (page in 0 until maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val url = "$BASE/en/search.json?base_query=${enc(term)}&normalized_state_name%5B%5D=Washington" +
                    "&normalized_country_code%5B%5D=USA&result_limit=$PAGE&offset=${page * PAGE}&sort=recent"
                val jobs = json.readTree(http.get(url)).path("jobs")
                for (j in jobs) {
                    val id = j.path("id_icims").asText("").ifBlank { j.path("id").asText("") }
                    if (id.isBlank() || id in seen) continue
                    val description = listOf(
                        j.path("description").asText(""),
                        j.path("basic_qualifications").asText("").takeIf { it.isNotBlank() }?.let { "BASIC QUALIFICATIONS\n$it" }.orEmpty(),
                        j.path("preferred_qualifications").asText("").takeIf { it.isNotBlank() }?.let { "PREFERRED QUALIFICATIONS\n$it" }.orEmpty(),
                    ).mapNotNull { htmlToText(it) }.joinToString("\n\n").ifBlank { null }
                    seen[id] = Posting(
                        company = employer.company,
                        externalId = id,
                        title = j.path("title").asText(""),
                        location = j.path("normalized_location").asText("").ifBlank { j.path("location").asText("") },
                        url = BASE + j.path("job_path").asText(""),
                        description = description,
                    )
                }
                if (jobs.size() < PAGE) break
            }
        }
        return seen.values.toList()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        private const val BASE = "https://www.amazon.jobs"
        private const val PAGE = 100
    }
}
