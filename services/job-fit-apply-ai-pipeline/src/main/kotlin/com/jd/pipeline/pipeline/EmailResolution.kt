package com.jd.pipeline.pipeline

import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest

/**
 * What to do with a raw email after ingestion (scan → scrape). Pure decision, no I/O —
 * the caller ([WorkerCommandHandler.resolveEmail]) performs the bridge writes. Extracted
 * so the digest / not-a-job / process branching is testable without a bridge or Gmail.
 */
sealed interface EmailDisposition {
    /** Ingestion (scan/scrape) failed — e.g. a transient LLM error. NOT a verdict on the email. */
    data class Error(val message: String) : EmailDisposition

    /** A job-board scrape exhausted its usable paths with a durable, URL-specific outcome. */
    data class ScrapeTerminal(val reason: ScrapeTerminalReason, val message: String) : EmailDisposition

    /** Digest: fan out — re-enqueue each job-posting child as its own JD_SCRAPED item. */
    data class ReEnqueueChildren(val children: List<JDState>) : EmailDisposition

    /** Not a job posting — complete the item with no further processing. */
    object SkipNotJob : EmailDisposition

    /** Interview / status mail about a role already in progress — label it and skip. */
    object SkipApplicationUpdate : EmailDisposition

    /** A single job posting — hand off to the ProcessingPipeline. */
    object Process : EmailDisposition
}

object EmailResolution {
    /**
     * Order matters. A terminal scrape outcome wins over its scrape error, but an arbitrary node
     * error is checked before normal routing: a node that failed (e.g. ScanEmailNode caught a
     * transient LLM 507 and returned `isJobPosting=false` WITH `error` set) must not be mistaken
     * for a clean "not a job posting" — that would label a real job `JD_Not_Found` and drop it from
     * the intake query forever. Mirrors [TerminalLabel.forState]'s error-first precedence.
     */
    fun classify(ingested: JDState): EmailDisposition {
        val scrapeTerminal = ScrapeOutcome.classify(ingested)
        return when {
            scrapeTerminal != null -> EmailDisposition.ScrapeTerminal(scrapeTerminal, ingested.error)
            ingested.error.isNotEmpty() -> EmailDisposition.Error(ingested.error)
            ingested.isDigest || ingested.isInlineDigest ->
                EmailDisposition.ReEnqueueChildren(ingested.digestJobs.filter { it.isJobPosting })
            ingested.isApplicationUpdate -> EmailDisposition.SkipApplicationUpdate
            !ingested.isJobPosting -> EmailDisposition.SkipNotJob
            else                   -> EmailDisposition.Process
        }
    }
}
