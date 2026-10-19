package com.jd.jobbot.actions

import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Clock

/**
 * The action table: every button tap JobBot acted on, keyed by (verb, job identity). It is the
 * idempotency record (a double tap or a second card for the same posting finds the first action),
 * the audit trail behind /jdstatus, and the undo record (archive stores the labels it removed).
 *
 * SQLite in jobbot-mcp's own state dir; jobbot-mcp is its only writer.
 */
class ActionStore(dbPath: String, private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    data class Action(
        val id: Long,
        val verb: String,
        val jobKey: String,
        val seq: Long,
        val status: String,
        val chatId: String?,
        val messageId: String?,
        val details: String?,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class ErrorRow(val at: Long, val context: String, val message: String)

    private val conn: Connection

    init {
        if (dbPath != ":memory:") Paths.get(dbPath).toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS actions (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  verb TEXT NOT NULL,
                  job_key TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  status TEXT NOT NULL,
                  chat_id TEXT,
                  message_id TEXT,
                  details TEXT,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS actions_verb_job ON actions(verb, job_key, id)")
            st.execute(
                "CREATE TABLE IF NOT EXISTS errors (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, context TEXT NOT NULL, message TEXT NOT NULL)",
            )
        }
    }

    @Synchronized
    fun insert(verb: String, jobKey: String, seq: Long, status: String, chatId: String?, messageId: String?, details: String? = null): Action {
        val now = clock.millis()
        conn.prepareStatement(
            "INSERT INTO actions(verb, job_key, seq, status, chat_id, message_id, details, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, verb); ps.setString(2, jobKey); ps.setLong(3, seq); ps.setString(4, status)
            ps.setString(5, chatId); ps.setString(6, messageId); ps.setString(7, details)
            ps.setLong(8, now); ps.setLong(9, now)
            ps.executeUpdate()
        }
        val id = conn.createStatement().use { it.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
        return byId(id)!!
    }

    @Synchronized
    fun update(id: Long, status: String, details: String? = null): Action? {
        conn.prepareStatement(
            "UPDATE actions SET status = ?, details = COALESCE(?, details), updated_at = ? WHERE id = ?",
        ).use { ps ->
            ps.setString(1, status); ps.setString(2, details); ps.setLong(3, clock.millis()); ps.setLong(4, id)
            ps.executeUpdate()
        }
        return byId(id)
    }

    @Synchronized
    fun byId(id: Long): Action? =
        conn.prepareStatement("SELECT * FROM actions WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toAction() else null }
        }

    /** The most recent action of [verb] on [jobKey], whatever its status. */
    @Synchronized
    fun latest(verb: String, jobKey: String): Action? =
        conn.prepareStatement("SELECT * FROM actions WHERE verb = ? AND job_key = ? ORDER BY id DESC LIMIT 1").use { ps ->
            ps.setString(1, verb); ps.setString(2, jobKey)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toAction() else null }
        }

    @Synchronized
    fun recent(limit: Int = 10): List<Action> =
        conn.prepareStatement("SELECT * FROM actions ORDER BY id DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toAction() else null }.toList() }
        }

    /** How many [verb] actions reached [status] since [sinceMillis] (the daily send cap). */
    @Synchronized
    fun countSince(verb: String, status: String, sinceMillis: Long): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM actions WHERE verb = ? AND status = ? AND updated_at >= ?").use { ps ->
            ps.setString(1, verb); ps.setString(2, status); ps.setLong(3, sinceMillis)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    /** Actions still waiting on the user (or on JobBot), by status. */
    @Synchronized
    fun pendingCounts(): Map<String, Int> =
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT status, COUNT(*) FROM actions WHERE status IN ('started','awaiting_approval','queued','filling','needs_human') GROUP BY status",
            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) to rs.getInt(2) else null }.toMap() }
        }

    @Synchronized
    fun recordError(context: String, message: String) {
        conn.prepareStatement("INSERT INTO errors(at, context, message) VALUES (?,?,?)").use { ps ->
            ps.setLong(1, clock.millis()); ps.setString(2, context); ps.setString(3, message.take(500))
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun recentErrors(limit: Int = 5): List<ErrorRow> =
        conn.prepareStatement("SELECT at, context, message FROM errors ORDER BY id DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) ErrorRow(rs.getLong(1), rs.getString(2), rs.getString(3)) else null }.toList()
            }
        }

    private fun ResultSet.toAction() = Action(
        id = getLong("id"), verb = getString("verb"), jobKey = getString("job_key"), seq = getLong("seq"),
        status = getString("status"), chatId = getString("chat_id"), messageId = getString("message_id"),
        details = getString("details"), createdAt = getLong("created_at"), updatedAt = getLong("updated_at"),
    )

    override fun close() = conn.close()
}
