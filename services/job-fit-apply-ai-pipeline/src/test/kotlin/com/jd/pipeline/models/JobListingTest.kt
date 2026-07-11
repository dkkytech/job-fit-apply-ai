package com.jd.pipeline.models

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for JobListing data class.
 *
 * Covers Jackson deserialization, @JsonIgnoreProperties behavior,
 * null/empty field handling, and data class equality/copy behavior.
 */
class JobListingTest {

    private val mapper = ObjectMapper()

    @Nested
    @DisplayName("Jackson Deserialization Tests")
    inner class JacksonDeserializationTests {

        @Test
        @DisplayName("Should deserialize all fields from JSON correctly")
        fun testDeserializeAllFields() {
            // Given
            val json = """
                {
                    "job_id": "abc123",
                    "job_title": "Senior SDET",
                    "employer_name": "Acme Corp",
                    "job_city": "Seattle",
                    "job_state": "WA",
                    "job_is_remote": true,
                    "job_description": "Build test frameworks",
                    "job_apply_link": "https://example.com/apply",
                    "job_posted_at_datetime_utc": "2024-01-15T10:00:00.000Z",
                    "job_min_salary": 120000.0,
                    "job_max_salary": 180000.0,
                    "job_salary": 150000.0,
                    "job_salary_string": "${'$'}150k - ${'$'}180k",
                    "job_publisher": "LinkedIn"
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then
            assertEquals("abc123", listing.jobId)
            assertEquals("Senior SDET", listing.jobTitle)
            assertEquals("Acme Corp", listing.employerName)
            assertEquals("Seattle", listing.jobCity)
            assertEquals("WA", listing.jobState)
            assertEquals(true, listing.jobIsRemote)
            assertEquals("Build test frameworks", listing.jobDescription)
            assertEquals("https://example.com/apply", listing.jobApplyLink)
            assertEquals("2024-01-15T10:00:00.000Z", listing.jobPostedAtDatetimeUtc)
            assertEquals(120000.0, listing.jobMinSalary)
            assertEquals(180000.0, listing.jobMaxSalary)
            assertEquals(150000.0, listing.jobSalary)
            assertEquals("${'$'}150k - ${'$'}180k", listing.jobSalaryString)
            assertEquals("LinkedIn", listing.jobPublisher)
        }

        @Test
        @DisplayName("Should deserialize with null optional fields")
        fun testDeserializeWithNullFields() {
            // Given
            val json = """
                {
                    "job_id": "def456",
                    "job_title": "QA Engineer",
                    "employer_name": "TestCo",
                    "job_city": null,
                    "job_state": null,
                    "job_is_remote": false,
                    "job_description": null,
                    "job_apply_link": null,
                    "job_posted_at_datetime_utc": null,
                    "job_min_salary": null,
                    "job_max_salary": null,
                    "job_salary": null,
                    "job_salary_string": null,
                    "job_publisher": null
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then
            assertEquals("def456", listing.jobId)
            assertEquals("QA Engineer", listing.jobTitle)
            assertEquals("TestCo", listing.employerName)
            assertNull(listing.jobCity)
            assertNull(listing.jobState)
            assertEquals(false, listing.jobIsRemote)
            assertNull(listing.jobDescription)
            assertNull(listing.jobApplyLink)
            assertNull(listing.jobPostedAtDatetimeUtc)
            assertNull(listing.jobMinSalary)
            assertNull(listing.jobMaxSalary)
            assertNull(listing.jobSalary)
            assertNull(listing.jobSalaryString)
            assertNull(listing.jobPublisher)
        }

        @Test
        @DisplayName("Should deserialize with missing optional fields")
        fun testDeserializeWithMissingFields() {
            // Given
            val json = """
                {
                    "job_id": "ghi789",
                    "job_title": "Software Engineer in Test",
                    "employer_name": "BigTech"
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then
            assertEquals("ghi789", listing.jobId)
            assertEquals("Software Engineer in Test", listing.jobTitle)
            assertEquals("BigTech", listing.employerName)
            assertNull(listing.jobCity)
            assertNull(listing.jobState)
            assertEquals(false, listing.jobIsRemote)
            assertNull(listing.jobDescription)
            assertNull(listing.jobApplyLink)
            assertNull(listing.jobPostedAtDatetimeUtc)
            assertNull(listing.jobMinSalary)
            assertNull(listing.jobMaxSalary)
            assertNull(listing.jobSalary)
            assertNull(listing.jobSalaryString)
            assertNull(listing.jobPublisher)
        }
    }

    @Nested
    @DisplayName("JsonIgnoreProperties Tests")
    inner class JsonIgnorePropertiesTests {

        @Test
        @DisplayName("Should ignore unknown fields in JSON")
        fun testIgnoreUnknownFields() {
            // Given
            val json = """
                {
                    "job_id": "xyz999",
                    "job_title": "Test Engineer",
                    "employer_name": "UnknownCorp",
                    "unknown_field_1": "some value",
                    "extra_nested": {
                        "foo": "bar"
                    },
                    "random_number": 42
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then
            assertEquals("xyz999", listing.jobId)
            assertEquals("Test Engineer", listing.jobTitle)
            assertEquals("UnknownCorp", listing.employerName)
        }

        @Test
        @DisplayName("Should handle JSON with only unknown extra fields beyond required")
        fun testOnlyRequiredFieldsWithExtras() {
            // Given
            val json = """
                {
                    "job_id": "minimal123",
                    "job_title": "Minimal Job",
                    "employer_name": "MinimalCorp",
                    "extra_data": [1, 2, 3],
                    "metadata": {
                        "source": "test",
                        "version": 1.0
                    }
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then - should not throw and should parse required fields
            assertNotNull(listing)
            assertEquals("minimal123", listing.jobId)
            assertEquals("Minimal Job", listing.jobTitle)
            assertEquals("MinimalCorp", listing.employerName)
        }
    }

    @Nested
    @DisplayName("Null and Empty Field Handling Tests")
    inner class NullEmptyFieldTests {

        @Test
        @DisplayName("Should handle empty string fields gracefully")
        fun testEmptyStringFields() {
            // Given
            val json = """
                {
                    "job_id": "empty001",
                    "job_title": "",
                    "employer_name": "",
                    "job_city": "",
                    "job_state": "",
                    "job_salary_string": ""
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then - empty strings are valid values, not null
            assertEquals("empty001", listing.jobId)
            assertEquals("", listing.jobTitle)
            assertEquals("", listing.employerName)
            assertEquals("", listing.jobCity)
            assertEquals("", listing.jobState)
            assertEquals("", listing.jobSalaryString)
        }

        @Test
        @DisplayName("Should handle job_is_remote default when missing")
        fun testRemoteDefaultWhenMissing() {
            // Given
            val json = """
                {
                    "job_id": "remote001",
                    "job_title": "Remote Job",
                    "employer_name": "RemoteCorp"
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then - should default to false
            assertEquals(false, listing.jobIsRemote)
        }

        @Test
        @DisplayName("Should handle job_is_remote when explicitly false")
        fun testRemoteExplicitlyFalse() {
            // Given
            val json = """
                {
                    "job_id": "remote002",
                    "job_title": "Onsite Job",
                    "employer_name": "OnsiteCorp",
                    "job_is_remote": false
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then
            assertEquals(false, listing.jobIsRemote)
        }

        @Test
        @DisplayName("Should handle job_is_remote when explicitly true")
        fun testRemoteExplicitlyTrue() {
            // Given
            val json = """
                {
                    "job_id": "remote003",
                    "job_title": "Fully Remote",
                    "employer_name": "RemoteFirst",
                    "job_is_remote": true
                }
            """.trimIndent()

            // When
            val listing = mapper.readValue(json, JobListing::class.java)

            // Then
            assertEquals(true, listing.jobIsRemote)
        }
    }

    @Nested
    @DisplayName("Data Class Equality and Copy Tests")
    inner class EqualityAndCopyTests {

        @Test
        @DisplayName("Should be equal when all fields match")
        fun testEqualityWithMatchingFields() {
            // Given
            val listing1 = JobListing(
                jobId = "eq001",
                jobTitle = "SDET",
                employerName = "EqCorp",
                jobCity = "Seattle",
                jobState = "WA",
                jobIsRemote = false,
                jobDescription = "Test stuff",
                jobApplyLink = "https://eq.com",
                jobPostedAtDatetimeUtc = "2024-01-01",
                jobMinSalary = 100000.0,
                jobMaxSalary = 150000.0,
                jobSalary = 125000.0,
                jobSalaryString = "\$125k",
                jobPublisher = "TestPub"
            )
            val listing2 = listing1.copy()

            // Then
            assertEquals(listing1, listing2)
            assertEquals(listing1.hashCode(), listing2.hashCode())
        }

        @Test
        @DisplayName("Should not be equal when fields differ")
        fun testInequalityWithDifferentFields() {
            // Given
            val listing1 = JobListing(
                jobId = "neq001",
                jobTitle = "SDET",
                employerName = "CorpA"
            )
            val listing2 = JobListing(
                jobId = "neq001",
                jobTitle = "QA Engineer",
                employerName = "CorpA"
            )

            // Then
            assertNotNull(listing1)
            assertNotNull(listing2)
            assertFalse(listing1 == listing2)
        }

        @Test
        @DisplayName("Should copy with modified fields")
        fun testCopyWithModification() {
            // Given
            val original = JobListing(
                jobId = "copy001",
                jobTitle = "Original Title",
                employerName = "OriginalCorp",
                jobCity = "Seattle"
            )

            // When
            val modified = original.copy(jobTitle = "Modified Title", jobCity = "Portland")

            // Then
            assertEquals("copy001", modified.jobId)
            assertEquals("Modified Title", modified.jobTitle)
            assertEquals("OriginalCorp", modified.employerName)
            assertEquals("Portland", modified.jobCity)
            // Original should be unchanged
            assertEquals("Original Title", original.jobTitle)
            assertEquals("Seattle", original.jobCity)
        }

        @Test
        @DisplayName("Should support toString without crashing")
        fun testToString() {
            // Given
            val listing = JobListing(
                jobId = "ts001",
                jobTitle = "Test Role",
                employerName = "TestCorp"
            )

            // When
            val str = listing.toString()

            // Then
            assertTrue(str.contains("ts001"))
            assertTrue(str.contains("Test Role"))
            assertTrue(str.contains("TestCorp"))
        }

        @Test
        @DisplayName("Should support component functions for destructuring")
        fun testComponentFunctions() {
            // Given
            val listing = JobListing(
                jobId = "comp001",
                jobTitle = "Component Test",
                employerName = "CompCorp"
            )

            // When - use component1 (jobId) and component2 (jobTitle)
            val id = listing.component1()
            val title = listing.component2()

            // Then
            assertEquals("comp001", id)
            assertEquals("Component Test", title)
        }
    }
}
