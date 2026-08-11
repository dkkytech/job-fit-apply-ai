package com.jd.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.jd.e2e.FakeLlmServer.Companion.ok
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.relativeTo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue #56, scenarios 9 and 10 — the acceptance tests for the multi-instance work (#51).
 *
 * Two compose slices run side by side: the normal test slice (processing happens here) and a
 * synthetic **source** slice shaped like prod — own project name, ports, and state dir, no
 * processor, no poller/jsearch. CI never mounts actual production data: both slices are
 * `${E2E_STATE_DIR}`-scoped repo-local dirs created fresh per run.
 *
 * Scenario 9 proves that fully processing a job in the test slice leaves every source-shaped
 * resource byte-identically unchanged. Scenario 10 proves `scripts/replay-jobs.sh` — the only
 * intake path a test instance has — reads the source store read-only, routes each stored type
 * to the right endpoint, preserves payloads, dedupes repeats, and forks on `--force`.
 *
 * The classes skip (not fail) when no source slice is running (`make e2e` stays a two-slice-free
 * run); `make e2e-multi` and the CI e2e job set E2E_SOURCE_BRIDGE_URL and friends.
 */
@Tag("tier-b")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Multi-instance isolation + source-to-test replay (issue #56 scenarios 9/10)")
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class MultiInstanceE2ETest {
    private lateinit var harness: E2eScenarioHarness
    private val sourceUrl: String get() = E2eConfig.sourceBridgeUrl!!

    @BeforeAll
    fun startHarness() {
        assumeFalse(E2eConfig.realLlm, "fake-LLM-only scenarios: planned responses and exact call sequences")
        assumeTrue(
            E2eConfig.sourceConfigured,
            "no source slice (E2E_SOURCE_BRIDGE_URL unset) — run via `make e2e-multi` or the CI e2e job",
        )
        harness = SharedE2eHarness.start()
        // The source slice must be reachable before any snapshot is trusted.
        harness.getString("$sourceUrl/health")
    }

    // ── Scenario 9 — multi-instance isolation ────────────────────────────────

    /**
     * Everything observable about the source slice, captured before and after test-slice
     * activity. Comparing the whole snapshot at once means a leak anywhere (a claimed row, a
     * moved cursor, a stray artifact) fails with a diff naming the resource that moved.
     */
    private data class SourceSnapshot(
        val bridgeRows: List<String>,
        val completedHead: Long,
        val tracksCount: Int,
        val outputListing: List<String>,
        val notifierState: Map<String, String>,
    )

    @Test
    @DisplayName("scenario 9: a job processed in the test instance leaves the source instance unchanged")
    fun testInstanceActivityLeavesSourceUntouched() {
        val nonce = System.currentTimeMillis().toString()

        // Seed one PENDING job into the source bridge. The source slice has no processor, so
        // the only way this row can change is a cross-instance leak — which is the point.
        val seedCompany = "E2E SrcSeed $nonce"
        val seedId = harness.postJson(
            "$sourceUrl/api/jobs",
            harness.mapper.writeValueAsString(
                mapOf(
                    "jd_text" to sourceSeedJd(seedCompany),
                    "company" to seedCompany,
                    "role_title" to "Source Seed Engineer",
                    "source" to "MANUAL",
                    "idempotency_key" to "e2e-src-seed-$nonce",
                ),
            ),
        ).path("job_id").asText()
        assertTrue(seedId.isNotBlank(), "source seed submission returned no job_id")

        // Cold-start writes (notifier cursor seeding) settle before the snapshot is taken.
        Thread.sleep(E2eConfig.settleMs)
        val before = sourceSnapshot()
        assertTrue(
            before.bridgeRows.any { seedId in it && "pending" in it },
            "seed $seedId must be a pending source row before the test-slice run: ${before.bridgeRows}",
        )

        // Neither project may have Gmail-touching intake containers, running OR stopped.
        val allContainers = docker("ps", "-a", "--format", "{{.Names}}").lines()
        for (project in listOf(E2eConfig.sourceProject, E2eConfig.testProject)) {
            assertTrue(
                allContainers.none { it.startsWith("$project-poller") || it.startsWith("$project-jsearch") },
                "instance '$project' must not have poller/jsearch containers (Gmail is account-global): $allContainers",
            )
        }

        // A full transaction in the TEST instance: submit → process → track → notify.
        val company = "E2E Isolation $nonce"
        val result = harness.runScenario(company) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company)),
                idempotencyKey = "e2e-isolation-$nonce",
            )
        }
        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertEquals(1, result.completedEvents.size)

        // The source slice: byte-identical on every axis.
        Thread.sleep(E2eConfig.settleMs)
        val after = sourceSnapshot()
        assertEquals(before.bridgeRows, after.bridgeRows, "source bridge store rows must not change")
        assertEquals(before.completedHead, after.completedHead, "source completed-feed head must not move")
        assertEquals(before.tracksCount, after.tracksCount, "source Postgres must gain no tracks rows")
        assertEquals(before.outputListing, after.outputListing, "source output dir must gain no artifacts")
        assertEquals(before.notifierState, after.notifierState, "source notifier cursor must not move")

        // The seeded row specifically: still pending, still unclaimed.
        val seedStatus = harness.mapper.readTree(harness.getString("$sourceUrl/api/jobs/$seedId"))
        assertEquals("pending", seedStatus.path("status").asText(), "nothing may claim a source job: $seedStatus")

        // Source containers still healthy after the test slice did real work.
        for (service in listOf("db", "bridge", "markserv", "notifier")) {
            val name = "${E2eConfig.sourceProject}-$service"
            assertEquals(
                "healthy",
                docker("inspect", "-f", "{{.State.Health.Status}}", name).trim(),
                "source container $name must stay healthy",
            )
        }

        // And no cross-talk in the evidence: the source company never reached the test feed.
        assertTrue(
            result.allCompletedEvents.none { it.path("company").asText() == seedCompany },
            "the source-only company must never appear in the test slice's completed feed",
        )
    }

    // ── Scenario 10 — source-to-test replay ──────────────────────────────────

    @Test
    @DisplayName("scenario 10: stored source payloads replay verbatim to the right endpoints, dedupe on repeat, fork on --force")
    fun replayRoutesPreservesDedupesAndForks() {
        val nonce = System.currentTimeMillis().toString()
        val companyJob = "E2E ReplayJob $nonce"
        val companyEmail = "E2E ReplayEmail $nonce"
        val companyPage = "E2E ReplayPage $nonce"
        val emailRole = "Staff Mobile Test Infrastructure Engineer"
        val pageRole = "Principal Quality Platform Engineer"

        // 1. Seed the source slice with all three intake types (rows stay pending — no processor).
        val srcJobId = harness.postJson(
            "$sourceUrl/api/jobs",
            harness.mapper.writeValueAsString(
                mapOf(
                    "jd_text" to harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to companyJob)),
                    "company" to companyJob,
                    "role_title" to "Staff Software Engineer in Test",
                    "location" to "Remote (US)",
                    "source" to "MANUAL",
                    "idempotency_key" to "e2e-replay-job-$nonce",
                ),
            ),
        ).path("job_id").asText()
        val srcEmailId = harness.postJson(
            "$sourceUrl/api/emails",
            harness.mapper.writeValueAsString(
                mapOf(
                    "message_id" to "e2e-replay-message-$nonce",
                    "subject" to "$emailRole opportunity at $companyEmail",
                    "body" to
                        """
                        EMAIL_SOURCE_MARKER
                        Hello, I am recruiting for a specific $emailRole opening at $companyEmail.
                        The role owns mobile, API, and CI/CD test infrastructure and requires eight
                        years of Kotlin, Appium, contract-testing, and platform reliability experience.
                        """.trimIndent(),
                    "html_body" to null,
                    "from" to "Recruiter <recruiter@example.invalid>",
                    "is_recruiter_hint" to true,
                    "idempotency_key" to "e2e-replay-email-$nonce",
                ),
            ),
        ).path("job_id").asText()
        val srcPageId = harness.postJson(
            "$sourceUrl/api/pages",
            harness.mapper.writeValueAsString(
                mapOf(
                    "url" to "https://capture.invalid/replay/$nonce",
                    "title" to "$pageRole at $companyPage",
                    "text" to
                        """
                        PAGE_CAPTURE_SOURCE_MARKER
                        $companyPage is hiring a $pageRole. The role owns Kotlin test infrastructure,
                        mobile and API automation, CI/CD paved roads, contract testing, and
                        observability. Candidates should have eight years of experience leading
                        reliable quality platforms across distributed engineering organizations.
                        """.trimIndent(),
                    "idempotency_key" to "e2e-replay-page-$nonce",
                ),
            ),
        ).path("job_id").asText()
        val sourceIds = listOf(srcJobId, srcEmailId, srcPageId)
        assertTrue(sourceIds.all { it.isNotBlank() }, "source seeding failed: $sourceIds")

        val beforeRows = sourceJobsDump()
        val beforeHead = sourceCompletedHead()
        val beforeTracks = sourceTracksCount()

        // 2. Plan the extraction routes for the replayed email/page; scoring uses defaults.
        harness.fakeLlm.reset(
            mapOf(
                "scan_email" to listOf(
                    ok(harness.fixture("llm/scan_email.json", mapOf("COMPANY" to companyEmail, "ROLE" to emailRole))),
                ),
                "scrape_jd" to listOf(
                    ok(harness.fixture("llm/scrape_jd.json", mapOf("COMPANY" to companyPage, "ROLE" to pageRole))),
                ),
            ),
        )
        harness.sink.reset()
        val testCursor = harness.mapper
            .readTree(harness.getString("${E2eConfig.bridgeUrl}/api/jobs/completed/head"))
            .path("max_seq").asLong(0)

        // 3. Replay by --id: selection and per-type endpoint routing in one pass.
        val first = replay("--id", srcJobId, "--id", srcEmailId, "--id", srcPageId)
        assertEquals(3, first.size, "expected one replay line per source row: $first")
        val byName = first.associateBy { it.path("source_id").asText() }
        assertEquals("/api/jobs", byName.getValue(srcJobId).path("endpoint").asText())
        assertEquals("/api/emails", byName.getValue(srcEmailId).path("endpoint").asText())
        assertEquals("/api/pages", byName.getValue(srcPageId).path("endpoint").asText())
        first.forEach { line ->
            assertTrue(line.path("http_status").asInt() in 200..299, "replay POST failed: $line")
            assertFalse(line.path("response").path("deduped").asBoolean(false), "first replay must not dedupe: $line")
        }
        val testIds = sourceIds.map { byName.getValue(it).path("response").path("job_id").asText() }

        // 4. Every replayed job completes — in the TEST instance.
        testIds.forEach { awaitDone(it) }

        // 5. Payload preservation: the test store holds semantically identical jd_json per row.
        val sourcePayloads = jdJsonById(E2eConfig.sourceStateDir, sourceIds)
        val testPayloads = jdJsonById(E2eConfig.stateDir, testIds)
        sourceIds.zip(testIds).forEach { (sourceId, testId) ->
            assertEquals(
                harness.mapper.readTree(sourcePayloads.getValue(sourceId)),
                harness.mapper.readTree(testPayloads.getValue(testId)),
                "replayed payload for $sourceId must reach the test bridge unaltered",
            )
        }

        // 6. The source was read, never written: rows byte-identical, head parked, no tracking.
        Thread.sleep(E2eConfig.settleMs)
        assertEquals(beforeRows, sourceJobsDump(), "replay must open the source store read-only")
        assertEquals(beforeHead, sourceCompletedHead(), "replay must not complete anything in the source")
        assertEquals(beforeTracks, sourceTracksCount(), "replay must not track anything in the source")

        // 7. Artifacts/events/notifications exist only in the test instance, once per company.
        val events = harness.completedEventsSince(testCursor)
        for (company in listOf(companyJob, companyEmail, companyPage)) {
            assertEquals(
                1, events.count { it.path("company").asText() == company },
                "exactly one test completed event for '$company'",
            )
            assertEquals(
                1, harness.sink.discordTexts().count { it.contains(company) },
                "exactly one Discord notification for '$company' (from the test notifier)",
            )
            assertEquals(1, harness.countTracks(company), "exactly one test tracks row for '$company'")
        }

        // 8. Replaying the same rows again dedupes to the same test jobs — no new side effects.
        val second = replay("--id", srcJobId, "--id", srcEmailId, "--id", srcPageId)
        second.forEach { line ->
            assertTrue(line.path("response").path("deduped").asBoolean(false), "repeat replay must dedupe: $line")
            assertTrue(
                line.path("response").path("job_id").asText() in testIds,
                "dedupe must return the original test job: $line",
            )
        }
        Thread.sleep(E2eConfig.settleMs)
        val afterDedupe = harness.completedEventsSince(testCursor)
        for (company in listOf(companyJob, companyEmail, companyPage)) {
            assertEquals(
                1, afterDedupe.count { it.path("company").asText() == company },
                "a deduped replay must not create a second completed event for '$company'",
            )
        }

        // 9. --force is the deliberate fork: a NEW test execution for the same source row.
        val forced = replay("--id", srcJobId, "--force").single()
        assertFalse(forced.path("response").path("deduped").asBoolean(false), "--force must beat dedupe: $forced")
        val forcedId = forced.path("response").path("job_id").asText()
        assertNotEquals(testIds[0], forcedId, "--force must create a new test job")
        awaitDone(forcedId)
        assertEquals(
            2,
            harness.completedEventsSince(testCursor).count { it.path("company").asText() == companyJob },
            "--force must produce a second, distinct test execution",
        )

        // 10. No Gmail write-back can happen from the test instance: the terminal email job's
        // write-back stays queued forever because no poller exists to drain it.
        val writeback = writebackDone(E2eConfig.stateDir, testIds[1])
        assertEquals(false, writeback, "the replayed email's writeback_done must stay false in the test store")
        assertTrue(
            docker("ps", "-a", "--format", "{{.Names}}").lines()
                .none { it.startsWith("${E2eConfig.testProject}-poller") },
            "the test instance must have no poller container to drain Gmail write-backs",
        )

        // And the source store STILL has not moved, force replay included.
        assertEquals(beforeRows, sourceJobsDump(), "the source store must survive the whole scenario untouched")
    }

    // ── Evidence helpers ─────────────────────────────────────────────────────

    private fun sourceSnapshot(): SourceSnapshot = SourceSnapshot(
        bridgeRows = sourceJobsDump(),
        completedHead = sourceCompletedHead(),
        tracksCount = sourceTracksCount(),
        outputListing = dirListing(E2eConfig.sourceStateDir.resolve("output")),
        notifierState = dirContents(E2eConfig.sourceStateDir.resolve("notifier-state")),
    )

    /** Full, deterministic dump of the source bridge store — any mutation shows as a diff. */
    private fun sourceJobsDump(): List<String> = jobsDump(E2eConfig.sourceStateDir)

    private fun jobsDump(stateDir: Path): List<String> =
        E2eConfig.bridgeStoreConnection(stateDir).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT id, status, type, coalesce(claim_token, ''), writeback_done,
                           coalesce(completed_seq, -1), coalesce(jd_json, ''), created_at, updated_at
                    FROM jobs ORDER BY id
                    """.trimIndent(),
                ).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add((1..9).joinToString("|") { rows.getString(it) ?: "" })
                        }
                    }
                }
            }
        }

    private fun jdJsonById(stateDir: Path, ids: List<String>): Map<String, String> =
        E2eConfig.bridgeStoreConnection(stateDir).use { connection ->
            ids.associateWith { id ->
                connection.prepareStatement("SELECT jd_json FROM jobs WHERE id = ?").use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next(), "no row $id in $stateDir bridge store")
                        rows.getString(1).orEmpty()
                    }
                }
            }
        }

    private fun writebackDone(stateDir: Path, id: String): Boolean =
        E2eConfig.bridgeStoreConnection(stateDir).use { connection ->
            connection.prepareStatement("SELECT writeback_done FROM jobs WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next(), "no row $id in $stateDir bridge store")
                    rows.getBoolean(1)
                }
            }
        }

    private fun sourceCompletedHead(): Long = harness.mapper
        .readTree(harness.getString("$sourceUrl/api/jobs/completed/head"))
        .path("max_seq").asLong(0)

    private fun sourceTracksCount(): Int = E2eConfig.sourcePgConnection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM tracks").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /** Relative path + size of every file under [dir] — presence AND content-length changes show. */
    private fun dirListing(dir: Path): List<String> {
        if (!Files.exists(dir)) return emptyList()
        return Files.walk(dir).use { stream ->
            stream.filter(Files::isRegularFile)
                .map { "${it.relativeTo(dir)}:${Files.size(it)}" }
                .toList()
                .sorted()
        }
    }

    /** Name → content for a small state dir (the notifier cursor lives here). */
    private fun dirContents(dir: Path): Map<String, String> {
        if (!Files.exists(dir)) return emptyMap()
        return Files.walk(dir).use { stream ->
            stream.filter(Files::isRegularFile).toList()
                .associate { "${it.relativeTo(dir)}" to Files.readString(it) }
        }
    }

    private fun awaitDone(jobId: String) {
        val deadline = System.nanoTime() + E2eConfig.timeoutSeconds * 1_000_000_000L
        var last = "unread"
        while (true) {
            // Transient transport failures are re-polled, matching the harness's pollUntil.
            runCatching { harness.mapper.readTree(harness.getString("${E2eConfig.bridgeUrl}/api/jobs/$jobId")) }
                .getOrNull()?.let { status ->
                    when (status.path("status").asText()) {
                        "done" -> return
                        "error" -> fail(
                            "replayed job $jobId errored: ${status.path("error").asText()} " +
                                "(llm calls: ${harness.fakeLlm.calls})",
                        )
                        else -> last = status.toString()
                    }
                }
            if (System.nanoTime() > deadline) {
                fail("replayed job $jobId not done after ${E2eConfig.timeoutSeconds}s (last=$last, llm calls: ${harness.fakeLlm.calls})")
            }
            Thread.sleep(2000)
        }
    }

    // ── Process helpers ──────────────────────────────────────────────────────

    private val repoRoot: Path = Paths.get("../..").toAbsolutePath().normalize()

    /** Run scripts/replay-jobs.sh against the source store → parsed --json lines. */
    private fun replay(vararg args: String): List<JsonNode> {
        val command = listOf(
            repoRoot.resolve("scripts/replay-jobs.sh").toString(),
            "--store", E2eConfig.sourceStateDir.resolve("bridge-store").toString(),
            "--status", "all",
            "--to", E2eConfig.bridgeUrl,
            "--json",
        ) + args
        val process = ProcessBuilder(command).directory(repoRoot.toFile()).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "replay-jobs.sh timed out: $command")
        assertEquals(0, process.exitValue(), "replay-jobs.sh failed.\nstdout: $stdout\nstderr: $stderr")
        return stdout.lineSequence().filter { it.isNotBlank() }
            .map { harness.mapper.readTree(it) }
            .toList()
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "docker ${args.joinToString(" ")} timed out")
        assertEquals(0, process.exitValue(), "docker ${args.joinToString(" ")} failed: $output")
        return output
    }

    private fun sourceSeedJd(company: String): String =
        """
        $company is hiring a Source Seed Engineer to look after synthetic fixtures. This
        pending row exists only to prove that no other instance can claim or mutate it —
        the source slice runs no processor, so any state change here is a cross-instance
        isolation failure by definition. Requirements: none. Benefits: immutability.
        """.trimIndent()
}
