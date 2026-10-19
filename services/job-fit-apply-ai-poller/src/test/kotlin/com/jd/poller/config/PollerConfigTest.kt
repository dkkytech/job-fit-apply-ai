package com.jd.poller.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@DisplayName("PollerConfig")
class PollerConfigTest {
    @Test
    @DisplayName("accepts the smallest valid batch and zero inter-submit delay")
    fun acceptsPacingBoundaries() {
        assertEquals(1, PollerConfig.positiveInt("INTAKE_BATCH_SIZE", "1"))
        assertEquals(0L, PollerConfig.nonNegativeLong("INTAKE_INTER_SUBMIT_DELAY_MS", "0"))
    }

    @Test
    @DisplayName("rejects malformed and overflow pacing values with actionable errors")
    fun rejectsMalformedPacingValues() {
        val batchError = assertFailsWith<IllegalArgumentException> {
            PollerConfig.positiveInt("INTAKE_BATCH_SIZE", "2147483648")
        }
        val delayError = assertFailsWith<IllegalArgumentException> {
            PollerConfig.nonNegativeLong("INTAKE_INTER_SUBMIT_DELAY_MS", "15s")
        }

        assertEquals("INTAKE_BATCH_SIZE must be an integer, got 2147483648", batchError.message)
        assertEquals("INTAKE_INTER_SUBMIT_DELAY_MS must be an integer, got 15s", delayError.message)
    }

    @Test
    @DisplayName("rejects an intake batch size below one")
    fun rejectsInvalidBatchSize() {
        val error = assertFailsWith<IllegalArgumentException> {
            PollerConfig.positiveInt("INTAKE_BATCH_SIZE", "0")
        }

        assertEquals("INTAKE_BATCH_SIZE must be >= 1, got 0", error.message)
    }

    @Test
    @DisplayName("rejects a negative inter-submit delay")
    fun rejectsNegativeDelay() {
        val error = assertFailsWith<IllegalArgumentException> {
            PollerConfig.nonNegativeLong("INTAKE_INTER_SUBMIT_DELAY_MS", "-1")
        }

        assertEquals("INTAKE_INTER_SUBMIT_DELAY_MS must be >= 0, got -1", error.message)
    }
}