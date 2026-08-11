package com.jd.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal OpenAI-compatible fake serving `POST /v1/chat/completions` for the whole
 * ProcessingPipeline. The processor reaches it via host.docker.internal (compose override
 * points MLX_LOCAL_BASE_URL here).
 *
 * Dispatch is on `messages[0].content` — the pipeline sends no system message. Design
 * constraints it deliberately honours:
 *  - `response_format` is IGNORED: summary_rewrite requests json_object but requires prose.
 *  - Markers are matched with `contains`, never `startsWith` — qwen3-family calls get a
 *    `/no_think\n` prefix.
 *  - bullet_rewrite responses are built by echoing role/company/start_date (the join keys)
 *    parsed out of the prompt's roles JSON — a hardcoded response would silently be
 *    discarded by the fold-back join.
 *  - An unmatched prompt is a loud HTTP 500, never a default response: prompt-marker
 *    drift must fail the run visibly.
 *
 * Every served call is recorded in [calls] (`:refine` / `:retry` suffixed when the prompt
 * carries those markers) so tests can assert the exact call sequence.
 */
class FakeLlmServer(private val port: Int, private val fixturesDir: Path) {
    /**
     * One queued reply for a route.
     *
     * The happy path only ever needs [content]. The failure fields exist because the
     * *interesting* pipeline contracts are the degradation ones — every tailor-gate failure
     * degrades a node rather than crashing — and those cannot be provoked from fixture text.
     * Note that a failure is still a served call: it lands in [calls], so an assertion on the
     * exact sequence keeps working across an injected fault.
     */
    data class PlannedResponse(
        /** Served as `choices[0].message.content`. Ignored when [rawBody] is set. */
        val content: String? = null,
        /** Non-2xx makes the fake answer with this code instead of a completion. */
        val status: Int = 200,
        /** Stall before answering — for hard-timeout paths. */
        val delayMs: Long = 0,
        /** Sent verbatim instead of a well-formed completion envelope. */
        val rawBody: String? = null,
    )

    companion object {
        /** Appended to every rewritten bullet so the fold-back join is observable. */
        const val BULLET_MARKER = "(e2e-rewrite)"

        /** A normal completion carrying [content]. */
        fun ok(content: String) = PlannedResponse(content = content)

        /**
         * An HTTP failure. `LlmClient` retries **429 only** (exponential backoff); every other
         * status throws on the first attempt, so one queued 500 is enough to fail a node.
         */
        fun failure(status: Int, body: String = """{"error":"e2e injected $status"}""") =
            PlannedResponse(status = status, rawBody = body)

        /** A well-formed 200 whose body is not a completion envelope. */
        fun malformed(body: String = "not json at all") = PlannedResponse(rawBody = body)

        /** A completion whose content is blank — `LlmClient` rejects this explicitly. */
        fun empty() = PlannedResponse(content = "")

        /** A slow reply, for exercising the client's hard timeout. */
        fun stall(delayMs: Long, content: String = "") =
            PlannedResponse(content = content, delayMs = delayMs)
    }

    private val mapper = ObjectMapper().registerKotlinModule()
    private var engine: ApplicationEngine? = null

    /**
     * CopyOnWriteArrayList, not synchronizedList: tests iterate this (Tier B asserts the
     * exact sequence) while the Netty handler thread appends, and a synchronizedList's
     * iterator is not itself synchronized.
     */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    /** Per-route FIFO responses installed by one scenario; defaults resume when exhausted. */
    private val responsePlan = ConcurrentHashMap<String, ConcurrentLinkedQueue<PlannedResponse>>()

    /** Reset all observable state before a scenario and optionally install queued responses. */
    fun reset(responses: Map<String, List<PlannedResponse>> = emptyMap()) {
        calls.clear()
        responsePlan.clear()
        responses.forEach { (route, planned) ->
            responsePlan[route] = ConcurrentLinkedQueue(planned)
        }
    }

