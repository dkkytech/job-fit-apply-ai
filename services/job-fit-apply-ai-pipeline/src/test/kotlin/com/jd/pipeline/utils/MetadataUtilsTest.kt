package com.jd.pipeline.utils
import com.jd.pipeline.state.PipelineAction

import com.jd.pipeline.fixtures.TestJDStateFactory
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for MetadataUtils.
 */
@DisplayName("MetadataUtilsTest")
class MetadataUtilsTest {

    @Test
    @DisplayName("writeMetadata should not create files when outputPath is empty")
    fun testWriteMetadataNoOpWhenOutputPathEmpty(@TempDir tempDir: Path) {
        // Given
        val state = JDState(
            outputPath = "",
            company = "TestCo",
            roleTitle = "Engineer"
        )

        // When
        MetadataUtils.writeMetadata(state)

        // Then: no files should be created
        assertTrue(Files.list(tempDir).toList().isEmpty())
    }

    @Test
    @DisplayName("writeMetadata should not create files when output directory doesn't exist")
    fun testWriteMetadataNoOpWhenDirectoryNotExists(@TempDir tempDir: Path) {
        // Given: output path points to non-existent directory
        val state = JDState(
            outputPath = tempDir.resolve("non_existent_dir").toString(),
            company = "TestCo",
            roleTitle = "Engineer"
        )

        // When
        MetadataUtils.writeMetadata(state)

        // Then: no files should be created
        assertFalse(Files.exists(tempDir.resolve("non_existent_dir")))
    }

    @Test
    @DisplayName("writeMetadata should create metadata.json with all fields")
    fun testWriteMetadataCreatesJsonFile(@TempDir tempDir: Path) {
        // Given
        val state = TestJDStateFactory.createHighScoredState().copy(
            outputPath = tempDir.toString(),
            company = "TestCo",
            roleTitle = "Senior Engineer",
            location = "Seattle, WA",
            remotePolicy = "remote",
            salaryRange = "$100k - $150k",
            techStack = listOf("Kotlin", "Java"),
            fitScore = 85.0f,
            pipelineAction = PipelineAction.TAILOR,
            jobUrl = "https://example.com/job/123",
            artifactUrl = "https://artifacts.com/test-output"
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then
        val jsonFile = tempDir.resolve("metadata.json")
        assertTrue(Files.exists(jsonFile), "metadata.json should exist")
        val content = Files.readString(jsonFile)
        assertTrue(content.contains("TestCo"))
        assertTrue(content.contains("Senior Engineer"))
        assertTrue(content.contains("85"))
        assertTrue(content.contains("tailor"))
    }

    @Test
    @DisplayName("writeMetadata should create report.md with all sections")
    fun testWriteMetadataCreatesMarkdownFile(@TempDir tempDir: Path) {
        // Given
        val state = TestJDStateFactory.createHighScoredState().copy(
            outputPath = tempDir.toString(),
            company = "MarkdownTest Co",
            roleTitle = "QA Engineer",
            location = "Remote",
            remotePolicy = "remote",
            fitScore = 75.0f,
            fitReasoning = "Good technical match",
            strengths = listOf("Strong Kotlin skills"),
            gaps = listOf("Limited AWS experience"),
            redFlags = listOf("Low salary range"),
            techStack = listOf("Kotlin", "Selenium")
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then
        val mdFile = tempDir.resolve("report.md")
        assertTrue(Files.exists(mdFile), "report.md should exist")
        val content = Files.readString(mdFile)
        assertTrue(content.contains("# QA Engineer — MarkdownTest Co"))
        assertTrue(content.contains("## Job Details"))
        assertTrue(content.contains("## Pipeline Result"))
        assertTrue(content.contains("## Fit Analysis"))
        assertTrue(content.contains("Strong Kotlin skills"))
        assertTrue(content.contains("Limited AWS experience"))
    }

    @Test
    @DisplayName("report.md reports Resume Generation — fully generated vs short-circuited")
    fun testResumeGenerationField(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir)
        val base = TestJDStateFactory.createHighScoredState().copy(
            outputPath = tempDir.toString(),
            pipelineAction = com.jd.pipeline.state.PipelineAction.TAILOR,
        )

        // Fully generated (no degraded nodes)
        MetadataUtils.writeMetadata(base.copy(tailoringDegradedNodes = emptyList()))
        val full = Files.readString(tempDir.resolve("report.md"))
        assertTrue(full.contains("| Resume Generation |"), "report should have a Resume Generation row")
        assertTrue(full.contains("Fully generated"), "clean tailoring should read 'Fully generated'")

        // Short-circuited (a node fell back to base content)
        MetadataUtils.writeMetadata(base.copy(tailoringDegradedNodes = listOf("bullet_rewrite")))
        val degraded = Files.readString(tempDir.resolve("report.md"))
        assertTrue(degraded.contains("Short-circuited"), "degraded tailoring should read 'Short-circuited'")
        assertTrue(degraded.contains("bullet_rewrite"), "report should name the node that fell back")
    }

    @Test
    @DisplayName("Resume Generation is N/A for non-tailored jobs; metadata.json carries the fields")
    fun testResumeGenerationNonTailoredAndJson(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir)
        val state = TestJDStateFactory.createHighScoredState().copy(
            outputPath = tempDir.toString(),
            pipelineAction = com.jd.pipeline.state.PipelineAction.SKIP,
        )
        MetadataUtils.writeMetadata(state)

        val md = Files.readString(tempDir.resolve("report.md"))
        assertTrue(md.contains("N/A (not tailored)"), "SKIP jobs should show N/A for Resume Generation")

        val json = Files.readString(tempDir.resolve("metadata.json"))
        assertTrue(json.contains("resume_generation"), "metadata.json should carry resume_generation")
        assertTrue(json.contains("resume_degraded_nodes"), "metadata.json should carry resume_degraded_nodes")
    }

