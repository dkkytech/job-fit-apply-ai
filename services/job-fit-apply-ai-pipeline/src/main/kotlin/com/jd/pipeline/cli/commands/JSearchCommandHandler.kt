package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.JSearchClient
import com.jd.pipeline.config.JSearchConfig
import com.jd.pipeline.models.JobListing
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.JdRecord

object JSearchCommandHandler {
    fun run() {
        println("[INFO] Fetching jobs via JSearch API...")

        val client = JSearchClient()
        val results: List<JobListing> = client.search(JSearchConfig.DEFAULT_LIST)

        val remoteCount   = results.count { it.jobIsRemote }
        val localCount    = results.size - remoteCount
        val employerCount = results.map { it.employerName }.toSet().size

        println("[INFO] Fetched ${results.size} unique job(s) — $remoteCount remote, $localCount local, $employerCount employer(s)")
        println()

        if (results.isEmpty()) {
            println("[WARN] No jobs returned from JSearch.")
            return
        }

        val bridge = BridgeClient()
        var submitted = 0
        var deduped   = 0
        var failed    = 0

        for (listing in results) {
            val location = listOfNotNull(listing.jobCity, listing.jobState)
                .joinToString(", ")
                .ifBlank { if (listing.jobIsRemote) "Remote" else "Unknown" }

            val record = JdRecord(
                jdText         = listing.jobDescription ?: "",
                company        = listing.employerName,
                roleTitle      = listing.jobTitle,
                location       = location,
                jobUrl         = listing.jobApplyLink,
                source         = IngestionSource.JSEARCH,
                idempotencyKey = listing.jobId,
            )

            if (record.jdText.length < 150) {
                println("  [SKIP] ${listing.jobTitle} @ ${listing.employerName} — jdText too short (${record.jdText.length} chars)")
                failed++
                continue
            }

            try {
                val jobId = bridge.submit(record)
                val status = bridge.getStatus(jobId)
                if (status.status == "pending") {
                    submitted++
                    println("  [QUEUED] $jobId — ${listing.jobTitle} @ ${listing.employerName}")
                } else {
                    deduped++
                    println("  [DEDUPED] $jobId — ${listing.jobTitle} @ ${listing.employerName}")
                }
            } catch (e: Exception) {
                System.err.println("  [ERROR] ${listing.jobTitle} @ ${listing.employerName}: ${e.message}")
                failed++
            }
        }

        println("\n[JSearch done] ${results.size} fetched — $submitted queued, $deduped deduped, $failed skipped/failed")
        println("[INFO] Worker will process queued jobs. Run --worker to drain the queue.")
    }
}
