package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.Node
import com.jd.pipeline.nodes.ScanEmailNode
import com.jd.pipeline.nodes.ScrapeJdNode
import com.jd.pipeline.nodes.SaveJobDescriptionNode
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest

/**
 * Ingestion half of the pipeline: scan → scrape → save.
 *
 * Handles email-sourced states. JSearch/Synthetic states should go directly
 * to ProcessingPipeline (they already have full JD text from the API).
 */
class IngestionPipeline {

    private var scanNode: Node<JDState> = ScanEmailNode()
    var scrapeNode = ScrapeJdNode()
    private var saveNode: Node<JDState> = SaveJobDescriptionNode()

    /**
     * Run the ingestion nodes on an email-sourced state.
     * For digest emails each child is scraped and saved individually.
     * Returns the updated state (with digestJobs populated for digests).
     */
    fun invoke(state: JDState): JDState {
        var current = scanNode.process(state)

        if (current.isDigest) {
            val digestJobs = current.digestJobs
            if (digestJobs.isNotEmpty()) {
                val processed = digestJobs
                    .filter { it.isJobPosting }
                    .map { child ->
                        // A link-only child (employer alert) is scraped later, at its own claim,
                        // and only if the bridge doesn't already have the posting.
                        if (child.submitAsUrl) return@map child
                        var c = scrapeNode.process(child)
                        c = saveNode.process(c)
                        c
                    }
                return current.copy(digestJobs = processed)
            }
            return current
        }

        if (!current.isJobPosting) {
            return saveNode.process(current)
        }

        current = withEmailJdFallback(current, scrapeNode.process(current))
        current = saveNode.process(current)
        return current
    }

    /**
     * A recruiter email that already carries the JD only links a page to enrich it, so a failed
     * scrape of that link (Steel down, a sign-in wall, a mail-merge "respond" page) must not fail
     * the email. Before this, every such failure became JD_Error even with a full JD in hand.
     * Below [MIN_EMAIL_JD_CHARS] the email holds a blurb, not a JD, and the scrape error stands.
     */
    internal fun withEmailJdFallback(beforeScrape: JDState, scraped: JDState): JDState {
        if (scraped.error.isEmpty() || beforeScrape.jdText.length < MIN_EMAIL_JD_CHARS) return scraped
        println(
            "[ingestion] Scrape of ${beforeScrape.jobUrl} failed (${scraped.error.lineSequence().first().take(160)}) " +
                "— using the JD from the email body (${beforeScrape.jdText.length} chars)",
        )
        // A job_url the scan could not make a URL of ("N/A", prose) would still classify the email
        // MALFORMED_URL (JD_Scrape_Failed) after the error is cleared; it carries nothing to keep.
        return scraped.copy(error = "", jobUrl = scraped.jobUrl.takeIf { ScrapeOutcome.isHttpUrl(it) } ?: "")
    }

    companion object {
        /** The JD text an email body must already hold for a failed scrape to fall back to it. */
        const val MIN_EMAIL_JD_CHARS = 500
    }

    /**
     * Map an ingested JDState to a JdRecord for queue submission.
     * [state] must have gone through [invoke] first.
     */
    fun toJdRecord(state: JDState, idempotencyKey: String? = null): JdRecord = JdRecord(
        jdText       = state.jdText,
        company      = state.company.ifBlank { null },
        roleTitle    = state.roleTitle.ifBlank { null },
        location     = state.location.ifBlank { null },
        jobUrl       = state.jobUrl.ifBlank { null },
        source       = IngestionSource.EMAIL,
        idempotencyKey = idempotencyKey,
        intakeMeta   = state.intake,
        salaryRange    = state.salaryRange.ifBlank { null },
        remotePolicy   = state.remotePolicy.ifBlank { null },
        employmentType = state.employmentType.ifBlank { null },
        seniorityLevel = state.seniorityLevel.ifBlank { null },
        yoeRequired    = state.yoeRequired,
        techStack      = state.techStack.ifEmpty { null },
        // Scraping happens here, in ingestion; the run_log is written from the processing result.
        // Dropping this was why every job logged an empty scrapePath.
        scrapePath     = state.scrapePath,
    )

    fun resetBatch() = scrapeNode.resetBatch()
    fun batchBlockedDomains(): Set<String> = scrapeNode.batchBlockedDomains.toSet()
    fun batchLinkedInSessionExpired(): Boolean = scrapeNode.batchLinkedInSessionExpired

    /** Release the scraper's shared CDP browser connection. Call when the batch is done. */
    fun close() = scrapeNode.close()
}
