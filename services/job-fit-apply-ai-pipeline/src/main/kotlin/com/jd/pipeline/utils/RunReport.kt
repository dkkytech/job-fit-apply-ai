package com.jd.pipeline.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.config.Config
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Appends one JSON line per processed job to `output/runs/run_log.jsonl`.
 *
 * Purpose: turn the worker's ephemeral stdout/stderr into a durable, structured,
 * analyzable record so a post-run LLM (see `tuner/run-analyzer`) can reason over
 * bounded structured signal instead of re-parsing logs every time. The file is
 * append-only and timestamped, so an analyzer reads just the slice for a given
 * run window and can diff metrics across runs (regression detection).
 *
 * Best-effort: any failure here is swallowed so it can never break the worker loop.
 */
object RunReport {
    private val mapper = ObjectMapper().registerKotlinModule()

    private val defaultPath: Path by lazy { Config.OUTPUT_DIR.resolve("runs").resolve("run_log.jsonl") }

    /**
     * Test seam: redirect the log to a temp file so tests asserting a line was written don't append
     * to the real run log (which the analyzer reads). Null — the production value — uses [defaultPath].
     */
    internal var pathOverride: Path? = null

    private val path: Path get() = pathOverride ?: defaultPath

    /**
     * One processed-job record. Field choices are deliberate: `jdTextLen` exposes
     * thin-JD problems (e.g. a digest whose scrape was blocked, so it scored on a
     * summary), `error` carries the captured failure string, and `board`/`source`
     * let the analyzer group failures by job board.
     */
    data class JobRecord(
        val ts: String,
        val jobId: String,
        val company: String,
        val roleTitle: String,
        val source: String,
        val board: String,
        val isDigest: Boolean,
        val isRecruiter: Boolean,
        val action: String,
        val score: Int,
        val isDuplicate: Boolean,
        val error: String?,
        val jdTextLen: Int,
        val hasJobUrl: Boolean,
        val outputPath: String?,
        val durationMs: Long,
        // How the JD text was obtained ("http" | "cdp_profile" | "cdp_forced" | "cdp_fallback" |
        // "captured" | "blocked" | "empty" | ""). Lets the analyzer see the HTTP-vs-browser split
        // and confirm the browser path (Steel/CDP) is actually carrying LinkedIn/forced domains.
        val scrapePath: String,
        // Gmail terminal-label decision ("JD_Processed" | "JD_Processed_Digest" | "JD_Error" |
        // "JD_Not_Found" | "Recruiter_Response_Required" | null). NOTE this does NOT separate a
        // digest parent from a digest child — a child inherits the parent's `isDigest` intake, and
        // TerminalLabel.forState checks isDigest before everything else, so both read
        // JD_Processed_Digest. Use [pipelineRan] for that.
        val terminalLabel: String?,
        // Whether the job ran through ProcessingPipeline (scored/tailored) or went terminal during
        // the processor's scan/scrape resolve. This is what separates a digest PARENT — which only
        // fans its children out into their own jobs and is never scored, so its thin jdText is
        // meaningless — from a digest CHILD, which is a real posting that did reach score_fit.
        val pipelineRan: Boolean,
    )

    fun record(
        jobId: String,
        record: JdRecord,
        result: ProcessingResult,
        durationMs: Long,
        pipelineRan: Boolean = true,
    ) {
        try {
            val email = record.intakeMeta as? IntakeContext.Email
            val rec = JobRecord(
                ts = Instant.now().toString(),
                jobId = jobId,
                company = record.company ?: "",
                roleTitle = record.roleTitle ?: "",
                source = record.source.name,
                board = boardOf(email?.from, record.jobUrl),
                isDigest = email?.isDigest ?: false,
                isRecruiter = email?.isRecruiter ?: false,
                action = result.pipelineAction,
                score = result.fitScore,
                isDuplicate = result.isDuplicate,
                error = result.error,
                jdTextLen = record.jdText.length,
                hasJobUrl = !record.jobUrl.isNullOrBlank(),
                outputPath = result.outputPath,
                durationMs = durationMs,
                scrapePath = result.scrapePath,
                terminalLabel = result.terminalLabel,
                pipelineRan = pipelineRan,
            )
            Files.createDirectories(path.parent)
            Files.writeString(
                path, mapper.writeValueAsString(rec) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            System.err.println("[run_report] WARN: failed to append run record: ${e.message}")
        }
    }

    /** Best-effort board label from the sender domain, falling back to the job-URL host. */
    private fun boardOf(from: String?, jobUrl: String?): String {
        val sender = from?.substringAfter('@', "")?.substringBefore('>')?.trim()?.lowercase().orEmpty()
        val host = sender.ifBlank {
            runCatching { URI.create(jobUrl ?: "").host }.getOrNull()?.lowercase().orEmpty()
        }
        if (host.isBlank()) return "unknown"
        // Collapse "noreply.glassdoor.com" / "www.glassdoor.com" → "glassdoor.com".
        val parts = host.removePrefix("www.").split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }
}
