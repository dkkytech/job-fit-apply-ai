package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting

/**
 * Greenhouse job board API. `?content=true` returns every posting with its full description, so
 * no per-job detail fetch is needed. Location = the posting's location plus its office names,
 * because some boards (e.g. Datadog) put the Seattle office only under `offices`.
 */
class GreenhouseAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "greenhouse employer ${employer.company} has no board" }
        val root = json.readTree(http.get("https://boards-api.greenhouse.io/v1/boards/$board/jobs?content=true"))
        return root.path("jobs").map { j ->
            val offices = j.path("offices").mapNotNull { o ->
                listOf(o.path("name").asText(""), o.path("location").asText("")).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
            }
            Posting(
                company = employer.company,
                externalId = j.path("id").asText(),
                title = j.path("title").asText(""),
                location = (listOf(j.path("location").path("name").asText("")) + offices).filter { it.isNotBlank() }.distinct().joinToString(" | "),
                url = j.path("absolute_url").asText(""),
                description = htmlToText(j.path("content").asText(null), escaped = true),
            )
        }
    }
}
