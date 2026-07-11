package com.jdbridge

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.jdbridge.Routes")

private suspend fun enqueueOne(body: SubmitJobRequest): SubmitJobResponse {
    val existing = findActiveDuplicate(body.job_url, body.idempotency_key)
    if (existing != null) {
        log.info("Dedup hit for ${body.job_url ?: body.idempotency_key} → $existing")
        return SubmitJobResponse(job_id = existing, status = JobStatus.PENDING.value, deduped = true)
    }
    val jobId = enqueue(Json.encodeToString(body), body.job_url, body.idempotency_key)
    log.info("Job enqueued: $jobId (${body.role_title} @ ${body.company})")
    return SubmitJobResponse(job_id = jobId, status = JobStatus.PENDING.value)
}

fun Routing.configureRoutes() {

    // ── Health ────────────────────────────────────────────────────────────────

    get("/health") {
        call.respond(mapOf("status" to "ok", "service" to "jd-bridge"))
    }

    // ── Submit job (single) ───────────────────────────────────────────────────

    post("/api/jobs") {
        val body = call.receive<SubmitJobRequest>()

        if (body.jd_text.length < 150) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse("jd_text must be at least 150 characters"),
            )
            return@post
        }

        val response = enqueueOne(body)
        call.respond(if (response.deduped) HttpStatusCode.OK else HttpStatusCode.Accepted, response)
    }

    // ── Submit job (batch) ────────────────────────────────────────────────────

    post("/api/jobs/batch") {
        val bodies = call.receive<List<SubmitJobRequest>>()

        val invalid = bodies.firstOrNull { it.jd_text.length < 150 }
        if (invalid != null) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse("jd_text must be at least 150 characters"),
            )
            return@post
        }

        val responses = bodies.map { enqueueOne(it) }
        call.respond(HttpStatusCode.Accepted, responses)
    }

    // ── Job status ────────────────────────────────────────────────────────────

    get("/api/jobs/{job_id}") {
        val jobId = call.parameters["job_id"]!!
        val row = getJob(jobId)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Job not found"))
                return@get
            }

        val artifacts = if (row.status == JobStatus.DONE.value) row.artifacts else null

        call.respond(
            JobStatusResponse(
                job_id          = jobId,
                status          = row.status,
                fit_score       = row.fitScore,
                pipeline_action = row.pipelineAction,
                artifacts       = artifacts,
                error           = row.error,
            )
        )
    }

    // ── Worker queue endpoints ────────────────────────────────────────────────

    get("/api/queue/claim") {
        val claimed = claimNext()
        if (claimed == null) {
            call.respond(HttpStatusCode.NoContent)
            return@get
        }

        val jdRecordElement = runCatching {
            Json.parseToJsonElement(claimed.jdJson).jsonObject
        }.getOrElse {
            log.error("[${claimed.id}] Could not parse jd_json: ${it.message}")
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Corrupt jd_json for job ${claimed.id}"))
            return@get
        }

        log.info("Claimed job: ${claimed.id}")
        call.respond(ClaimResponse(job_id = claimed.id, jd_record = jdRecordElement))
    }

    post("/api/jobs/{job_id}/result") {
        val jobId = call.parameters["job_id"]!!
        val req = call.receive<ResultRequest>()
        recordResult(jobId, req)
        log.info("Result recorded for $jobId: ${req.pipeline_action}, score=${req.fit_score}, error=${req.error}")
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }

    post("/api/jobs/{job_id}/artifacts") {
        val jobId = call.parameters["job_id"]!!
        val dir = jobDir(jobId).toFile()

        var resumeUrl: String? = null
        var coverUrl: String? = null

        val multipart = call.receiveMultipart()
        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                val name = part.originalFileName ?: part.name ?: ""
                val target = when {
                    name.endsWith(".pdf")          -> dir.resolve("resume.pdf").also { resumeUrl = "/api/jobs/$jobId/resume.pdf" }
                    name == "cover_letter.txt"     -> dir.resolve("cover_letter.txt").also { coverUrl = "/api/jobs/$jobId/cover_letter.txt" }
                    else                           -> null
                }
                target?.writeBytes(part.streamProvider().readBytes())
            }
            part.dispose()
        }

        if (resumeUrl != null || coverUrl != null) {
            setArtifacts(jobId, ArtifactUrls(
                resume_pdf       = resumeUrl ?: "",
                cover_letter_txt = coverUrl ?: "",
            ))
        }

        log.info("Artifacts uploaded for $jobId — pdf=$resumeUrl, cl=$coverUrl")
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }

    // ── Artifact downloads ────────────────────────────────────────────────────

    get("/api/jobs/{job_id}/resume.pdf") {
        val jobId = call.parameters["job_id"]!!
        val file = STORE_DIR.resolve("jobs/$jobId/resume.pdf").toFile()
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("resume.pdf not found for job $jobId"))
            return@get
        }
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"resume.pdf\"")
        call.respondFile(file)
    }

    get("/api/jobs/{job_id}/cover_letter.txt") {
        val jobId = call.parameters["job_id"]!!
        val file = STORE_DIR.resolve("jobs/$jobId/cover_letter.txt").toFile()
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("cover_letter.txt not found for job $jobId"))
            return@get
        }
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"cover_letter.txt\"")
        call.respondFile(file)
    }
}
