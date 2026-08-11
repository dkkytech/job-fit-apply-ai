package com.jdbridge

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

// ── Store path management ─────────────────────────────────────────────────────

/** Create a fresh temp directory and wire it as the store root. Call in @BeforeEach. */
fun useTempStoreDir(): Path {
    val dir = Files.createTempDirectory("jd-bridge-test-")
    STORE_DIR = dir
    return dir
}

// ── Artifact fixtures ─────────────────────────────────────────────────────────

fun fakePdf(): ByteArray =
    "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\nxref\n0 2\n%%EOF".toByteArray()

fun fakeCoverLetter(): String =
    "Dear Hiring Manager,\n\nThank you for the opportunity."

fun sampleJdText(): String =
    "Staff SDET Opportunity – Mobile Platform\n\n" +
    "We have a Staff SDET opening on our Mobile Platform team. Responsibilities include " +
    "owning iOS and Android BVT automation frameworks, building and maintaining CI/CD " +
    "pipelines on Bitrise and GitHub Actions, and driving test infrastructure strategy " +
    "across mobile teams. Requirements: 6+ years in mobile test automation, deep " +
    "experience with XCUITest and Espresso, CI/CD pipeline ownership (Bitrise preferred), " +
    "Kotlin or Swift fluency. Total compensation: \$220K–\$280K."

fun minimalJdText(): String = "x".repeat(150)

fun defaultJdJson(
    jdText: String = minimalJdText(),
    roleTitle: String? = "Staff SDET",
    company: String? = "Acme Corp",
    location: String? = "Seattle, WA",
    jobUrl: String? = null,
): String = Json.encodeToString(SubmitJobRequest(
    jd_text    = jdText,
    role_title = roleTitle,
    company    = company,
    location   = location,
    job_url    = jobUrl,
))

// ── Queue manipulation helpers ────────────────────────────────────────────────

/**
 * Enqueue a job, claim it, then immediately record a result — simulates a worker
 * completing a job synchronously for test purposes.
 */
suspend fun enqueueAndComplete(
    jdJson: String = defaultJdJson(),
    fitScore: Int = 82,
    pipelineAction: String = "TAILOR",
    includeCoverLetter: Boolean = true,
    error: String? = null,
): String {
    val jobId = enqueue(jdJson, null, null)
    claimNext() // claim the job we just enqueued
    val result = ResultRequest(
        pipeline_action = pipelineAction,
        fit_score       = fitScore,
        error           = error,
    )
    recordResult(jobId, result)
    if (error == null) {
        val dir = jobDir(jobId)
        dir.resolve("resume.pdf").toFile().writeBytes(fakePdf())
        if (includeCoverLetter) dir.resolve("cover_letter.txt").toFile().writeText(fakeCoverLetter())
        setArtifacts(jobId, ArtifactUrls(
            resume_pdf       = "/api/jobs/$jobId/resume.pdf",
            cover_letter_txt = if (includeCoverLetter) "/api/jobs/$jobId/cover_letter.txt" else "",
        ))
    }
    return jobId
}

// ── DB init helper ────────────────────────────────────────────────────────────

fun initTestDb() = runBlocking { initDb() }

/**
 * Age a claim past the stale window so the next `claimNext()` requeues it.
 *
 * Epoch rather than "now minus the window": the window is configurable
 * (`JD_BRIDGE_STALE_CLAIM_SECONDS`), and a test that hard-codes the default silently stops
 * exercising the requeue the moment someone tunes it.
 */
fun expireClaim(jobId: String) = transaction {
    Jobs.update({ Jobs.id eq jobId }) { it[Jobs.claimedAt] = 0L }
}
