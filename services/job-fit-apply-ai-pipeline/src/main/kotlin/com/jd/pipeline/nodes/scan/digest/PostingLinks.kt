package com.jd.pipeline.nodes.scan.digest

import java.net.URI
import java.net.URLDecoder

/**
 * A job posting an alert email links to: where to fetch it ([url]) and the identity the bridge
 * dedupes it on ([key]).
 *
 * For the ATSes the watchlist also polls, [key] is exactly the watchlist's idempotency key
 * (`watchlist:<ats>:<boardKey>:<postingId>`, pinned by
 * services/job-fit-apply-ai-watchlist/contract/posting_keys.json), so a posting found by both an
 * employer's alert email and its feed is processed once. Anything else is keyed on its cleaned URL.
 */
data class PostingLink(val url: String, val key: String, val ats: String? = null)

object PostingLinks {
    private const val MAX_UNWRAP = 3

    /** Query parameters a click-tracker uses to carry the real destination (Eightfold's `vsimp?n=`). */
    private val REDIRECT_PARAMS = listOf("n", "url", "u", "target", "redirect", "redirect_url", "dest", "destination", "link")

    /** Tracking-only query parameters, dropped so one posting has one URL. */
    private val TRACKING_PARAMS = Regex("^(utm_.*|src|source|ref|refid|trk|gh_src|lever-source.*|mkt_tok|sc_cid|cid|iis|iisn|profile_type|codes|from|eid)$", RegexOption.IGNORE_CASE)

    /** Alert plumbing, never a posting: unsubscribe, settings, profile, sign-in, search pages. */
    private val NOT_A_POSTING = Regex(
        "unsubscribe|opt-?out|notification_settings|/alerts?(/|$|\\?)|job-?alert|preferences|/profile|/me(\\?|/|$)|" +
            "/login|/signin|/sign-in|/account|privacy|/search|/talentcommunity|/candidate/",
        RegexOption.IGNORE_CASE,
    )

    private val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val GREENHOUSE = Regex("^/([A-Za-z0-9_-]+)/jobs/(\\d+)")
    private val LEVER = Regex("^/([A-Za-z0-9_.-]+)/($UUID)", RegexOption.IGNORE_CASE)
    private val ASHBY = Regex("^/([A-Za-z0-9_.%-]+)/($UUID)", RegexOption.IGNORE_CASE)
    private val WORKDAY_HOST = Regex("^([a-z0-9-]+)\\.wd\\d+\\.myworkdayjobs\\.com$")
    private val WORKDAY_PATH = Regex("^/(?:[a-z]{2}-[A-Z]{2}/)?([^/]+)/(?:job|details)/.*_([A-Za-z0-9-]+)/?$")
    private val EIGHTFOLD_PATH = Regex("^/(?:careers/job|careerhub/explore/jobs)/(\\d{6,})")
    private val ICIMS_PATH = Regex("^/jobs/(\\d+)(?:/|$)")
    /** A path that names one posting: a /job(s)/ segment followed somewhere by an id-like token. */
    private val GENERIC_POSTING = Regex("/jobs?/(?:[^?#]*/)?(?:\\d{3,}|$UUID|[A-Za-z0-9-]*\\d{3,}[A-Za-z0-9-]*)(?:[/?#]|$)", RegexOption.IGNORE_CASE)

    /** Follow tracker wrappers that carry the destination in a query parameter. */
    fun unwrap(raw: String): String {
        var url = cleanUrl(raw.trim())
        repeat(MAX_UNWRAP) {
            val inner = queryParams(url).firstNotNullOfOrNull { (k, v) ->
                v.takeIf { k.lowercase() in REDIRECT_PARAMS && (it.startsWith("http://") || it.startsWith("https://")) }
            } ?: return url
            url = inner
        }
        return url
    }

    /** The posting [raw] points to, or null for anything that isn't one posting page. */
    fun identify(raw: String): PostingLink? {
        val url = unwrap(raw)
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()

        // ATS postings the watchlist also polls: key them its way.
        when {
            host == "boards.greenhouse.io" || host == "job-boards.greenhouse.io" ->
                GREENHOUSE.find(path)?.let { m ->
                    val (board, id) = m.destructured
                    return PostingLink("https://job-boards.greenhouse.io/$board/jobs/$id", "watchlist:greenhouse:$board:$id", "greenhouse")
                }
            host == "jobs.lever.co" ->
                LEVER.find(path)?.let { m ->
                    val (board, id) = m.destructured
                    return PostingLink("https://jobs.lever.co/$board/$id", "watchlist:lever:$board:$id", "lever")
                }
            host == "jobs.ashbyhq.com" ->
                ASHBY.find(path)?.let { m ->
                    val (board, id) = m.destructured
                    return PostingLink("https://jobs.ashbyhq.com/$board/$id", "watchlist:ashby:$board:$id", "ashby")
                }
            WORKDAY_HOST.matches(host) -> {
                val tenant = WORKDAY_HOST.find(host)!!.groupValues[1]
                WORKDAY_PATH.find(path)?.let { m ->
                    val (site, id) = m.destructured
                    return PostingLink(stripTracking(url), "watchlist:workday:$tenant/$site:$id", "workday")
                }
            }
            host.endsWith(".icims.com") ->
                ICIMS_PATH.find(path)?.let { m ->
                    val id = m.groupValues[1]
                    return PostingLink("https://$host/jobs/$id/job", "watchlist:icims:$host:$id", "icims")
                }
        }
        // Eightfold career sites (Microsoft's among them). The alert links the logged-in careerhub
        // page, which redirects to a sign-in; /careers/job/<id> is the public, server-rendered one.
        EIGHTFOLD_PATH.find(path)?.let { m ->
            val id = m.groupValues[1]
            return PostingLink("https://$host/careers/job/$id", "watchlist:eightfold:$host:$id", "eightfold")
        }

        // Judged on the clean URL: `utm_source=jobalert` says how it was sent, not what it is.
        val clean = stripTracking(url)
        if (NOT_A_POSTING.containsMatchIn(clean) || !GENERIC_POSTING.containsMatchIn(path)) return null
        return PostingLink(clean, "alert:" + clean.substringAfter("://"))
    }

    /** [url] without its fragment and tracking-only query parameters. */
    internal fun stripTracking(url: String): String {
        val noFragment = url.substringBefore('#')
        val base = noFragment.substringBefore('?')
        val kept = queryParams(noFragment).filterNot { (k, _) -> TRACKING_PARAMS.matches(k) }
        if (kept.isEmpty()) return base
        return base + "?" + noFragment.substringAfter('?').split('&')
            .filter { part -> !TRACKING_PARAMS.matches(decode(part.substringBefore('='))) }
            .joinToString("&")
    }

    /** URL-decoded, or [s] as-is when it is not valid encoding (a stray `%` in a hand-made link). */
    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)

    private fun queryParams(url: String): List<Pair<String, String>> {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { part ->
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            decode(k) to decode(v)
        }
    }
}
