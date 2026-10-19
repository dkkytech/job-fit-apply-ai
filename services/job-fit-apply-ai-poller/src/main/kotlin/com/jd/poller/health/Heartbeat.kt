package com.jd.poller.health

import java.nio.file.Files
import java.nio.file.Path

/**
 * Liveness marker for the container healthcheck. Each loop iteration calls [beat], writing the
 * current epoch-millis to a file. `--health` reads it via [isFresh]. The path is injectable so
 * tests don't touch a shared location.
 *
 * A sibling `<path>.auth-failed` file records a dead Gmail grant ([markAuthFailed]): the loops
 * keep cycling (and beating) when every Gmail call fails, so freshness alone would hide it.
 */
class Heartbeat(private val path: Path) {

    private val authFailurePath: Path = path.resolveSibling("${path.fileName}.auth-failed")

    /** Record a beat. Best-effort — a failed write must never crash the poll loop. */
    fun beat(now: Long) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, now.toString())
        }
    }

    /** Age of the last beat in ms, or null if the file is missing/unreadable. */
    fun ageMillis(now: Long): Long? {
        if (!Files.exists(path)) return null
        val ts = runCatching { Files.readString(path).trim().toLong() }.getOrNull() ?: return null
        return now - ts
    }

    /** True when the last beat is within [maxAgeMs] (and not in the future beyond a small skew). */
    fun isFresh(maxAgeMs: Long, now: Long): Boolean {
        val age = ageMillis(now) ?: return false
        return age in -1_000..maxAgeMs
    }

    /** Record that Gmail auth is dead (needs --reauth). Best-effort, like [beat]. */
    fun markAuthFailed(reason: String) {
        runCatching {
            authFailurePath.parent?.let { Files.createDirectories(it) }
            Files.writeString(authFailurePath, reason)
        }
    }

    /** Clear a previous [markAuthFailed] after a successful token refresh or re-auth. */
    fun clearAuthFailure() {
        runCatching { Files.deleteIfExists(authFailurePath) }
    }

    /** The recorded auth-failure reason, or null when auth is not known to be dead. */
    fun authFailure(): String? {
        if (!Files.exists(authFailurePath)) return null
        return runCatching { Files.readString(authFailurePath).trim() }.getOrNull()
            ?.ifEmpty { null } ?: "Gmail auth failed - run --reauth"
    }

    companion object {
        fun fromConfig(path: String) = Heartbeat(Path.of(path))
    }
}
