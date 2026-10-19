package com.jd.notifier.notify

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArtifactLinks (report + resume resolution)")
class ArtifactLinksTest {

    private fun links(enabled: Boolean = false) =
        ArtifactLinks(enabled = enabled, timeoutMs = 200, bridgeBase = "http://bridge.example:8765")

    @Test
    @DisplayName("bridge-relative resume path is made absolute")
    fun absolutisesBridgePath() {
        val r = links().resolve(
            "http://host:8081/20260901_acme/",
            "/api/jobs/abc-123/resume.pdf",
        )
        assertEquals("http://host:8081/20260901_acme/report.md", r.reportUrl)
        assertEquals("http://bridge.example:8765/api/jobs/abc-123/resume.pdf", r.resumeUrl)
    }

    @Test
    @DisplayName("an already-absolute resume URL is passed through untouched")
    fun keepsAbsoluteResumeUrl() {
        val r = links().resolve("http://host:8081/j/", "https://cdn.example/r.pdf")
        assertEquals("https://cdn.example/r.pdf", r.resumeUrl)
    }

    @Test
    @DisplayName("report URL drops a trailing slash rather than doubling it")
    fun reportUrlNoDoubleSlash() {
        val r = links().resolve("http://host:8081/job///", null)
        assertEquals("http://host:8081/job/report.md", r.reportUrl)
    }

    @Test
    @DisplayName("no artifactUrl and no resume path means no links at all")
    fun nothingToLink() {
        val r = links().resolve(null, null)
        assertNull(r.reportUrl)
        assertNull(r.resumeUrl)
    }

    @Test
    @DisplayName("blank artifactUrl is treated as absent")
    fun blankArtifactUrl() {
        assertNull(links().resolve("   ", null).reportUrl)
    }

    @Test
    @DisplayName("report link survives even when the resume lookup is disabled")
    fun reportSurvivesDisabledLookup() {
        val r = links(enabled = false).resolve("http://host:8081/j/", null)
        assertEquals("http://host:8081/j/report.md", r.reportUrl)
        assertNull(r.resumeUrl, "metadata lookup off means no resume link, not a failed one")
    }

    @Test
    @DisplayName("metadata.json parsing pulls tailored_resume_url")
    fun parsesMetadataResumeUrl() {
        val body = """{"job_title":"Staff SDET","tailored_resume_url":"http://host:8081/j/R_CV.pdf"}"""
        assertEquals("http://host:8081/j/R_CV.pdf", links().resumeUrlFrom(body))
    }

    @Test
    @DisplayName("metadata without a resume URL yields null, not an empty string")
    fun metadataMissingField() {
        assertNull(links().resumeUrlFrom("""{"job_title":"x"}"""))
        assertNull(links().resumeUrlFrom("""{"tailored_resume_url":"   "}"""))
    }

    @Test
    @DisplayName("malformed metadata body yields null instead of throwing")
    fun malformedMetadata() {
        assertNull(links().resumeUrlFrom("<html>not json</html>"))
        assertNull(links().resumeUrlFrom(""))
    }

    @Test
    @DisplayName("an unreachable host degrades to a report-only link, never an exception")
    fun unreachableHostDegrades() {
        // enabled=true forces the fetch; 127.0.0.1:1 refuses immediately.
        val r = ArtifactLinks(enabled = true, timeoutMs = 150, bridgeBase = "http://x:1")
            .resolve("http://127.0.0.1:1/none/", null)
        assertEquals("http://127.0.0.1:1/none/report.md", r.reportUrl)
        assertNull(r.resumeUrl)
    }

    @Test
    @DisplayName("resolution never throws for odd inputs")
    fun oddInputsSafe() {
        val l = links()
        listOf(null, "", "not a url", "http://", "///").forEach { u ->
            val r = l.resolve(u, null)
            val report: String? = r.reportUrl
            assertTrue(report == null || report.isNotBlank())
        }
    }
}