    /**
     * Binding is not enough to prove we own the port. A JVM binds `0.0.0.0:$port`
     * successfully even while another process holds `127.0.0.1:$port` (the JDK sets
     * SO_REUSEADDR by default), and the kernel then routes loopback connections — which
     * is what `host.docker.internal` traffic arrives as — to the *more specific* socket.
     * The container would silently talk to that other server for the whole run and the
     * only symptom would be a timeout with an empty [calls] list. So: refuse to start if
     * anything already answers on the port.
     */
    private fun assertPortUnoccupied() {
        val occupied = runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                true
            }
        }.getOrDefault(false)
        check(!occupied) {
            "Something is already listening on 127.0.0.1:$port — the fake LLM would bind " +
                "0.0.0.0:$port but the existing loopback socket would keep winning, so the " +
                "processor would talk to *it*, not the fake. This is almost always a real " +
                "local model server (oMLX). Set E2E_FAKE_LLM_PORT to a free port for BOTH " +
                "`make e2e-up` and the test run, or use REAL_LLM=1 to skip the fake entirely."
        }
    }

    fun start() {
        assertPortUnoccupied()
        try {
            // 0.0.0.0 (not loopback) so the processor container can reach us over the
            // docker bridge via host.docker.internal. This does expose the fake on the
            // LAN/tailnet for the duration of the run; it serves only canned fixtures.
            engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                routing {
                    post("/v1/chat/completions") {
                        val body = mapper.readTree(call.receiveText())
                        val prompt = body.path("messages").path(0).path("content").asText("")
                        val model = body.path("model").asText("")
                        val routed = dispatch(prompt)
                        if (routed == null) {
                            val head = prompt.take(300).replace('\n', ' ')
                            System.err.println("[fake-llm] UNMATCHED PROMPT (model=$model): $head")
                            call.respondText(
                                mapper.writeValueAsString(
                                    mapOf("error" to "FakeLlmServer: no dispatch marker matched this prompt (marker drift?). Prompt head: $head")
                                ),
                                ContentType.Application.Json,
                                HttpStatusCode.InternalServerError,
                            )
                        } else {
                            val (route, planned) = routed
                            // Recorded before the reply is shaped, so an injected fault still
                            // appears in the call sequence — a scenario asserting "score_fit ran
                            // and then nothing did" needs the failed call to be visible.
                            calls += route
                            if (planned.delayMs > 0) delay(planned.delayMs)
                            val failing = planned.status !in 200..299
                            println(
                                "[fake-llm] served $route (model=$model)" +
                                    (if (failing) " → HTTP ${planned.status} (injected)" else "") +
                                    (if (planned.delayMs > 0) " after ${planned.delayMs}ms" else ""),
                            )
                            val payload = planned.rawBody ?: mapper.writeValueAsString(
                                mapOf("choices" to listOf(mapOf("message" to mapOf("content" to planned.content.orEmpty())))),
                            )
                            call.respondText(
                                payload,
                                ContentType.Application.Json,
                                HttpStatusCode.fromValue(planned.status),
                            )
                        }
                    }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            throw IllegalStateException(
                "FakeLlmServer failed to bind 0.0.0.0:$port — is a real local model server (oMLX) using it? " +
                    "Either run with REAL_LLM=1 or export E2E_FAKE_LLM_PORT=<free port> for BOTH `make e2e-up` and the test run.",
                e,
            )
        }
    }

    fun stop() {
        engine?.stop(500, 2000)
    }

    private fun dispatch(prompt: String): Pair<String, PlannedResponse>? {
        val suffix = buildString {
            if (prompt.contains("PREVIOUS VALIDATION FEEDBACK (revision pass")) append(":refine")
            if (prompt.contains("YOUR PREVIOUS OUTPUT WAS INVALID") || prompt.contains("REJECTED DRAFT")) append(":retry")
        }
        fun name(n: String) = n + suffix
        fun response(route: String, default: () -> String): Pair<String, PlannedResponse> {
            val routedName = name(route)
            return routedName to (responsePlan[routedName]?.poll() ?: ok(default()))
        }
        // The cover-letter prompt also contains score_fit's fallback marker
        // ("\n\nJOB DESCRIPTION:\n", GenerateCoverLetterNode). Identify it positively and
        // exclude it from the score branch, so the two can't alias if either prompt drifts.
        // Dispatch is on prompt markers only — never on the model name, which would
        // misroute the moment someone points a prose model at another node.
        val isCoverLetter = prompt.contains("Write a professional yet casual cover letter") ||
            prompt.contains("CANDIDATE STRENGTHS TO HIGHLIGHT")
        return when {
            prompt.contains("# Draft Reply Skill") || prompt.contains("You are drafting a professional reply to a recruiter") ->
                response("draft_reply") { fixture("draft_reply.txt") }
            isCoverLetter ->
                response("cover_letter") { fixture("cover_letter.txt") }
            prompt.contains("# SCAN_SKILL") ->
                response("scan_email") { fixture("scan_email.json") }
            prompt.contains("# SCRAPE_SKILL") ->
                response("scrape_jd") { fixture("scrape_jd.json") }
            prompt.contains("JD_EXTRACTION_SKILL") || prompt.contains("<job_description>") ->
                response("jd_extraction") { fixture("jd_extraction.json") }
            prompt.contains("GAP_ANALYSIS_SKILL") ->
                response("gap_analysis") { fixture("gap_analysis.json") }
            prompt.contains("SUMMARY_REWRITE_SKILL") ->
                response("summary_rewrite") { fixture("summary.txt") }
            prompt.contains("BULLET_REWRITE_SKILL") ->
                response("bullet_rewrite") { bulletEcho(prompt) }
            prompt.contains("SKILLS_RESTRUCTURE_SKILL") ->
                response("skills_restructure") { fixture("skills_restructure.json") }
            prompt.contains("ATS_VALIDATION_SKILL") ->
                response("ats_validation") { fixture("ats_validation.json") }
            prompt.contains("SCORE_SKILL") || (!isCoverLetter && prompt.contains("\n\nJOB DESCRIPTION:\n")) ->
                response("score_fit") { fixture("score_fit.json") }
            else -> null
        }
    }

    private fun fixture(name: String): String =
        Files.readString(fixturesDir.resolve("llm").resolve(name)).trim()

    /**
     * Parse the roles JSON the prompt ends with (after the CANDIDATE ROLES marker) and
     * echo every role's join keys and bullets back — deterministic, LaTeX-safe, and never
     * discarded by the role-key join.
     *
     * `rewritten` is the original text plus [BULLET_MARKER]. It must NOT be the identity:
     * BulletRewriteNode silently keeps the original bullet when the role join key misses,
     * so an identity echo makes "rewrite applied" and "every rewrite discarded" produce
     * byte-identical output and no assertion can tell them apart. The marker is asserted
     * in the rendered YAML (Tier B). It is plain ASCII, and deliberately not any
     * never-claim / unsupported fixture term, so it trips no leak or anti-parrot gate.
     */
    private fun bulletEcho(prompt: String): String {
        // "CANDIDATE ROLES" occurs TWICE in the assembled prompt: once in the prepended
        // BULLET_REWRITE_SKILL.md prose (followed by an *example* JSON array), and again
        // as the real data-section header that rolesJson is appended under. Anchoring on
        // the first occurrence parses the doc's example, so every echoed join key is a
        // placeholder, BulletRewriteNode's fold-back join misses every role, and all
        // rewrites are silently dropped. Anchor on the data header specifically.
        val dataHeader = "CANDIDATE ROLES (career history + projects)"
        val marker = prompt.indexOf(dataHeader)
            .takeIf { it >= 0 }
            ?: prompt.lastIndexOf("CANDIDATE ROLES")
        check(marker >= 0) {
            "bullet_rewrite prompt carries no 'CANDIDATE ROLES' marker (prompt drift?) — " +
                "refusing to guess at the roles JSON offset"
        }
        val arrStart = prompt.indexOf('[', marker)
        check(arrStart >= 0) { "bullet_rewrite prompt carries no roles JSON array" }
        val roles = mapper.readTree(prompt.substring(arrStart))
        val out = roles.map { role ->
            mapOf(
                "role" to role.path("role").asText(),
                "company" to role.path("company").asText(),
                "start_date" to role.path("start_date").asText(),
                "bullets" to role.path("bullets").mapIndexed { idx, b ->
                    mapOf(
                        "original" to b.path("text").asText(),
                        "category" to b.path("category").asText(),
                        "rewritten" to "${b.path("text").asText()} $BULLET_MARKER",
                        "must_have_hits" to emptyList<String>(),
                        "quantified" to false,
                        "seniority_signal" to (idx == 0),
                    )
                },
            )
        }
        return mapper.writeValueAsString(out)
    }
}
