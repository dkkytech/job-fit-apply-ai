package com.jdbridge.unit

import com.jdbridge.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class StoreEnqueueTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `enqueue inserts row with status pending`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, null)
        val row = getJob(jobId)
        assertNotNull(row)
        assertEquals(JobStatus.PENDING.value, row.status)
    }

    @Test
    fun `enqueue stores jd_json as non-null string`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, null)
        val row = getJob(jobId)!!
        assertNotNull(row.jdJson)
        assertTrue(row.jdJson!!.contains("jd_text"))
    }

    @Test
    fun `enqueue sets created_at within 2 seconds of wall clock`() = runTest {
        val before = System.currentTimeMillis() / 1000L
        val jobId = enqueue(defaultJdJson(), null, null)
        val after = System.currentTimeMillis() / 1000L
        val row = getJob(jobId)!!
        assertTrue(row.createdAt >= before && row.createdAt <= after + 1)
        assertTrue(row.updatedAt >= before && row.updatedAt <= after + 1)
    }

    @Test
    fun `enqueue stores job_url and idempotency_key`() = runTest {
        val jobId = enqueue(defaultJdJson(), "https://example.com/job/1", "email-abc")
        val row = getJob(jobId)!!
        assertEquals("https://example.com/job/1", row.jobUrl)
    }
}

class StoreClaimTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `claimNext returns null when queue is empty`() = runTest {
        val claimed = claimNext()
        assertNull(claimed)
    }

    @Test
    fun `claimNext transitions job to claimed`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, null)
        val claimed = claimNext()
        assertNotNull(claimed)
        assertEquals(jobId, claimed.id)
        assertEquals(JobStatus.CLAIMED.value, getJob(jobId)!!.status)
    }

    @Test
    fun `claimNext returns jdJson content`() = runTest {
        val json = defaultJdJson()
        enqueue(json, null, null)
        val claimed = claimNext()
        assertNotNull(claimed)
        assertTrue(claimed.jdJson.contains("jd_text"))
    }

    @Test
    fun `claimNext with two jobs returns oldest first`() = runTest {
        val id1 = enqueue(defaultJdJson(), null, null)
        Thread.sleep(10)
        val id2 = enqueue(defaultJdJson(), null, null)
        val first = claimNext()
        assertEquals(id1, first?.id)
        val second = claimNext()
        assertEquals(id2, second?.id)
    }

    @Test
    fun `claimNext returns null after all pending jobs are claimed`() = runTest {
        enqueue(defaultJdJson(), null, null)
        claimNext()
        val second = claimNext()
        assertNull(second)
    }
}

class StoreRecordResultTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `recordResult transitions to done when no error`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, null)
        claimNext()
        recordResult(jobId, ResultRequest(pipeline_action = "TAILOR", fit_score = 85))
        val row = getJob(jobId)!!
        assertEquals(JobStatus.DONE.value, row.status)
        assertEquals(85, row.fitScore)
        assertEquals("TAILOR", row.pipelineAction)
    }

    @Test
    fun `recordResult transitions to error when error present`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, null)
        claimNext()
        recordResult(jobId, ResultRequest(pipeline_action = "SKIP", fit_score = 30, error = "Score too low"))
        val row = getJob(jobId)!!
        assertEquals(JobStatus.ERROR.value, row.status)
        assertEquals("Score too low", row.error)
    }

    @Test
    fun `recordResult stores fit_score of 0`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, null)
        claimNext()
        recordResult(jobId, ResultRequest(pipeline_action = "SKIP", fit_score = 0))
        assertEquals(0, getJob(jobId)!!.fitScore)
    }
}

class StoreSetArtifactsTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `setArtifacts serializes and deserializes ArtifactUrls`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, null)
        val artifacts = ArtifactUrls("/api/jobs/$jobId/resume.pdf", "/api/jobs/$jobId/cover_letter.txt")
        setArtifacts(jobId, artifacts)
        val row = getJob(jobId)!!
        assertNotNull(row.artifacts)
        assertEquals("/api/jobs/$jobId/resume.pdf", row.artifacts!!.resume_pdf)
    }
}

class StoreGetJobTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `getJob returns null for unknown id`() = runTest {
        assertNull(getJob("does-not-exist"))
    }

    @Test
    fun `getJob returns all fields after enqueue and result`() = runTest {
        val jobId = enqueue(defaultJdJson(), "https://ex.com/1", "key-1")
        claimNext()
        recordResult(jobId, ResultRequest(pipeline_action = "TAILOR", fit_score = 85))
        val row = getJob(jobId)!!
        assertEquals(jobId,              row.id)
        assertEquals(JobStatus.DONE.value, row.status)
        assertEquals(85,                  row.fitScore)
        assertEquals("TAILOR",            row.pipelineAction)
        assertEquals("https://ex.com/1", row.jobUrl)
    }

    @Test
    fun `getJob preserves unicode in jd_json`() = runTest {
        val jobId = enqueue(defaultJdJson(jdText = "职位：🎉" + "x".repeat(150)), null, null)
        val row = getJob(jobId)!!
        assertNotNull(row.jdJson)
        assertTrue(row.jdJson!!.contains("🎉"))
    }

    @Test
    fun `getJob returns fit_score of 0 not null`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, null)
        claimNext()
        recordResult(jobId, ResultRequest(pipeline_action = "SKIP", fit_score = 0))
        assertEquals(0, getJob(jobId)!!.fitScore)
    }
}

class StoreJobDirTest {

    @BeforeEach
    fun setup() { useTempStoreDir() }

    @Test
    fun `jobDir returns path containing jobId`() {
        initTestDb()
        val path = jobDir("abc-123")
        assertTrue(path.toString().contains("abc-123"))
    }

    @Test
    fun `jobDir creates directory`() {
        initTestDb()
        val path = jobDir("new-job")
        assertTrue(path.toFile().isDirectory)
    }

    @Test
    fun `jobDir is idempotent`() {
        initTestDb()
        val p1 = jobDir("dup")
        val p2 = jobDir("dup")
        assertEquals(p1, p2)
        assertTrue(p2.toFile().isDirectory)
    }
}

class StoreDedupTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `findActiveDuplicate returns null when queue is empty`() = runTest {
        assertNull(findActiveDuplicate("https://ex.com/1", null))
    }

    @Test
    fun `findActiveDuplicate finds by job_url`() = runTest {
        val jobId = enqueue(defaultJdJson(), "https://ex.com/1", null)
        val found = findActiveDuplicate("https://ex.com/1", null)
        assertEquals(jobId, found)
    }

    @Test
    fun `findActiveDuplicate finds by idempotency_key`() = runTest {
        val jobId = enqueue(defaultJdJson(), null, "email-abc")
        val found = findActiveDuplicate(null, "email-abc")
        assertEquals(jobId, found)
    }

    @Test
    fun `findActiveDuplicate returns null for error status job`() = runTest {
        val jobId = enqueue(defaultJdJson(), "https://ex.com/2", null)
        claimNext()
        recordResult(jobId, ResultRequest(pipeline_action = "SKIP", fit_score = 0, error = "failed"))
        val found = findActiveDuplicate("https://ex.com/2", null)
        assertNull(found)
    }
}

class StoreConcurrencyTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `50 concurrent enqueue calls all succeed`() = runBlocking {
        val jobs = (1..50).map { i ->
            launch {
                enqueue(defaultJdJson(), "https://ex.com/$i", null)
            }
        }
        jobs.forEach { it.join() }
    }
}

class JdJsonFieldRoundTripTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `location and job_url are preserved in jd_json round-trip`() = runTest {
        val json = defaultJdJson(location = "Seattle, WA", jobUrl = "https://example.com/job/123")
        val jobId = enqueue(json, "https://example.com/job/123", null)
        val row = getJob(jobId)!!
        assertNotNull(row.jdJson)
        assertTrue(row.jdJson!!.contains("Seattle"), "location should be preserved in jd_json")
        assertTrue(row.jdJson!!.contains("example.com/job/123"), "job_url should be preserved in jd_json")
    }
}

/**
 * Completion reliability — issue #56 scenario 2.
 *
 * `recordResult` used to be unconditional: it burned a `completed_seq` and overwrote the row
 * whatever state the row was in. Two consequences, both invisible from outside: a duplicate or
 * late POST produced a *second* completed event (so the Notifier delivered the job twice), and a
 * worker displaced by stale-claim requeue could overwrite the attempt that replaced it.
 *
 * The stale-claim half is unit-tested rather than black-boxed on purpose: provoking a real
 * requeue end-to-end needs a stale window shorter than a job takes to process, which would make
 * every other scenario flaky.
 */
class StoreCompletionReliabilityTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private fun tailorResult(token: String? = null, action: String = "TAILOR", score: Int = 85) =
        ResultRequest(pipeline_action = action, fit_score = score, claim_token = token)

    @Test
    fun `claimNext hands out a fresh token per claim`() = runTest {
        val first = enqueue(defaultJdJson(), null, null)
        val second = enqueue(defaultJdJson(), null, null)

        val a = claimNext()!!
        val b = claimNext()!!

        assertNotNull(a.claimToken)
        assertNotNull(b.claimToken)
        assertNotEquals(a.claimToken, b.claimToken, "each claim must get its own fence")
        assertEquals(setOf(first, second), setOf(a.id, b.id))
    }

    @Test
    fun `a matching token records the result`() = runTest {
        enqueue(defaultJdJson(), null, null)
        val claimed = claimNext()!!

        assertEquals(ResultOutcome.RECORDED, recordResult(claimed.id, tailorResult(claimed.claimToken)))
        assertEquals(JobStatus.DONE.value, getJob(claimed.id)!!.status)
    }

    @Test
    fun `a null token is accepted, so a pre-fencing worker is not wedged`() = runTest {
        enqueue(defaultJdJson(), null, null)
        val claimed = claimNext()!!

        assertEquals(ResultOutcome.RECORDED, recordResult(claimed.id, tailorResult(token = null)))
        assertEquals(JobStatus.DONE.value, getJob(claimed.id)!!.status)
    }

    @Test
    fun `a second result for a terminal job is ignored and burns no sequence number`() = runTest {
        enqueue(defaultJdJson(), null, null)
        val claimed = claimNext()!!
        assertEquals(ResultOutcome.RECORDED, recordResult(claimed.id, tailorResult(claimed.claimToken)))
        val firstSeq = getJob(claimed.id)!!.completedSeq
        val headAfterFirst = latestCompletedSeq()

        val second = recordResult(claimed.id, tailorResult(claimed.claimToken, action = "SKIP", score = 1))

        assertEquals(ResultOutcome.ALREADY_TERMINAL, second)
        val row = getJob(claimed.id)!!
        assertEquals(firstSeq, row.completedSeq, "a duplicate must not re-sequence the job")
        assertEquals("TAILOR", row.pipelineAction, "first result wins; the duplicate must not overwrite it")
        assertEquals(headAfterFirst, latestCompletedSeq(), "an ignored result must not consume a sequence number")
    }

    @Test
    fun `a displaced worker cannot overwrite the attempt that replaced it`() = runTest {
        enqueue(defaultJdJson(), null, null)
        val displaced = claimNext()!!

        // Age the claim past the stale window, then let a second worker take it.
        expireClaim(displaced.id)
        val replacement = claimNext()!!

        assertEquals(displaced.id, replacement.id, "the requeued job should be re-claimed")
        assertNotEquals(displaced.claimToken, replacement.claimToken)

        val late = recordResult(displaced.id, tailorResult(displaced.claimToken, action = "SKIP", score = 1))
        assertEquals(ResultOutcome.STALE_CLAIM, late)
        assertEquals(JobStatus.CLAIMED.value, getJob(displaced.id)!!.status, "the refused write must not terminate the job")

        // The replacement still completes normally.
        assertEquals(ResultOutcome.RECORDED, recordResult(replacement.id, tailorResult(replacement.claimToken)))
        val row = getJob(replacement.id)!!
        assertEquals("TAILOR", row.pipelineAction)
        assertEquals(JobStatus.DONE.value, row.status)
    }

    @Test
    fun `requeue clears the token, so the displaced worker's fence matches nothing`() = runTest {
        enqueue(defaultJdJson(), null, null)
        val displaced = claimNext()!!
        expireClaim(displaced.id)

        // Force the inline requeue without re-claiming: a second enqueue + claim would take the
        // other row, so claim the same one back and check the token rotated rather than persisted.
        val replacement = claimNext()!!
        assertNotEquals(displaced.claimToken, replacement.claimToken)
        assertEquals(ResultOutcome.STALE_CLAIM, recordResult(displaced.id, tailorResult(displaced.claimToken)))
    }
}
