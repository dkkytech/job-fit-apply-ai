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

        current = scrapeNode.process(current)
        current = saveNode.process(current)
        return current
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
