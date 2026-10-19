package com.jdbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories

private val log = LoggerFactory.getLogger("com.jdbridge.Store")

// ── Configurable store root (JD_BRIDGE_STORE_DIR; overridden in tests) ─────────

/**
 * The store root: [envValue] (JD_BRIDGE_STORE_DIR) when set — a mounted volume like
 * /data in a container — otherwise ~/.openclaw/jd-bridge on the host. Blank → default.
 */
fun resolveStoreDir(envValue: String?): Path =
    envValue?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".openclaw", "jd-bridge")

var STORE_DIR: Path = resolveStoreDir(System.getenv("JD_BRIDGE_STORE_DIR"))
    set(value) {
        field = value
        _database = null  // force re-init when path changes
    }

val DB_PATH  get() = STORE_DIR.resolve("jobs.db")
val JOBS_DIR get() = STORE_DIR.resolve("jobs")

// Dedup window: jobs with matching key/url within this many hours are considered duplicates.
private val DEDUP_WINDOW_HOURS =
    (System.getenv("JD_BRIDGE_DEDUP_WINDOW_HOURS")?.toLongOrNull() ?: 72L)

// Per-source windows. Career-site postings stay open for weeks and the watchlist re-lists them
// daily, so the global 72h window would re-score every open posting every three days.
// JD_BRIDGE_DEDUP_WINDOW_HOURS_<SOURCE> overrides (e.g. ..._CAREER_SITE=720).
private val DEFAULT_SOURCE_WINDOW_HOURS = mapOf("CAREER_SITE" to 60L * 24L)

/** Dedup window for a submission from [source]; unknown/absent sources use the global window. */
internal fun dedupWindowHours(source: String?, env: (String) -> String? = System::getenv): Long {
    val s = source?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return DEDUP_WINDOW_HOURS
    return env("JD_BRIDGE_DEDUP_WINDOW_HOURS_$s")?.toLongOrNull()
        ?: DEFAULT_SOURCE_WINDOW_HOURS[s]
        ?: DEDUP_WINDOW_HOURS
}

// Stale claim threshold: claimed rows older than this are re-queued. Configurable because a
// 30-minute default makes stale-claim recovery untestable — see StoreTest.
private val STALE_CLAIM_MILLIS =
    (System.getenv("JD_BRIDGE_STALE_CLAIM_SECONDS")?.toLongOrNull() ?: 1800L) * 1000L

/** Retry at most this many transient failures before recording a terminal error. */
private const val MAX_RETRYABLE_ATTEMPTS = 3
private const val RETRY_BACKOFF_BASE_SECONDS = 30L

private var _database: Database? = null

// Monotonic write-back cursor. In-process AtomicLong (the bridge is single-process) →
// concurrency-safe and O(1), unlike an in-transaction MAX+1 read-modify-write. Seeded from
// the DB max in initDb() so it survives restarts.
private val completedSeqCounter = AtomicLong(0)

// ── Exposed table definition ──────────────────────────────────────────────────

