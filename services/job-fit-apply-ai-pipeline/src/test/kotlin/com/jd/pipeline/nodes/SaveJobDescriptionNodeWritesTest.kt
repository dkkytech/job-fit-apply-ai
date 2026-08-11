package com.jd.pipeline.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the files [SaveJobDescriptionNode] actually writes.
 *
 * The node writes into Config.OUTPUT_DIR (not injectable), so each test reads the artifacts back
 * from the unique timestamped directory reported in `result.outputPath` and deletes it afterwards.
 * These cover the raw-page / meta.json / raw-email write branches that the existing suite skips.
 */
@DisplayName("SaveJobDescriptionNode — file writes")
class SaveJobDescriptionNodeWritesTest {

    private val node = SaveJobDescriptionNode()
    private val mapper = ObjectMapper()
    private val createdDirs = mutableListOf<Path>()

    @AfterEach
    fun cleanUp() {
        createdDirs.forEach { dir ->
            if (dir.exists()) {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    private fun emailIntake(rawBody: String) = IntakeContext.Email(
        emailId = "e-1", subject = "Role", from = "a@b.com",
        rawBody = rawBody, htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )

    private fun track(result: JDState): Path {
        val dir = Path.of(result.outputPath)
        createdDirs.add(dir)
        return dir
    }

    @Test
    @DisplayName("writes job_description.txt, raw page, meta.json and raw email content")
    fun writesAllArtifacts() {
        val input = JDState(
            jdText = "Full job description body",
            rawPageContent = "<html>raw scraped page</html>",
            scrapePath = "cdp",
            company = "SaveWritesCo",
            roleTitle = "Backend Engineer",
            intake = emailIntake("Original recruiter email text"),
        )

        val result = node.process(input)
        val dir = track(result)

        assertTrue(result.error.isEmpty(), "unexpected error: ${result.error}")
        assertEquals("Full job description body", dir.resolve("job_description.txt").readText())
        assertEquals("<html>raw scraped page</html>", dir.resolve("raw_page_contents.txt").readText())
        assertEquals("Original recruiter email text", dir.resolve("raw_email_contents.txt").readText())

        val meta = mapper.readTree(dir.resolve("meta.json").readText())
        assertEquals("cdp", meta.get("scrape_path").asText())
    }

    @Test
    @DisplayName("falls back to scrapedContent for job_description.txt when jdText is empty")
    fun fallsBackToScrapedContent() {
        val input = JDState(
            jdText = "",
            scrapedContent = "Scraped fallback content",
            company = "FallbackCo",
            roleTitle = "SDET",
        )

        val result = node.process(input)
        val dir = track(result)

        assertEquals("Scraped fallback content", dir.resolve("job_description.txt").readText())
    }

    @Test
    @DisplayName("omits optional raw-page and raw-email files when their sources are empty")
    fun omitsOptionalFilesWhenAbsent() {
        val input = JDState(
            jdText = "Just a JD",
            company = "MinimalCo",
            roleTitle = "QA",
        )

        val result = node.process(input)
        val dir = track(result)

        assertTrue(dir.resolve("job_description.txt").exists())
        assertFalse(dir.resolve("raw_page_contents.txt").exists())
        assertFalse(dir.resolve("raw_email_contents.txt").exists())
    }

    @Test
    @DisplayName("keeps an existing outputPath instead of overwriting it with the new directory")
    fun keepsExistingOutputPath() {
        val input = JDState(
            jdText = "content",
            company = "KeepPathCo",
            roleTitle = "Engineer",
            outputPath = "/pre/existing/path",
        )

        val result = node.process(input)
        // Node still creates a fresh dir on disk but does NOT change state.outputPath.
        val timestamped = OutputPathProbe.lastCreatedFor("KeepPathCo", "Engineer")
        if (timestamped != null) createdDirs.add(timestamped)

        assertEquals("/pre/existing/path", result.outputPath)
    }
}

/** Locates the timestamped directory the node just created for cleanup. */
private object OutputPathProbe {
    fun lastCreatedFor(company: String, role: String): Path? {
        val outputDir = com.jd.pipeline.config.Config.OUTPUT_DIR
        if (!outputDir.exists()) return null
        val companySlug = com.jd.pipeline.utils.OutputUtils.sanitizeFileName(company)
        val roleSlug = com.jd.pipeline.utils.OutputUtils.sanitizeFileName(role)
        return Files.list(outputDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith("_${companySlug}_${roleSlug}") }
                .max(Comparator.comparing { it.fileName.toString() })
                .orElse(null)
        }
    }
}
