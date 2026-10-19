package com.jd.watchlist

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docker-entrypoint.sh drives the healthcheck: the container is healthy while the heartbeat file
 * is fresh. A full sweep takes ~10–15 min, longer than the healthcheck's start period, so the loop
 * must mark itself alive before the first sweep — not only after it (PR #140).
 */
class EntrypointTest {
    @TempDir lateinit var dir: Path

    private fun runEntrypoint(stubBody: String, env: Map<String, String> = emptyMap()): Pair<Process, Path> {
        val heartbeat = dir.resolve("heartbeat")
        val marker = dir.resolve("marker")
        val stub = dir.resolve("watchlist-stub").also {
            Files.writeString(it, "#!/bin/sh\n$stubBody\n")
            it.toFile().setExecutable(true)
        }
        val proc = ProcessBuilder("sh", Path.of("docker-entrypoint.sh").toAbsolutePath().toString())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("out.log").toFile())
            .also {
                it.environment()["HEARTBEAT_FILE"] = heartbeat.toString()
                it.environment()["WATCHLIST_BIN"] = stub.toString()
                it.environment()["WATCHLIST_CHECK_INTERVAL_S"] = "3600"
                it.environment()["MARKER"] = marker.toString()
                it.environment()["WATCHLIST_NEXT_DUE_FILE"] = dir.resolve("next-due").toString()
                it.environment().putAll(env)
            }
            .start()
        return proc to marker
    }

    /**
     * Kill the loop, then its `sleep` child, so no orphaned sleep outlives the test. Loop first:
     * killing the sleep first lets the loop run another pass while @TempDir is being deleted.
     */
    private fun stop(proc: Process) {
        val children = proc.descendants().toList()
        proc.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        children.forEach { it.destroyForcibly(); it.onExit().get(5, TimeUnit.SECONDS) }
    }

    private fun awaitFile(path: Path, timeoutMs: Long = 10_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path) && Files.size(path) > 0) return Files.readString(path).trim()
            Thread.sleep(50)
        }
        error("timed out waiting for $path")
    }

    @Test
    fun `heartbeat exists before the first sweep runs`() {
        // The stub records whether the heartbeat was already there when the sweep started.
        val (proc, marker) = runEntrypoint("""if [ -f "${'$'}HEARTBEAT_FILE" ]; then echo present > "${'$'}MARKER"; else echo missing > "${'$'}MARKER"; fi""")
        try {
            assertEquals("present", awaitFile(marker))
        } finally {
            stop(proc)
        }
    }

    @Test
    fun `a failing sweep does not stop the loop and still refreshes the heartbeat`() {
        val (proc, marker) = runEntrypoint("""rm -f "${'$'}HEARTBEAT_FILE"; echo ran > "${'$'}MARKER"; exit 1""")
        try {
            assertEquals("ran", awaitFile(marker))
            val deadline = System.currentTimeMillis() + 10_000
            while (!Files.exists(dir.resolve("heartbeat")) && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(Files.exists(dir.resolve("heartbeat")), "the loop must touch the heartbeat after a failed --once")
            assertTrue(proc.isAlive, "the loop keeps running (now sleeping until the next tick)")
            assertTrue(Files.readString(dir.resolve("out.log")).contains("--once exited non-zero"))
        } finally {
            stop(proc)
        }
    }

    /** Polls [marker] (one line per sweep) until it has [n] lines or [timeoutMs] passes; returns the count. */
    private fun awaitSweeps(marker: Path, n: Int, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun count() = if (Files.exists(marker)) Files.readAllLines(marker).size else 0
        while (count() < n && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return count()
    }

    /** A stub sweep that logs itself and says the next one is due [dueExpr] (shell arithmetic on `now`). */
    private fun sweepDue(dueExpr: String) =
        """echo ran >> "${'$'}MARKER"; now=${'$'}(date +%s); echo ${'$'}(($dueExpr)) > "${'$'}WATCHLIST_NEXT_DUE_FILE""""

    @Test
    fun `wakes when the next sweep is due instead of waiting out the hourly tick`() {
        val (proc, marker) = runEntrypoint(sweepDue("now + 2"), mapOf("WATCHLIST_MIN_SLEEP_S" to "1"))
        try {
            assertEquals(2, awaitSweeps(marker, 2, 8_000), "the second --once ran ~2s later, not 3600s later")
        } finally {
            stop(proc)
        }
    }

    @Test
    fun `a far-off due time still wakes every check interval to refresh the heartbeat`() {
        val (proc, marker) = runEntrypoint(sweepDue("now + 86400"), mapOf("WATCHLIST_CHECK_INTERVAL_S" to "1"))
        try {
            assertEquals(2, awaitSweeps(marker, 2, 8_000))
        } finally {
            stop(proc)
        }
    }

    @Test
    fun `a due time in the past or garbage cannot spin the loop`() {
        for (due in listOf("now - 600", "0")) {
            val (proc, marker) = runEntrypoint(sweepDue(due), mapOf("WATCHLIST_MIN_SLEEP_S" to "2"))
            try {
                assertEquals(1, awaitSweeps(marker, 2, 1_500), "no second sweep before the 2s floor ($due)")
                assertEquals(2, awaitSweeps(marker, 2, 8_000), "but one after it ($due)")
            } finally {
                stop(proc)
                Files.deleteIfExists(marker)
            }
        }
        val (proc, marker) = runEntrypoint("""echo ran >> "${'$'}MARKER"; echo soon > "${'$'}WATCHLIST_NEXT_DUE_FILE"""",
            mapOf("WATCHLIST_MIN_SLEEP_S" to "1", "WATCHLIST_CHECK_INTERVAL_S" to "2"))
        try {
            assertEquals(1, awaitSweeps(marker, 2, 1_500), "a non-number falls back to the check interval, not the floor")
            assertEquals(2, awaitSweeps(marker, 2, 8_000))
        } finally {
            stop(proc)
        }
    }
}