internal object Jobs : Table("jobs") {
    val id              = text("id")
    val status          = text("status").default("pending")
    val type            = text("type").default(WorkItemType.JD_SCRAPED)
    val jdJson          = text("jd_json").nullable()
    val jobUrl          = text("job_url").nullable()
    val idempotencyKey  = text("idempotency_key").nullable()
    val fitScore        = integer("fit_score").nullable()
    val pipelineAction  = text("pipeline_action").nullable()
    val artifactsJson   = text("artifacts_json").nullable()
    // Processed-posting identity (from the result) — for completed-feed event-stream consumers.
    val company         = text("company").nullable()
    val roleTitle       = text("role_title").nullable()
    val artifactUrl     = text("artifact_url").nullable()   // markserv report URL
    // Postgres `tracks.id` the Processor wrote — links the job to its track_events history.
    // Nullable, so createMissingTablesAndColumns can ALTER it onto an existing queue database.
    val trackId         = integer("track_id").nullable()
    // Why a scored job was not tailored. Nullable, so it ALTERs onto an existing queue database.
    val skipReason      = text("skip_reason").nullable()
    // Card details (nullable, ALTERed onto an existing queue database). Lists are JSON arrays.
    val location        = text("location").nullable()
    val remotePolicy    = text("remote_policy").nullable()
    val salaryRange     = text("salary_range").nullable()
    val jobSource       = text("source").nullable()   // not `source`: that name is Table's own
    val strengthsJson   = text("strengths_json").nullable()
    val gapsJson        = text("gaps_json").nullable()
    // An OFFER's processed record (JSON) — what a Tailor tap re-queues. Nullable, ALTERed on.
    val tailorRecord    = text("tailor_record").nullable()
    // Where a job with a tailor_record stands: offered (Tailor button), deferred (waiting for the
    // cloud quota), released (its TAILOR_REQUEST was queued by the sweep) or expired. ALTERed on.
    val tailorState     = text("tailor_state").nullable()
    val error           = text("error").nullable()
    val retryCount      = integer("retry_count").default(0)
    val nextAttemptAt   = long("next_attempt_at").nullable()
    val claimedAt       = long("claimed_at").nullable()
    // Fencing token, rotated on every claim. A worker must present the token it was handed to
    // record a result, so a claim that was requeued underneath it cannot overwrite the attempt
    // that replaced it. Nullable: cleared on requeue, and absent on rows claimed before this existed.
    val claimToken      = text("claim_token").nullable()
    val createdAt       = long("created_at")
    val updatedAt       = long("updated_at")

    // Gmail write-back (set when the job goes terminal; drained by the Poller).
    val terminalLabel   = text("terminal_label").nullable()
    val draftText       = text("draft_text").nullable()
    val isRecruiter     = bool("is_recruiter").default(false)
    val messageId       = text("message_id").nullable()
    val writebackDone   = bool("writeback_done").default(false)
    val completedSeq    = long("completed_seq").nullable()   // monotonic; assigned on terminal

    override val primaryKey = PrimaryKey(id)
}

// ── DB helpers ────────────────────────────────────────────────────────────────

private suspend fun <T> dbQuery(block: Transaction.() -> T): T =
    withContext(Dispatchers.IO) {
        transaction(_database!!) { block() }
    }

// ── Lifecycle ─────────────────────────────────────────────────────────────────

