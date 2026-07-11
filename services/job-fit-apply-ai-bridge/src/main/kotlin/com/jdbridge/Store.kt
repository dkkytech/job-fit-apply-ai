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
import kotlin.io.path.createDirectories

private val log = LoggerFactory.getLogger("com.jdbridge.Store")

// ── Configurable store root (override in tests) ───────────────────────────────

var STORE_DIR: Path = Paths.get(System.getProperty("user.home"), ".openclaw", "jd-bridge")
    set(value) {
        field = value
        _database = null  // force re-init when path changes
    }

val DB_PATH  get() = STORE_DIR.resolve("jobs.db")
val JOBS_DIR get() = STORE_DIR.resolve("jobs")

// Dedup window: jobs with matching key/url within this many hours are considered duplicates.
private val DEDUP_WINDOW_HOURS =
    (System.getenv("JD_BRIDGE_DEDUP_WINDOW_HOURS")?.toLongOrNull() ?: 72L)

// Stale claim threshold: claimed rows older than this are re-queued.
private val STALE_CLAIM_MILLIS = 30L * 60 * 1000   // 30 minutes

private var _database: Database? = null

// ── Exposed table definition ──────────────────────────────────────────────────

internal object Jobs : Table("jobs") {
    val id              = text("id")
    val status          = text("status").default("pending")
    val jdJson          = text("jd_json").nullable()
    val jobUrl          = text("job_url").nullable()
    val idempotencyKey  = text("idempotency_key").nullable()
    val fitScore        = integer("fit_score").nullable()
    val pipelineAction  = text("pipeline_action").nullable()
    val artifactsJson   = text("artifacts_json").nullable()
    val error           = text("error").nullable()
    val claimedAt       = long("claimed_at").nullable()
    val createdAt       = long("created_at")
    val updatedAt       = long("updated_at")

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
    }
    log.info("Store initialised at $DB_PATH")
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
 * Insert a new PENDING row and return its id.
 */
suspend fun enqueue(jdJson: String, jobUrl: String?, idempotencyKey: String?): String {
    val jobId = UUID.randomUUID().toString()
    val now = System.currentTimeMillis() / 1000L
    dbQuery {
        Jobs.insert {
            it[Jobs.id]             = jobId
            it[Jobs.status]         = JobStatus.PENDING.value
            it[Jobs.jdJson]         = jdJson
            it[Jobs.jobUrl]         = jobUrl
            it[Jobs.idempotencyKey] = idempotencyKey
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

    // Requeue stale claims inline (cheap single-pass).
    val staleThreshold = now - STALE_CLAIM_MILLIS
    Jobs.update({ (Jobs.status eq JobStatus.CLAIMED.value) and (Jobs.claimedAt less staleThreshold) }) {
        it[Jobs.status]    = JobStatus.PENDING.value
        it[Jobs.claimedAt] = null
        it[Jobs.updatedAt] = now / 1000L
    }

    val row = Jobs.selectAll()
        .where { Jobs.status eq JobStatus.PENDING.value }
        .orderBy(Jobs.createdAt, SortOrder.ASC)
        .firstOrNull()
        ?: return@dbQuery null

    val jobId = row[Jobs.id]
    Jobs.update({ Jobs.id eq jobId }) {
        it[Jobs.status]    = JobStatus.CLAIMED.value
        it[Jobs.claimedAt] = now
        it[Jobs.updatedAt] = now / 1000L
    }

    val jdJson = row[Jobs.jdJson] ?: return@dbQuery null
    ClaimedJob(id = jobId, jdJson = jdJson)
}

/**
 * Persist the worker's result and move the row to DONE or ERROR.
 */
suspend fun recordResult(jobId: String, req: ResultRequest) {
    val now = System.currentTimeMillis() / 1000L
    val newStatus = if (req.error != null) JobStatus.ERROR else JobStatus.DONE
    dbQuery {
        Jobs.update({ Jobs.id eq jobId }) { row ->
            row[Jobs.status]          = newStatus.value
            row[Jobs.fitScore]        = req.fit_score
            row[Jobs.pipelineAction]  = req.pipeline_action
            req.error?.let { row[Jobs.error] = it }
            row[Jobs.claimedAt]       = null
            row[Jobs.updatedAt]       = now
        }
    }
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
        jdJson         = this[Jobs.jdJson],
        jobUrl         = this[Jobs.jobUrl],
        fitScore       = this[Jobs.fitScore],
        pipelineAction = this[Jobs.pipelineAction],
        artifacts      = artifacts,
        error          = this[Jobs.error],
        claimedAt      = this[Jobs.claimedAt],
        createdAt      = this[Jobs.createdAt],
        updatedAt      = this[Jobs.updatedAt],
    )
}
