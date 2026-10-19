package com.jd.jobbot.actions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock

/**
 * Messages jobbot-mcp needs posted to Telegram without a tap to answer (fill results, hand-offs,
 * review reminders). jobbot-mcp cannot reach Telegram; the Hermes plugin polls this outbox,
 * posts each item (with its buttons and screenshot), and acknowledges it.
 */
class Outbox(dbPath: String, private val shotsDir: Path, private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    @Serializable
    data class Item(
        val id: Long,
        val text: String,
        val buttons: List<InlineButton> = emptyList(),
        @SerialName("has_photo") val hasPhoto: Boolean = false,
    )

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute(
                "CREATE TABLE IF NOT EXISTS outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, " +
                    "buttons TEXT, photo TEXT, created_at INTEGER NOT NULL, delivered_at INTEGER)",
            )
        }
        Files.createDirectories(shotsDir)
    }

    @Synchronized
    fun post(text: String, buttons: List<InlineButton> = emptyList(), photo: ByteArray? = null): Long {
        val photoPath = photo?.let { bytes ->
            val p = shotsDir.resolve("shot-${clock.millis()}-${(0..9999).random()}.png")
            Files.write(p, bytes)
            p.toString()
        }
        conn.prepareStatement("INSERT INTO outbox(text, buttons, photo, created_at) VALUES (?,?,?,?)").use { ps ->
            ps.setString(1, text.take(MAX_TEXT)); ps.setString(2, JSON.encodeToString(BUTTONS, buttons))
            ps.setString(3, photoPath); ps.setLong(4, clock.millis())
            ps.executeUpdate()
        }
        return conn.createStatement().use { it.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
    }

    @Synchronized
    fun pending(limit: Int = 20): List<Item> =
        conn.prepareStatement("SELECT id, text, buttons, photo FROM outbox WHERE delivered_at IS NULL ORDER BY id LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else Item(
                        rs.getLong(1), rs.getString(2),
                        rs.getString(3)?.let { JSON.decodeFromString(BUTTONS, it) } ?: emptyList(),
                        rs.getString(4) != null,
                    )
                }.toList()
            }
        }

    @Synchronized
    fun photo(id: Long): ByteArray? =
        conn.prepareStatement("SELECT photo FROM outbox WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1)?.let { Files.readAllBytes(Path.of(it)) } else null }
        }

    @Synchronized
    fun delivered(id: Long) {
        conn.prepareStatement("UPDATE outbox SET delivered_at = ? WHERE id = ?").use { ps ->
            ps.setLong(1, clock.millis()); ps.setLong(2, id); ps.executeUpdate()
        }
    }

    override fun close() = conn.close()

    companion object {
        /** Telegram's caption limit is 1024; messages 4096. Keep items postable either way. */
        const val MAX_TEXT = 3800
        private val JSON = Json { ignoreUnknownKeys = true }
        private val BUTTONS = kotlinx.serialization.builtins.ListSerializer(InlineButton.serializer())
    }
}
