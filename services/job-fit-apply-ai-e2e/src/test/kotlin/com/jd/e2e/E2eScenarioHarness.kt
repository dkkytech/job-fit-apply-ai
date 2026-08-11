package com.jd.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertTrue
import kotlin.test.fail

data class TrackEvidence(
    val emailId: String,
    val roleTitle: String,
    val pipelineAction: String,
    val artifactUrl: String?,
    val outputPath: String?,
    val jobUrl: String,
    val jdText: String,
)

data class ScenarioResult(
    val jobId: String,
    val company: String,
    val completedCursor: Long,
    val finalStatus: JsonNode,
    val completedEvents: List<JsonNode>,
    /**
     * Every completed event since this scenario's cursor, *whatever* its `job_id`. A duplicate
     * processed as a second job, or a digest child, carries an id the submitter never saw — so
     * per-job filtering cannot observe it. Correlate on company / message_id here instead.
     */
    val allCompletedEvents: List<JsonNode>,
    val artifactUrl: String?,
    val outputDir: Path?,
    val track: TrackEvidence,
    val apiTrack: JsonNode,
    val discordMessages: List<String>,
    val telegramMessages: List<String>,
    val llmCalls: List<String>,
) {
    val completedEvent: JsonNode get() = completedEvents.singleOrNull()
        ?: error("expected exactly one completed event for $jobId, got ${completedEvents.size}: $completedEvents")

    /** Completed events for [company], including any this scenario did not submit directly. */
    fun eventsForCompany(company: String = this.company): List<JsonNode> =
        allCompletedEvents.filter { it.path("company").asText() == company }
}

/**
 * Mirrors NOTIFICATION_FIT_THRESHOLD, which docker-compose.e2e.yml pins to 50 for the slice
 * precisely so this side can be a constant. Change one, change the other — a mismatch shows up
 * as a 30s timeout waiting for a Telegram that was never going to be sent.
 */
private const val NOTIFIER_FIT_THRESHOLD = 50

/**
 * The one misconfiguration that produces a TAILOR run with no `artifact_url`, named at the point
 * of failure so the assertion does not just say "it was null".
 */
const val NO_ARTIFACT_URL_HINT: String =
    "completed event carries no artifact_url — ARTIFACT_BASE_URL is not reaching the processor " +
        "(it must live in .e2e/pipeline.env, not the compose `environment:` block)"

/**
 * The single harness every E2E class shares.
 *
 * The fake LLM and the sink bind *fixed* ports — the ones docker compose interpolated into the
 * containers at `up` time, so they cannot be per-class ephemeral. An instance per test class
 * therefore has two objects competing for one port pair: safe today only because JUnit runs
 * classes sequentially in a single fork (the `@Execution(SAME_THREAD)` annotations do not
 * provide this — parallel execution is simply off), and even then it depends on Netty releasing
 * the port between classes. When it loses that race the symptom is FakeLlmServer's
 * "something is already listening" message, which blames a stray oMLX and sends you hunting in
 * the wrong place. One instance, started on first use, stopped when the test JVM exits.
 */
object SharedE2eHarness {
    private val harness = E2eScenarioHarness()
    private var started = false

    @Synchronized
    fun start(): E2eScenarioHarness {
        if (!started) {
            harness.start()
            // No @AfterAll can own this: the next class still needs the servers up. The test JVM
            // is short-lived and the servers are in-process, so exit is the right teardown point.
            Runtime.getRuntime().addShutdownHook(Thread(harness::stop, "e2e-harness-stop"))
            started = true
        }
        return harness
    }
}

/** Black-box transaction harness. Obtain the shared instance via [SharedE2eHarness]. */
class E2eScenarioHarness {
    val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    val fakeLlm = FakeLlmServer(E2eConfig.fakeLlmPort, E2eConfig.fixturesDir)
    val sink = MockNotificationSink(E2eConfig.sinkPort)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun start() {
        if (!E2eConfig.realLlm) fakeLlm.start()
        sink.start()
        preflight()
    }

