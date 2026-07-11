package com.jd.pipeline.cli

import com.jd.pipeline.client.NotificationClient
import com.jd.pipeline.utils.NodeTimer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals

@DisplayName("BatchNotificationService")
class BatchNotificationServiceTest {

    private lateinit var client: NotificationClient
    private lateinit var service: BatchNotificationService

    private fun summary(
        emailsProcessed: Int = 1,
        jobs: Int = 1,
        tailored: Int = 0,
        skipped: Int = 0,
        duplicate: Int = 0,
        scoredJobs: List<ScoredJob> = emptyList(),
    ) = BatchNotificationService.BatchSummary(
        emailsProcessed = emailsProcessed,
        jobs            = jobs,
        tailored        = tailored,
        skipped         = skipped,
        duplicate       = duplicate,
        startTime       = Instant.now().minusSeconds(60),
        scoredJobs      = scoredJobs,
    )

    @BeforeEach
    fun setUp() {
        NodeTimer.reset()
        client = mock<NotificationClient>()
        service = BatchNotificationService(client = client, fitThreshold = 75)
    }

    @Nested
    @DisplayName("early-exit conditions")
    inner class EarlyExit {

        @Test
        @DisplayName("sends nothing when neither Discord nor Telegram is configured")
        fun skipsWhenNoChannelsConfigured() {
            whenever(client.discordConfigured).thenReturn(false)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notify(summary())
            verify(client, never()).postDiscord(any())
            verify(client, never()).postTelegram(any())
        }

        @Test
        @DisplayName("sends nothing when emailsProcessed is 0")
        fun skipsWhenNoEmailsProcessed() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notify(summary(emailsProcessed = 0))
            verify(client, never()).postDiscord(any())
            verify(client, never()).postTelegram(any())
        }
    }

    @Nested
    @DisplayName("Discord messages")
    inner class DiscordMessages {

        @BeforeEach
        fun enableDiscord() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(false)
        }

        @Test
        @DisplayName("posts batch summary to Discord")
        fun postsBatchSummary() {
            service.notify(summary(emailsProcessed = 3, jobs = 2, tailored = 1))
            verify(client).postDiscord(any())
        }

        @Test
        @DisplayName("posts at least two Discord messages when scoredJobs is non-empty")
        fun postsScoredJobsWhenPresent() {
            val jobs = listOf(ScoredJob("Acme", "Engineer", 80, "TAILOR", null))
            service.notify(summary(scoredJobs = jobs))
            // batch summary + scored-jobs list = at least 2 Discord calls
            verify(client, org.mockito.kotlin.atLeast(2)).postDiscord(any())
        }

        @Test
        @DisplayName("posts at least one Discord message even when scoredJobs is empty")
        fun postsAtLeastOneWhenNoScoredJobs() {
            service.notify(summary(scoredJobs = emptyList()))
            verify(client, org.mockito.kotlin.atLeast(1)).postDiscord(any())
        }
    }

    @Nested
    @DisplayName("Telegram high-fit ping")
    inner class TelegramPing {

        @BeforeEach
        fun enableBoth() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
        }

        @Test
        @DisplayName("sends Telegram ping when at least one job meets threshold")
        fun pingsSentForHighFitJob() {
            val jobs = listOf(ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null))
            service.notify(summary(scoredJobs = jobs))
            verify(client).postTelegram(any())
        }

        @Test
        @DisplayName("does not send Telegram ping when all jobs are below threshold")
        fun noPingBelowThreshold() {
            val jobs = listOf(ScoredJob("Acme", "Engineer", 50, "SKIP", null))
            service.notify(summary(scoredJobs = jobs))
            verify(client, never()).postTelegram(any())
        }

        @Test
        @DisplayName("does not send Telegram ping for high-score jobs that have errors")
        fun noPingForErrorJobs() {
            val jobs = listOf(ScoredJob("Acme", "Engineer", 90, "TAILOR", "LLM timeout"))
            service.notify(summary(scoredJobs = jobs))
            verify(client, never()).postTelegram(any())
        }

        @Test
        @DisplayName("still calls postTelegram when configured; the client handles unconfigured internally")
        fun telegramMethodCalledWhenConfigured() {
            // telegramConfigured=true is already set in @BeforeEach for this class
            val jobs = listOf(ScoredJob("Acme", "Staff SDET", 95, "TAILOR", null))
            service.notify(summary(scoredJobs = jobs))
            verify(client).postTelegram(any())
        }
    }

    @Nested
    @DisplayName("ScoredJob data class")
    inner class ScoredJobModel {

        @Test
        @DisplayName("holds all fields correctly")
        fun holdsFields() {
            val job = ScoredJob("Acme", "Staff SDET", 88, "TAILOR", null)
            assertEquals("Acme", job.company)
            assertEquals("Staff SDET", job.roleTitle)
            assertEquals(88, job.fitScore)
            assertEquals("TAILOR", job.pipelineAction)
            assertEquals(null, job.error)
        }

        @Test
        @DisplayName("supports null fitScore and error")
        fun supportsNulls() {
            val job = ScoredJob("Corp", "Engineer", null, null, "oops")
            assertEquals(null, job.fitScore)
            assertEquals("oops", job.error)
        }
    }
}