    @Test
    @DisplayName("writeMetadata should handle duplicate jobs correctly")
    fun testWriteMetadataHandlesDuplicate(@TempDir tempDir: Path) {
        // Given
        val state = JDState(
            outputPath = tempDir.toString(),
            company = "DupCo",
            roleTitle = "Engineer",
            isDuplicate = true,
            skippedReason = "Duplicate within 30d: DupCo — Engineer"
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then
        val jsonFile = tempDir.resolve("metadata.json")
        val content = Files.readString(jsonFile)
        assertTrue(content.contains("\"is_duplicate\" : true"))
    }

    @Test
    @DisplayName("writeMetadata should handle errors in state")
    fun testWriteMetadataHandlesError(@TempDir tempDir: Path) {
        // Given
        val state = JDState(
            outputPath = tempDir.toString(),
            company = "ErrorCo",
            roleTitle = "Engineer",
            error = "Test error message"
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then
        val mdFile = tempDir.resolve("report.md")
        val content = Files.readString(mdFile)
        assertTrue(content.contains("## Error"))
        assertTrue(content.contains("Test error message"))
    }

    @Test
    @DisplayName("writeMetadata should include email context when from email")
    fun testWriteMetadataIncludesEmailContext(@TempDir tempDir: Path) {
        // Given
        val state = JDState(
            outputPath = tempDir.toString(),
            company = "EmailCo",
            roleTitle = "Engineer",
            intake = IntakeContext.Email(
                emailId = "email-001",
                from = "recruiter@emailco.com",
                subject = "Job Opportunity: Engineer",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            )
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then
        val mdFile = tempDir.resolve("report.md")
        val content = Files.readString(mdFile)
        assertTrue(content.contains("## Email Context"))
        assertTrue(content.contains("recruiter@emailco.com"))
        assertTrue(content.contains("Job Opportunity: Engineer"))
        // Source cell links the label to the originating email in Gmail.
        assertTrue(
            content.contains("| Source | <a href=\"https://mail.google.com/mail/u/0/#all/email-001\" target=\"_blank\" rel=\"noopener noreferrer\">email</a> |"),
            "Source row should link 'email' to the Gmail deep-link"
        )

        // ...and metadata.json carries the deep-link.
        val json = Files.readString(tempDir.resolve("metadata.json"))
        assertTrue(
            json.contains("https://mail.google.com/mail/u/0/#all/email-001"),
            "metadata.json should carry email_url"
        )
    }

    @Test
    @DisplayName("Source cell is a plain label (no Gmail link) for non-email jobs")
    fun testSourceCellNoLinkForNonEmail(@TempDir tempDir: Path) {
        // Given: an API/jsearch-sourced job (no email intake)
        val state = JDState(
            outputPath = tempDir.toString(),
            company = "ApiCo",
            roleTitle = "Engineer",
            intake = IntakeContext.Api(board = "linkedin")
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then: the Source row shows the plain label with no anchor
        val content = Files.readString(tempDir.resolve("report.md"))
        assertTrue(content.contains("| Source | api |"), "non-email jobs should render a plain source label")
        assertFalse(content.contains("mail.google.com"), "non-email jobs must not link to Gmail")

        // metadata.json email_url is null for non-email jobs
        val json = Files.readString(tempDir.resolve("metadata.json"))
        assertTrue(json.contains("\"email_url\" : null"), "metadata.json email_url should be null for non-email jobs")
    }

    @Test
    @DisplayName("writeMetadata should handle missing optional fields gracefully")
    fun testWriteMetadataHandlesMissingFields(@TempDir tempDir: Path) {
        // Given: minimal state with only required fields
        val state = JDState(
            outputPath = tempDir.toString(),
            company = "MinCo",
            roleTitle = "Engineer"
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then: files should be created without errors
        assertTrue(Files.exists(tempDir.resolve("metadata.json")))
        assertTrue(Files.exists(tempDir.resolve("report.md")))
    }

    @Test
    @DisplayName("writeMetadata should handle cover letter URL")
    fun testWriteMetadataIncludesCoverLetter(@TempDir tempDir: Path) {
        // Given
        val state = JDState(
            outputPath = tempDir.toString(),
            company = "CoverCo",
            roleTitle = "Engineer",
            pipelineAction = PipelineAction.TAILOR,
            coverLetter = "Cover letter content",
            artifactUrl = "https://artifacts.com/output"
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then
        val jsonFile = tempDir.resolve("metadata.json")
        val content = Files.readString(jsonFile)
        assertTrue(content.contains("cover_letter"))
    }

    @Test
    @DisplayName("writeMetadata should include tech stack section")
    fun testWriteMetadataIncludesTechStack(@TempDir tempDir: Path) {
        // Given
        val state = JDState(
            outputPath = tempDir.toString(),
            company = "TechCo",
            roleTitle = "Engineer",
            techStack = listOf("Kotlin", "Java", "AWS", "Docker")
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then
        val mdFile = tempDir.resolve("report.md")
        val content = Files.readString(mdFile)
        assertTrue(content.contains("## Tech Stack"))
        assertTrue(content.contains("`Kotlin`"))
        assertTrue(content.contains("`Java`"))
        assertTrue(content.contains("`AWS`"))
    }

    @Test
    @DisplayName("writeMetadata should handle suppressed fields correctly")
    fun testWriteMetadataHandlesSuppressedFields(@TempDir tempDir: Path) {
        // Given: state with "unknown" remote policy (should show as dash)
        val state = JDState(
            outputPath = tempDir.toString(),
            company = "UnknownCo",
            roleTitle = "Engineer",
            remotePolicy = "unknown",
            employmentType = "",
            seniorityLevel = ""
        )
        Files.createDirectories(tempDir)

        // When
        MetadataUtils.writeMetadata(state)

        // Then
        val mdFile = tempDir.resolve("report.md")
        val content = Files.readString(mdFile)
        // "unknown" should be displayed as "—" (dash)
        assertTrue(content.contains("—"))
    }
}