suspend fun initDb() = withContext(Dispatchers.IO) {
    STORE_DIR.createDirectories()
    JOBS_DIR.createDirectories()
    _database = Database.connect(
        url    = "jdbc:sqlite:$DB_PATH",
        driver = "org.sqlite.JDBC",
    )
    transaction(_database!!) {
        SchemaUtils.createMissingTablesAndColumns(Jobs)
        // Seed the write-back cursor from the persisted max (DESC → highest non-null first).
        // updateAndGet(maxOf) — never lower the counter, so a re-init (configureApplication
        // fires initDb again async on ApplicationStarted) can't reset it backward and re-issue
        // an already-used seq.
        val maxSeq = Jobs.selectAll()
            .orderBy(Jobs.completedSeq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.get(Jobs.completedSeq) ?: 0L
        completedSeqCounter.updateAndGet { maxOf(it, maxSeq) }
    }
    log.info("Store initialised at $DB_PATH (completed_seq cursor at ${completedSeqCounter.get()})")
}

// ── Queue operations ──────────────────────────────────────────────────────────

/**
 * Return an existing job_id if a non-ERROR row matching [key] or [jobUrl]
 * already exists within the dedup window for [source]. Returns null otherwise.
 */
suspend fun findActiveDuplicate(jobUrl: String?, key: String?, source: String? = null): String? = dbQuery {
    val cutoff = System.currentTimeMillis() / 1000L - dedupWindowHours(source) * 3600L
    val terminal = listOf(JobStatus.ERROR.value)

    if (!key.isNullOrBlank()) {
        val row = Jobs.selectAll()
            .where { (Jobs.idempotencyKey eq key) and (Jobs.status notInList terminal) and (Jobs.createdAt greaterEq cutoff) }
            .firstOrNull()
        if (row != null) return@dbQuery row[Jobs.id]
    }

    if (!jobUrl.isNullOrBlank()) {
        val row = Jobs.selectAll()
            .where { (Jobs.jobUrl eq jobUrl) and (Jobs.status notInList terminal) and (Jobs.createdAt greaterEq cutoff) }
            .firstOrNull()
        if (row != null) return@dbQuery row[Jobs.id]
    }

    null
}

/**
 * Insert a new PENDING row and return its id. [type] discriminates the payload for the Processor.
 */
suspend fun enqueue(
    jdJson: String,
    jobUrl: String?,
    idempotencyKey: String?,
    type: String = WorkItemType.JD_SCRAPED,
    messageId: String? = null,
): String {
    val jobId = UUID.randomUUID().toString()
    val now = System.currentTimeMillis() / 1000L
    dbQuery {
        Jobs.insert {
            it[Jobs.id]             = jobId
            it[Jobs.status]         = JobStatus.PENDING.value
            it[Jobs.type]           = type
            it[Jobs.jdJson]         = jdJson
            it[Jobs.jobUrl]         = jobUrl
            it[Jobs.idempotencyKey] = idempotencyKey
            it[Jobs.messageId]      = messageId
            it[Jobs.createdAt]      = now
            it[Jobs.updatedAt]      = now
        }
    }
    return jobId
}

/**
 * Atomically claim the oldest PENDING row (after requeuing stale claims).
 * Returns null when the queue is empty.
 */
suspend fun claimNext(): ClaimedJob? = dbQuery {
    val now = System.currentTimeMillis()

    // Requeue stale claims inline (cheap single-pass). Clearing the token is what fences the
    // displaced worker: its held token no longer matches anything, so its late result is refused.
    val staleThreshold = now - STALE_CLAIM_MILLIS
    Jobs.update({ (Jobs.status eq JobStatus.CLAIMED.value) and (Jobs.claimedAt less staleThreshold) }) {
        it[Jobs.status]     = JobStatus.PENDING.value
        it[Jobs.claimedAt]  = null
        it[Jobs.claimToken] = null
        it[Jobs.updatedAt]  = now / 1000L
    }

    val row = Jobs.selectAll()
        .where {
            (Jobs.status eq JobStatus.PENDING.value) and
                (Jobs.nextAttemptAt.isNull() or (Jobs.nextAttemptAt lessEq (now / 1000L)))
        }
        .orderBy(Jobs.createdAt, SortOrder.ASC)
        .firstOrNull()
        ?: return@dbQuery null

    val jobId = row[Jobs.id]
    val claimToken = java.util.UUID.randomUUID().toString()
    Jobs.update({ Jobs.id eq jobId }) {
        it[Jobs.status]     = JobStatus.CLAIMED.value
        it[Jobs.claimedAt]  = now
        it[Jobs.claimToken] = claimToken
        it[Jobs.nextAttemptAt] = null
        it[Jobs.updatedAt]  = now / 1000L
    }

    val jdJson = row[Jobs.jdJson] ?: return@dbQuery null
    ClaimedJob(id = jobId, type = row[Jobs.type], jdJson = jdJson, claimToken = claimToken)
}

/** What [recordResult] did — the caller turns this into a status code. */
enum class ResultOutcome {
    /** First terminal result for this job: persisted, completed_seq assigned. */
    RECORDED,

    /** A retry-eligible provider failure was durably deferred for a later claim. */
    REQUEUED,

    /**
     * The job was already terminal. Ignored — completion is idempotent. Without this, a
     * duplicate or late POST assigned a *second* completed_seq, so the job appeared twice in
     * the completed feed and the Notifier delivered it twice.
     */
    ALREADY_TERMINAL,

    /**
     * The presented claim token is not the one the row currently holds — the claim was
     * requeued and re-claimed underneath this worker. Refused, so a slow worker cannot
     * overwrite the attempt that replaced it.
     */
    STALE_CLAIM,
}

/**
 * The compare-and-set predicate for a fenced worker result. Legacy workers without a claim token
 * retain their compatibility behavior; a current worker must still own the row in CLAIMED state.
 */
private fun resultClaimPredicate(jobId: String, claimToken: String?): Op<Boolean> =
    SqlExpressionBuilder.run {
        val id = Jobs.id eq jobId
        if (claimToken == null) id else
            id and (Jobs.status eq JobStatus.CLAIMED.value) and (Jobs.claimToken eq claimToken)
    }

/**
 * Persist the worker's result and move the row to DONE or ERROR.
 *
 * A `null` [ResultRequest.claim_token] is accepted against any row: a worker built before
 * fencing existed would otherwise have every result refused during a rolling deploy, wedging
 * the queue. That is a version skew, not the hazard being defended against — a *displaced*
 * worker always holds a token, and a stale one is refused.
 */
suspend fun recordResult(jobId: String, req: ResultRequest): ResultOutcome {
    val now = System.currentTimeMillis() / 1000L

    val guard = dbQuery {
        val row = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()
        when {
            row == null -> null
            row[Jobs.completedSeq] != null &&
                row[Jobs.status] in listOf(JobStatus.DONE.value, JobStatus.ERROR.value) ->
                ResultOutcome.ALREADY_TERMINAL
            req.claim_token != null && req.claim_token != row[Jobs.claimToken] ->
                ResultOutcome.STALE_CLAIM
            else -> null
        }
    }
    if (guard != null) {
        log.warn("Result for $jobId ignored: $guard")
        return guard
    }

    if (req.retryable && req.error != null) {
        val requeued = dbQuery {
            val row = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()
                ?: return@dbQuery ResultOutcome.STALE_CLAIM
            val retryCount = row[Jobs.retryCount]
            if (retryCount < MAX_RETRYABLE_ATTEMPTS) {
                val delaySeconds = RETRY_BACKOFF_BASE_SECONDS * (1L shl retryCount)
                val changed = Jobs.update({ resultClaimPredicate(jobId, req.claim_token) }) {
                    it[Jobs.status]        = JobStatus.PENDING.value
                    it[Jobs.error]         = req.error
                    it[Jobs.retryCount]    = retryCount + 1
                    it[Jobs.nextAttemptAt] = now + delaySeconds
                    it[Jobs.claimedAt]     = null
                    it[Jobs.claimToken]    = null
                    it[Jobs.terminalLabel] = null
                    it[Jobs.updatedAt]     = now
                }
                if (changed == 0) ResultOutcome.STALE_CLAIM
                else ResultOutcome.REQUEUED
            } else {
                ResultOutcome.RECORDED
            }
        }
        // The retry-budget boundary is terminal. Keep the terminal write outside Exposed's
        // synchronous transaction lambda.  Force terminal label to JD_Error so exhausted retries
        // don't land as a blank label (which the Poller maps to JD_Not_Found).
        return if (requeued == ResultOutcome.RECORDED) {
            val forRetryExhaustion = recordTerminalResult(
                jobId, req.copy(terminal_label = "JD_Error"), now
            )
            forRetryExhaustion
        } else requeued
    }

    return recordTerminalResult(jobId, req, now)
}

/** Persist a terminal completion. Call only after claim/token guards have passed. */
private suspend fun recordTerminalResult(jobId: String, req: ResultRequest, now: Long): ResultOutcome {
    val newStatus = if (req.error != null) JobStatus.ERROR else JobStatus.DONE
    return dbQuery {
        val existing = Jobs.selectAll().where { Jobs.id eq jobId }.firstOrNull()
            ?: return@dbQuery ResultOutcome.STALE_CLAIM
        if (existing[Jobs.completedSeq] != null &&
            existing[Jobs.status] in listOf(JobStatus.DONE.value, JobStatus.ERROR.value)) {
            return@dbQuery ResultOutcome.ALREADY_TERMINAL
        }
        if (req.claim_token != null && req.claim_token != existing[Jobs.claimToken]) {
            return@dbQuery ResultOutcome.STALE_CLAIM
        }

        // Burn a sequence only after checking ownership; the conditional update below is the final
        // compare-and-set fence against a claim that changed after the read.
        val nextSeq = completedSeqCounter.incrementAndGet()
        val changed = Jobs.update({ resultClaimPredicate(jobId, req.claim_token) }) { row ->
            // Clear stale transient errors on a retry-then-success terminal result.
            row[Jobs.error]           = req.error
            row[Jobs.status]          = newStatus.value
            row[Jobs.fitScore]        = req.fit_score
            row[Jobs.pipelineAction]  = req.pipeline_action
            // Processed-posting identity + report URL (for completed-feed consumers). job_url may
            // have been null at enqueue (EMAIL_RAW) — the result carries the scraped value.
            req.company?.let { row[Jobs.company] = it }
            req.role_title?.let { row[Jobs.roleTitle] = it }
            req.job_url?.let { row[Jobs.jobUrl] = it }
            req.artifact_url?.let { row[Jobs.artifactUrl] = it }
            req.track_id?.let { row[Jobs.trackId] = it }
            row[Jobs.skipReason]      = req.skip_reason
            row[Jobs.location]        = req.location
            row[Jobs.remotePolicy]    = req.remote_policy
            row[Jobs.salaryRange]     = req.salary_range
            row[Jobs.jobSource]       = req.source
            row[Jobs.strengthsJson]   = req.strengths.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
            row[Jobs.gapsJson]        = req.gaps.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
            row[Jobs.tailorRecord]    = req.tailor_record?.toString()
            row[Jobs.tailorState]     = if (req.tailor_record == null) null else when (req.pipeline_action.uppercase()) {
                "OFFER" -> TailorState.OFFERED
                "DEFERRED" -> TailorState.DEFERRED
                else -> null
            }
            row[Jobs.claimedAt]       = null
            row[Jobs.claimToken]      = null
            row[Jobs.nextAttemptAt]   = null
            row[Jobs.updatedAt]       = now
            // Gmail write-back payload
            row[Jobs.terminalLabel]   = req.terminal_label
            row[Jobs.draftText]       = req.draft_text
            row[Jobs.isRecruiter]     = req.is_recruiter
            req.message_id?.let { row[Jobs.messageId] = it }
            row[Jobs.writebackDone]   = false
            row[Jobs.completedSeq]    = nextSeq
        }
        if (changed == 0) ResultOutcome.STALE_CLAIM else ResultOutcome.RECORDED
    }
}

/**
 * Completed jobs the Poller still needs to write back to Gmail: terminal status,
 * not yet acknowledged, with completed_seq > [since]. Oldest first.
 */
// [all] = false → the Poller's work queue (writeback_done = false). [all] = true → the full event
// stream (every completed job by completed_seq > since, regardless of write-back) for consumers
// like the Notifier that track their own cursor.
suspend fun completedJobs(since: Long, limit: Int = 50, all: Boolean = false): List<CompletedJob> = dbQuery {
    Jobs.selectAll()
        .where {
            val terminal = (Jobs.status inList listOf(JobStatus.DONE.value, JobStatus.ERROR.value)) and
                (Jobs.completedSeq greater since)
            if (all) terminal else terminal and (Jobs.writebackDone eq false)
        }
        .orderBy(Jobs.completedSeq, SortOrder.ASC)
        .limit(limit)
        .map { row ->
            val artifacts = row[Jobs.artifactsJson]?.let {
                runCatching { Json.decodeFromString<ArtifactUrls>(it) }.getOrNull()
            }
            CompletedJob(
                job_id          = row[Jobs.id],
                completed_seq   = row[Jobs.completedSeq] ?: 0L,
                status          = row[Jobs.status],
                message_id      = row[Jobs.messageId],
                terminal_label  = row[Jobs.terminalLabel],
                draft_text      = row[Jobs.draftText],
                is_recruiter    = row[Jobs.isRecruiter],
                artifacts       = artifacts,
                error           = row[Jobs.error],
                company         = row[Jobs.company],
                role_title      = row[Jobs.roleTitle],
                fit_score       = row[Jobs.fitScore],
                pipeline_action = row[Jobs.pipelineAction],
                job_url         = row[Jobs.jobUrl],
                artifact_url    = row[Jobs.artifactUrl],
                track_id        = row[Jobs.trackId],
                skip_reason     = row[Jobs.skipReason],
                location        = row[Jobs.location],
                remote_policy   = row[Jobs.remotePolicy],
                salary_range    = row[Jobs.salaryRange],
                source          = row[Jobs.jobSource],
                strengths       = stringList(row[Jobs.strengthsJson]),
                gaps            = stringList(row[Jobs.gapsJson]),
            )
        }
}

/** A stored JSON string array; anything unreadable is an empty list (the card just shows less). */
private fun stringList(json: String?): List<String> =
    json?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

/** The current max completed_seq — a cursor consumer seeds here on cold start to skip history. */
fun latestCompletedSeq(): Long = completedSeqCounter.get()

/** Mark a completed job as written back to Gmail (drops it from the feed). */
suspend fun markWritebackDone(jobId: String): Boolean = dbQuery {
    Jobs.update({ Jobs.id eq jobId }) {
        it[Jobs.writebackDone] = true
        it[Jobs.updatedAt]     = System.currentTimeMillis() / 1000L
    } > 0
}

/**
 * Attach artifact URLs (set by the bridge after the worker uploads files).
 */
suspend fun setArtifacts(jobId: String, artifacts: ArtifactUrls) {
    val now = System.currentTimeMillis() / 1000L
    dbQuery {
        Jobs.update({ Jobs.id eq jobId }) { row ->
            row[Jobs.artifactsJson] = Json.encodeToString(artifacts)
            row[Jobs.updatedAt]     = now
        }
    }
}

// ── Status queries ────────────────────────────────────────────────────────────

suspend fun getJob(jobId: String): JobRow? = dbQuery {
    Jobs.selectAll().where { Jobs.id eq jobId }
        .singleOrNull()
        ?.toJobRow()
}

// ── Deferred tailoring ────────────────────────────────────────────────────────

/** Deferred jobs last updated before [cutoff] (epoch seconds) become expired; returns them. */
suspend fun expireDeferred(cutoff: Long): List<JobRow> = dbQuery {
    val stale = Jobs.selectAll()
        .where { (Jobs.tailorState eq TailorState.DEFERRED) and (Jobs.updatedAt less cutoff) }
        .map { it.toJobRow() }
    if (stale.isNotEmpty()) {
        Jobs.update({ Jobs.id inList stale.map { it.id } }) { it[Jobs.tailorState] = TailorState.EXPIRED }
    }
    stale
}

/** TAILOR_REQUESTs still queued or running. */
suspend fun tailorRequestsInFlight(): Long = dbQuery {
    Jobs.selectAll().where {
        (Jobs.type eq WorkItemType.TAILOR_REQUEST) and
            (Jobs.status inList listOf(JobStatus.PENDING.value, JobStatus.CLAIMED.value))
    }.count()
}

/** Up to [limit] deferred jobs, best fit first (oldest first among equals). */
suspend fun deferredBestFirst(limit: Int): List<JobRow> = if (limit <= 0) emptyList() else dbQuery {
    Jobs.selectAll().where { Jobs.tailorState eq TailorState.DEFERRED }
        .orderBy(Jobs.fitScore to SortOrder.DESC_NULLS_LAST, Jobs.updatedAt to SortOrder.ASC)
        .limit(limit)
        .map { it.toJobRow() }
}

suspend fun countDeferred(): Long = dbQuery { Jobs.selectAll().where { Jobs.tailorState eq TailorState.DEFERRED }.count() }

suspend fun setTailorState(jobId: String, state: String) = dbQuery {
    Jobs.update({ Jobs.id eq jobId }) { it[Jobs.tailorState] = state }
}

/**
 * Queue [row]'s stored [record] as a TAILOR_REQUEST, once per job: a job already requested (and
 * not errored) answers with that request, deduped. The job is marked released only once a request
 * exists — if [insert] fails, a deferred job stays deferred for the next sweep or tap.
 */
internal suspend fun queueTailor(
    row: JobRow,
    record: JsonElement,
    insert: suspend (jdJson: String, jobUrl: String?, key: String, type: String) -> String =
        { jdJson, jobUrl, key, type -> enqueue(jdJson, jobUrl, key, type) },
): SubmitJobResponse {
    val key = "tailor:${row.id}"
    findLiveByKey(key)?.let { existing ->
        log.info("Tailor already requested for ${row.id} → ${existing.id} (${existing.status})")
        setTailorState(row.id, TailorState.RELEASED)
        return SubmitJobResponse(existing.id, existing.status, deduped = true)
    }
    val payload = buildJsonObject {
        put("record", record)
        put("parent_job_id", row.id)
        row.trackId?.let { put("track_id", it) }
        row.jobSource?.let { put("job_board", it) }
    }
    val tailorJobId = insert(payload.toString(), row.jobUrl, key, WorkItemType.TAILOR_REQUEST)
    setTailorState(row.id, TailorState.RELEASED)
    log.info("Tailor requested for ${row.id} → $tailorJobId (${row.roleTitle} @ ${row.company})")
    return SubmitJobResponse(tailorJobId, JobStatus.PENDING.value)
}

/** The newest non-ERROR job enqueued under [key], whatever its age — for once-per-job requests. */
suspend fun findLiveByKey(key: String): JobRow? = dbQuery {
    Jobs.selectAll()
        .where { (Jobs.idempotencyKey eq key) and (Jobs.status neq JobStatus.ERROR.value) }
        .orderBy(Jobs.createdAt, SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.toJobRow()
}

fun jobDir(jobId: String): Path {
    val dir = JOBS_DIR.resolve(jobId)
    dir.createDirectories()
    return dir
}

// ── Row mapping ───────────────────────────────────────────────────────────────

private fun ResultRow.toJobRow(): JobRow {
    val artifactsJson = this[Jobs.artifactsJson]
    val artifacts = artifactsJson?.let {
        runCatching { Json.decodeFromString<ArtifactUrls>(it) }.getOrNull()
    }
    return JobRow(
        id             = this[Jobs.id],
        status         = this[Jobs.status],
        type           = this[Jobs.type],
        jdJson         = this[Jobs.jdJson],
        jobUrl         = this[Jobs.jobUrl],
        fitScore       = this[Jobs.fitScore],
        pipelineAction = this[Jobs.pipelineAction],
        artifacts      = artifacts,
        error          = this[Jobs.error],
        retryCount     = this[Jobs.retryCount],
        nextAttemptAt  = this[Jobs.nextAttemptAt],
        claimedAt      = this[Jobs.claimedAt],
        createdAt      = this[Jobs.createdAt],
        updatedAt      = this[Jobs.updatedAt],
        terminalLabel  = this[Jobs.terminalLabel],
        draftText      = this[Jobs.draftText],
        isRecruiter    = this[Jobs.isRecruiter],
        messageId      = this[Jobs.messageId],
        writebackDone  = this[Jobs.writebackDone],
        completedSeq   = this[Jobs.completedSeq],
        company        = this[Jobs.company],
        roleTitle      = this[Jobs.roleTitle],
        trackId        = this[Jobs.trackId],
        jobSource      = this[Jobs.jobSource],
        tailorRecord   = this[Jobs.tailorRecord],
        tailorState    = this[Jobs.tailorState],
        artifactUrl    = this[Jobs.artifactUrl],
    )
}
