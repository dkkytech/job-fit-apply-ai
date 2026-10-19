package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import org.jsoup.Jsoup

/**
 * Small, list-everything job boards: one call returns every posting, so there is no search or
 * location filter to configure — the pre-filter handles titles and locations.
 */

/** Workable widget API (DomainTools, Seeq, Radiant Vision Systems, Xplore); `details=true` inlines descriptions. */
class WorkableAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "workable employer ${employer.company} has no board" }
        val root = json.readTree(http.get("https://apply.workable.com/api/v1/widget/accounts/$board?details=true"))
        return root.path("jobs").map { j ->
            val locations = listOf(listOf("city", "state", "country").map { j.path(it).asText("") }.filter { it.isNotBlank() }.joinToString(", ")) +
                j.path("locations").map { l -> listOf("city", "region", "country").map { l.path(it).asText("") }.filter { it.isNotBlank() }.joinToString(", ") } +
                listOfNotNull("Remote".takeIf { j.path("telecommuting").asBoolean(false) })
            Posting(
                company = employer.company,
                externalId = j.path("shortcode").asText(),
                title = j.path("title").asText(""),
                location = locations.filter { it.isNotBlank() }.distinct().joinToString(" | "),
                url = j.path("url").asText("").ifBlank { "https://apply.workable.com/$board/j/${j.path("shortcode").asText()}/" },
                description = htmlToText(j.path("description").asText(null)),
            )
        }
    }
}

/** Gem job boards (Wyze): one call, plain-text content inline. */
class GemAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "gem employer ${employer.company} has no board" }
        return json.readTree(http.get("https://api.gem.com/job_board/v0/$board/job_posts")).map { j ->
            val locations = listOf(j.path("location").path("name").asText("")) +
                j.path("offices").map { it.path("location").path("name").asText(it.path("name").asText("")) } +
                listOfNotNull("Remote".takeIf { j.path("location_type").asText("").contains("remote", true) })
            Posting(
                company = employer.company,
                externalId = j.path("id").asText(),
                title = j.path("title").asText(""),
                location = locations.filter { it.isNotBlank() }.distinct().joinToString(" | "),
                url = j.path("absolute_url").asText(""),
                description = j.path("content_plain").asText("").ifBlank { htmlToText(j.path("content").asText(null)) },
            )
        }
    }
}

/** Rippling ATS boards (Zap Energy): the list has no description; the per-job document has role HTML. */
class RipplingAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "rippling employer ${employer.company} has no board" }
        return json.readTree(http.get("https://api.rippling.com/platform/api/ats/v1/board/$board/jobs")).map { j ->
            Posting(
                company = employer.company,
                externalId = j.path("uuid").asText(),
                title = j.path("name").asText(""),
                location = j.path("workLocation").path("label").asText(""),
                url = j.path("url").asText(""),
                detailRef = j.path("uuid").asText(),
            )
        }
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val d = json.readTree(http.get("https://api.rippling.com/platform/api/ats/v1/board/${employer.board}/jobs/${posting.detailRef}")).path("description")
        return htmlToText(d.path("role").asText(null))
    }
}

/**
 * ADP Workforce Now career centers (Rocketdyne, magniX, Karman, Intellectual Ventures).
 * [Employer.feed] is the `job-requisitions` URL with its `cid`/`ccId`; pages of 20 via `$skip`.
 */
class AdpAdapter(private val http: Http, private val pause: () -> Unit = {}, private val maxPages: Int = 25) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val feed = requireNotNull(employer.feed) { "adp employer ${employer.company} has no feed" }
        val out = mutableListOf<Posting>()
        for (page in 0 until maxPages) {
            if (page > 0) pause()
            val root = json.readTree(http.get("$feed&\$skip=${page * PAGE}&\$top=$PAGE"))
            val reqs = root.path("jobRequisitions")
            for (r in reqs) {
                val id = r.path("itemID").asText("")
                if (id.isBlank()) continue
                val locations = r.path("requisitionLocations").map { l ->
                    val a = l.path("address")
                    listOf(a.path("cityName").asText(""), a.path("countrySubdivisionLevel1").path("codeValue").asText(""))
                        .filter { it.isNotBlank() }.joinToString(", ")
                }
                out += Posting(
                    company = employer.company,
                    externalId = id,
                    title = r.path("requisitionTitle").asText(""),
                    location = locations.filter { it.isNotBlank() }.joinToString(" | "),
                    url = publicUrl(feed, id),
                    detailRef = id,
                )
            }
            val total = root.path("meta").path("totalNumber").asInt(0)
            if (reqs.size() < PAGE || (total > 0 && (page + 1) * PAGE >= total)) break
        }
        return out
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val feed = requireNotNull(employer.feed)
        val detail = feed.substringBefore('?') + "/" + posting.detailRef + "?" + feed.substringAfter('?', "")
        return htmlToText(json.readTree(http.get(detail)).path("requisitionDescription").asText(null))
    }

    companion object {
        private const val PAGE = 20

        /** The candidate-facing posting page: ADP's recruitment SPA with the job id. */
        internal fun publicUrl(feed: String, id: String): String {
            val q = feed.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            return "https://workforcenow.adp.com/mascsr/default/mdf/recruitment/recruitment.html?cid=${q["cid"].orEmpty()}" +
                "&ccId=${q["ccId"].orEmpty()}&jobId=$id&lang=en_US"
        }
    }
}

