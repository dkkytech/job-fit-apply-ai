package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import java.net.URLEncoder

/**
 * Kforce: its job search runs in the browser against an Azure Cognitive Search index, using the
 * public, read-only query key the site ships in its app script. The key is read from that script
 * each run (it can rotate), never stored here. Postings are filtered to [Employer.location]
 * (a state code); the index carries the description, so no posting page is fetched.
 */
class KforceAdapter(private val http: Http, private val searchTerms: List<String>) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val state = requireNotNull(employer.location) { "kforce employer ${employer.company} has no location (state code)" }
        val key = queryKey()
        val found = LinkedHashMap<String, Posting>()
        for (term in searchTerms.ifEmpty { listOf("*") }) {
            val url = "https://$SEARCH_HOST/indexes/kforcewebjobentity/docs?api-version=2016-09-01" +
                "&search=${enc(term)}&\$filter=${enc("State eq '$state'")}&\$top=100" +
                "&\$select=${enc("Id, Title, City, State, ReferenceCode, ApplyUrl, Responsibilities, Skills")}"
            for (j in json.readTree(http.get(url, mapOf("api-key" to key))).path("value")) {
                val ref = j.path("ReferenceCode").asText("").ifBlank { j.path("Id").asText("") }
                if (ref.isBlank() || ref in found) continue
                val internal = INTERNAL_ID.find(j.path("ApplyUrl").asText(""))?.groupValues?.get(1)
                found[ref] = Posting(
                    company = employer.company,
                    externalId = ref,
                    title = j.path("Title").asText(""),
                    location = listOf(j.path("City").asText(""), j.path("State").asText("")).filter { it.isNotBlank() }.joinToString(", "),
                    url = internal?.let { "https://www.kforce.com/Jobs/$it/" } ?: "https://www.kforce.com/find-work/search-jobs/",
                    description = listOf("Responsibilities", "Skills").mapNotNull { htmlToText(j.path(it).asText(null)) }
                        .joinToString("\n\n").ifBlank { null },
                )
            }
        }
        return found.values.toList()
    }

    /** The query key from the site's app script (two requests to www.kforce.com per run). */
    private fun queryKey(): String {
        val page = http.get("https://www.kforce.com/find-work/search-jobs/")
        val script = SCRIPT.find(page)?.groupValues?.get(1) ?: error("kforce: app script not found on the search page")
        return KEY.find(http.get(script))?.groupValues?.get(1) ?: error("kforce: query key not found in $script")
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val SEARCH_HOST = "kforcewebeast.search.windows.net"
        val SCRIPT = Regex("""src="(https://[^"]+/app\.min\.js[^"]*)"""")
        val KEY = Regex("""url:"https://kforcewebeast\.search\.windows\.net",key:"([0-9A-Fa-f]{32})"""")
        val INTERNAL_ID = Regex("""/Jobs/([^/]+)/""")
    }
}

/**
 * Robert Half: the job-search page embeds its first page of results as `initialResults =
 * JSON.parse('…')` (a JS string). [Employer.feed] is the location page filtered to a line of
 * business (`?lobid=RHT`, Robert Half Technology), which fits in that first page.
 */
class RobertHalfAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val feed = requireNotNull(employer.feed) { "roberthalf employer ${employer.company} has no feed" }
        val raw = INITIAL.find(http.get(feed))?.groupValues?.get(1) ?: error("roberthalf: no initialResults on $feed")
        return json.readTree(unescapeJs(raw)).path("data").path("jobs").map { j ->
            Posting(
                company = employer.company,
                externalId = j.path("unique_job_number").asText(j.path("sf_jo_number").asText("")),
                title = j.path("jobtitle").asText(""),
                location = (listOf(listOf(j.path("city").asText(""), j.path("stateprovince").asText("")).filter { it.isNotBlank() }.joinToString(", ")) +
                    listOfNotNull("Remote".takeIf { j.path("remote").asText("").equals("yes", true) })).filter { it.isNotBlank() }.joinToString(" | "),
                url = j.path("job_detail_url").asText(""),
                description = htmlToText(j.path("description").asText(null)),
            )
        }
    }

    private companion object {
        val INITIAL = Regex("""initialResults = JSON\.parse\('(.*?)'\);""", RegexOption.DOT_MATCHES_ALL)

        /** A single-quoted JS string literal's escapes: \xHH, \uHHHH, \/, \', \\, \n, \t, \r. */
        fun unescapeJs(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) { append(c); i++; continue }
                when (val n = s[i + 1]) {
                    'x' -> { append(s.substring(i + 2, i + 4).toInt(16).toChar()); i += 4 }
                    'u' -> { append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                    'n' -> { append('\n'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    else -> { append(n); i += 2 }   // \/ \' \\ \"
                }
            }
        }
    }
}

