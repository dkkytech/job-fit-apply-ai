package com.jd.poller.intake

import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.config.PollerConfig
import com.jd.poller.gmail.GmailClient
import com.jd.poller.gmail.TerminalLabels
import com.jd.poller.health.Heartbeat

/**
 * Intake loop: fetch unprocessed JD emails from Gmail and submit each to the bridge as an
 * EMAIL_RAW work item, then mark it Processing so it isn't re-fetched. The bridge dedups by
 * message_id, so a re-submit after a labeling failure is harmless.
 */
class IntakeLoop(
    private val gmail: GmailClient,
    private val bridge: PollerBridgeClient,
    private val heartbeat: Heartbeat? = null,
    private val batchSize: Int = PollerConfig.INTAKE_BATCH_SIZE,
    private val interSubmitDelayMs: Long = PollerConfig.INTAKE_INTER_SUBMIT_DELAY_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** One intake pass. Returns the number of emails submitted to the bridge. */
    fun pollOnce(): Int {
        val emails = gmail.fetchIntakeEmails(batchSize)
        var submitted = 0
        for ((index, email) in emails.withIndex()) {
            try {
                val jobId = bridge.submitEmail(email)
                // Apply the in-flight label AFTER a successful submit so a failed submit is
                // retried next pass (the email stays unlabeled → re-fetched).
                runCatching { gmail.labelEmail(email.messageId, gmail.getOrCreateLabel(TerminalLabels.PROCESSING)) }
                    .onFailure { System.err.println("[intake] Processing label failed for ${email.messageId}: ${it.message}") }
                submitted++
                println("[intake] submitted ${email.messageId} → job $jobId")
                if (index < emails.lastIndex) {
                    try {
                        sleep(interSubmitDelayMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        // Don't swallow the shutdown signal — exit this pass immediately
                        break
                    }
                }
            } catch (e: Exception) {
                System.err.println("[intake] submit failed for ${email.messageId}: ${e.message}")
            }
        }
        return submitted
    }

    fun runForever(intervalMs: Long = PollerConfig.INTAKE_POLL_INTERVAL_MS) {
        while (!Thread.currentThread().isInterrupted) {
            runCatching { pollOnce() }.onFailure { System.err.println("[intake] poll error: ${it.message}") }
            heartbeat?.beat(System.currentTimeMillis())   // liveness: the loop is cycling
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
