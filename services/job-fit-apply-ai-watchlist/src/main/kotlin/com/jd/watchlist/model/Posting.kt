package com.jd.watchlist.model

/**
 * One job posting as listed by an employer's feed. [description] is filled when the listing call
 * already carries it (Greenhouse, Lever, Ashby, most RSS); otherwise the adapter fetches it on
 * demand via [detailRef], and only for postings that passed the title/location pre-filter.
 */
data class Posting(
    val company: String,
    /** The employer's own posting/requisition id — stable across runs. */
    val externalId: String,
    val title: String,
    val location: String,
    val url: String,
    val description: String? = null,
    /** Adapter-private handle for the detail fetch (e.g. Workday's externalPath). */
    val detailRef: String? = null,
)
