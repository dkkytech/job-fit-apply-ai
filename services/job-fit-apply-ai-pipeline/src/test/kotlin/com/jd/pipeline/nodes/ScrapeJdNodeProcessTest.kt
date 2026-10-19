package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ScrapeJdNode.process] covering branches that do NOT require
 * a live HTTP connection or Playwright. Specifically: blank-URL early return,
 * and batch-level skip tracking (blocked domains, LinkedIn session expiry).
 *
 * The full scrape → LLM parse path requires network/browser and is covered
 * by integration/functional tests.
 */
@DisplayName("ScrapeJdNode — process()")
class ScrapeJdNodeProcessTest {

    private lateinit var node: ScrapeJdNode

    @BeforeEach
    fun setUp() {
        // LLM is never reached in these tests, but we provide a strict mock
        // so that any accidental invocation causes an obvious test failure.
        node = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called in this test") })
        node.resetBatch()
    }

    // ── Blank URL early return ────────────────────────────────────────────────

    @Nested
    @DisplayName("blank / null jobUrl")
    inner class BlankJobUrl {

        @Test
        @DisplayName("returns input unchanged when jobUrl is empty")
        fun emptyJobUrlPassesThrough() {
            val input = JDState(isJobPosting = true, company = "Acme", jdText = "existing jd text")
            val result = node.process(input)
            assertEquals(input, result)
        }

        @Test
        @DisplayName("whitespace-only jobUrl is not treated as blank — node attempts fetch")
        fun whitespaceJobUrlNotBlank() {
            // "   " is non-null and non-empty, so the guard doesn't fire.
            // The node will try to extract a host and do a fetch; it fails with an error.
            val input = JDState(isJobPosting = true, jobUrl = "   ")
            val result = node.process(input)
            // The key behaviour: input is NOT returned unchanged — node tried to process it
            assertFalse(result == input && result.error.isBlank(),
                "Whitespace URL should either be processed or return an error state")
        }

        @Test
        @DisplayName("preserves existing jdText when jobUrl is empty")
        fun preservesJdText() {
            val input = JDState(jobUrl = "", jdText = "pre-populated jd text", company = "Corp")
            val result = node.process(input)
            assertEquals("pre-populated jd text", result.jdText)
        }
    }

    // ── Batch-level skip: blocked domains ────────────────────────────────────

    @Nested
    @DisplayName("blocked-domain batch skip")
    inner class BlockedDomain {

        @Test
        @DisplayName("returns error state when domain was blocked earlier in the batch")
        fun blockedDomainReturnsError() {
            node.batchBlockedDomains.add("example.com")
            val input = JDState(isJobPosting = true, jobUrl = "https://example.com/jobs/123")
            val result = node.process(input)
            assertTrue(result.error.isNotBlank(), "Expected an error message for blocked domain")
            assertTrue(result.error.contains("example.com"), "Error should mention the blocked domain")
        }

        @Test
        @DisplayName("unblocked domain is not pre-rejected (falls through to fetch)")
        fun unblockedDomainNotRejected() {
            // This test just ensures blocked-domain check doesn't incorrectly fire for different domains
            node.batchBlockedDomains.add("other.com")
            // We use a synthetic URL that won't resolve — the error will come from the HTTP fetch
            // not from the batch-block path. We verify the error does NOT contain "blocked earlier".
            val input = JDState(isJobPosting = true, jobUrl = "https://totally-different-domain.example/job/1")
            val result = node.process(input)
            // May error (network) but not from the blocked-domain guard
            assertFalse(result.error.contains("blocked earlier"), "Should not be rejected as blocked domain")
        }
    }

