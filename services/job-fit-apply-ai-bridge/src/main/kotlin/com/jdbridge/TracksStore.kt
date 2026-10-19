package com.jdbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Read/update access to the shared `tracks` table in the Postgres container
 * (docker compose `db`), plus each track's application history (`track_events`).
 * This is the app-domain data the backlog UI reads — kept separate from the
 * bridge's own SQLite job queue (see [Store]).
 *
 * Raw JDBC, connection-per-call (low-traffic UI reads); mirrors the pipeline's
 * PostgresGateway so both services talk to the container the same way.
 */
object TracksStore {

    /** Status values the backlog UI uses; guards the update endpoint. */
    val ALLOWED_STATUSES = setOf(
        "backlog", "duplicate", "applied", "interested",
        "skipped", "interviewing", "rejected", "offer",
    )

    /** Event kinds a track's history accepts; guards POST /api/tracks/{id}/events. */
    val ALLOWED_EVENT_KINDS = setOf(
        "status_changed", "note", "email_received", "email_sent", "reply_drafted",
        "archived", "unarchived", "applied", "application_filled", "application_submitted",
        "account_created", "interview", "rejected", "offer",
    )

    /** `track_events.source` is a short actor tag (frontend, bridge, jobbot …), not prose. */
    const val MAX_SOURCE_LENGTH = 32

    private const val SELECT_COLS =
        "id, company, role_title, location, remote_policy, fit_score, " +
        "job_url, artifact_url, tech_stack, status, created_at, duplicate"

    private const val EVENT_COLS = "id, track_id, occurred_at, kind, summary, source, details"

