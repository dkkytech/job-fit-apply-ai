package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers POST /api/tailoring/release-deferred — the processor's sweep for jobs DEFERRED while the
 * cloud quota was out: expire the stale ones, queue the best fits, a few in flight at a time.
 */
class ReleaseDeferredApiTest {

    private val json = Json { ignoreUnknownKeys = true }
    private var submitted = 0

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private suspend fun obj(res: HttpResponse) = json.parseToJsonElement(res.bodyAsText()).jsonObject

    /** Submit, claim and complete a job as [action] with [fit]; DEFERRED/OFFER carry a record. */
    private suspend fun completed(client: HttpClient, action: String, fit: Int, company: String = "Co$fit"): String {
        val n = ++submitted
        val jobId = obj(client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("jd_text", sampleJdText()); put("company", company); put("role_title", "SDET $n")
                put("job_url", "https://jobs.example.com/qa/$n")
            }.toString())
        })["job_id"]!!.jsonPrimitive.content
        val claim = obj(client.get("/api/queue/claim"))
        assertEquals(jobId, claim["job_id"]!!.jsonPrimitive.content)
        val result = buildJsonObject {
            put("pipeline_action", action); put("fit_score", fit); put("company", company); put("role_title", "SDET $n")
            put("artifact_url", "https://markserv.example/$n/"); put("track_id", n)
            if (action == "DEFERRED" || action == "OFFER") put("tailor_record", buildJsonObject { put("jd_text", "JD $n"); put("company", company) })
            put("claim_token", claim["claim_token"]!!.jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.OK, client.post("/api/jobs/$jobId/result") { contentType(ContentType.Application.Json); setBody(result.toString()) }.status)
        return jobId
    }

    private suspend fun sweep(client: HttpClient, limit: Int = 2, maxAgeDays: Int = 5) = obj(client.post("/api/tailoring/release-deferred") {
        contentType(ContentType.Application.Json)
        setBody("""{"limit":$limit,"max_age_days":$maxAgeDays}""")
    })

    private fun ids(arr: JsonElement?) = arr!!.jsonArray.map { it.jsonObject["job_id"]!!.jsonPrimitive.content }

    /** Finish the TAILOR_REQUEST at the head of the queue. */
    private suspend fun finishOneTailorRun(client: HttpClient) {
        val claim = obj(client.get("/api/queue/claim"))
        assertEquals(WorkItemType.TAILOR_REQUEST, claim["type"]!!.jsonPrimitive.content)
        client.post("/api/jobs/${claim["job_id"]!!.jsonPrimitive.content}/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"TAILOR","fit_score":80,"claim_token":"${claim["claim_token"]!!.jsonPrimitive.content}"}""")
        }
    }

    @Test
    fun `best fits are released first, a batch in flight at a time`() = testApplication {
        application { configureApplication() }
        val d70 = completed(client, "DEFERRED", 70)
        val d90 = completed(client, "DEFERRED", 90)
        val d80 = completed(client, "DEFERRED", 80)
        completed(client, "OFFER", 72)      // an offer waits for its button, never the sweep
        completed(client, "TAILOR", 95)

        val first = sweep(client)
        assertEquals(listOf(d90, d80), ids(first["released"]), "best fit first")
        assertEquals(1, first["remaining"]!!.jsonPrimitive.int)
        first["released"]!!.jsonArray.forEach { assertTrue(it.jsonObject["tailor_job_id"]!!.jsonPrimitive.content.isNotBlank()) }

        assertEquals(emptyList(), ids(sweep(client)["released"]), "two already in flight: nothing more")

        finishOneTailorRun(client)
        val third = sweep(client)
        assertEquals(listOf(d70), ids(third["released"]), "a slot opened")
        assertEquals(0, third["remaining"]!!.jsonPrimitive.int)
        assertEquals(emptyList(), ids(sweep(client, limit = 5)["released"]), "nothing is released twice; the offer stays put")
    }

    @Test
    fun `limit 0 only expires, and stale deferred jobs expire instead of being released`() = testApplication {
        application { configureApplication() }
        val stale = completed(client, "DEFERRED", 92)
        val fresh = completed(client, "DEFERRED", 77)
        transaction { Jobs.update({ Jobs.id eq stale }) { it[Jobs.updatedAt] = System.currentTimeMillis() / 1000L - 6 * 86_400L } }

        val outage = sweep(client, limit = 0)
        assertEquals(listOf(stale), ids(outage["expired"]))
        assertEquals("https://markserv.example/1/", outage["expired"]!!.jsonArray.single().jsonObject["artifact_url"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), ids(outage["released"]), "the quota is still out")
        assertEquals(1, outage["remaining"]!!.jsonPrimitive.int)

        val back = sweep(client)
        assertEquals(listOf(fresh), ids(back["released"]), "the expired one is never released")
        assertEquals(emptyList(), ids(back["expired"]), "and never reported twice")
    }

    @Test
    fun `a failed enqueue leaves the job deferred, so the next sweep still finds it`() = testApplication {
        application { configureApplication() }
        val d = completed(client, "DEFERRED", 88)
        val row = getJob(d)!!
        val record = Json.parseToJsonElement(row.tailorRecord!!)

        kotlin.test.assertFailsWith<IllegalStateException> {
            queueTailor(row, record) { _, _, _, _ -> error("disk full") }
        }
        assertEquals(TailorState.DEFERRED, getJob(d)!!.tailorState, "not stranded as released")

        assertEquals(listOf(d), ids(sweep(client)["released"]))
        assertEquals(TailorState.RELEASED, getJob(d)!!.tailorState)
    }

    @Test
    fun `a deferred job already tapped is not queued a second time`() = testApplication {
        application { configureApplication() }
        val d = completed(client, "DEFERRED", 85)
        val tapped = obj(client.post("/api/jobs/$d/tailor"))["job_id"]!!.jsonPrimitive.content
        val swept = sweep(client)
        assertEquals(emptyList(), ids(swept["released"]))
        assertEquals(0, swept["remaining"]!!.jsonPrimitive.int)
        assertEquals(tapped, obj(client.get("/api/queue/claim"))["job_id"]!!.jsonPrimitive.content)
        assertEquals(204, client.get("/api/queue/claim").status.value, "one TAILOR_REQUEST, not two")
    }
}