    fun stop() {
        fakeLlm.stop()
        sink.stop()
    }

    fun fixture(relativePath: String, replacements: Map<String, String> = emptyMap()): String =
        replacements.entries.fold(Files.readString(E2eConfig.fixturesDir.resolve(relativePath)).trim()) { text, entry ->
            text.replace("{{${entry.key}}}", entry.value)
        }

    fun runScenario(
        company: String,
        responses: Map<String, List<FakeLlmServer.PlannedResponse>> = emptyMap(),
        sinkResponses: Map<String, List<MockNotificationSink.PlannedResponse>> = emptyMap(),
        /**
         * The terminal bridge status this scenario is asserting about. Reaching the *other*
         * terminal state is itself a failure, and is reported as one — a fault-injection
         * scenario that silently completes `done` would otherwise assert against a healthy run.
         */
        expectTerminal: String = "done",
        submit: E2eScenarioHarness.() -> JsonNode,
    ): ScenarioResult {
        // A queued plan under REAL_LLM=1 is a caller mistake, not something to paper over:
        // silently dropping it would leave the scenario asserting canned values against a
        // real model and fail somewhere far from the cause.
        check(!E2eConfig.realLlm || responses.isEmpty()) {
            "runScenario('$company') queued fake-LLM responses ${responses.keys} but E2E_REAL_LLM=1 — " +
                "a planned-response scenario cannot run against a real model. Tag the test tier-b and " +
                "guard it with assumeFalse(E2eConfig.realLlm)."
        }
        if (!E2eConfig.realLlm) fakeLlm.reset(responses)
        sink.reset(sinkResponses)

        val completedCursor = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/jobs/completed/head"))
            .path("max_seq").asLong(0)
        val submitResponse = submit()
        val jobId = submitResponse.path("job_id").asText("")
        assertTrue(jobId.isNotBlank(), "submit returned no job_id: $submitResponse")
        assertTrue(!submitResponse.path("deduped").asBoolean(false), "unique scenario input unexpectedly deduped: $submitResponse")
        println("[e2e] submitted job $jobId as '$company'")

        val finalStatus = pollUntil(E2eConfig.timeoutSeconds, 2000, "job $jobId to reach $expectTerminal") {
            val status = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/jobs/$jobId"))
            when (val reached = status.path("status").asText()) {
                expectTerminal -> status
                "error" -> fail(
                    "job $jobId ended in status=error: ${status.path("error").asText()} " +
                        "(fake-llm calls so far: ${fakeLlm.calls})",
                )
                "done" -> fail(
                    "job $jobId completed as done, but this scenario expects terminal " +
                        "'$expectTerminal' — the injected fault did not take effect " +
                        "(fake-llm calls: ${fakeLlm.calls})",
                )
                else -> null.also { _ -> check(reached.isNotBlank()) { "blank status for $jobId" } }
            }
        }
        println("[e2e] job done: $finalStatus")

        // First sighting only — the snapshot the "exactly one" verdict is read from is taken
        // further down, after the notifier round trip and a settle window. A poll that returns
        // the moment the job appears cannot see a second event, so asserting uniqueness on it
        // would be very nearly a tautology.
        val firstSighting = pollUntil(
            30,
            500,
            "job $jobId to appear in the completed feed since seq=$completedCursor",
        ) {
            completedEventsFor(jobId, completedCursor).takeIf { it.isNotEmpty() }
        }
        println("[e2e] job $jobId in completed feed (${firstSighting.size} event(s) at first sighting)")

        val firstDiscord = pollUntil(
            90,
            1000,
            { "notifier to deliver Discord for '$company' (sink saw: ${sink.describe()})" },
        ) {
            sink.discordTexts().filter { it.contains(company) }.takeIf { it.isNotEmpty() }
        }
        println("[e2e] notifier delivered Discord for '$company' (${firstDiscord.size} message(s) at first sighting)")

        // Everything below is evidence for "exactly one" / "none at all" assertions, which are
        // only as strong as the window they are given. Notifier.notify() posts Discord and THEN
        // Telegram inside one call, so a snapshot taken the instant Discord lands would miss a
        // wrongly-sent Telegram by one HTTP round trip.
        Thread.sleep(E2eConfig.settleMs)

        val discordMessages = sink.discordTexts().filter { it.contains(company) }
        val telegramCompany = company.replace("&", "&amp;")
        val telegramMessages = if (finalStatus.path("fit_score").asInt() >= NOTIFIER_FIT_THRESHOLD) {
            pollUntil(
                30,
                1000,
                { "notifier to deliver Telegram for '$company' (sink saw: ${sink.describe()})" },
            ) {
                sink.telegramTexts().filter { it.contains(telegramCompany) }.takeIf { it.isNotEmpty() }
            }
        } else {
            sink.telegramTexts().filter { it.contains(telegramCompany) }
        }

        // Re-read the feed now: the notifier round trip above means real time has passed since
        // the first sighting, so a duplicate completion has had a chance to land and be seen.
        val allCompletedEvents = pollUntil(10, 500, "completed-feed re-read for job $jobId") {
            completedEventsSince(completedCursor).takeIf { events ->
                events.any { it.path("job_id").asText() == jobId }
            }
        }
        val completedEvents = allCompletedEvents.filter { it.path("job_id").asText() == jobId }
        val artifactUrl = completedEvents.first().path("artifact_url").asText("").ifBlank { null }
        val outputDir = artifactUrl?.let {
            val dirName = URLDecoder.decode(it.trimEnd('/').substringAfterLast('/'), Charsets.UTF_8)
            E2eConfig.outputDir.resolve(dirName)
        }

        val track = loadTrack(company)
        val apiTrack = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/tracks"))
            .firstOrNull { it.path("company").asText() == company }
            ?: fail("/api/tracks has no row for '$company'")

        return ScenarioResult(
            jobId = jobId,
            company = company,
            completedCursor = completedCursor,
            finalStatus = finalStatus,
            completedEvents = completedEvents,
            allCompletedEvents = allCompletedEvents,
            artifactUrl = artifactUrl,
            outputDir = outputDir,
            track = track,
            apiTrack = apiTrack,
            discordMessages = discordMessages,
            telegramMessages = telegramMessages,
            llmCalls = if (E2eConfig.realLlm) emptyList() else fakeLlm.calls.toList(),
        )
    }