    /**
     * `track_events` DDL, mirrored from db/init/001_schema.sql — keep the two in sync. Production
     * never re-runs db/init (there is no migration runner), so the bridge creates it itself.
     */
    private val EVENTS_DDL = listOf(
        """
        CREATE TABLE IF NOT EXISTS track_events (
            id           BIGSERIAL   PRIMARY KEY,
            track_id     INTEGER     NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
            occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            kind         TEXT        NOT NULL,
            summary      TEXT,
            source       TEXT        NOT NULL,
            details      JSONB
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_track_events_track_occurred ON track_events (track_id, occurred_at)",
    )

    @Volatile private var eventsSchemaReady = false

    private data class Dsn(val jdbcUrl: String, val user: String, val password: String)

    private val dsn: Dsn by lazy {
        val uri = URI(getEnv("DATABASE_URL", "postgresql://jobfit:jobfit@localhost:5432/jobfit"))
        val creds = uri.userInfo?.split(":", limit = 2) ?: emptyList()
        val port = if (uri.port > 0) uri.port else 5432
        val db = uri.path.trimStart('/')
        // stringtype=unspecified → string params get type-inferred by Postgres.
        Dsn(
            "jdbc:postgresql://${uri.host}:$port/$db?stringtype=unspecified",
            creds.getOrElse(0) { "" },
            creds.getOrElse(1) { "" },
        )
    }

    private suspend fun <T> withConnection(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            DriverManager.getConnection(dsn.jdbcUrl, dsn.user, dsn.password).use(block)
        }

    /** All tracks, newest first. Fit/dupe/status filtering is done client-side by the UI. */
    suspend fun list(): List<TrackDto> = withConnection { conn ->
        conn.prepareStatement("SELECT $SELECT_COLS FROM tracks ORDER BY created_at DESC").use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toTrackDto()) }
            }
        }
    }

    /**
     * Update a track's status and append a `status_changed` event (details `{from, to}`) in one
     * transaction. Returns false when no row with that id exists. Re-posting the current status
     * records no event — nothing changed.
     */
    suspend fun updateStatus(id: Int, status: String, source: String = "frontend"): Boolean =
        withConnection { conn ->
            ensureEventsSchema(conn)
            conn.inTransaction {
                val previous = conn.prepareStatement("SELECT status FROM tracks WHERE id = ? FOR UPDATE").use { ps ->
                    ps.setInt(1, id)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else return@inTransaction false }
                }
                conn.prepareStatement("UPDATE tracks SET status = ? WHERE id = ?").use { ps ->
                    ps.setString(1, status)
                    ps.setInt(2, id)
                    ps.executeUpdate()
                }
                if (previous != status) {
                    val details = buildJsonObject { put("from", previous); put("to", status) }
                    insertEvent(conn, id, "status_changed", "$previous → $status", source, details)
                }
                true
            }
        }

    // ── Track events (application history) ────────────────────────────────────

    /**
     * Create `track_events` (+ index) if missing. Idempotent. Runs at bridge startup and, until it
     * has succeeded once, before any events access — so a bridge that booted while Postgres was
     * unreachable still heals on first use.
     */
    suspend fun ensureSchema() = withConnection { createEventsSchema(it) }

    /** Validation message for a `track_events.source`, or null when it is acceptable. */
    fun sourceError(source: String): String? = when {
        source.isBlank() -> "source must not be blank"
        source.length > MAX_SOURCE_LENGTH -> "source must be at most $MAX_SOURCE_LENGTH characters"
        else -> null
    }

    /** A track's events, newest first; null when the track does not exist. */
    suspend fun listEvents(trackId: Int, limit: Int): List<TrackEventDto>? = withConnection { conn ->
        ensureEventsSchema(conn)
        val exists = conn.prepareStatement("SELECT 1 FROM tracks WHERE id = ?").use { ps ->
            ps.setInt(1, trackId)
            ps.executeQuery().use { it.next() }
        }
        if (!exists) return@withConnection null
        conn.prepareStatement(
            "SELECT $EVENT_COLS FROM track_events WHERE track_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ?",
        ).use { ps ->
            ps.setInt(1, trackId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toTrackEventDto()) }
            }
        }
    }

    /** Append an event to a track's history. Returns the stored row, or null when the track does not exist. */
    suspend fun addEvent(
        trackId: Int,
        kind: String,
        summary: String?,
        source: String,
        details: JsonElement?,
    ): TrackEventDto? = withConnection { conn ->
        ensureEventsSchema(conn)
        insertEvent(conn, trackId, kind, summary, source, details)
    }

    /** INSERT … SELECT FROM tracks: an unknown track inserts nothing (null) rather than raising an FK error. */
    private fun insertEvent(
        conn: Connection,
        trackId: Int,
        kind: String,
        summary: String?,
        source: String,
        details: JsonElement?,
    ): TrackEventDto? = conn.prepareStatement(
        "INSERT INTO track_events (track_id, kind, summary, source, details) " +
            "SELECT id, ?, ?, ?, ?::jsonb FROM tracks WHERE id = ? RETURNING $EVENT_COLS",
    ).use { ps ->
        ps.setString(1, kind)
        ps.setString(2, summary)
        ps.setString(3, source)
        ps.setString(4, details?.takeUnless { it is JsonNull }?.toString())
        ps.setInt(5, trackId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.toTrackEventDto() else null }
    }

    private fun createEventsSchema(conn: Connection) = synchronized(this) {
        conn.createStatement().use { st -> EVENTS_DDL.forEach { st.execute(it) } }
        eventsSchemaReady = true
    }

    private fun ensureEventsSchema(conn: Connection) {
        if (!eventsSchemaReady) createEventsSchema(conn)
    }

    /** Run [block] as one transaction. Connections are per-call, so autoCommit is not restored. */
    private inline fun <T> Connection.inTransaction(block: () -> T): T {
        autoCommit = false
        return try {
            block().also { commit() }
        } catch (e: Throwable) {
            rollback()
            throw e
        }
    }

    private fun ResultSet.toTrackDto(): TrackDto {
        val techStack = getArray("tech_stack")?.let { arr ->
            (arr.array as? Array<*>)?.mapNotNull { it?.toString() }
        }
        return TrackDto(
            id = getInt("id"),
            company = getString("company") ?: "",
            role_title = getString("role_title"),
            location = getString("location"),
            remote_policy = getString("remote_policy"),
            fit_score = getBigDecimal("fit_score")?.toDouble(),
            job_url = getString("job_url"),
            artifact_url = getString("artifact_url"),
            tech_stack = techStack,
            status = getString("status") ?: "backlog",
            created_at = getTimestamp("created_at")?.toInstant()?.toString() ?: "",
            duplicate = getBoolean("duplicate"),
        )
    }

    private fun ResultSet.toTrackEventDto() = TrackEventDto(
        id = getLong("id"),
        track_id = getInt("track_id"),
        occurred_at = getTimestamp("occurred_at").toInstant().toString(),
        kind = getString("kind"),
        summary = getString("summary"),
        source = getString("source"),
        details = getString("details")?.let { Json.parseToJsonElement(it) },
    )
}
