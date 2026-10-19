package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting

/**
 * SmartRecruiters public postings API (ServiceNow, Expeditors, Trupanion, LinkedIn, Logic20/20).
 * The listing pages through every posting with a structured location; the description lives in
 * the per-posting `ref` document as HTML sections.
 */
class SmartRecruitersAdapter(
    private val http: Http,
    private val pause: () -> Unit = {},
    private val maxPages: Int = 20,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "smartrecruiters employer ${employer.company} has no board" }
        val out = mutableListOf<Posting>()
        var offset = 0
        for (page in 0 until maxPages) {
            if (page > 0) pause()
            val root = json.readTree(http.get("https://api.smartrecruiters.com/v1/companies/$board/postings?limit=$PAGE&offset=$offset"))
            val content = root.path("content")
            for (p in content) {
                val loc = p.path("location")
                val parts = listOf(loc.path("fullLocation").asText(""), loc.path("city").asText(""), loc.path("region").asText("")) +
                    listOfNotNull("Remote".takeIf { loc.path("remote").asBoolean(false) })
                val id = p.path("id").asText()
                out += Posting(
                    company = employer.company,
                    externalId = id,
                    title = p.path("name").asText(""),
                    location = parts.filter { it.isNotBlank() }.distinct().joinToString(" | "),
                    url = "https://jobs.smartrecruiters.com/$board/$id",
                    detailRef = p.path("ref").asText(null),
                )
            }
            offset += content.size()
            if (content.size() == 0 || offset >= root.path("totalFound").asInt(0)) break
        }
        return out
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val ref = posting.detailRef ?: return posting.description
        val sections = json.readTree(http.get(ref)).path("jobAd").path("sections")
        return listOf("jobDescription", "qualifications", "additionalInformation")
            .mapNotNull { htmlToText(sections.path(it).path("text").asText(null)) }
            .joinToString("\n\n").ifBlank { null }
    }

    companion object {
        private const val PAGE = 100
    }
}
