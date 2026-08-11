package com.jd.e2e

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * Every endpoint the suite touches is an env var with a localhost default matching the
 * docker-compose.e2e.yml override (alternate ports so the e2e slice coexists with a
 * production stack on the same host). Point these at any deployed stack to reuse the
 * suite as a synthetic monitor.
 */
object E2eConfig {
    private fun get(key: String, default: String): String =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

    /** Numeric env vars, with the offending var named on a bad value. */
    private fun getInt(key: String, default: String): Int {
        val raw = get(key, default)
        return raw.toIntOrNull() ?: error("$key must be an integer, got '$raw'")
    }

    private fun getLong(key: String, default: String): Long {
        val raw = get(key, default)
        return raw.toLongOrNull() ?: error("$key must be an integer, got '$raw'")
    }

    val bridgeUrl: String = get("E2E_BRIDGE_URL", "http://127.0.0.1:18765").trimEnd('/')
    val markservUrl: String = get("E2E_MARKSERV_URL", "http://127.0.0.1:18082").trimEnd('/')

    /** postgres URI form (repo convention); converted to JDBC in [pgConnection]. */
    val databaseUrl: String = get("E2E_DATABASE_URL", "postgresql://jobfit:jobfit@127.0.0.1:15433/jobfit")

    /**
     * Must match the value docker compose interpolated at `up` time — both sides read
     * the same env var, so export it once (or take both defaults).
     *
     * The default is deliberately NOT 11436: that is the production oMLX port, which
     * docker-compose.yml also points the production processor at. Sharing it is unsafe
     * in both directions — the fake binds 0.0.0.0 successfully even while oMLX holds
     * 127.0.0.1:11436 (SO_REUSEADDR), but the more-specific loopback socket wins, so
     * (a) the e2e run would silently hit real oMLX and time out, and (b) with oMLX down
     * the fake would answer the *production* processor with fixture data. Under
     * REAL_LLM=1 the Makefile sets this to 11436 on purpose (the fake never starts).
     */
    val fakeLlmPort: Int = getInt("E2E_FAKE_LLM_PORT", "21436")
    val sinkPort: Int = getInt("E2E_SINK_PORT", "18099")

    /**
     * The dummy notification credentials docker-compose.e2e.yml gives the notifier.
     * The sink asserts the inbound paths carry these, so a client that posts to a
     * wrong-but-plausible URL is caught instead of silently accepted.
     */
    val discordChannelId: String = get("E2E_DISCORD_CHANNEL_ID", "e2e-channel")
    val telegramBotToken: String = get("E2E_TELEGRAM_BOT_TOKEN", "e2e-telegram-token")

    val timeoutSeconds: Long = getLong("E2E_TIMEOUT_SECONDS", "300")

    /**
     * Grace window a scenario waits before snapshotting the evidence its "exactly one" and
     * "none at all" assertions read. Those two shapes are the only ones that can pass by
     * arriving late rather than by being right, so they get a window; every positive check
     * still polls. Raise it on a slow runner — it is paid once per scenario, not per check.
     */
    val settleMs: Long = getLong("E2E_SETTLE_MS", "1000")

    /** 1 = don't start the fake; the container's MLX port is a real local model server. */
    val realLlm: Boolean = get("E2E_REAL_LLM", "0") == "1"

    /** Host-side view of the processor's /app/output mount. Relative to this module dir. */
    val outputDir: Path = Paths.get(get("E2E_OUTPUT_DIR", "../../.e2e/output")).toAbsolutePath().normalize()

    /** Host-side view of the whole test slice's state dir (bridge store, notifier cursor…). */
    val stateDir: Path = Paths.get(get("E2E_STATE_DIR", "../../.e2e")).toAbsolutePath().normalize()

    val fixturesDir: Path = Paths.get(get("E2E_FIXTURES_DIR", "fixtures")).toAbsolutePath().normalize()

    // ── Second, "source"-shaped slice (multi-instance scenarios 9/10, issue #56) ──
    // Present only when `make e2e-multi` / the CI e2e job started the source slice; the
    // multi-instance test classes assume-skip when E2E_SOURCE_BRIDGE_URL is unset, so a
    // plain `make e2e` stays green with no extra containers.

    /** Null when no source slice is running — the multi-instance scenarios skip then. */
    val sourceBridgeUrl: String? =
        System.getenv("E2E_SOURCE_BRIDGE_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/')

    val sourceConfigured: Boolean get() = sourceBridgeUrl != null

    val sourceDatabaseUrl: String =
        get("E2E_SOURCE_DATABASE_URL", "postgresql://jobfit:jobfit@127.0.0.1:15434/jobfit")

    /** Host-side view of the source slice's state dir (its bridge store, notifier cursor…). */
    val sourceStateDir: Path =
        Paths.get(get("E2E_SOURCE_STATE_DIR", "../../.e2e-src")).toAbsolutePath().normalize()

    /** Compose project names, for container-level isolation assertions (docker ps/inspect). */
    val sourceProject: String = get("E2E_SOURCE_PROJECT", "jobfit-e2e-src")
    val testProject: String = get("E2E_PROJECT", "jobfit-e2e")

    fun pgConnection(): Connection = pgConnection(databaseUrl)

    fun sourcePgConnection(): Connection = pgConnection(sourceDatabaseUrl)

    private fun pgConnection(url: String): Connection {
        val uri = URI(url.removePrefix("jdbc:"))
        val userInfo = (uri.userInfo ?: "jobfit:jobfit").split(":", limit = 2)
        val port = if (uri.port > 0) uri.port else 5432
        val jdbc = "jdbc:postgresql://${uri.host}:$port${uri.path}"
        return DriverManager.getConnection(jdbc, userInfo[0], userInfo.getOrElse(1) { "" })
    }

    /** Read-only JDBC connection to a bridge instance's SQLite store on the host. */
    fun bridgeStoreConnection(storeDir: Path): Connection {
        val db = storeDir.resolve("bridge-store/jobs.db")
        check(java.nio.file.Files.exists(db)) { "no bridge store at $db — is the slice up?" }
        val props = java.util.Properties().apply { setProperty("open_mode", "1") } // SQLITE_OPEN_READONLY
        return DriverManager.getConnection("jdbc:sqlite:$db", props)
    }
}
