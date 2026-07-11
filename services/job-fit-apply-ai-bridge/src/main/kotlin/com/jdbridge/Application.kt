package com.jdbridge

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger("com.jdbridge.Application")

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    loadDotEnv()
    val tailscaleHost = resolveTailscaleHost(getEnv("JD_BRIDGE_HOST", "__tailscale__"))
    val port = getEnv("JD_BRIDGE_PORT", "8765").toIntOrNull() ?: 8765

    val hosts = buildList {
        add("127.0.0.1")
        if (tailscaleHost != null && tailscaleHost != "127.0.0.1") add(tailscaleHost)
    }
    log.info("jd-bridge binding to ${hosts.joinToString(", ")} on port $port")

    val env = applicationEngineEnvironment {
        for (h in hosts) connector { host = h; this.port = port }
        module { configureApplication() }
    }
    embeddedServer(Netty, env).start(wait = true)
}

// ── Application configuration (injectable for tests) ─────────────────────────

fun Application.configureApplication() {
    val jobsDir = STORE_DIR.resolve("jobs").toFile().also { it.mkdirs() }

    install(ContentNegotiation) {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        json(Json {
            explicitNulls  = false
            encodeDefaults = true
        })
    }

    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
    }

    routing {
        staticFiles("/api/jobs-static", jobsDir)
        configureRoutes()
    }

    environment.monitor.subscribe(ApplicationStarted) { app ->
        app.launch {
            initDb()
            log.info("jd-bridge ready. Artifacts at $jobsDir")
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Read from OS env first, fall back to JVM system property, then default. */
fun getEnv(key: String, default: String = ""): String =
    System.getenv(key) ?: System.getProperty(key) ?: default

/**
 * Resolve the __tailscale__ sentinel to the node's Tailscale IPv4 address.
 * Returns null if Tailscale is unavailable or the host override is not the sentinel.
 */
fun resolveTailscaleHost(host: String): String? {
    if (host != "__tailscale__") return host.ifBlank { null }
    return try {
        val result = ProcessBuilder("tailscale", "ip", "-4")
            .redirectErrorStream(true)
            .start()
        val ip = result.inputStream.bufferedReader().readText().trim()
        result.waitFor()
        if (ip.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
            log.info("Resolved Tailscale IP: $ip")
            ip
        } else {
            log.warn("tailscale ip -4 returned unexpected output — Tailscale connector skipped")
            null
        }
    } catch (e: Exception) {
        log.warn("Could not resolve Tailscale IP (${e.message}) — Tailscale connector skipped")
        null
    }
}

/**
 * Load key=value pairs from a .env file in the working directory.
 * Only sets properties not already present in the OS environment.
 */
fun loadDotEnv() {
    val envFile = Path.of(System.getProperty("user.dir"), ".env").toFile()
    if (!envFile.exists()) return
    envFile.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx < 1) return@forEachLine
        val key   = trimmed.substring(0, eqIdx).trim()
        val value = trimmed.substring(eqIdx + 1).trim()
        if (System.getenv(key) == null && System.getProperty(key) == null) {
            System.setProperty(key, value)
        }
    }
}
