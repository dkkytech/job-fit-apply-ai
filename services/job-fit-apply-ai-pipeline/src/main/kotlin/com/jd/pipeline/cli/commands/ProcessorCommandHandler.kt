package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.ClaimDto
import com.jd.pipeline.client.SigninServer
import com.jd.pipeline.client.WorkItemType
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.Heartbeat
import com.jd.pipeline.pipeline.EmailDisposition
import com.jd.pipeline.pipeline.EmailResolution
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.pipeline.TerminalLabel
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction

/**
 * The Processor loop (formerly `--worker`). Gmail-free: it claims work items from the bridge,
 * scans/scrapes raw emails, scores + tailors, and posts the result — including the write-back
 * fields ([ProcessingResult.terminalLabel], `draftText`, `messageId`) that the Poller applies
 * to Gmail. This service never touches Gmail or OAuth.
 */
object ProcessorCommandHandler {

    fun run(
        bridge: BridgeClient = BridgeClient(),
        pipeline: ProcessingPipeline = ProcessingPipeline(),
        ingestion: IngestionPipeline = IngestionPipeline(),
        heartbeat: Heartbeat = Heartbeat.fromConfig(Config.HEARTBEAT_FILE),
    ) {
        // Messaging (Discord/Telegram) is now a separate Notifier service that consumes the bridge's
        // completed-event stream. The Processor just posts results.
        println("[processor] Starting — polling ${System.getenv("JD_BRIDGE_URL") ?: "http://127.0.0.1:8765"}")
        startSigninServer()

        while (true) {
            // Liveness for the container healthcheck (`--health`). Beats only between jobs, so
            // the freshness window (HEALTH_MAX_AGE_MIN) must tolerate a long job.
            heartbeat.beat()

            val claimed = try {
                bridge.claim()
            } catch (e: Exception) {
                System.err.println("[processor] claim() failed: ${e.message} — retrying in 5s")
                Thread.sleep(5_000)
                continue
            }

            if (claimed == null) {
                Thread.sleep(2_000)
                continue
            }

            // Timed from the moment we own the claim so EVERY terminal outcome — including the
            // scan/scrape short-circuits below — gets a real durationMs in the run log.
            val jobStartedAt = System.currentTimeMillis()

            // Work-item branch: EMAIL_RAW is scanned/scraped here (digest children re-enqueued);
            // JD_PAGE_RAW (browser extension) is LLM-extracted from captured page text here;
            // JD_SCRAPED (JSearch / digest child) goes straight to processing. A branch that
            // terminates here (digest/non-job/error) is recorded to the run log before we `continue`,
            // so the analyzer never loses a completed job.
            val jdRecord: JdRecord = when (claimed.type) {
                WorkItemType.EMAIL_RAW -> when (val r = resolveEmail(claimed, bridge, ingestion)) {
                    is Resolution.Proceed  -> r.jdRecord
                    is Resolution.Terminal -> { recordTerminal(claimed.jobId, r, jobStartedAt); continue }
                }
                WorkItemType.JD_PAGE_RAW -> when (val r = resolvePageCapture(claimed, bridge, ingestion)) {
                    is Resolution.Proceed  -> r.jdRecord
                    is Resolution.Terminal -> { recordTerminal(claimed.jobId, r, jobStartedAt); continue }
                }
                else -> {
                    val rec = claimed.jdRecord
                    if (rec == null) {
                        // Must POST a result, not just `continue`: a bare skip leaves the bridge row
                        // CLAIMED, so the stale-claim sweep re-queues it and we spin on it forever.
                        System.err.println("[processor] claim ${claimed.jobId} (${claimed.type}) has no jd_record — failing it")
                        val terminal = postTerminal(
                            bridge, claimed, emptyLogRecord(IngestionSource.MANUAL),
                            skipResult("${claimed.type} claim has no jd_record", TerminalLabel.JD_ERROR),
                        )
                        recordTerminal(claimed.jobId, terminal, jobStartedAt)
                        continue
                    }
                    rec
                }
            }

            println("[processor] Processing job ${claimed.jobId} — ${jdRecord.roleTitle} @ ${jdRecord.company}")

            val result: ProcessingResult = try {
                pipeline.invoke(jdRecord)
            } catch (e: Exception) {
                System.err.println("[processor] pipeline threw for ${claimed.jobId}: ${e.message}")
                ProcessingResult(
                    pipelineAction = PipelineAction.SKIP.name,
                    fitScore       = 0,
                    strengths      = emptyList(),
                    isDuplicate    = false,
                    outputPath     = null,
                    hasCoverLetter = false,
                    error          = e.message,
                    // Carry identity so the completed-event (JD_Error) still shows what failed.
                    company        = jdRecord.company,
                    roleTitle      = jdRecord.roleTitle,
                    jobUrl         = jdRecord.jobUrl,
                    // …and how its JD was fetched, so a pipeline crash still tells the analyzer
                    // whether the browser backend was involved.
                    scrapePath     = jdRecord.scrapePath,
                )
            }

            try {
                val files = buildList {
                    result.outputPath?.let { dir ->
                        java.io.File(dir).listFiles { f -> f.extension == "pdf" }
                            ?.firstOrNull()
                            ?.let { add(it) }
                        val cl = java.io.File(dir, "cover_letter.txt")
                        if (cl.exists()) add(cl)
                    }
                }
                if (files.isNotEmpty()) bridge.uploadArtifacts(claimed.jobId, files)
                bridge.postResult(claimed.jobId, result, claimed.claimToken)
                println("[processor] Job ${claimed.jobId} complete — ${result.pipelineAction}, score=${result.fitScore}")
            } catch (e: Exception) {
                System.err.println("[processor] Failed to post result for ${claimed.jobId}: ${e.message}")
                runCatching {
                    bridge.postResult(claimed.jobId, result.copy(error = "Failed to post result: ${e.message}"), claimed.claimToken)
                }
            }

            // Durable structured record for the run analyzer (see tuner/run-analyzer).
            com.jd.pipeline.utils.RunReport.record(
                claimed.jobId, jdRecord, result, System.currentTimeMillis() - jobStartedAt,
            )
        }
    }

    /**
     * Bring up the tap-to-sign-in endpoint ([SigninServer]) alongside the loop, so a re-auth alert
     * can link to something that still works when the user taps it minutes or hours later.
     *
     * Gated on STEEL_SIGNIN_PUBLIC_URL: unset (the default) means the endpoint is neither advertised
     * in alerts nor started, so this is a no-op unless it has been deliberately configured. Failure
     * to bind is logged, not fatal — job processing is the Processor's actual job, and it must not
     * fail to start because a convenience port is taken.
     */
    private fun startSigninServer() {
        if (Config.STEEL_SIGNIN_PUBLIC_URL.isBlank()) return
        if (Config.STEEL_BASE_URL.isBlank()) {
            println("[processor] Sign-in endpoint not started — STEEL_BASE_URL is unset (Steel disabled).")
            return
        }
        runCatching { SigninServer().start() }
            .onFailure { System.err.println("[processor] Sign-in endpoint failed to start: ${it.message}") }
    }

    /**
     * Outcome of resolving a claim into something processable. [Proceed] carries the [JdRecord] to
     * run through [ProcessingPipeline]; [Terminal] means the claim is already done here (digest
     * fan-out, not-a-job, or an ingestion/extraction error) — the result has been posted and the
     * [jdRecord]/[result] are carried back so the loop can write the run-log line.
     */
    private sealed interface Resolution {
        data class Proceed(val jdRecord: JdRecord) : Resolution
        data class Terminal(val jdRecord: JdRecord, val result: ProcessingResult) : Resolution
    }

    /**
     * Append the run-log line for a terminal-at-resolve outcome (skip/error/digest). `pipelineRan
     * = false` marks it as never having reached [ProcessingPipeline] — the signal the run-analyzer
     * uses to tell a digest PARENT (fanned out here) from a digest CHILD that was really scored.
     */
    private fun recordTerminal(jobId: String, terminal: Resolution.Terminal, jobStartedAt: Long) {
        com.jd.pipeline.utils.RunReport.record(
            jobId, terminal.jdRecord, terminal.result, System.currentTimeMillis() - jobStartedAt,
            pipelineRan = false,
        )
    }

    /**
     * Post a terminal result and package it (with a record for the run log) as a [Resolution].
     * A failed post is logged, not thrown: the bridge being briefly unreachable must not take the
     * whole processor loop down, and the run-log line is still worth writing (the stale-claim sweep
     * re-queues the job, and [com.jd.pipeline.utils.RunReport] keeps the last line per job id).
     */
    private fun postTerminal(
        bridge: BridgeClient,
        claimed: ClaimDto,
        record: JdRecord,
        result: ProcessingResult,
    ): Resolution.Terminal {
        // The claim carries the fence: a terminal posted after this claim was requeued and
        // re-claimed is refused by the bridge rather than overwriting its replacement.
        runCatching { bridge.postResult(claimed.jobId, result, claimed.claimToken) }
            .onFailure { System.err.println("[processor] Failed to post terminal result for ${claimed.jobId}: ${it.message}") }
        return Resolution.Terminal(record, result)
    }

    /** What one digest fan-out achieved. [failed] is empty exactly when every child is accounted for. */
    private data class FanOutReport(
        val total: Int,
        val queued: List<String> = emptyList(),
        val deduped: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
    ) {
        override fun toString() =
            "$total child(ren): ${queued.size} queued, ${deduped.size} already present, ${failed.size} failed"
    }

    /**
     * Submit every discovered child, giving each a **stable** idempotency key derived from the
     * parent message. Without one, a retried digest re-queues children that already exist: the
     * bridge can only dedupe on job_url, and a child extracted from inline text has none.
     *
     * The key prefers the child's URL over its position, because a re-scan of the same digest can
     * legitimately return the children in a different order — an index-only key would then map the
     * same posting to a different identity and duplicate it.
     */
    private fun submitDigestChildren(
        children: List<JDState>,
        parentMessageId: String,
        bridge: BridgeClient,
        ingestion: IngestionPipeline,
    ): FanOutReport {
        val queued = mutableListOf<String>()
        val deduped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        children.forEachIndexed { index, child ->
            val identity = child.jobUrl.ifBlank { "#$index" }
            val key = "$parentMessageId|$identity"
            // Everything inside the try: an `onSuccess` block runs *outside* runCatching's
            // protection, so a throw while classifying the outcome would escape and kill the
            // processor loop — losing not just this child but the parent's terminal result.
            try {
                val outcome = bridge.submitDetailed(ingestion.toJdRecord(child, idempotencyKey = key))
                if (outcome.deduped) deduped += key else queued += key
            } catch (e: Exception) {
                System.err.println("[processor] digest child submit failed ($key): ${e.message}")
                failed += "$key: ${e.message}"
            }
        }
        return FanOutReport(children.size, queued, deduped, failed)
    }

    /**
     * A JdRecord built straight from ingestion [state] (NOT via the pipeline's toJdRecord) purely so
     * a terminal outcome carries enough identity — company/role/jobUrl/jd-length/intake — for the
     * run log. Reading state fields directly keeps this independent of any mapping mock.
     */
    private fun logRecordOf(state: JDState, source: IngestionSource): JdRecord = JdRecord(
        jdText     = state.jdText,
        company    = state.company.ifBlank { null },
        roleTitle  = state.roleTitle.ifBlank { null },
        location   = state.location.ifBlank { null },
        jobUrl     = state.jobUrl.ifBlank { null },
        source     = source,
        intakeMeta = state.intake,
    )

    /** A bare JdRecord for a terminal claim we couldn't resolve at all (missing payload). */
    private fun emptyLogRecord(source: IngestionSource): JdRecord = JdRecord(
        jdText = "", company = null, roleTitle = null, location = null, jobUrl = null, source = source,
    )

    /**
     * Scan/scrape a claimed raw email into a [Resolution]. [Resolution.Terminal] when the item ends
     * here — digest (children re-enqueued as JD_SCRAPED), not-a-job, or an ingestion error — in which
     * case the bridge job has been completed via postResult and the run-log line is written by the
     * caller. [Resolution.Proceed] carries the record to run through [ProcessingPipeline].
     */
    private fun resolveEmail(claimed: ClaimDto, bridge: BridgeClient, ingestion: IngestionPipeline): Resolution {
        val email = claimed.email
        if (email == null) {
            return postTerminal(
                bridge, claimed, emptyLogRecord(IngestionSource.EMAIL),
                skipResult("EMAIL_RAW claim missing email payload", TerminalLabel.JD_ERROR),
            )
        }
        val emailState = JDState(
            intake = IntakeContext.Email(
                emailId        = email.messageId,
                subject        = email.subject,
                from           = email.from,
                rawBody        = email.body,
                htmlBody       = email.htmlBody ?: "",
                isRecruiter    = email.isRecruiterHint,
                isDigest       = false,
                isInlineDigest = false,
            ),
        )
        val ingState = try {
            ingestion.invoke(emailState)   // scan → digest fan-out → scrape
        } catch (e: Exception) {
            System.err.println("[processor] ingestion failed for ${claimed.jobId}: ${e.message}")
            return postTerminal(
                bridge, claimed, logRecordOf(emailState, IngestionSource.EMAIL),
                skipResult("ingestion: ${e.message}", TerminalLabel.JD_ERROR),
            )
        }

        return when (val disposition = EmailResolution.classify(ingState)) {
            is EmailDisposition.Error -> {
                // Scan/scrape FAILED (e.g. a transient LLM 507) — not a verdict that this isn't a
                // job. Label JD_Error, never JD_Not_Found: mislabeling a real recruiter email as
                // "not found" silently drops it (the intake query excludes JD_Not_Found) and the
                // user has no signal it needs a retry.
                System.err.println("[processor] ingestion error for ${claimed.jobId}: ${disposition.message}")
                postTerminal(
                    bridge, claimed, logRecordOf(ingState, IngestionSource.EMAIL),
                    skipResult(disposition.message, TerminalLabel.JD_ERROR, ingState.scrapePath),
                )
            }
            is EmailDisposition.ReEnqueueChildren -> {
                val fanOut = submitDigestChildren(disposition.children, email.messageId, bridge, ingestion)
                println("[processor] digest ${claimed.jobId} fan-out: $fanOut")
                if (fanOut.failed.isEmpty()) {
                    // parent digest complete → archive
                    postTerminal(
                        bridge, claimed, logRecordOf(ingState, IngestionSource.EMAIL),
                        skipResult(null, TerminalLabel.JD_PROCESSED_DIGEST, ingState.scrapePath),
                    )
                } else {
                    // A digest whose children did not all land is NOT "processed". Reporting
                    // success here is how siblings get silently lost: the parent is archived, the
                    // failed children exist nowhere, and nothing is left to retry from. JD_Error
                    // keeps it visible and retryable — and because children carry stable
                    // idempotency keys, the retry re-submits only what is missing.
                    postTerminal(
                        bridge, claimed, logRecordOf(ingState, IngestionSource.EMAIL),
                        skipResult(
                            "digest fan-out incomplete: ${fanOut.failed.size} of ${fanOut.total} children " +
                                "failed to enqueue (${fanOut.failed.joinToString("; ")})",
                            TerminalLabel.JD_ERROR,
                            ingState.scrapePath,
                        ),
                    )
                }
            }
            EmailDisposition.SkipNotJob ->
                // not a job posting
                postTerminal(
                    bridge, claimed, logRecordOf(ingState, IngestionSource.EMAIL),
                    skipResult(null, TerminalLabel.JD_NOT_FOUND, ingState.scrapePath),
                )
            EmailDisposition.Process ->
                Resolution.Proceed(ingestion.toJdRecord(ingState, idempotencyKey = email.messageId))
        }
    }

    /**
     * LLM-extract a JD from a claimed raw page capture into a [JdRecord] to process. The page was
     * rendered in the user's authenticated browser, so [ScrapeJdNode] skips fetching and extracts
     * straight from the captured text. Returns null when the page isn't a job posting or extraction
     * fails — in which case the bridge job has already been completed via postResult (a SKIP) and
     * the caller writes the run-log line from the returned [Resolution.Terminal].
     */
    private fun resolvePageCapture(claimed: ClaimDto, bridge: BridgeClient, ingestion: IngestionPipeline): Resolution {
        val cap = claimed.pageCapture
        if (cap == null) {
            return postTerminal(
                bridge, claimed, emptyLogRecord(IngestionSource.EXTENSION),
                skipResult("JD_PAGE_RAW claim missing page payload"),
            )
        }
        val state = JDState(
            intake       = IntakeContext.WebCapture(url = cap.url, title = cap.title),
            jobUrl       = cap.url,
            capturedText = cap.text,
        )
        val extracted = try {
            ingestion.scrapeNode.process(state)   // dual-mode: extracts from capturedText, no fetch
        } catch (e: Exception) {
            System.err.println("[processor] page extraction failed for ${claimed.jobId}: ${e.message}")
            return postTerminal(
                bridge, claimed, logRecordOf(state, IngestionSource.EXTENSION),
                skipResult("extraction: ${e.message}"),
            )
        }

        // The scrape prompt has no explicit is-job flag, so gate on the load-bearing jd_text:
        // if the LLM couldn't extract a usable JD, treat the page as "not a job posting".
        if (extracted.error.isNotBlank() || extracted.jdText.length < 150) {
            return postTerminal(
                bridge, claimed, logRecordOf(extracted, IngestionSource.EXTENSION),
                skipResult(
                    "This page doesn't look like a job posting (no JD could be extracted)",
                    scrapePath = extracted.scrapePath,
                ),
            )
        }

        return Resolution.Proceed(
            JdRecord(
                jdText         = extracted.jdText,
                company        = extracted.company.ifBlank { null },
                roleTitle      = extracted.roleTitle.ifBlank { null },
                location       = extracted.location.ifBlank { null },
                jobUrl         = extracted.jobUrl.ifBlank { null },
                source         = IngestionSource.EXTENSION,
                idempotencyKey = cap.url,
                intakeMeta     = extracted.intake,
                // Rendered in the user's own browser, so this is the "captured" path — recording it
                // keeps extension traffic distinguishable from a backend scrape in the run_log.
                scrapePath     = extracted.scrapePath,
            ),
        )
    }

    /**
     * A terminal SKIP result for an item that never reaches [ProcessingPipeline]. [terminalLabel]
     * is the Gmail label the Poller must apply (mirrors [TerminalLabel.forState] for the states we
     * short-circuit past): without it the email carries no terminal label, so the Poller can't move
     * it out of the intake query and it loops — re-fetched, re-submitted (deduped to this already
     * written-back job) and re-labeled JD_Processing forever. The Poller reads message_id from the
     * bridge row, which preserves the enqueue-time value, so this result need not repeat it.
     */
    private fun skipResult(
        error: String?,
        terminalLabel: String? = null,
        scrapePath: String = "",
    ): ProcessingResult = ProcessingResult(
        pipelineAction = PipelineAction.SKIP.name,
        fitScore       = 0,
        strengths      = emptyList(),
        isDuplicate    = false,
        outputPath     = null,
        hasCoverLetter = false,
        error          = error,
        terminalLabel  = terminalLabel,
        // These items never reach ProcessingPipeline, so nothing else can record how their JD text
        // was fetched. A scrape that FAILED terminates right here — exactly the case the analyzer
        // most needs — so an empty scrapePath would hide browser-backend problems entirely.
        scrapePath     = scrapePath,
    )
}
