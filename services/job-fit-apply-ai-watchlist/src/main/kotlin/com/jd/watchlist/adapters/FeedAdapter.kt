package com.jd.watchlist.adapters

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Reads one ATS platform's public job feed. One adapter per platform, not per employer: the
 * employer's entry in employers.yaml supplies the board/tenant/feed parameters.
 */
interface FeedAdapter {
    /** All postings currently listed by [employer] (location/title filtering happens later). */
    fun list(employer: Employer): List<Posting>

    /** Plain-text description, fetching it when [Posting.description] is absent. Null if unavailable. */
    fun describe(employer: Employer, posting: Posting): String? = posting.description

    /**
     * The description plus, for boards whose list has no location, the location the posting page
     * states — so the runner can still drop out-of-state postings before they reach the bridge.
     */
    fun detail(employer: Employer, posting: Posting): PostingDetail = PostingDetail(describe(employer, posting))
}

data class PostingDetail(val text: String?, val location: String? = null)

/** The adapters this service ships, keyed by the `ats` value in employers.yaml. */
object Adapters {
    /**
     * [pause] runs between paged requests to the same employer (politeness / rate limits);
     * [slowPause] is the longer one for Eightfold, which rate-limits per IP.
     */
    fun registry(http: Http, searchTerms: List<String>, pause: () -> Unit = {}, slowPause: () -> Unit = pause): Map<String, FeedAdapter> = mapOf(
        "greenhouse" to GreenhouseAdapter(http),
        "lever" to LeverAdapter(http),
        "ashby" to AshbyAdapter(http),
        "workday" to WorkdayAdapter(http, searchTerms),
        "rss" to RssAdapter(http),
        "oracle_hcm" to OracleHcmAdapter(http, pause),
        "eightfold" to EightfoldAdapter(http, searchTerms, slowPause),
        "smartrecruiters" to SmartRecruitersAdapter(http, pause),
        "jibe" to JibeAdapter(http, searchTerms, pause),
        "amazon" to AmazonAdapter(http, searchTerms, pause),
        "workable" to WorkableAdapter(http),
        "gem" to GemAdapter(http),
        "rippling" to RipplingAdapter(http),
        "adp" to AdpAdapter(http, pause),
        "phenom" to PhenomAdapter(http, searchTerms, pause),
        "tiktok" to TikTokAdapter(http, searchTerms, pause),
        "icims" to IcimsClassicAdapter(http, pause),
        "jobvite" to JobviteAdapter(http),
        "apple" to AppleAdapter(http, searchTerms, pause),
        "capgemini" to CapgeminiAdapter(http, searchTerms, pause),
        "jobsyn" to JobsynAdapter(http, searchTerms, pause),
        "epam" to EpamAdapter(http, searchTerms, pause),
        "successfactors" to SuccessFactorsAdapter(http, searchTerms, pause),
        "ukg" to UkgAdapter(http),
        "paylocity" to PaylocityAdapter(http),
        "zoho" to ZohoRecruitAdapter(http),
        "atlassian" to AtlassianAdapter(http),
        "html" to GenericHtmlAdapter(http, searchTerms, pause),
        "kforce" to KforceAdapter(http, searchTerms),
        "roberthalf" to RobertHalfAdapter(http),
        "jobdiva" to JobDivaAdapter(http, searchTerms, pause),
        "insightglobal" to InsightGlobalAdapter(http, searchTerms, pause),
        "griddynamics" to GridDynamicsAdapter(http),
    )
}

internal val json: ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** HTML (optionally entity-escaped, as Greenhouse sends it) → readable plain text with line breaks. */
internal fun htmlToText(html: String?, escaped: Boolean = false): String? {
    if (html.isNullOrBlank()) return null
    val markup = if (escaped) Parser.unescapeEntities(html, false) else html
    val doc = Jsoup.parse(markup)
    doc.select("br").append("\\n")
    doc.select("p, li, h1, h2, h3, h4, h5, h6, div").prepend("\\n")
    return doc.text().replace("\\n", "\n")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .ifBlank { null }
}
