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
    val existing = findActiveDuplicate(body.job_url, body.idempotency_key, body.source)
    if (existing != null) {
        log.info("Dedup hit for ${body.job_url ?: body.idempotency_key} → $existing")
        return SubmitJobResponse(job_id = existing, status = JobStatus.PENDING.value, deduped = true)
    }
    val jobId = enqueue(Json.encodeToString(body), body.job_url, body.idempotency_key)
    log.info("Job enqueued: $jobId (${body.role_title} @ ${body.company})")
    return SubmitJobResponse(job_id = jobId, status = JobStatus.PENDING.value)
}

private fun JobRow.out(tailorJobId: String? = null) = DeferredJobOut(
    job_id = id, company = company, role_title = roleTitle, fit_score = fitScore,
    artifact_url = artifactUrl, job_url = jobUrl, tailor_job_id = tailorJobId,
)

/** An absolute http(s) URL with a host — the only kind the Processor's scraper can fetch. */
internal fun isHttpUrl(raw: String): Boolean = runCatching {
    val u = java.net.URI(raw.trim())
    (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.host.isNullOrBlank()
}.getOrDefault(false)

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

    // ── Submit raw email (Poller → Processor scans/scrapes it) ─────────────────

    post("/api/emails") {
        val body = call.receive<SubmitEmailRequest>()
        if (body.body.isBlank()) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("email body must not be empty"))
            return@post
        }
        val key = body.idempotency_key ?: body.message_id
        val existing = findActiveDuplicate(null, key)
        if (existing != null) {
            call.respond(HttpStatusCode.OK, SubmitJobResponse(existing, JobStatus.PENDING.value, deduped = true))
            return@post
        }
        val jobId = enqueue(
            jdJson         = Json.encodeToString(body),
            jobUrl         = null,
            idempotencyKey = key,
            type           = WorkItemType.EMAIL_RAW,
            messageId      = body.message_id,
        )
        log.info("Email enqueued: $jobId (${body.subject})")
        call.respond(HttpStatusCode.Accepted, SubmitJobResponse(jobId, JobStatus.PENDING.value))
    }

    // ── Submit raw page capture (browser extension → Processor LLM-extracts it) ─

    post("/api/pages") {
        val body = call.receive<SubmitPageCaptureRequest>()
        if (body.text.length < 200) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse("captured page text must be at least 200 characters"),
            )
            return@post
        }
        val key = body.idempotency_key ?: body.url
        val existing = findActiveDuplicate(body.url, key)
        if (existing != null) {
            call.respond(HttpStatusCode.OK, SubmitJobResponse(existing, JobStatus.PENDING.value, deduped = true))
            return@post
        }
        val jobId = enqueue(
            jdJson         = Json.encodeToString(body),
            jobUrl         = body.url,
            idempotencyKey = key,
            type           = WorkItemType.JD_PAGE_RAW,
        )
        log.info("Page capture enqueued: $jobId (${body.title.ifBlank { body.url }})")
        call.respond(HttpStatusCode.Accepted, SubmitJobResponse(jobId, JobStatus.PENDING.value))
    }

    // ── Submit a posting URL (alert-email rules, HTML boards → Processor scrapes it) ─

    post("/api/urls") {
        val body = call.receive<SubmitUrlRequest>()
        if (!isHttpUrl(body.url)) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("url must be an absolute http(s) URL"))
            return@post
        }
        val key = body.idempotency_key ?: body.url
        val existing = findActiveDuplicate(body.url, key, body.source)
        if (existing != null) {
            log.info("Dedup hit for ${body.url} → $existing")
            call.respond(HttpStatusCode.OK, SubmitJobResponse(existing, JobStatus.PENDING.value, deduped = true))
            return@post
        }
        val jobId = enqueue(
            jdJson         = Json.encodeToString(body),
            jobUrl         = body.url,
            idempotencyKey = key,
            type           = WorkItemType.JD_URL,
        )
        log.info("URL enqueued: $jobId (${body.role_title ?: body.url})")
        call.respond(HttpStatusCode.Accepted, SubmitJobResponse(jobId, JobStatus.PENDING.value))
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
                // Posting identity + track link arrive with the worker's result; null until then.
                title           = row.roleTitle,
                company         = row.company,
                fit_score       = row.fitScore,
                pipeline_action = row.pipelineAction,
                artifacts       = artifacts,
                error           = row.error,
                track_id        = row.trackId,
            )
        )
    }

    // ── On-demand tailoring ───────────────────────────────────────────────────

    /**
     * A Tailor tap on an OFFERed job: queue the record its OFFER handed back as a TAILOR_REQUEST.
     * Once per job — a second tap answers with the first request (unless that one errored) — and
     * refused (409) for a job that was not offered, or has not finished.
     */
    post("/api/jobs/{job_id}/tailor") {
        val jobId = call.parameters["job_id"]!!
        val row = getJob(jobId) ?: run {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Job not found"))
            return@post
        }
        val record = row.tailorRecord?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        if (row.status != JobStatus.DONE.value || record == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Job $jobId was not offered for tailoring (status=${row.status}, action=${row.pipelineAction})"),
            )
            return@post
        }
        val queued = queueTailor(row, record)
        call.respond(if (queued.deduped) HttpStatusCode.OK else HttpStatusCode.Accepted, queued)
    }

    /**
     * The processor's sweep for DEFERRED tailoring (jobs that would have been tailored while the
     * cloud quota was out): expire the ones older than max_age_days, then queue the best fits as
     * TAILOR_REQUESTs until `limit` are in flight — so a backlog is worked off a few at a time and
     * new intake never waits behind all of it.
     */
    post("/api/tailoring/release-deferred") {
        val body = call.receive<ReleaseDeferredRequest>()
        val cutoff = System.currentTimeMillis() / 1000L - body.max_age_days.coerceAtLeast(0) * 86_400L
        val expired = expireDeferred(cutoff)
        val slots = (body.limit - tailorRequestsInFlight()).toInt().coerceAtLeast(0)
        val released = deferredBestFirst(slots).mapNotNull { row ->
            val record = row.tailorRecord?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                ?: return@mapNotNull null.also { setTailorState(row.id, TailorState.EXPIRED) }
            val queued = queueTailor(row, record)
            row.out(tailorJobId = queued.job_id)
        }
        if (released.isNotEmpty() || expired.isNotEmpty()) {
            log.info("Deferred tailoring: released ${released.size}, expired ${expired.size}")
        }
        call.respond(ReleaseDeferredResponse(released, expired.map { it.out() }, countDeferred()))
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

        log.info("Claimed job: ${claimed.id} (${claimed.type})")
        call.respond(
            ClaimResponse(
                job_id = claimed.id,
                type = claimed.type,
                jd_record = jdRecordElement,
                claim_token = claimed.claimToken,
            ),
        )
    }

    post("/api/jobs/{job_id}/result") {
        val jobId = call.parameters["job_id"]!!
        val req = call.receive<ResultRequest>()
        when (recordResult(jobId, req)) {
            ResultOutcome.RECORDED -> {
                log.info("Result recorded for $jobId: ${req.pipeline_action}, score=${req.fit_score}, error=${req.error}")
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
            ResultOutcome.REQUEUED -> {
                log.info("Retryable result requeued for $jobId: ${req.error}")
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "requeued"))
            }
            // 200, not an error: a duplicate POST is a worker retrying a lost response, and the
            // desired end state (one terminal result, one completed event) already holds.
            ResultOutcome.ALREADY_TERMINAL ->
                call.respond(HttpStatusCode.OK, mapOf("status" to "already_recorded"))
            // 409: this worker was displaced. Loud, so a stale worker's logs say why.
            ResultOutcome.STALE_CLAIM ->
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("stale claim token for $jobId — the claim was requeued and re-claimed"),
                )
        }
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

    // ── Gmail write-back feed (Poller drains completed jobs) ───────────────────

    get("/api/jobs/completed") {
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        // all=true → full event stream (ignores writeback_done) for cursor consumers like the Notifier.
        val all = call.request.queryParameters["all"]?.toBooleanStrictOrNull() ?: false
        call.respond(completedJobs(since, limit, all))
    }

    // Current max completed_seq — cursor consumers seed here on cold start to skip history.
    get("/api/jobs/completed/head") {
        call.respond(mapOf("max_seq" to latestCompletedSeq()))
    }

    post("/api/jobs/{job_id}/writeback-done") {
        val jobId = call.parameters["job_id"]!!
        if (markWritebackDone(jobId)) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Job not found"))
        }
    }

    // ── Tracks (backlog UI data — Postgres `tracks` table) ─────────────────────

    get("/api/tracks") {
        call.respond(TracksStore.list())
    }

    post("/api/tracks/{id}/status") {
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid track id"))

        val req = call.receive<TrackStatusUpdate>()
        if (req.status !in TracksStore.ALLOWED_STATUSES) {
            return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Invalid status: ${req.status}"))
        }
        TracksStore.sourceError(req.source)?.let {
            return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(it))
        }

        // Also appends a status_changed event to the track's history (same transaction).
        if (!TracksStore.updateStatus(id, req.status, req.source)) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Track $id not found"))
        }
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }

    // ── Track events (application history — Postgres `track_events`) ───────────

    get("/api/tracks/{id}/events") {
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid track id"))
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100

        val events = TracksStore.listEvents(id, limit)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Track $id not found"))
        call.respond(events)
    }

    post("/api/tracks/{id}/events") {
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid track id"))

        // A missing kind/source or malformed JSON is a 400 with the reason, not a bare 500.
        val req = runCatching { call.receive<TrackEventCreate>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid event body: ${it.message}"))
        }
        val invalid = if (req.kind !in TracksStore.ALLOWED_EVENT_KINDS) "Invalid kind: ${req.kind}"
                      else TracksStore.sourceError(req.source)
        if (invalid != null) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(invalid))
        }

        val event = TracksStore.addEvent(id, req.kind, req.summary, req.source, req.details)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Track $id not found"))
        call.respond(HttpStatusCode.Created, event)
    }
}
