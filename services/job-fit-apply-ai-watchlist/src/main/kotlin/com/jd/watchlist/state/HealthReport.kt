package com.jd.watchlist.state

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path

/**
 * Outcome of one employer in one run. Feeds rot quietly (rebrands, ATS migrations, renamed
 * boards), so a feed that errors or suddenly lists zero jobs is the signal to re-check the entry.
 */
data class EmployerHealth(
    val company: String,
    val ats: String,
    val listed: Int = 0,
    val matched: Int = 0,
    val queued: Int = 0,
    val deduped: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
    /** Consecutive runs (including this one) in which this employer was [suspect]; 0 when healthy. */
    val suspectRuns: Int = 0,
    /** Copied from [com.jd.watchlist.model.Employer.mayBeEmpty]: listing nothing is normal here. */
    val mayBeEmpty: Boolean = false,
) {
    /**
     * True when the entry needs a human look: the feed failed, or it listed nothing at all (unless
     * that is this employer's normal state).
     */
    val suspect: Boolean get() = error != null || (listed == 0 && !mayBeEmpty)
}

data class RunHealth(
    val ranAt: Long,
    val employers: List<EmployerHealth>,
    val unsupported: Int,
) {
    val polled: Int get() = employers.size
    val failing: List<String> get() = employers.filter { it.error != null }.map { it.company }
    val empty: List<String> get() = employers.filter { it.error == null && it.listed == 0 && !it.mayBeEmpty }.map { it.company }
}

/** Writes the last run's [RunHealth] as JSON (read by scripts/doctor.sh). Best-effort. */
class HealthReport(private val path: Path) {
    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(SerializationFeature.INDENT_OUTPUT)

    /** Each employer's [EmployerHealth.suspectRuns] from the last written run (empty if none/unreadable). */
    fun previousSuspectRuns(): Map<String, Int> = runCatching {
        if (!Files.exists(path)) return emptyMap()
        mapper.readTree(Files.readString(path)).path("employers")
            .associate { it.path("company").asText() to it.path("suspect_runs").asInt(0) }
    }.getOrDefault(emptyMap())

    fun write(health: RunHealth) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, mapper.writeValueAsString(health))
        }.onFailure { System.err.println("[health] failed to write $path: ${it.message}") }
    }
}