/**
 * JobDiva (Aditi Consulting): the candidate portal is a script-driven app, but JobDiva publishes an
 * RSS feed per portal ([Employer.board] is the portal's `a` token). The feed holds the 20 newest
 * postings matching `SearchString`, so it is read once per search term — weeks of matches for QE
 * terms, so a daily run misses none. Titles carry the location: "QA Engineer (26-06479) - WA - Redmond".
 */
class JobDivaAdapter(private val http: Http, private val searchTerms: List<String>, private val pause: () -> Unit = {}) : FeedAdapter {
    private val rss = RssAdapter(http)

    override fun list(employer: Employer): List<Posting> {
        val token = requireNotNull(employer.board) { "jobdiva employer ${employer.company} has no board (portal token)" }
        val found = LinkedHashMap<String, Posting>()
        searchTerms.ifEmpty { listOf("") }.forEachIndexed { i, term ->
            if (i > 0) pause()
            val feed = "$BASE/getjobsrssfeed.jsp?a=${enc(token)}&SearchString=${enc(term)}"
            for (p in rss.list(employer.copy(feed = feed))) {
                val id = ID.find(p.url)?.groupValues?.get(1) ?: continue
                if (id in found) continue
                val m = TITLE.matchEntire(p.title)
                found[id] = p.copy(
                    externalId = id,
                    title = m?.groupValues?.get(1) ?: p.title,
                    location = m?.let { "${it.groupValues[3]}, ${it.groupValues[2]}" } ?: p.location,
                    url = "$BASE/openjob_outside.jsp?a=${enc(token)}&id=$id",
                )
            }
        }
        return found.values.toList()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val BASE = "https://www2.jobdiva.com/candidates/myjobs"
        val ID = Regex("""[?&]id=(\d+)""")
        /** "Title (req no.) - ST - City"; anything else keeps the feed's title and location. */
        val TITLE = Regex("""^(.*?)\s*\([^()]*\)\s*-\s*([A-Za-z]{2})\s*-\s*(.+)$""")
    }
}

/**
 * Insight Global: the job search is an Angular app over a public JSON API (`/all/jobs`), searched
 * per term within [Employer.location] (a location slug such as `seattle-wa`) and a 50-mile radius.
 * The list carries no description; [describe] reads `/all/jobs/<id>/details`.
 */
class InsightGlobalAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPages: Int = 5,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val area = requireNotNull(employer.location) { "insightglobal employer ${employer.company} has no location (search slug)" }
        val found = LinkedHashMap<String, Posting>()
        var first = true
        for (term in searchTerms.ifEmpty { listOf("") }) {
            var page = 1
            while (page <= maxPages) {
                if (!first) pause()
                first = false
                val body = json.readTree(http.get(
                    "$BASE/all/jobs?keyword=${enc(term)}&page=$page&size=$PAGE_SIZE&sort=relevance,desc" +
                        "&filter.includeOnlyRemoteWork=false&filter.locations=${enc(area)}&filter.distance=$MILES&filter.status=active",
                ))
                for (j in body.path("jobs")) {
                    val id = j.path("requisitionId").asText("")
                    if (id.isBlank()) continue
                    val address = j.path("workAddress")
                    val place = listOf(address.path("locality").asText(""), address.path("administrativeArea").asText(""))
                        .filter { it.isNotBlank() }.joinToString(", ")
                    found[id] = Posting(
                        company = employer.company,
                        externalId = id,
                        title = j.path("jobTitle").asText(""),
                        location = listOfNotNull(place.ifBlank { null }, "Remote".takeIf { j.path("workRemote").asBoolean(false) }).joinToString(" | "),
                        url = "$BASE/jobs/$id",
                    )
                }
                if (page >= body.path("pageMetadata").path("totalPages").asInt(1)) break
                page++
            }
        }
        return found.values.toList()
    }

    override fun describe(employer: Employer, posting: Posting): String? =
        htmlToText(json.readTree(http.get("$BASE/all/jobs/${posting.externalId}/details")).path("description").asText(null))

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val BASE = "https://insightglobal.com"
        const val PAGE_SIZE = 50
        const val MILES = 50
    }
}
