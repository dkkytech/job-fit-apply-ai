package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting

/** Lever postings API. The listing carries the description in parts: opening, lists, closing. */
class LeverAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "lever employer ${employer.company} has no board" }
        val root = json.readTree(http.get("https://api.lever.co/v0/postings/$board?mode=json"))
        return root.map { j ->
            val cats = j.path("categories")
            val locations = cats.path("allLocations").map { it.asText() }.ifEmpty { listOf(cats.path("location").asText("")) }
            val lists = j.path("lists").joinToString("\n\n") { l ->
                listOfNotNull(l.path("text").asText(null), htmlToText(l.path("content").asText(null))).joinToString("\n")
            }
            val description = listOf(j.path("descriptionPlain").asText(""), lists, j.path("additionalPlain").asText(""))
                .filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
            Posting(
                company = employer.company,
                externalId = j.path("id").asText(),
                title = j.path("text").asText(""),
                location = (locations + listOfNotNull(j.path("workplaceType").asText(null)?.takeIf { it == "remote" }))
                    .filter { it.isNotBlank() }.joinToString(" | "),
                url = j.path("hostedUrl").asText(""),
                description = description,
            )
        }
    }
}
