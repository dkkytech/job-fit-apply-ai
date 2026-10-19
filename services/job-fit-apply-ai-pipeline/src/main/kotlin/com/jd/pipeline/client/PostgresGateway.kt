package com.jd.pipeline.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.jd.pipeline.config.Config
import org.postgresql.util.PGobject
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types

/**
 * Direct-JDBC implementation of [SupabaseGateway] against the self-hosted Postgres
 * container. The drop-in replacement for [SupabaseClient] under the direct-Postgres
 * design (plan B) — selected by [GatewayProvider] when DB_BACKEND=postgres.
 *
 * Deliberately connection-per-call (no pool): the pipeline is low-concurrency and
 * runs as short-lived jobs, so a pool would add dependencies for no real benefit.
 *
 * Values are always bound as parameters. Table/column identifiers come from in-repo
 * constants (never user input) but are still validated defensively.
 */
object PostgresGateway : SupabaseGateway {

    private val mapper = ObjectMapper()
    private val identifier = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    // DATABASE_URL is a libpq-style URL: postgresql://user:pass@host:port/db
    private data class Dsn(val jdbcUrl: String, val user: String, val password: String)

    private val dsn: Dsn by lazy { parse(Config.DATABASE_URL) }

    private fun parse(url: String): Dsn {
        val uri = URI(url)
        val userInfo = uri.userInfo?.split(":", limit = 2) ?: emptyList()
        val user = userInfo.getOrElse(0) { "" }
        val password = userInfo.getOrElse(1) { "" }
        val port = if (uri.port > 0) uri.port else 5432
        val db = uri.path.trimStart('/')
        // stringtype=unspecified: send String params as `unknown` so Postgres infers the
        // column type from context (e.g. `created_at >= ?` binds a timestamptz, not varchar).
        // This mirrors how PostgREST's untyped literals behaved.
        return Dsn("jdbc:postgresql://${uri.host}:$port/$db?stringtype=unspecified", user, password)
    }

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(dsn.jdbcUrl, dsn.user, dsn.password).use(block)

    private fun requireIdentifier(name: String): String {
        require(identifier.matches(name)) { "Invalid SQL identifier: $name" }
        return name
    }

    override fun isConfigured(): Boolean = Config.DATABASE_URL.isNotBlank()

