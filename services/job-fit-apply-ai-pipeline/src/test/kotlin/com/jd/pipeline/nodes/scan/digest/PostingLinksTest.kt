package com.jd.pipeline.nodes.scan.digest

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The processor's half of services/job-fit-apply-ai-watchlist/contract/posting_keys.json: an
 * alert-email link keys the posting exactly as the watchlist keys its feed submission.
 */
class PostingLinksTest {

    private fun contract(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("services/job-fit-apply-ai-watchlist/contract/posting_keys.json")
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("posting_keys.json not found above ${Path.of("").toAbsolutePath()}")
    }

    @TestFactory
    fun contractCases(): List<DynamicTest> {
        val cases = ObjectMapper().readTree(Files.readString(contract())).path("cases").toList()
        assertTrue(cases.size >= 10, "too few contract cases")
        return cases.map { case ->
            DynamicTest.dynamicTest(case.path("name").asText()) {
                val link = PostingLinks.identify(case.path("url").asText())
                if (case.path("key").isNull) {
                    assertNull(link, "not a posting: ${case.path("url").asText()}")
                } else {
                    assertEquals(case.path("key").asText(), link?.key)
                    assertEquals(case.path("canonical_url").asText(), link?.url)
                }
            }
        }
    }

    @Test
    fun `nested trackers are unwrapped, a bounded number of times`() {
        val inner = "https://jobs.lever.co/acme/0f1e2d3c-4b5a-4978-8a9b-0c1d2e3f4a5b"
        val once = "https://click.example.com/c?url=" + java.net.URLEncoder.encode(inner, Charsets.UTF_8)
        val twice = "https://t.example.com/r?u=" + java.net.URLEncoder.encode(once, Charsets.UTF_8)
        assertEquals(inner, PostingLinks.unwrap(twice))
        assertEquals("watchlist:lever:acme:0f1e2d3c-4b5a-4978-8a9b-0c1d2e3f4a5b", PostingLinks.identify(twice)?.key)
        // A parameter that is not a URL is left alone.
        assertEquals("https://x.example.com/jobs/123?n=5", PostingLinks.unwrap("https://x.example.com/jobs/123?n=5"))
    }

    @Test
    fun `html entities in hrefs are decoded before parsing`() {
        val link = PostingLinks.identify("https://careers.example.com/job/QA-Engineer/604999123/?utm_source=a&amp;lang=en")
        assertEquals("https://careers.example.com/job/QA-Engineer/604999123/?lang=en", link?.url)
    }

    @Test
    fun `a malformed percent escape skips that one link instead of throwing`() {
        // identify must never throw: one bad link would abort the whole email's expansion.
        assertNull(PostingLinks.identify("https://careers.example.com/job/QA-Engineer/604999123/?bad%zz=1&utm_source=a"))
        assertEquals(
            "https://careers.example.com/job/QA-Engineer/604999123/?bad%zz=1&100%=x",
            PostingLinks.stripTracking("https://careers.example.com/job/QA-Engineer/604999123/?bad%zz=1&utm_source=a&100%=x"),
        )
    }

    @Test
    fun `sentence punctuation after a plain-text link is not part of it`() {
        listOf(".", ",", ";", ":", "!", "?", "...").forEach { p ->
            assertEquals("alert:careers.example.com/jobs/12345", PostingLinks.identify("https://careers.example.com/jobs/12345$p")?.key, p)
        }
    }

    @Test
    fun `non-http and malformed links are not postings`() {
        listOf("mailto:jobs@example.com", "javascript:void(0)", "ht!tp://bad url/jobs/123", "").forEach {
            assertNull(PostingLinks.identify(it), it)
        }
    }
}
