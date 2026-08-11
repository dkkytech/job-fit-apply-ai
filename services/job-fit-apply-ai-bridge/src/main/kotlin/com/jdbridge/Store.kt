package com.jdbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

// Stale claim threshold: claimed rows older than this are re-queued. Configurable because a
// 30-minute default makes stale-claim recovery untestable — see StoreTest.
private val STALE_CLAIM_MILLIS =
    (System.getenv("JD_BRIDGE_STALE_CLAIM_SECONDS")?.toLongOrNull() ?: 1800L) * 1000L

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
    val error           = text("error").nullable()
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
 * already exists within the dedup window. Returns null otherwise.
 */
suspend fun findActiveDuplicate(jobUrl: String?, key: String?): String? = dbQuery {
    val cutoff = System.currentTimeMillis() / 1000L - DEDUP_WINDOW_HOURS * 3600L
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
        .where { Jobs.status eq JobStatus.PENDING.value }
        .orderBy(Jobs.createdAt, SortOrder.ASC)
        .firstOrNull()
        ?: return@dbQuery null

    val jobId = row[Jobs.id]
    val claimToken = java.util.UUID.randomUUID().toString()
    Jobs.update({ Jobs.id eq jobId }) {
        it[Jobs.status]     = JobStatus.CLAIMED.value
        it[Jobs.claimedAt]  = now
        it[Jobs.claimToken] = claimToken
        it[Jobs.updatedAt]  = now / 1000L
    }

    val jdJson = row[Jobs.jdJson] ?: return@dbQuery null
    ClaimedJob(id = jobId, type = row[Jobs.type], jdJson = jdJson, claimToken = claimToken)
}

/** What [recordResult] did — the caller turns this into a status code. */
enum class ResultOutcome {
    /** First terminal result for this job: persisted, completed_seq assigned. */
    RECORDED,

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
 * Persist the worker's result and move the row to DONE or ERROR.
 *
 * A `null` [ResultRequest.claim_token] is accepted against any row: a worker built before
 * fencing existed would otherwise have every result refused during a rolling deploy, wedging
 * the queue. That is a version skew, not the hazard being defended against — a *displaced*
 * worker always holds a token, and a stale one is refused.
 */
suspend fun recordResult(jobId: String, req: ResultRequest): ResultOutcome {
    val now = System.currentTimeMillis() / 1000L
    val newStatus = if (req.error != null) JobStatus.ERROR else JobStatus.DONE

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

    // Only now is a sequence number burned — an ignored result must not consume one.
    val nextSeq = completedSeqCounter.incrementAndGet()
    dbQuery {
        Jobs.update({ Jobs.id eq jobId }) { row ->
            row[Jobs.status]          = newStatus.value
            row[Jobs.fitScore]        = req.fit_score
            row[Jobs.pipelineAction]  = req.pipeline_action
            req.error?.let { row[Jobs.error] = it }
            // Processed-posting identity + report URL (for completed-feed consumers). job_url may
            // have been null at enqueue (EMAIL_RAW) — the result carries the scraped value.
            req.company?.let { row[Jobs.company] = it }
            req.role_title?.let { row[Jobs.roleTitle] = it }
            req.job_url?.let { row[Jobs.jobUrl] = it }
            req.artifact_url?.let { row[Jobs.artifactUrl] = it }
            row[Jobs.claimedAt]       = null
            row[Jobs.claimToken]      = null
            row[Jobs.updatedAt]       = now
            // Gmail write-back payload
            row[Jobs.terminalLabel]   = req.terminal_label
            row[Jobs.draftText]       = req.draft_text
            row[Jobs.isRecruiter]     = req.is_recruiter
            req.message_id?.let { row[Jobs.messageId] = it }
            row[Jobs.writebackDone]   = false
            row[Jobs.completedSeq]    = nextSeq
        }
    }
    return ResultOutcome.RECORDED
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
            )
        }
}

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
        claimedAt      = this[Jobs.claimedAt],
        createdAt      = this[Jobs.createdAt],
        updatedAt      = this[Jobs.updatedAt],
        terminalLabel  = this[Jobs.terminalLabel],
        draftText      = this[Jobs.draftText],
        isRecruiter    = this[Jobs.isRecruiter],
        messageId      = this[Jobs.messageId],
        writebackDone  = this[Jobs.writebackDone],
        completedSeq   = this[Jobs.completedSeq],
    )
}