    fun submitScrapedJob(
        company: String,
        roleTitle: String,
        jdText: String,
        idempotencyKey: String,
        location: String = "Remote (US)",
    ): JsonNode = postJson(
        "${E2eConfig.bridgeUrl}/api/jobs",
        mapper.writeValueAsString(
            mapOf(
                "jd_text" to jdText,
                "company" to company,
                "role_title" to roleTitle,
                "location" to location,
                "source" to "MANUAL",
                "idempotency_key" to idempotencyKey,
            ),
        ),
    )

    fun submitPage(url: String, title: String, text: String, idempotencyKey: String): JsonNode = postJson(
        "${E2eConfig.bridgeUrl}/api/pages",
        mapper.writeValueAsString(
            mapOf("url" to url, "title" to title, "text" to text, "idempotency_key" to idempotencyKey),
        ),
    )

    fun submitEmail(
        messageId: String,
        subject: String,
        body: String,
        from: String,
        idempotencyKey: String,
    ): JsonNode = postJson(
        "${E2eConfig.bridgeUrl}/api/emails",
        mapper.writeValueAsString(
            mapOf(
                "message_id" to messageId,
                "subject" to subject,
                "body" to body,
                "html_body" to null,
                "from" to from,
                "is_recruiter_hint" to true,
                "idempotency_key" to idempotencyKey,
            ),
        ),
    )

    fun getString(url: String): String {
        val response = request(url, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "GET $url → ${response.statusCode()}: ${response.body().take(300)}"
        }
        return response.body()
    }

    fun getBytes(url: String): ByteArray {
        val response = request(url, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) { "GET $url → ${response.statusCode()}" }
        return response.body()
    }

