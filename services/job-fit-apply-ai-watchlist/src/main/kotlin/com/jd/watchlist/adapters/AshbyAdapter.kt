package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting

/** Ashby public job-board API. Unlisted postings are skipped; secondary locations count for the filter. */
class AshbyAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "ashby employer ${employer.company} has no board" }
        val root = json.readTree(http.get("https://api.ashbyhq.com/posting-api/job-board/$board"))
        return root.path("jobs").filter { it.path("isListed").asBoolean(true) }.map { j ->
            val locations = listOf(j.path("location").asText("")) +
                j.path("secondaryLocations").map { it.path("location").asText("") } +
                listOfNotNull("Remote".takeIf { j.path("isRemote").asBoolean(false) })
            Posting(
                company = employer.company,
                externalId = j.path("id").asText(),
                title = j.path("title").asText(""),
                location = locations.filter { it.isNotBlank() }.distinct().joinToString(" | "),
                url = j.path("jobUrl").asText(""),
                description = j.path("descriptionPlain").asText("").ifBlank { htmlToText(j.path("descriptionHtml").asText(null)) },
            )
        }
    }
}