    // ── SSRF guard ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("private-network targets")
    inner class PrivateTargets {

        @Test
        @DisplayName("a link to the private network is refused before any fetch or LLM call")
        fun refusesPrivateTargets() {
            val guarded = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called") }, allowPrivateHosts = false)
            listOf("http://169.254.169.254/latest/meta-data/", "http://host.docker.internal:8765/api/jobs", "http://127.0.0.1:3030/").forEach { url ->
                val result = guarded.process(JDState(isJobPosting = true, jobUrl = url))
                assertTrue(result.error.contains(ScrapeJdNode.PRIVATE_TARGET_REASON), "$url → ${result.error}")
                assertEquals("blocked", result.scrapePath)
            }
            assertTrue(guarded.batchBlockedDomains.isEmpty(), "a refused link is not a site-wide block")
        }

        @Test
        @DisplayName("each redirect hop is checked before it is requested; public redirects still work")
        fun redirectHopsAreCheckedBeforeRequest() {
            val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { ex ->
                    val path = ex.requestURI.path
                    hits.merge(path, 1, Int::plus)
                    when (path) {
                        "/start" -> { ex.responseHeaders.add("Location", "/private/secret"); ex.sendResponseHeaders(302, -1) }
                        "/start2" -> { ex.responseHeaders.add("Location", "/final"); ex.sendResponseHeaders(302, -1) }
                        else -> {
                            val body = ("<html><body><main>" + "Senior QA engineer owning test automation and release quality. ".repeat(30) +
                                "</main></body></html>").toByteArray()
                            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
                        }
                    }
                    ex.close()
                }
                start()
            }
            try {
                val base = "http://127.0.0.1:${server.address.port}"
                // Only paths under /private count as private here, so the loopback test server itself is reachable.
                val node = ScrapeJdNode(llm = LlmCaller { error("LLM not reached") }, allowPrivateHosts = false, isPrivateTarget = { "/private" in it })
                val blocked = node.process(JDState(isJobPosting = true, jobUrl = "$base/start"))
                assertTrue(blocked.error.contains(ScrapeJdNode.PRIVATE_TARGET_REASON), blocked.error)
                assertEquals(null, hits["/private/secret"], "the private hop must never be requested")

                node.process(JDState(isJobPosting = true, jobUrl = "$base/start2"))   // the LLM stub fails afterwards; only the fetch matters
                assertEquals(1, hits["/final"], "a public redirect is still followed")
            } finally {
                server.stop(0)
            }
        }

        @Test
        @DisplayName("the e2e opt-in lets a private target through to the fetch")
        fun optInSkipsTheGuard() {
            val checked = mutableListOf<String>()
            val open = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called") }, allowPrivateHosts = true,
                isPrivateTarget = { checked += it; true })
            // Port 9 (discard) on loopback refuses the connection: the fetch fails on its own, not the guard.
            val result = open.process(JDState(isJobPosting = true, jobUrl = "http://127.0.0.1:9/job"))
            assertFalse(result.error.contains(ScrapeJdNode.PRIVATE_TARGET_REASON), result.error)
            assertTrue(checked.isEmpty(), "with the opt-in the guard is not consulted")
        }
    }

    // ── Batch-level skip: LinkedIn session ────────────────────────────────────

    @Nested
    @DisplayName("LinkedIn session expiry batch skip")
    inner class LinkedInSessionExpiry {

        @Test
        @DisplayName("returns re-auth error when the host hit an auth wall earlier this batch")
        fun sessionExpiredReturnsError() {
            node.batchAuthExpiredDomains.add("linkedin.com")
            val input = JDState(isJobPosting = true, jobUrl = "https://www.linkedin.com/jobs/view/12345")
            val result = node.process(input)
            assertTrue(result.isChromeSessionExpired, "Expected isChromeSessionExpired flag")
            assertTrue(result.error.contains("linkedin", ignoreCase = true), "Error should name the host")
            assertTrue(node.batchLinkedInSessionExpired, "LinkedIn back-compat accessor should be true")
        }
    }

    // ── Batch state management ────────────────────────────────────────────────

    @Nested
    @DisplayName("batch state management")
    inner class BatchState {

        @Test
        @DisplayName("resetBatch clears batchBlockedDomains and batchAuthExpiredDomains")
        fun resetBatchClearsState() {
            node.batchBlockedDomains.add("example.com")
            node.batchAuthExpiredDomains.add("linkedin.com")
            node.resetBatch()
            assertTrue(node.batchBlockedDomains.isEmpty())
            assertTrue(node.batchAuthExpiredDomains.isEmpty())
            assertFalse(node.batchLinkedInSessionExpired)
        }

        @Test
        @DisplayName("verbose flag defaults to true")
        fun verboseDefaultsTrue() {
            assertTrue(node.verbose)
        }
    }
}
