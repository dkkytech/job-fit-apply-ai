package com.jd.e2e

import com.jd.e2e.FakeLlmServer.Companion.failure
import com.jd.e2e.MockNotificationSink.Companion.failure as sinkFailure
import com.jd.e2e.FakeLlmServer.Companion.ok
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deterministic scenarios for existing product paths; fake-LLM only by contract. */
@Tag("tier-b")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Existing product-path E2E scenarios")
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class ExistingProductPathsE2ETest {
    private lateinit var harness: E2eScenarioHarness

    /**
     * The `tier-b` tag alone does not keep these out of a real-LLM run: it only bites when
     * someone passes `-PexcludeTags` (the Makefile does under REAL_LLM=1; a bare `./gradlew
     * test` — what CI runs — does not). Every scenario here pins a planned fake response and
     * an exact call sequence, so against a real model they cannot pass: skip the container
     * outright rather than fail it. The tag stays — it is what makes `-PexcludeTags` work.
     */
    @BeforeAll
    fun startHarness() {
        assumeFalse(E2eConfig.realLlm, "fake-LLM-only scenarios: planned responses and exact call sequences")
        // Shared with HappyPathE2ETest — fixed ports, JVM-scoped teardown. See [SharedE2eHarness].
        harness = SharedE2eHarness.start()
    }

    @Test
    @DisplayName("low-fit JD completes as SKIP without tailoring artifacts")
    fun lowFitJobSkipsWithoutTailoring() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Skip $nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf("score_fit" to listOf(ok(harness.fixture("llm/score_fit_skip.json")))),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Junior Manual QA Analyst",
                jdText = """
                    $company needs a junior manual QA analyst to execute scripted browser checks,
                    record results in spreadsheets, and report visual defects. This role does not
                    include automation, mobile engineering, CI/CD, backend APIs, cloud platforms,
                    observability, performance testing, or test-infrastructure ownership.
                """.trimIndent(),
                idempotencyKey = "e2e-skip-$nonce",
            )
        }

        assertEquals("done", result.finalStatus.path("status").asText())
        assertEquals(42, result.finalStatus.path("fit_score").asInt())
        assertEquals("SKIP", result.finalStatus.path("pipeline_action").asText())
        assertNull(result.artifactUrl, "SKIP must not publish artifact_url")
        assertNull(result.outputDir, "SKIP must not create an output directory")
        assertFalse(result.finalStatus.has("artifacts"), "SKIP status must not advertise artifact endpoints")
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/resume.pdf"))
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/cover_letter.txt"))

        assertEquals("skip", result.track.pipelineAction)
        assertTrue(result.track.artifactUrl.isNullOrBlank())
        assertTrue(result.track.outputPath.isNullOrBlank())
        assertEquals(1, result.completedEvents.size)
        assertEquals(result.jobId, result.completedEvent.path("job_id").asText())
        assertTrue(result.discordMessages.single().contains("(SKIP)"))
        assertTrue(result.telegramMessages.isEmpty(), "low-fit SKIP must not emit a high-fit Telegram alert")
        assertEquals(listOf("score_fit"), result.llmCalls, "no post-score tailoring LLM call may run for SKIP")
    }

    @Test
    @DisplayName("a pay-gated high fit is skipped, but its card says why and links the scoring notes; no resume, no Apply")
    fun payGatedHighFitCardSaysWhy() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E PayGate $nonce"
        val result = harness.runScenario(
            company = company,
            // Scores 72 (over the threshold), but posts at most $80K against the profile's $150K target — more than 40% short.
            responses = mapOf("score_fit" to listOf(ok(harness.fixture("llm/score_fit_pay_gated.json")))),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = """
                    $company is hiring a Staff Software Engineer in Test to own Kotlin test infrastructure,
                    mobile automation with Espresso and XCUITest, and CI/CD pipelines. Salary $70,000-$80,000.
                """.trimIndent(),
                idempotencyKey = "e2e-paygate-$nonce",
            )
        }

        val reason = "Posted pay (max \$80K) is more than 40% below the \$150K target"
        assertEquals("SKIP", result.finalStatus.path("pipeline_action").asText())
        assertEquals(72, result.finalStatus.path("fit_score").asInt())
        assertEquals(reason, result.completedEvent.path("skip_reason").asText())
        assertEquals(listOf("score_fit"), result.llmCalls, "a skipped job is never tailored")
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/resume.pdf"))

        // The scoring notes: report.md with the reason, published at artifact_url, on the track too.
        val artifactUrl = assertNotNull(result.artifactUrl, "a pay-gated high fit publishes its scoring notes")
        val report = Files.readString(assertNotNull(result.outputDir).resolve("report.md"))
        assertTrue(report.contains(reason), "report.md says why: $report")
        assertEquals(artifactUrl, result.track.artifactUrl)

        val card = result.telegramMessages.single()
        assertTrue(card.lines().any { it == "Skipped: Posted pay (max \$80K) is more than 40% below the \$150K target" }, card)
        // The card's details come through processor → bridge → notifier (the fixture's strengths and gap).
        assertTrue(card.lines().first().endsWith("(72)"), "Company: Title (score): $card")
        assertTrue(card.contains("\n\nFit:\n• Espresso and XCUITest framework ownership\n• CI/CD pipeline ownership\n\n"), card)
        assertTrue(card.lines().any { it == "Gap: Contract testing experience" }, card)
        assertEquals(listOf("Espresso and XCUITest framework ownership", "CI/CD pipeline ownership"),
            result.completedEvent.path("strengths").map { it.asText() })
        assertEquals(
            listOf("View Report" to artifactUrl.trimEnd('/') + "/report.md"),
            result.telegramButtons,
            "View Report only: no resume, and no Apply (there is nothing to upload)",
        )
    }

    @Test
    @DisplayName("ATS validation fails once, refines once, then passes")
    fun atsValidationRefinesExactlyOnce() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Refine $nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf(
                "ats_validation" to listOf(
                    ok(harness.fixture("llm/ats_validation_refine.json")),
                    ok(harness.fixture("llm/ats_validation.json")),
                ),
                "summary_rewrite:refine" to listOf(ok(harness.fixture("llm/summary_refined.txt"))),
            ),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company)),
                idempotencyKey = "e2e-refine-$nonce",
            )
        }

        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertEquals(72, result.finalStatus.path("fit_score").asInt())
        val outputDir = requireNotNull(result.outputDir) { NO_ARTIFACT_URL_HINT }
        val yaml = java.nio.file.Files.readString(outputDir.resolve("tailored_resume.yaml"))
        assertTrue(yaml.contains("ATS_REFINED_MARKER"), "final artifact does not contain the refinement output")
        assertEquals(
            listOf(
                "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation",
                "summary_rewrite:refine", "bullet_rewrite:refine", "skills_restructure:refine",
                "ats_validation", "cover_letter",
            ),
            result.llmCalls,
            "ATS scenario must perform one and only one refinement pass",
        )
        assertEquals("tailor", result.track.pipelineAction)
        assertEquals(1, result.completedEvents.size)
        assertTrue(result.discordMessages.single().contains("(TAILOR)"))
        assertEquals(1, result.telegramMessages.size)
    }

    @Test
    @DisplayName("captured page enters through /api/pages without external scraping")
    fun capturedPageCompletesFromRawText() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Page $nonce"
        val role = "Principal Quality Platform Engineer"
        val captureUrl = "https://capture.invalid/jobs/$nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf(
                "scrape_jd" to listOf(
                    ok(harness.fixture("llm/scrape_jd.json", mapOf("COMPANY" to company, "ROLE" to role))),
                ),
            ),
        ) {
            submitPage(
                url = captureUrl,
                title = "$role at $company",
                text = """
                    PAGE_CAPTURE_SOURCE_MARKER
                    $company is hiring a $role. The role owns Kotlin test infrastructure,
                    mobile and API automation, CI/CD paved roads, contract testing, and
                    observability. Candidates should have eight years of experience leading
                    reliable quality platforms across distributed engineering organizations.
                """.trimIndent(),
                idempotencyKey = "e2e-page-$nonce",
            )
        }

        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertEquals(role, result.track.roleTitle)
        assertEquals(captureUrl, result.track.jobUrl)
        assertTrue(result.track.jdText.contains("PAGE_CAPTURE_JD_MARKER"))
        assertTrue(!result.artifactUrl.isNullOrBlank(), NO_ARTIFACT_URL_HINT)
        assertEquals(
            listOf(
                "scrape_jd", "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation", "cover_letter",
            ),
            result.llmCalls,
            "captured text must route through scrape_jd exactly once before normal processing",
        )
        // A posting URL makes the job applicable: the ping carries Apply as <verb>:<completed_seq>.
        // No source email, so Reply and Archive must not appear.
        val seq = result.completedEvent.path("completed_seq").asLong()
        assertEquals(
            listOf("cb:apply:$seq"),
            result.telegramButtons.map { it.second }.filter { it.startsWith("cb:") },
            "captured page should get exactly one action button: ${result.telegramButtons}",
        )
        assertEquals(1, result.completedEvents.size)
        assertTrue(result.discordMessages.single().contains("(TAILOR)"))
        assertEquals(1, result.telegramMessages.size)
    }

    @Test
    @DisplayName("direct recruiter email enters through /api/emails as one correlated work item")
    fun directRecruiterEmailCompletesAsSingleWorkItem() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Email $nonce"
        val role = "Staff Mobile Test Infrastructure Engineer"
        val messageId = "e2e-message-$nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf(
                "scan_email" to listOf(
                    ok(harness.fixture("llm/scan_email.json", mapOf("COMPANY" to company, "ROLE" to role))),
                ),
            ),
        ) {
            submitEmail(
                messageId = messageId,
                subject = "$role opportunity at $company",
                body = """
                    EMAIL_SOURCE_MARKER
                    Hello Richard, I am recruiting for a specific $role opening at $company.
                    The role owns mobile, API, and CI/CD test infrastructure and requires eight
                    years of Kotlin, Appium, contract-testing, and platform reliability experience.
                    This is a remote US full-time position. Please review the full description below.
                """.trimIndent(),
                from = "Recruiter <recruiter@example.invalid>",
                idempotencyKey = "e2e-email-$nonce",
            )
        }

        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertEquals(messageId, result.track.emailId)
        assertEquals(role, result.track.roleTitle)
        assertTrue(result.track.jobUrl.isBlank(), "direct email fixture must not trigger external scraping")
        assertTrue(result.track.jdText.contains("EMAIL_SCAN_JD_MARKER"))
        assertTrue(!result.artifactUrl.isNullOrBlank(), NO_ARTIFACT_URL_HINT)
        assertEquals(
            listOf(
                "scan_email", "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation", "cover_letter", "draft_reply",
            ),
            result.llmCalls,
            "direct email must scan once, avoid scrape_jd, and run one normal processing flow",
        )
        assertEquals(1, result.completedEvents.size, "the submitted email work item must complete exactly once")
        assertEquals(result.jobId, result.completedEvent.path("job_id").asText())
        assertTrue(result.discordMessages.single().contains("(TAILOR)"))
        assertEquals(1, result.telegramMessages.size)

        // A recruiter email stays in the inbox, so it can be replied to and archived; with no
        // posting URL there is nothing to apply to.
        val event = result.completedEvent
        assertEquals(messageId, event.path("message_id").asText())
        assertTrue(event.path("is_recruiter").asBoolean(), "recruiter email not flagged: $event")
        assertEquals("Recruiter_Response_Required", event.path("terminal_label").asText(), "event: $event")
        val seq = event.path("completed_seq").asLong()
        assertEquals(
            listOf("cb:reply:$seq", "cb:archive:$seq"),
            result.telegramButtons.map { it.second }.filter { it.startsWith("cb:") },
            "recruiter email buttons: ${result.telegramButtons}",
        )
    }

    /**
     * Issue #56, scenario 3 — the Processor error contract.
     *
     * The failure mode is not the one the issue originally assumed. `ScoreFitNode` catches every
     * exception and returns `fitScore = 0f, error = "score_fit: …"`, so the pipeline is never
     * aborted and the job is never stuck `claimed`; the error reaches the bridge through
     * `ProcessingResult.error` instead. What that leaves behind is a **score-0 SKIP that also
     * wrote a tracks row** — a broken LLM shaped exactly like a genuine low-fit skip. The
     * assertions below pin the parts that hold and the parts that make the two distinguishable.
     */
    /**
     * On-demand tailoring. The slice pins FIT_THRESHOLD=50 and AUTO_TAILOR_MIN_SCORE=70, so a 60 is
     * OFFERed: scored, tracked and noted, with no tailoring calls. A Tailor tap
     * (POST /api/jobs/{id}/tailor) queues a TAILOR_REQUEST that re-scores and tailors it, updates
     * the offer's tracks row in place, and publishes the résumé. A second tap is answered with the
     * first request, not a second tailoring run.
     */
    @Test
    @DisplayName("a fit below AUTO_TAILOR_MIN_SCORE is offered, and a Tailor tap tailors it into the same track")
    fun offeredJobIsTailoredOnTap() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Offer $nonce"
        val offered = harness.runScenario(
            company = company,
            responses = mapOf("score_fit" to listOf(ok(harness.fixture("llm/score_fit_offer.json")))),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company)),
                idempotencyKey = "e2e-offer-$nonce",
            )
        }

        assertEquals("OFFER", offered.finalStatus.path("pipeline_action").asText(), "status: ${offered.finalStatus}")
        assertEquals(60, offered.finalStatus.path("fit_score").asInt())
        assertEquals(listOf("score_fit"), offered.llmCalls, "an offer is scored, never tailored up front")
        assertEquals("offer", offered.track.pipelineAction)
        assertNotNull(offered.artifactUrl, "the offer card links its scoring notes")
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${offered.jobId}/resume.pdf"))
        val offerTrack = offered.finalStatus.path("track_id").asInt()
        assertTrue(offerTrack > 0, "the offer wrote a tracks row: ${offered.finalStatus}")
        // The card: says it is not tailored yet, links the notes, and carries the Tailor button.
        val offerSeq = offered.completedEvent.path("completed_seq").asLong()
        assertTrue(offered.telegramMessages.single().contains("Not tailored yet"), offered.telegramMessages.single())
        assertEquals(
            listOf("cb:tailor:$offerSeq"),
            offered.telegramButtons.map { it.second }.filter { it.startsWith("cb:") },
            "an offer's only action is Tailor: ${offered.telegramButtons}",
        )

        val tapUrl = "${E2eConfig.bridgeUrl}/api/jobs/${offered.jobId}/tailor"
        val tailored = harness.runScenario(company = company) { postJson(tapUrl, "") }

        assertEquals("TAILOR", tailored.finalStatus.path("pipeline_action").asText(), "status: ${tailored.finalStatus}")
        assertEquals("score_fit", tailored.llmCalls.first(), "re-scored for the tailoring inputs: ${tailored.llmCalls}")
        assertTrue("summary_rewrite" in tailored.llmCalls && "cover_letter" in tailored.llmCalls, "tailored: ${tailored.llmCalls}")
        assertEquals(200, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${tailored.jobId}/resume.pdf"))
        assertEquals(offerTrack, tailored.finalStatus.path("track_id").asInt(), "the tap reuses the offer's tracks row")
        assertEquals("tailor", tailored.track.pipelineAction)
        assertEquals(1, harness.countTracks(company), "one job, one tracks row")

        val (code, body) = harness.postForResponse(tapUrl, "")
        assertEquals(200, code, body)
        assertTrue(body.contains("\"deduped\":true") && body.contains(tailored.jobId), "a second tap is the first request: $body")
    }

    /**
     * Cloud quota exhausted → the same score_fit call is answered by the local fallback model.
     * The e2e processor scores through a pretend cloud model (`e2e-cloud-score:cloud`, routed to
     * this fake by scripts/e2e-ci-prepare.sh) with `LOCAL_FALLBACK_MODEL=e2e-local-fallback`.
     * The quota refusal is not retried against the cloud (that would burn the ~3.5 min bridge
     * retry budget for nothing) and the job finishes normally instead of erroring.
     */
    @Test
    @DisplayName("a cloud quota refusal on score_fit is answered by the local fallback model")
    fun cloudQuotaRefusalFallsBackToLocalModel() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Quota $nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf(
                "score_fit" to listOf(
                    failure(429, """{"error":"you have reached your weekly usage limit, upgrade your plan"}"""),
                    ok(harness.fixture("llm/score_fit_skip.json")),
                ),
            ),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Junior Manual QA Analyst",
                jdText = """
                    $company needs a junior manual QA analyst to execute scripted browser checks,
                    record results in spreadsheets, and report visual defects. This role does not
                    include automation, mobile engineering, CI/CD, backend APIs, cloud platforms,
                    observability, performance testing, or test-infrastructure ownership.
                """.trimIndent(),
                idempotencyKey = "e2e-quota-$nonce",
            )
        }

        assertEquals("done", result.finalStatus.path("status").asText(), "status: ${result.finalStatus}")
        assertEquals(42, result.finalStatus.path("fit_score").asInt(), "scored from the fallback's answer")
        assertEquals("SKIP", result.finalStatus.path("pipeline_action").asText())
        assertTrue(result.finalStatus.path("error").asText("").isBlank(), "no error: ${result.finalStatus}")
        assertEquals(listOf("score_fit", "score_fit"), result.llmCalls, "one cloud refusal, one local answer, no cloud retry")
        assertEquals(listOf("e2e-cloud-score", "e2e-local-fallback"), result.llmModels)
        assertEquals("skip", result.track.pipelineAction)
    }

    /**
     * Deferred tailoring. The score call meets a quota refusal, so the cloud quota counts as out:
     * the 72 (above AUTO_TAILOR_MIN_SCORE) is DEFERRED — scored by the local fallback, noted,
     * tracked and pinged, but no tailoring call is spent. The next job's score reaches the cloud,
     * which shows the quota is back, and the processor's release sweep queues the deferred job:
     * it is tailored into the same tracks row.
     */
    @Test
    @DisplayName("while the cloud quota is out a fit is deferred, then released and tailored once it is back")
    fun deferredWhileQuotaOutThenReleased() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Deferred $nonce"
        val deferred = harness.runScenario(
            company = company,
            responses = mapOf(
                "score_fit" to listOf(
                    failure(429, """{"error":"you have reached your weekly usage limit, upgrade your plan"}"""),
                    ok(harness.fixture("llm/score_fit.json")),
                ),
            ),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company)),
                idempotencyKey = "e2e-deferred-$nonce",
            )
        }

        assertEquals("DEFERRED", deferred.finalStatus.path("pipeline_action").asText(), "status: ${deferred.finalStatus}")
        assertEquals(72, deferred.finalStatus.path("fit_score").asInt())
        assertEquals(listOf("score_fit", "score_fit"), deferred.llmCalls, "cloud refusal, local score, no tailoring calls")
        assertEquals("deferred", deferred.track.pipelineAction)
        assertTrue(deferred.telegramMessages.single().contains("Tailoring deferred"), deferred.telegramMessages.single())
        assertTrue(deferred.telegramButtons.none { it.second.startsWith("cb:") }, "no action buttons: ${deferred.telegramButtons}")
        val trackId = deferred.finalStatus.path("track_id").asInt()

        // The quota is back: this job's score reaches the (fake) cloud.
        val healthy = "E2E Quota Back $nonce"
        harness.runScenario(company = healthy, responses = mapOf("score_fit" to listOf(ok(harness.fixture("llm/score_fit_skip.json"))))) {
            submitScrapedJob(
                company = healthy,
                roleTitle = "Junior Manual QA Analyst",
                jdText = "$healthy needs a junior manual QA analyst to run scripted browser checks and log visual defects in spreadsheets; " +
                    "no automation, mobile, CI/CD, backend, cloud, observability or performance work is part of the role.",
                idempotencyKey = "e2e-quota-back-$nonce",
            )
        }

        // The sweep released the deferred job; its tailor run completes as TAILOR on the same track.
        val tailored = harness.pollUntil(E2eConfig.timeoutSeconds, 2_000, "the released tailor run for '$company'") {
            harness.completedEventsSince(deferred.completedCursor).firstOrNull {
                it.path("company").asText() == company && it.path("pipeline_action").asText() == "TAILOR"
            }
        }
        assertEquals(trackId, tailored.path("track_id").asInt(), "tailored into the deferred job's tracks row")
        assertEquals(1, harness.countTracks(company))
        assertEquals(200, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${tailored.path("job_id").asText()}/resume.pdf"))
    }

    @Test
    @DisplayName("a failing score_fit call ends the job in error, publishes nothing, and does not wedge the loop")
    fun scoreFitFailureIsTerminalAndRecoverable() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Fail $nonce"
        val failed = harness.runScenario(
            company = company,
            // The retry policy intentionally defers 429/timeouts/5xx. Use a permanent client error
            // here to retain coverage for the terminal score-fit error contract.
            responses = mapOf("score_fit" to listOf(failure(400))),
            expectTerminal = "error",
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company)),
                idempotencyKey = "e2e-fail-$nonce",
            )
        }

        assertEquals("error", failed.finalStatus.path("status").asText())
        assertTrue(
            failed.finalStatus.path("error").asText().startsWith("score_fit:"),
            "the error must name the failing stage: ${failed.finalStatus.path("error").asText()}",
        )
        assertEquals(0, failed.finalStatus.path("fit_score").asInt())
        assertEquals(listOf("score_fit"), failed.llmCalls, "no node may run after the scoring failure")

        // Nothing published: no artifact endpoints, no artifact_url, no output directory.
        assertFalse(failed.finalStatus.has("artifacts"), "a failed run must not advertise artifact endpoints")
        assertNull(failed.artifactUrl, "a failed run must not publish artifact_url")
        assertNull(failed.outputDir, "a failed run must not create an output directory")
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${failed.jobId}/resume.pdf"))
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${failed.jobId}/cover_letter.txt"))

        // Exactly one completed event, and no second job appeared under this company.
        assertEquals(1, failed.completedEvents.size)
        assertEquals(1, failed.eventsForCompany().size, "a failed run must not fan out or re-enqueue")

        // Tracked, but not as a success — and not silently as a genuine low-fit skip.
        assertEquals("skip", failed.track.pipelineAction, "a failed run must not be tracked as a success")
        assertTrue(failed.track.artifactUrl.isNullOrBlank())
        assertTrue(failed.track.outputPath.isNullOrBlank())

        // Notified as an error, never as a high-fit win.
        assertTrue(
            failed.discordMessages.single().contains("error:"),
            "Discord must report the failure: ${failed.discordMessages.single()}",
        )
        assertTrue(failed.telegramMessages.isEmpty(), "a failed run must not emit a high-fit Telegram alert")

        // The loop survived: the very next healthy job completes normally.
        val healthyCompany = "E2E Recover $nonce"
        val healthy = harness.runScenario(healthyCompany) {
            submitScrapedJob(
                company = healthyCompany,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to healthyCompany)),
                idempotencyKey = "e2e-recover-$nonce",
            )
        }
        assertEquals("TAILOR", healthy.finalStatus.path("pipeline_action").asText())
        assertEquals(72, healthy.finalStatus.path("fit_score").asInt())
        assertTrue(!healthy.artifactUrl.isNullOrBlank(), NO_ARTIFACT_URL_HINT)
    }

    /**
     * Issue #56, scenario 2 — duplicate submission and late completion.
     *
     * Two different hazards with one shared requirement: a second *anything* must not produce a
     * second set of side effects. Bridge dedupe already covered the submission half. The
     * completion half did not: `recordResult` burned a fresh `completed_seq` and overwrote the
     * row whatever state it was in, so a worker retrying a lost response — or one displaced by
     * stale-claim requeue — pushed the job through the completed feed twice and the Notifier
     * delivered it twice.
     *
     * The displaced-worker half is unit-tested in the bridge (`StoreCompletionReliabilityTest`):
     * provoking a real requeue here would need a stale window shorter than a job takes to
     * process, which would make every other scenario flaky.
     */
    @Test
    @DisplayName("a duplicate submission and a late duplicate completion create no second side effect")
    fun duplicateSubmissionAndLateCompletionAreInert() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Dup $nonce"
        val role = "Staff Software Engineer in Test"
        val idempotencyKey = "e2e-dup-$nonce"
        val jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company))

        val result = harness.runScenario(company) {
            submitScrapedJob(company = company, roleTitle = role, jdText = jdText, idempotencyKey = idempotencyKey)
        }
        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertEquals(1, result.completedEvents.size)
        assertEquals(1, harness.countTracks(company), "the first run must produce exactly one tracks row")

        // 1. Re-submitting the identical payload is absorbed by the dedup window, not re-queued.
        val resubmitted = harness.submitScrapedJob(
            company = company, roleTitle = role, jdText = jdText, idempotencyKey = idempotencyKey,
        )
        assertTrue(resubmitted.path("deduped").asBoolean(false), "identical resubmission must dedupe: $resubmitted")
        assertEquals(result.jobId, resubmitted.path("job_id").asText(), "dedupe must return the original job")

        // 2. A late duplicate completion — a worker retrying a response it never saw land — is
        //    accepted as a no-op, not replayed. 200 rather than an error: the desired end state
        //    already holds, and the worker has nothing useful to do with a failure here.
        val (status, body) = harness.postForResponse(
            "${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/result",
            """{"pipeline_action":"SKIP","fit_score":1,"error":null}""",
        )
        assertEquals(200, status, "a duplicate completion must not be an error: $body")
        assertTrue(body.contains("already_recorded"), "expected the duplicate to be reported as ignored: $body")

        // 3. Nothing moved: the first result still stands and no new event, row, or ping appeared.
        Thread.sleep(E2eConfig.settleMs * 2)
        val after = harness.completedEventsSince(result.completedCursor)
        assertEquals(
            1, after.count { it.path("job_id").asText() == result.jobId },
            "a duplicate completion must not emit a second completed event",
        )
        assertEquals(1, after.count { it.path("company").asText() == company }, "no second job may appear for this company")

        val status2 = harness.getString("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}")
        assertTrue(status2.contains("\"fit_score\":72"), "the first result must win over the duplicate: $status2")
        assertTrue(status2.contains("TAILOR"), "the duplicate must not rewrite pipeline_action: $status2")

        assertEquals(1, harness.countTracks(company), "a duplicate must not write a second tracks row")
        assertEquals(1, harness.sink.discordTexts().count { it.contains(company) }, "one job, one Discord message")
        assertEquals(1, harness.sink.telegramTexts().count { it.contains(company) }, "one job, one Telegram message")
    }

    /**
     * Watchlist intake (PR #139): the watchlist service submits employer-feed postings as
     * `source: CAREER_SITE` with a `watchlist:<ats>:<board>:<id>` key. Two cross-service contracts
     * only a real stack proves: the Processor must deserialize the new source (an unknown
     * IngestionSource value fails the claim), and the Bridge must dedupe the daily re-listing of the
     * same open posting. The 60-day window itself is unit-tested in the bridge (`StoreDedupTest`).
     */
    @Test
    @DisplayName("a watchlist CAREER_SITE posting is processed end to end and its daily re-listing dedupes")
    fun careerSitePostingFromWatchlist() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Watchlist $nonce"
        val role = "Senior Quality Engineer"
        val idempotencyKey = "watchlist:greenhouse:e2e-board:$nonce"
        val jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company))

        val result = harness.runScenario(company) {
            submitScrapedJob(
                company = company, roleTitle = role, jdText = jdText, idempotencyKey = idempotencyKey,
                location = "Seattle, WA", source = "CAREER_SITE",
            )
        }
        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText(), "CAREER_SITE must reach scoring: ${result.finalStatus}")
        assertEquals(1, result.completedEvents.size)
        assertEquals(1, harness.countTracks(company))

        // The next day's sweep lists the same posting again: absorbed, not re-scored.
        val relisted = harness.submitScrapedJob(
            company = company, roleTitle = role, jdText = jdText, idempotencyKey = idempotencyKey,
            location = "Seattle, WA", source = "CAREER_SITE",
        )
        assertTrue(relisted.path("deduped").asBoolean(false), "re-listed posting must dedupe: $relisted")
        assertEquals(result.jobId, relisted.path("job_id").asText(), "dedupe must return the original job")

        Thread.sleep(E2eConfig.settleMs * 2)
        assertEquals(
            1, harness.completedEventsSince(result.completedCursor).count { it.path("company").asText() == company },
            "a re-listed posting must not produce a second completed event",
        )
        assertEquals(1, harness.countTracks(company), "a re-listed posting must not write a second tracks row")
    }

    /**
     * URL intake (POST /api/urls): a job-alert email rule or an HTML careers board knows only the
     * posting's link. The processor must scrape it (real ScrapeJdNode, plain HTTP against a page the
     * harness serves), route the text through scrape_jd once, and process it like any other job; a
     * re-submitted link dedupes.
     */
    @Test
    @DisplayName("a posting URL is scraped over HTTP and processed end to end; the same link dedupes")
    fun postingUrlIsScrapedAndProcessed() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Url $nonce"
        val role = "Senior Quality Engineer"
        val url = "http://host.docker.internal:${E2eConfig.fakeLlmPort}/e2e-jobs/$nonce"
        val key = "e2e-url-$nonce"

        val result = harness.runScenario(
            company = company,
            responses = mapOf("scrape_jd" to listOf(ok(harness.fixture("llm/scrape_jd.json", mapOf("COMPANY" to company, "ROLE" to role))))),
        ) {
            submitUrl(url = url, idempotencyKey = key, source = "EMAIL", company = company, roleTitle = role)
        }

        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText(), "URL job must reach scoring: ${result.finalStatus}")
        assertEquals(url, result.track.jobUrl)
        assertEquals("scrape_jd", result.llmCalls.first(), "the link is scraped before anything else: ${result.llmCalls}")
        assertEquals(1, result.llmCalls.count { it == "scrape_jd" })
        assertEquals(1, result.completedEvents.size)

        val again = harness.submitUrl(url = url, idempotencyKey = key)
        assertTrue(again.path("deduped").asBoolean(false), "re-submitted link must dedupe: $again")
        assertEquals(result.jobId, again.path("job_id").asText())
    }

    /**
     * Employer job-alert email (a SuccessFactors-style jobs2web alert). The processor parses the
     * posting links itself — no LLM read of the email — and hands each to /api/urls under the
     * posting's own key, so the bridge scrapes and scores it as a career-site job. A later alert for
     * the same posting (new message, new per-email `eid`) is dropped by the bridge before any scrape.
     */
    @Test
    @DisplayName("an employer job-alert email is parsed into its postings; a repeat alert is deduped before scraping")
    fun employerAlertEmailBecomesCareerSiteJobs() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Alert $nonce"
        val role = "Staff Software Engineer in Test"
        val posting = "http://host.docker.internal:${E2eConfig.fakeLlmPort}/job/E2E-SDET/$nonce"
        fun alertBody(eid: String) = """
            New jobs posted from careers.e2e.example
            $role - Seattle, WA $posting?from=email&eid=$eid
            Unsubscribe http://host.docker.internal:${E2eConfig.fakeLlmPort}/unsubscribe/?t=$eid
        """.trimIndent()
        val from = "E2E Careers <alerts@noreply9.jobs2web.com>"

        val result = harness.runScenario(
            company = company,
            responses = mapOf("scrape_jd" to listOf(ok(harness.fixture("llm/scrape_jd.json", mapOf("COMPANY" to company, "ROLE" to role))))),
        ) {
            submitEmail("e2e-alert-$nonce-1", "New jobs posted from careers.e2e.example", alertBody("$nonce-1"), from, "e2e-alert-$nonce-1", isRecruiterHint = false)
        }

        // The alert itself is a processed digest; its posting ran as its own job.
        assertEquals("JD_Processed_Digest", result.completedEvents.single().path("terminal_label").asText(), "alert: ${result.completedEvents}")
        assertTrue("scan_email" !in result.llmCalls, "the alert is parsed, not read by the LLM: ${result.llmCalls}")
        assertEquals(1, result.llmCalls.count { it == "scrape_jd" }, "the posting is scraped once: ${result.llmCalls}")
        assertEquals(posting, result.track.jobUrl, "fetched at its clean URL (tracking params dropped)")
        val child = result.eventsForCompany().single()
        assertEquals("TAILOR", child.path("pipeline_action").asText(), "the posting reaches scoring: $child")

        // The same posting in tomorrow's alert: a new email and a new eid, one job.
        val cursor = mapperOf().readTree(harness.getString("${E2eConfig.bridgeUrl}/api/jobs/completed/head")).path("max_seq").asLong(0)
        val callsBefore = harness.fakeLlm.calls.size
        val again = harness.submitEmail("e2e-alert-$nonce-2", "New jobs posted from careers.e2e.example", alertBody("$nonce-2"), from, "e2e-alert-$nonce-2", isRecruiterHint = false)
        val parentId = again.path("job_id").asText()
        harness.pollUntil(E2eConfig.timeoutSeconds, 1_000, "repeat alert $parentId to complete") {
            mapperOf().readTree(harness.getString("${E2eConfig.bridgeUrl}/api/jobs/$parentId")).takeIf { it.path("status").asText() == "done" }
        }
        Thread.sleep(E2eConfig.settleMs)
        assertEquals(0, harness.fakeLlm.calls.drop(callsBefore).count { it == "scrape_jd" }, "no second scrape: ${harness.fakeLlm.calls.drop(callsBefore)}")
        assertTrue(harness.completedEventsSince(cursor).none { it.path("company").asText() == company }, "no second job for the posting")
        assertEquals(1, harness.countTracks(company))
    }

    private fun mapperOf() = com.fasterxml.jackson.databind.ObjectMapper()

    /**
     * Issue #56, scenario 8 — Notifier delivery acknowledgement and cursor recovery.
     *
     * The old failure mode was invisible from both ends: `NotificationClient` logged a non-2xx and
     * returned normally, so `notify()` reported success on a 500 and `drainOnce` persisted the
     * cursor past the event. The notification was dropped permanently, and nothing anywhere said so.
     *
     * With delivery results plumbed through, a refused post holds the cursor and the next pass
     * redelivers. The partial-success policy matters here: Telegram lands on the first attempt
     * while Discord is refused, so the retry must re-send Discord *only*.
     */
    @Test
    @DisplayName("a refused Discord post is retried, delivered once, and does not duplicate Telegram")
    fun refusedDeliveryIsRetriedWithoutDuplicating() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Retry $nonce"
        val result = harness.runScenario(
            company = company,
            // One refusal, then the channel recovers. If the cursor advanced over the failure the
            // message would never arrive at all and this scenario would time out waiting for it.
            sinkResponses = mapOf("discord" to listOf(sinkFailure(500))),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company)),
                idempotencyKey = "e2e-retry-$nonce",
            )
        }

        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())

        // Two attempts, one delivery: the refusal was retried rather than dropped.
        val discordAttempts = harness.sink.attempts("discord").filter { it.body.path("content").asText().contains(company) }
        assertEquals(2, discordAttempts.size, "expected one refused attempt and one redelivery")
        assertEquals(listOf(500, 200), discordAttempts.map { it.status })
        assertEquals(1, result.discordMessages.size, "the user must see exactly one Discord message")
        assertTrue(result.discordMessages.single().contains("(TAILOR)"))

        // Telegram landed on the first attempt and must not be re-sent by the Discord retry.
        val telegramCompany = company.replace("&", "&amp;")
        val telegramAttempts = harness.sink.attempts("telegram").filter { it.body.path("text").asText().contains(telegramCompany) }
        assertEquals(1, telegramAttempts.size, "a channel that already landed must not be re-sent")
        assertEquals(1, result.telegramMessages.size)

        // Exactly one completed event, and the retry did not replay it into the feed.
        assertEquals(1, result.completedEvents.size)

        // The cursor recovered: the next job notifies normally rather than being stuck behind it.
        val nextCompany = "E2E After Retry $nonce"
        val next = harness.runScenario(nextCompany) {
            submitScrapedJob(
                company = nextCompany,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to nextCompany)),
                idempotencyKey = "e2e-after-retry-$nonce",
            )
        }
        assertEquals(1, next.discordMessages.size, "the cursor must not be parked after recovery")
        assertTrue(next.discordMessages.single().contains("(TAILOR)"))
    }
}
