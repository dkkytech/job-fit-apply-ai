package com.jd.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.e2e.FakeLlmServer.Companion.failure
import com.jd.e2e.FakeLlmServer.Companion.malformed
import com.jd.e2e.FakeLlmServer.Companion.ok
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit-level checks on the fake LLM's dispatch — no Docker, no compose slice. These guard
 * the routing rules that the black-box suite depends on but cannot isolate: prompt-marker
 * aliasing, the loud-500 contract, and the bullet fold-back echo.
 *
 * Tagged tier-b: they assert canned-fixture behaviour, so `-PexcludeTags=tier-b` (the
 * real-LLM mode) skips them alongside the other exact-value assertions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("tier-b")
@DisplayName("FakeLlmServer dispatch")
class FakeLlmServerTest {

    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var port = 0
    private lateinit var server: FakeLlmServer

    @BeforeAll
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = FakeLlmServer(port, E2eConfig.fixturesDir)
        server.start()
    }

    @AfterAll
    fun stop() = server.stop()

    private fun ask(prompt: String, model: String = "qwen3-test"): HttpResponse<String> {
        val body = mapper.writeValueAsString(
            mapOf("model" to model, "messages" to listOf(mapOf("role" to "user", "content" to prompt)))
        )
        return http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun content(resp: HttpResponse<String>): String =
        mapper.readTree(resp.body()).path("choices").path(0).path("message").path("content").asText()

    @Test
    @DisplayName("the cover-letter prompt routes to cover_letter, not score_fit")
    fun coverLetterDoesNotAliasScoreFit() {
        server.calls.clear()
        // GenerateCoverLetterNode's prompt contains score_fit's fallback marker verbatim.
        // Branch order alone used to be the only thing keeping them apart.
        val prompt = """
            Write a professional yet casual cover letter for a job application.

            JOB DESCRIPTION:
            some jd text

            CANDIDATE STRENGTHS TO HIGHLIGHT:
            [a, b]
        """.trimIndent()
        val resp = ask(prompt)
        assertEquals(200, resp.statusCode())
        assertEquals(listOf("cover_letter"), server.calls.toList())
        assertTrue(!content(resp).trimStart().startsWith("{"), "cover letter must be prose, not JSON")
    }

    @Test
    @DisplayName("a cover-letter prompt whose opening line drifted still does not fall through to score_fit")
    fun driftedCoverLetterStillNotScoreFit() {
        server.calls.clear()
        // Opening line reworded; only the CANDIDATE STRENGTHS marker remains.
        val prompt = "Compose a warm note for this role.\n\nJOB DESCRIPTION:\nx\n\nCANDIDATE STRENGTHS TO HIGHLIGHT:\n[a]"
        ask(prompt)
        assertEquals(listOf("cover_letter"), server.calls.toList())
    }

    @Test
    @DisplayName("the model name never influences routing")
    fun modelNameIsNotADispatchKey() {
        server.calls.clear()
        // A prose-capable model pointed at summary_rewrite used to be served the cover letter.
        ask("# SUMMARY_REWRITE_SKILL\nrewrite this", model = "gemma-3-27b")
        assertEquals(listOf("summary_rewrite"), server.calls.toList())
    }

    @Test
    @DisplayName("an unmatched prompt is a loud 500, never a default response")
    fun unmatchedPromptIs500() {
        server.calls.clear()
        val resp = ask("nothing here matches any known skill marker")
        assertEquals(500, resp.statusCode())
        assertTrue(resp.body().contains("no dispatch marker matched"), "unhelpful 500 body: ${resp.body()}")
        assertTrue(server.calls.isEmpty(), "an unmatched prompt must not be recorded as a served call")
    }

    @Test
    @DisplayName("bullet_rewrite echoes the join keys and marks the rewritten text")
    fun bulletEchoIsNotTheIdentity() {
        server.calls.clear()
        val prompt = """
            # BULLET_REWRITE_SKILL

            CANDIDATE ROLES
            [{"role":"SDET","company":"Acme","start_date":"2020-01",
              "bullets":[{"text":"Did a thing","category":"impact"}]}]
        """.trimIndent()
        val out = mapper.readTree(content(ask(prompt)))
        assertEquals("SDET", out[0].path("role").asText())
        assertEquals("Acme", out[0].path("company").asText())
        assertEquals("2020-01", out[0].path("start_date").asText())
        val bullet = out[0].path("bullets")[0]
        assertEquals("Did a thing", bullet.path("original").asText())
        // Must differ from the original, or a total join failure is unobservable.
        assertEquals("Did a thing ${FakeLlmServer.BULLET_MARKER}", bullet.path("rewritten").asText())
    }

    @Test
    @DisplayName("bullet_rewrite parses the DATA roles, not the skill doc's example array")
    fun bulletEchoIgnoresTheSkillDocExample() {
        server.calls.clear()
        // Mirrors the real assembled prompt: BULLET_REWRITE_SKILL.md (which says
        // "CANDIDATE ROLES" and then shows an EXAMPLE array) is prepended above the data
        // section. Anchoring on the first marker echoes the example's placeholder join
        // keys, BulletRewriteNode's fold-back join misses every real role, and every
        // rewrite is silently discarded — with an identity echo, invisibly so.
        val prompt = """
            # BULLET_REWRITE_SKILL

            Rewrite the bullets of every role in the `CANDIDATE ROLES` JSON array.
            Return ONLY a valid JSON array:

            ```
            [
              {"role": "EXAMPLE ROLE", "company": "EXAMPLE CO", "start_date": "1999-01",
               "bullets": [{"original": "x", "category": "y", "rewritten": "z"}]}
            ]
            ```

            CANDIDATE ROLES (career history + projects). Each bullet is { category, text }.

            [{"role":"SDET","company":"Acme","start_date":"2020-01",
              "bullets":[{"text":"Did a thing","category":"impact"}]}]
        """.trimIndent()
        val out = mapper.readTree(content(ask(prompt)))
        assertEquals(1, out.size(), "echoed the wrong array: $out")
        assertEquals("SDET", out[0].path("role").asText(), "echoed the skill doc's example role, not the real one")
        assertEquals("Acme", out[0].path("company").asText())
        assertEquals("2020-01", out[0].path("start_date").asText())
    }

    @Test
    @DisplayName("bullet_rewrite refuses to guess when the CANDIDATE ROLES marker drifts")
    fun bulletEchoFailsLoudlyOnMarkerDrift() {
        server.calls.clear()
        // A stray '[' earlier in the prompt used to be parsed as the roles array.
        val resp = ask("# BULLET_REWRITE_SKILL\nsee [notes] below\nROLES GO HERE\n[{\"role\":\"X\"}]")
        assertTrue(resp.statusCode() >= 500, "expected a server error, got ${resp.statusCode()}")
    }

    @Test
    @DisplayName("a response plan serves queued route responses in order and reset clears calls")
    fun responsePlansAreStatefulAndResettable() {
        server.reset(
            mapOf(
                "score_fit" to listOf(
                    ok("""{"fit_score":42,"marker":"first"}"""),
                    ok("""{"fit_score":88,"marker":"second"}"""),
                ),
            ),
        )

        val first = mapper.readTree(content(ask("# SCORE_SKILL\n\nJOB DESCRIPTION:\nfixture")))
        val second = mapper.readTree(content(ask("# SCORE_SKILL\n\nJOB DESCRIPTION:\nfixture")))

        assertEquals(42, first.path("fit_score").asInt())
        assertEquals("first", first.path("marker").asText())
        assertEquals(88, second.path("fit_score").asInt())
        assertEquals("second", second.path("marker").asText())
        assertEquals(listOf("score_fit", "score_fit"), server.calls.toList())

        server.reset()
        assertTrue(server.calls.isEmpty(), "reset must isolate call history between scenarios")
        assertEquals(72, mapper.readTree(content(ask("# SCORE_SKILL\n\nJOB DESCRIPTION:\nfixture"))).path("fit_score").asInt())
    }

    @Test
    @DisplayName("email scan and captured-page extraction have distinct loud routes")
    fun ingestionPromptsRouteIndependently() {
        server.reset(
            mapOf(
                "scan_email" to listOf(ok("""{"is_job_posting":true,"company":"Email Co"}""")),
                "scrape_jd" to listOf(ok("""{"company":"Page Co","jd_text":"fixture"}""")),
                "draft_reply" to listOf(ok("fixture reply")),
            ),
        )

        val scan = mapper.readTree(content(ask("# SCAN_SKILL\n\nSUBJECT: Staff SDET\n\nVISIBLE_BODY:\nfixture")))
        val scrape = mapper.readTree(content(ask("# SCRAPE_SKILL\n\nJOB PAGE URL: https://example.test/job\n\nCONTENT:\nfixture")))
        val draft = content(ask("# Draft Reply Skill\n\nYou are drafting a professional email reply to a recruiter"))

        assertEquals("Email Co", scan.path("company").asText())
        assertEquals("Page Co", scrape.path("company").asText())
        assertEquals("fixture reply", draft)
        assertEquals(listOf("scan_email", "scrape_jd", "draft_reply"), server.calls.toList())
    }

    @Test
    @DisplayName("an injected failure is served as that status, still recorded, and consumed once")
    fun injectedFailuresAreServedRecordedAndConsumed() {
        server.reset(mapOf("score_fit" to listOf(failure(500), failure(429))))

        val first  = ask("# SCORE_SKILL\n\nJOB DESCRIPTION:\nfixture")
        val second = ask("# SCORE_SKILL\n\nJOB DESCRIPTION:\nfixture")
        val third  = ask("# SCORE_SKILL\n\nJOB DESCRIPTION:\nfixture")

        assertEquals(500, first.statusCode())
        assertEquals(429, second.statusCode())
        // Plan exhausted → the fixture default resumes, so a scenario only perturbs what it queues.
        assertEquals(200, third.statusCode())
        assertEquals(72, mapper.readTree(content(third)).path("fit_score").asInt())
        // A failed call is still a call: exact-sequence assertions must survive an injected fault.
        assertEquals(listOf("score_fit", "score_fit", "score_fit"), server.calls.toList())
    }

    @Test
    @DisplayName("a malformed body is served verbatim, not wrapped in a completion envelope")
    fun malformedBodiesAreServedVerbatim() {
        server.reset(mapOf("gap_analysis" to listOf(malformed("<html>gateway</html>"))))

        val resp = ask("# GAP_ANALYSIS_SKILL\n\nfixture")

        assertEquals(200, resp.statusCode())
        assertEquals("<html>gateway</html>", resp.body())
        assertEquals(listOf("gap_analysis"), server.calls.toList())
    }

    @Test
    @DisplayName("start() refuses a port another process already answers on")
    fun refusesAnOccupiedPort() {
        ServerSocket(0).use { squatter ->
            val blocked = FakeLlmServer(squatter.localPort, E2eConfig.fixturesDir)
            val e = assertFailsWith<IllegalStateException> { blocked.start() }
            assertTrue(
                e.message!!.contains("already listening"),
                "the occupied-port error must name the cause: ${e.message}",
            )
        }
    }
}