    override fun insert(table: String, record: Map<String, Any?>): JsonNode {
        requireIdentifier(table)
        val cols = record.keys.onEach { requireIdentifier(it) }.toList()
        val placeholders = cols.indices.joinToString(", ") { "?" }
        val sql = "INSERT INTO $table (${cols.joinToString(", ")}) VALUES ($placeholders) RETURNING *"

        return withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                cols.forEachIndexed { i, col -> bind(conn, ps, i + 1, record[col]) }
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToJson(rs) else mapper.createObjectNode()
                }
            }
        }
    }

    override fun query(
        table: String,
        filters: Map<String, String>,
        select: String,
        limit: Int
    ): List<JsonNode> {
        requireIdentifier(table)
        // select is "*" or a comma list of column names
        val cols = if (select.trim() == "*") "*"
        else select.split(",").joinToString(", ") { requireIdentifier(it.trim()) }

        val conds = mutableListOf<String>()
        val values = mutableListOf<String>()
        filters.forEach { (col, expr) ->
            requireIdentifier(col)
            val (op, value) = parseFilter(expr)
            conds += "$col $op ?"
            values += value
        }
        val where = if (conds.isEmpty()) "" else " WHERE ${conds.joinToString(" AND ")}"
        val sql = "SELECT $cols FROM $table$where LIMIT ?"

        return withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                values.forEachIndexed { i, v -> ps.setString(i + 1, v) }
                ps.setInt(values.size + 1, limit)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rowToJson(rs)) }
                }
            }
        }
    }

    override fun delete(table: String, filterCol: String, filterVal: String) {
        requireIdentifier(table)
        requireIdentifier(filterCol)
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM $table WHERE $filterCol = ?").use { ps ->
                ps.setString(1, filterVal)
                ps.executeUpdate()
            }
        }
    }

    override fun update(table: String, record: Map<String, Any?>, filterCol: String, filterVal: String): JsonNode {
        requireIdentifier(table)
        requireIdentifier(filterCol)
        val cols = record.keys.onEach { requireIdentifier(it) }.toList()
        require(cols.isNotEmpty()) { "update($table) with no columns" }
        val sql = "UPDATE $table SET ${cols.joinToString(", ") { "$it = ?" }} WHERE $filterCol = ? RETURNING *"

        return withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                cols.forEachIndexed { i, col -> bind(conn, ps, i + 1, record[col]) }
                ps.setString(cols.size + 1, filterVal)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rowToJson(rs) else mapper.createObjectNode()
                }
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Translate a PostgREST filter expr ("eq.value", "gte.value") into (sqlOp, value). */
    private fun parseFilter(expr: String): Pair<String, String> {
        val dot = expr.indexOf('.')
        if (dot < 0) return "=" to expr
        val op = expr.substring(0, dot)
        val value = expr.substring(dot + 1)
        val sqlOp = when (op) {
            "eq" -> "="
            "neq" -> "<>"
            "gt" -> ">"
            "gte" -> ">="
            "lt" -> "<"
            "lte" -> "<="
            "ilike" -> "ILIKE"
            "like" -> "LIKE"
            else -> "="
        }
        return sqlOp to value
    }

    /** Bind a Kotlin value to a JDBC parameter, mapping Lists → text[] and Maps → jsonb. */
    private fun bind(conn: Connection, ps: java.sql.PreparedStatement, idx: Int, value: Any?) {
        when (value) {
            null -> ps.setNull(idx, Types.NULL)
            is String -> ps.setString(idx, value)
            is Int -> ps.setInt(idx, value)
            is Long -> ps.setLong(idx, value)
            is Float -> ps.setFloat(idx, value)
            is Double -> ps.setDouble(idx, value)
            is Boolean -> ps.setBoolean(idx, value)
            is List<*> -> {
                if (value.all { it is String || it == null }) {
                    ps.setArray(idx, conn.createArrayOf("text", value.toTypedArray()))
                } else {
                    ps.setObject(idx, jsonb(value))
                }
            }
            is Map<*, *> -> ps.setObject(idx, jsonb(value))
            else -> ps.setObject(idx, value)
        }
    }

    private fun jsonb(value: Any?): PGobject = PGobject().apply {
        type = "jsonb"
        this.value = mapper.writeValueAsString(value)
    }

    /** Convert the current [ResultSet] row into a Jackson [ObjectNode] with typed values. */
    private fun rowToJson(rs: ResultSet): ObjectNode {
        val node = mapper.createObjectNode()
        val meta = rs.metaData
        for (i in 1..meta.columnCount) {
            val name = meta.getColumnLabel(i)
            when (val v = rs.getObject(i)) {
                null -> node.putNull(name)
                is java.sql.Array -> {
                    val arr: ArrayNode = node.putArray(name)
                    (v.array as? Array<*>)?.forEach { el ->
                        if (el == null) arr.addNull() else arr.add(el.toString())
                    }
                }
                is Boolean -> node.put(name, v)
                is Int -> node.put(name, v)
                is Long -> node.put(name, v)
                is Short -> node.put(name, v.toInt())
                is Float -> node.put(name, v)
                is Double -> node.put(name, v)
                is java.math.BigDecimal -> node.put(name, v)
                is Timestamp -> node.put(name, v.toInstant().toString())
                is PGobject -> {
                    // jsonb/json columns arrive as PGobject — reparse into structured JSON
                    runCatching { node.set<JsonNode>(name, mapper.readTree(v.value)) }
                        .onFailure { node.put(name, v.value) }
                }
                else -> node.put(name, v.toString())
            }
        }
        return node
    }
}
