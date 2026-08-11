package com.jd.poller.intake

import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.gmail.GmailClient
import com.jd.poller.health.Heartbeat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lifecycle tests for [IntakeLoop.runForever]: it must beat the heartbeat each pass, keep cycling
 * across a failing poll, and exit promptly when its thread is interrupted.
 */
@DisplayName("IntakeLoop.runForever")
class IntakeLoopRunForeverTest {

    /** Runs the loop on a daemon thread, waits until [ready] fires, interrupts, and joins. */
    private fun driveUntilInterrupt(loop: IntakeLoop, ready: CountDownLatch) {
        val thread = Thread({ loop.runForever(intervalMs = 5) }, "intake-test")
        thread.isDaemon = true
        thread.start()
        assertTrue(ready.await(3, TimeUnit.SECONDS), "loop did not run a pass in time")
        thread.interrupt()
        thread.join(3_000)
        assertFalse(thread.isAlive, "runForever should exit after interruption")
    }

    @Test
    @DisplayName("beats the heartbeat on a successful pass and stops when interrupted")
    fun beatsAndStopsOnSuccess(@TempDir tempDir: Path) {
        val ran = CountDownLatch(1)
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails() } doReturn emptyList()
        }
        // Count the latch down as a side effect of the first fetch.
        whenever(gmail.fetchIntakeEmails()).thenAnswer { ran.countDown(); emptyList<Any>() }
        val bridge = mock<PollerBridgeClient>()
        val heartbeat = Heartbeat(tempDir.resolve("hb"))

        driveUntilInterrupt(IntakeLoop(gmail, bridge, heartbeat), ran)

        assertNotNull(heartbeat.ageMillis(System.currentTimeMillis()), "heartbeat file should have been written")
    }

    @Test
    @DisplayName("keeps cycling when a poll throws, then stops when interrupted")
    fun survivesPollFailure(@TempDir tempDir: Path) {
        val ran = CountDownLatch(1)
        val gmail = mock<GmailClient>()
        whenever(gmail.fetchIntakeEmails()).thenAnswer { ran.countDown(); throw RuntimeException("gmail down") }
        val bridge = mock<PollerBridgeClient>()
        val heartbeat = Heartbeat(tempDir.resolve("hb"))

        // A throwing pollOnce is swallowed by runForever's runCatching; the loop still beats and
        // remains interruptible.
        driveUntilInterrupt(IntakeLoop(gmail, bridge, heartbeat), ran)

        assertNotNull(heartbeat.ageMillis(System.currentTimeMillis()), "heartbeat should beat even after a failed poll")
    }
}
