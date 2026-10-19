package com.jd.watchlist.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path

/**
 * One watched employer, as listed in employers.yaml. [ats] picks the adapter; the remaining
 * fields are that adapter's parameters. Employers on platforms without an adapter yet stay in the
 * file (so it is the whole watchlist) and are skipped at run time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Employer(
    val company: String,
    val ats: String,
    /** Greenhouse / Lever / Ashby board token. */
    val board: String? = null,
    /** Workday: e.g. boeing.wd1.myworkdayjobs.com */
    val host: String? = null,
    /** Workday tenant, e.g. boeing. */
    val tenant: String? = null,
    /** Workday career site, e.g. EXTERNAL_CAREERS. */
    val site: String? = null,
    /** RSS feed URL; Jibe: the `/api/jobs` URL including its location filter. */
    val feed: String? = null,
    /** Eightfold: the tenant domain parameter, e.g. starbucks.com. */
    val domain: String? = null,
    /** Eightfold: location filter passed to the search, e.g. "Seattle, WA". */
    val location: String? = null,
    /** Eightfold API flavour: "pcsx" (newer search) or "v2" (apply/v2). */
    val api: String? = null,
    /**
     * html: the regex an absolute link must match to be a posting; group 1 (or the whole match) is
     * the posting id. [feed] is the list page; `{term}` and `{page}` in it are filled from the
     * search terms and 1..[pages].
     */
    val linkPattern: String? = null,
    /** html: CSS selector (within the card, else the link) for the title, when the link wraps the whole card. */
    val titleSelector: String? = null,
    /** html: CSS selector of the card around a posting link whose text carries its location. */
    val cardSelector: String? = null,
    /** html: list pages to read when [feed] has `{page}` (default 1). */
    val pages: Int? = null,
    /** html: the label before the location on the posting page ("Location:"), when the list has none. */
    val locationLabel: String? = null,
    /** Search terms for this employer, replacing the global ones (Oracle: Seattle-area city names). */
    val search: List<String>? = null,
    /** RSS: CSS selector for the description on the posting page, used when the feed item has none. */
    val detailSelector: String? = null,
    /** Human-facing careers page (not polled). */
    val careersUrl: String? = null,
    /** high / medium / low — how often this employer hires QE in the Seattle area. */
    val qeFit: String? = null,
    val enabled: Boolean = true,
    /**
     * Zero matching postings is this employer's normal state (a small team, or one that rarely
     * hires in Washington), so an empty listing is not the "feed broke" signal.
     */
    val mayBeEmpty: Boolean = false,
) {
    /** Stable per-employer scope for idempotency keys: survives a company rename. */
    val boardKey: String
        get() = listOfNotNull(
            board,
            tenant?.let { t -> site?.let { "$t/$it" } },
            host?.let { h -> site?.let { "$h/$it" } ?: h },   // Oracle (host + site), Eightfold (host)
            feed?.let { feedKey(it) },
        )
            .firstOrNull() ?: company.lowercase().replace(Regex("[^a-z0-9]+"), "-")

    private fun feedKey(url: String): String =
        url.substringAfter("://").substringBefore("?").substringBefore("/")
}

object EmployerCatalog {
    private val yaml = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(text: String): List<Employer> = yaml.readValue(text)

    /** Load [file] if given, else the bundled employers.yaml. */
    fun load(file: String): List<Employer> {
        val text = if (file.isNotBlank()) Files.readString(Path.of(file))
        else EmployerCatalog::class.java.getResource("/employers.yaml")?.readText()
            ?: error("bundled employers.yaml missing from the classpath")
        return parse(text)
    }
}