    fun statusOf(url: String): Int = request(url, HttpResponse.BodyHandlers.discarding()).statusCode()

    /**
     * POST returning status *and* body without throwing, for endpoints whose refusal is the
     * thing under test — a duplicate result is a 200 the caller must inspect, and a fenced-off
     * one is a 409.
     */
    fun postForResponse(url: String, body: String): Pair<Int, String> {
        val response = request(url, HttpResponse.BodyHandlers.ofString(), body)
        return response.statusCode() to response.body()
    }

    /** How many `tracks` rows exist for [company] — one logical job must produce exactly one. */
    fun countTracks(company: String): Int = E2eConfig.pgConnection().use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM tracks WHERE company = ?").use { statement ->
            statement.setString(1, company)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    /** Every completed-feed event since [cursor] — this scenario's slice, all job ids. */
    fun completedEventsSince(cursor: Long): List<JsonNode> =
        mapper.readTree(
            getString("${E2eConfig.bridgeUrl}/api/jobs/completed?since=$cursor&limit=200&all=true"),
        ).toList()

    private fun completedEventsFor(jobId: String, cursor: Long): List<JsonNode> =
        completedEventsSince(cursor).filter { it.path("job_id").asText() == jobId }

    private fun preflight() {
        val health = runCatching { getString("${E2eConfig.bridgeUrl}/health") }
        if (health.isFailure) {
            fail(
                "Bridge not reachable at ${E2eConfig.bridgeUrl} — is the e2e slice running? " +
                    "Start it with `make e2e-up`. (${health.exceptionOrNull()?.message})",
            )
        }
    }

    /** Public: the multi-instance scenarios also POST to the *source* slice's bridge. */
    fun postJson(url: String, body: String): JsonNode {
        val response = request(url, HttpResponse.BodyHandlers.ofString(), body)
        check(response.statusCode() in 200..299) {
            "POST $url → ${response.statusCode()}: ${response.body().take(300)}"
        }
        return mapper.readTree(response.body())
    }

    private fun loadTrack(company: String): TrackEvidence = E2eConfig.pgConnection().use { connection ->
        connection.prepareStatement(
            """
            SELECT email_id, role_title, pipeline_action, artifact_url, output_path, job_url, jd_text
            FROM tracks WHERE company = ? ORDER BY id DESC LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, company)
            statement.executeQuery().use { result ->
                assertTrue(result.next(), "no tracks row for company '$company'")
                TrackEvidence(
                    emailId = result.getString("email_id").orEmpty(),
                    roleTitle = result.getString("role_title").orEmpty(),
                    pipelineAction = result.getString("pipeline_action").orEmpty(),
                    artifactUrl = result.getString("artifact_url"),
                    outputPath = result.getString("output_path"),
                    jobUrl = result.getString("job_url").orEmpty(),
                    jdText = result.getString("jd_text").orEmpty(),
                )
            }
        }
    }

    private fun <T> request(
        url: String,
        handler: HttpResponse.BodyHandler<T>,
        body: String? = null,
    ): HttpResponse<T> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
        if (body == null) {
            builder.GET()
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        }
        return http.send(builder.build(), handler)
    }

    private fun <T> pollUntil(
        deadlineSeconds: Long,
        intervalMs: Long,
        what: String,
        probe: () -> T?,
    ): T = pollUntil(deadlineSeconds, intervalMs, { what }, probe)

    private fun <T> pollUntil(
        deadlineSeconds: Long,
        intervalMs: Long,
        what: () -> String,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + deadlineSeconds * 1_000_000_000L
        var lastTransient: Exception? = null
        while (true) {
            try {
                probe()?.let { return it }
                lastTransient = null
            } catch (error: IOException) {
                lastTransient = error
            } catch (error: IllegalStateException) {
                lastTransient = error
            }
            if (System.nanoTime() > deadline) {
                fail(
                    "timed out after ${deadlineSeconds}s waiting for ${what()}" +
                        (lastTransient?.let { " (last transport failure: $it)" } ?: ""),
                )
            }
            Thread.sleep(intervalMs)
        }
    }
}
