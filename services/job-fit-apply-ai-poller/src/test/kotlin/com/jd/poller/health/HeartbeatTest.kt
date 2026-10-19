package com.jd.poller.health

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("HeartbeatTest")
class HeartbeatTest {

    @Test
    @DisplayName("beat writes a timestamp; ageMillis reflects elapsed time")
    fun beatThenAge(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        hb.beat(now = 1_000_000L)
        assertEquals(500L, hb.ageMillis(now = 1_000_500L))
    }

    @Test
    @DisplayName("beat creates parent directories")
    fun beatCreatesParents(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("nested/sub/hb"))
        hb.beat(now = 42L)
        assertEquals(0L, hb.ageMillis(now = 42L))
    }

    @Test
    @DisplayName("ageMillis is null when the file is missing")
    fun missingFileIsNull(@TempDir dir: Path) {
        assertNull(Heartbeat(dir.resolve("absent")).ageMillis(now = 1L))
    }

    @Test
    @DisplayName("isFresh true within the window, false when stale or missing")
    fun freshness(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        hb.beat(now = 10_000L)
        assertTrue(hb.isFresh(maxAgeMs = 5_000L, now = 12_000L), "2s old within a 5s window is fresh")
        assertFalse(hb.isFresh(maxAgeMs = 5_000L, now = 20_000L), "10s old beyond a 5s window is stale")
        assertFalse(Heartbeat(dir.resolve("absent")).isFresh(maxAgeMs = 5_000L, now = 1L), "missing is not fresh")
    }

    @Test
    @DisplayName("markAuthFailed records a reason in a sibling file; clearAuthFailure removes it")
    fun authFailureMarker(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        assertNull(hb.authFailure(), "no marker by default")

        hb.markAuthFailed("invalid_grant - run --reauth")
        assertEquals("invalid_grant - run --reauth", hb.authFailure())
        assertTrue(dir.resolve("hb.auth-failed").toFile().exists())

        hb.clearAuthFailure()
        assertNull(hb.authFailure())
    }

    @Test
    @DisplayName("an empty auth-failure marker still reports a failure")
    fun emptyAuthFailureMarker(@TempDir dir: Path) {
        dir.resolve("hb.auth-failed").toFile().writeText("")
        assertEquals("Gmail auth failed - run --reauth", Heartbeat(dir.resolve("hb")).authFailure())
    }

    @Test
    @DisplayName("small clock skew (beat slightly in the future) still counts as fresh")
    fun toleratesSkew(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        hb.beat(now = 10_000L)
        assertTrue(hb.isFresh(maxAgeMs = 5_000L, now = 9_500L), "500ms future skew tolerated")
    }
}