/** UKG Pro Recruiting (UltiPro) job boards (Sound Transit): one search call; the detail page embeds the description JSON. */
class UkgAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val tenant = requireNotNull(employer.tenant) { "ukg employer ${employer.company} has no tenant" }
        val board = requireNotNull(employer.board) { "ukg employer ${employer.company} has no board" }
        val base = "https://recruiting.ultipro.com/$tenant/JobBoard/$board"
        val body = """{"opportunitySearch":{"Top":200,"Skip":0,"QueryString":"","OrderBy":[{"Value":"postedDateDesc","PropertyName":"PostedDate","Ascending":false}],"Filters":[]},"matchCriteria":{"PreferredJobs":[],"Educations":[],"LicenseAndCertifications":[],"Skills":[],"hasNoLicenses":false,"SkippedSkills":[]}}"""
        return json.readTree(http.postJson("$base/JobBoardView/LoadSearchResults", body)).path("opportunities").map { o ->
            val id = o.path("Id").asText()
            Posting(
                company = employer.company,
                externalId = id,
                title = o.path("Title").asText(""),
                location = o.path("Locations").map { l ->
                    val a = l.path("Address")
                    listOf(a.path("City").asText(""), a.path("State").path("Code").asText(""), a.path("Country").path("Code").asText(""))
                        .filter { it.isNotBlank() }.joinToString(", ")
                }.filter { it.isNotBlank() }.distinct().joinToString(" | "),
                url = "$base/OpportunityDetail?opportunityId=$id",
            )
        }
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val page = http.get(posting.url)
        val start = page.indexOf(DETAIL_CALL).takeIf { it >= 0 }?.let { page.indexOf('{', it) }?.takeIf { it >= 0 } ?: return null
        // Read exactly one JSON value from the brace: nested objects, and a "});" inside a string,
        // cannot cut it short the way a regex could.
        val detail = json.factory.createParser(page.substring(start)).use { json.readTree<com.fasterxml.jackson.databind.JsonNode>(it) }
        return htmlToText(detail?.path("Description")?.asText(null))
    }

    private companion object {
        const val DETAIL_CALL = "new US.Opportunity.CandidateOpportunityDetail("
    }
}

/**
 * Paylocity Recruiting job boards (evo): the list page embeds every job as `window.pageData`, but
 * only with a ~110-character teaser; the full description is on the job's detail page.
 */
class PaylocityAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val board = requireNotNull(employer.board) { "paylocity employer ${employer.company} has no board" }
        val page = http.get("https://recruiting.paylocity.com/recruiting/jobs/All/$board")
        val data = Regex("""window\.pageData\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL).find(page)
            ?: error("paylocity board $board: no pageData on the jobs page")
        return json.readTree(data.groupValues[1]).path("Jobs").map { j ->
            val loc = j.path("JobLocation")
            Posting(
                company = employer.company,
                externalId = j.path("JobId").asText(),
                title = j.path("JobTitle").asText(""),
                location = (listOf(listOf(loc.path("City").asText(""), loc.path("State").asText("")).filter { it.isNotBlank() }.joinToString(", ")) +
                    listOfNotNull("Remote".takeIf { j.path("IsRemote").asBoolean(false) })).filter { it.isNotBlank() }.joinToString(" | "),
                url = "https://recruiting.paylocity.com/Recruiting/Jobs/Details/${j.path("JobId").asText()}",
            )
        }
    }

    override fun describe(employer: Employer, posting: Posting): String? =
        htmlToText(Jsoup.parse(http.get(posting.url)).selectFirst(".job-preview-details")?.apply { select("a, button").remove() }?.html())
}

/** Zoho Recruit career sites (Ziply Fiber): the page holds every job, description included, in a hidden `jobs` input. */
class ZohoRecruitAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> {
        val host = requireNotNull(employer.host) { "zoho employer ${employer.company} has no host" }
        val site = employer.site ?: "Careers"
        val page = Jsoup.parse(http.get("https://$host/jobs/$site"))
        val raw = page.selectFirst("input#jobs")?.attr("value") ?: error("zoho site $host/$site: no jobs input on the page")
        return json.readTree(raw).filter { it.path("Publish").asBoolean(true) }.map { j ->
            val id = j.path("id").asText()
            val title = j.path("Posting_Title").asText(j.path("Job_Opening_Name").asText(""))
            Posting(
                company = employer.company,
                externalId = id,
                title = title,
                location = (listOf(listOf("City", "State", "Country").map { j.path(it).asText("") }.filter { it.isNotBlank() }.joinToString(", ")) +
                    listOfNotNull("Remote".takeIf { j.path("Remote_Job").asBoolean(false) })).filter { it.isNotBlank() }.joinToString(" | "),
                url = "https://$host/jobs/$site/$id/${title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')}",
                description = htmlToText(j.path("Job_Description").asText(null)),
            )
        }
    }
}

/** Atlassian's own careers listings (the iCIMS board behind it is not public): one call, every listing with its text. */
class AtlassianAdapter(private val http: Http) : FeedAdapter {
    override fun list(employer: Employer): List<Posting> =
        // A job posted to several regions appears once per region under different ids, same page.
        json.readTree(http.get("https://www.atlassian.com/endpoint/careers/listings")).map { j ->
            Posting(
                company = employer.company,
                externalId = j.path("id").asText(),
                title = j.path("title").asText(""),
                location = j.path("locations").map { it.asText("") }.filter { it.isNotBlank() }.joinToString(" | "),
                url = j.path("portalJobPost").path("portalUrl").asText("").ifBlank { j.path("applyUrl").asText("") },
                description = listOf("overview", "responsibilities", "qualifications")
                    .mapNotNull { htmlToText(j.path(it).asText(null)) }.joinToString("\n\n").ifBlank { null },
            )
        }.distinctBy { it.url.ifBlank { it.externalId } }
}

