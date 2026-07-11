package com.jd.pipeline.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for JSearchConfig data class.
 *
 * Covers default values, custom overrides, and companion object presets.
 */
class JSearchConfigTest {

    @Nested
    @DisplayName("Default Values Tests")
    inner class DefaultValuesTests {

        @Test
        @DisplayName("Should have default numPages of 1")
        fun testDefaultNumPages() {
            // Given
            val config = JSearchConfig(queries = listOf("test"))

            // Then
            assertEquals(1, config.numPages)
        }

        @Test
        @DisplayName("Should have default datePosted of 'today'")
        fun testDefaultDatePosted() {
            // Given
            val config = JSearchConfig(queries = listOf("test"))

            // Then
            assertEquals("today", config.datePosted)
        }

        @Test
        @DisplayName("Should have default location of 'Seattle, WA'")
        fun testDefaultLocation() {
            // Given
            val config = JSearchConfig(queries = listOf("test"))

            // Then
            assertEquals("Seattle, WA", config.location)
        }

        @Test
        @DisplayName("Should have default radius of 30")
        fun testDefaultRadius() {
            // Given
            val config = JSearchConfig(queries = listOf("test"))

            // Then
            assertEquals(30, config.radius)
        }

        @Test
        @DisplayName("Should have default remoteJobsOnly of false")
        fun testDefaultRemoteJobsOnly() {
            // Given
            val config = JSearchConfig(queries = listOf("test"))

            // Then
            assertFalse(config.remoteJobsOnly)
        }
    }

    @Nested
    @DisplayName("Custom Values Override Tests")
    inner class CustomValuesTests {

        @Test
        @DisplayName("Should override numPages correctly")
        fun testOverrideNumPages() {
            // Given
            val config = JSearchConfig(
                queries = listOf("test"),
                numPages = 5
            )

            // Then
            assertEquals(5, config.numPages)
        }

        @Test
        @DisplayName("Should override datePosted correctly")
        fun testOverrideDatePosted() {
            // Given
            val config = JSearchConfig(
                queries = listOf("test"),
                datePosted = "week"
            )

            // Then
            assertEquals("week", config.datePosted)
        }

        @Test
        @DisplayName("Should override location correctly")
        fun testOverrideLocation() {
            // Given
            val config = JSearchConfig(
                queries = listOf("test"),
                location = "San Francisco, CA"
            )

            // Then
            assertEquals("San Francisco, CA", config.location)
        }

        @Test
        @DisplayName("Should override location to null")
        fun testOverrideLocationToNull() {
            // Given
            val config = JSearchConfig(
                queries = listOf("test"),
                location = null
            )

            // Then
            assertNull(config.location)
        }

        @Test
        @DisplayName("Should override radius correctly")
        fun testOverrideRadius() {
            // Given
            val config = JSearchConfig(
                queries = listOf("test"),
                radius = 50
            )

            // Then
            assertEquals(50, config.radius)
        }

        @Test
        @DisplayName("Should override remoteJobsOnly correctly")
        fun testOverrideRemoteJobsOnly() {
            // Given
            val config = JSearchConfig(
                queries = listOf("test"),
                remoteJobsOnly = true
            )

            // Then
            assertTrue(config.remoteJobsOnly)
        }

        @Test
        @DisplayName("Should allow multiple custom values simultaneously")
        fun testMultipleOverrides() {
            // Given
            val config = JSearchConfig(
                queries = listOf("SDET", "QA Engineer"),
                numPages = 3,
                datePosted = "3days",
                location = "Portland, OR",
                radius = 50,
                remoteJobsOnly = true
            )

            // Then
            assertEquals(listOf("SDET", "QA Engineer"), config.queries)
            assertEquals(3, config.numPages)
            assertEquals("3days", config.datePosted)
            assertEquals("Portland, OR", config.location)
            assertEquals(50, config.radius)
            assertTrue(config.remoteJobsOnly)
        }
    }

    @Nested
    @DisplayName("Companion Object DEFAULT Preset Tests")
    inner class DefaultPresetTests {

        @Test
        @DisplayName("DEFAULT preset should have expected queries")
        fun testDefaultPresetQueries() {
            // Given
            val default = JSearchConfig.DEFAULT

            // Then
            assertEquals(2, default.queries.size)
            assertTrue(default.queries[0].contains("SDET"))
            assertTrue(default.queries[0].contains("test engineer"))
            assertTrue(default.queries[0].contains("QA engineer"))
            assertTrue(default.queries[1].contains("SDET"))
            assertTrue(default.queries[1].contains("-mobile"))
        }

        @Test
        @DisplayName("DEFAULT preset should have numPages of 1 (class default)")
        fun testDefaultPresetNumPages() {
            // Given
            val default = JSearchConfig.DEFAULT

            // Then (numPages uses class default of 1)
            assertEquals(1, default.numPages)
        }

        @Test
        @DisplayName("DEFAULT preset should use default location")
        fun testDefaultPresetLocation() {
            // Given
            val default = JSearchConfig.DEFAULT

            // Then
            assertEquals("Seattle, WA", default.location)
        }

        @Test
        @DisplayName("DEFAULT preset should use default remoteJobsOnly")
        fun testDefaultPresetRemoteJobsOnly() {
            // Given
            val default = JSearchConfig.DEFAULT

            // Then
            assertFalse(default.remoteJobsOnly)
        }
    }

    @Nested
    @DisplayName("Companion Object DEFAULT_LIST Preset Tests")
    inner class DefaultListPresetTests {

        @Test
        @DisplayName("DEFAULT_LIST should contain exactly 2 configs")
        fun testDefaultListSize() {
            // Given
            val defaultList = JSearchConfig.DEFAULT_LIST

            // Then
            assertEquals(2, defaultList.size)
        }

        @Test
        @DisplayName("DEFAULT_LIST first config should have Seattle location and not remote")
        fun testDefaultListFirstConfig() {
            // Given
            val first = JSearchConfig.DEFAULT_LIST[0]

            // Then
            assertEquals("Seattle, WA", first.location)
            assertFalse(first.remoteJobsOnly)
            assertEquals(2, first.queries.size)
            assertTrue(first.queries[0].contains("SDET"))
        }

        @Test
        @DisplayName("DEFAULT_LIST second config should have null location and remote only")
        fun testDefaultListSecondConfig() {
            // Given
            val second = JSearchConfig.DEFAULT_LIST[1]

            // Then
            assertNull(second.location)
            assertTrue(second.remoteJobsOnly)
            assertEquals(2, second.queries.size)
            assertTrue(second.queries[0].contains("SDET"))
        }

        @Test
        @DisplayName("DEFAULT_LIST configs should share same queries")
        fun testDefaultListSharedQueries() {
            // Given
            val first = JSearchConfig.DEFAULT_LIST[0]
            val second = JSearchConfig.DEFAULT_LIST[1]

            // Then
            assertEquals(first.queries, second.queries)
        }

        @Test
        @DisplayName("DEFAULT_LIST configs should have default numPages of 1")
        fun testDefaultListNumPages() {
            // Given
            val first = JSearchConfig.DEFAULT_LIST[0]
            val second = JSearchConfig.DEFAULT_LIST[1]

            // Then
            assertEquals(1, first.numPages)
            assertEquals(1, second.numPages)
        }
    }
}
