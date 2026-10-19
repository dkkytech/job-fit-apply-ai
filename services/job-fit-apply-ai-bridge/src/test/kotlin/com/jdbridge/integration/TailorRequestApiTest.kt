package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers POST /api/jobs/{id}/tailor — the Tailor tap on an OFFERed job. The OFFER's result hands
 * back the processed record; the tap re-queues it as a TAILOR_REQUEST, once per job.
 */
class TailorRequestApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private val record = """{"jd_text":"Senior SDET owning Playwright and Appium suites","company":"Acme","role_title":"Senior SDET","location":"Seattle, WA","job_url":"https://jobs.example.com/qa/9","source":"EMAIL","salary_range":"$150k-$180k","intake_meta":null}"""

    private var submitted = 0

    private suspend fun obj(res: HttpResponse) = json.parseToJsonElement(res.bodyAsText()).jsonObject

    /** Submit a job, claim it and complete it with [result] (a ResultRequest JSON object body). */
    private suspend fun completeWith(client: HttpClient, result: String): String {
        // A fresh URL each time: the same link would dedup onto the earlier job.
        val jobId = obj(submitJob(client, "Acme", "https://jobs.example.com/qa/${++submitted}"))["job_id"]!!.jsonPrimitive.content
        val claim = obj(client.get("/api/queue/claim"))
        assertEquals(jobId, claim["job_id"]!!.jsonPrimitive.content)
        val token = claim["claim_token"]!!.jsonPrimitive.content
        val posted = client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody(result.removeSuffix("}") + ""","claim_token":"$token"}""")
        }
        assertEquals(HttpStatusCode.OK, posted.status, posted.bodyAsText())
        return jobId
    }

    private suspend fun submitJob(client: HttpClient, company: String, url: String) = client.post("/api/jobs") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("jd_text", sampleJdText()); put("company", company); put("role_title", "Senior SDET")
            put("job_url", url); put("source", "EMAIL")
        }.toString())
    }

    private suspend fun tap(client: HttpClient, jobId: String) = client.post("/api/jobs/$jobId/tailor")

    private val offer = """{"pipeline_action":"OFFER","fit_score":68,"track_id":7,"source":"jobright.ai","company":"Acme","role_title":"Senior SDET","tailor_record":$record}"""

    @Test
    fun `a tap on an offer queues a TAILOR_REQUEST carrying the record, track and board`() = testApplication {
        application { configureApplication() }
        val offered = completeWith(client, offer)

        val res = tap(client, offered)
        assertEquals(HttpStatusCode.Accepted, res.status, res.bodyAsText())
        val tailorJob = obj(res)["job_id"]!!.jsonPrimitive.content
        assertNotEquals(offered, tailorJob)

        val claim = obj(client.get("/api/queue/claim"))
        assertEquals(tailorJob, claim["job_id"]!!.jsonPrimitive.content)
        assertEquals(WorkItemType.TAILOR_REQUEST, claim["type"]!!.jsonPrimitive.content)
        val payload = claim["jd_record"]!!.jsonObject
        assertEquals(json.parseToJsonElement(record), payload["record"], "the record round-trips verbatim")
        assertEquals(offered, payload["parent_job_id"]!!.jsonPrimitive.content)
        assertEquals(7, payload["track_id"]!!.jsonPrimitive.int)
        assertEquals("jobright.ai", payload["job_board"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a second tap answers with the first request, until that one errors`() = testApplication {
        application { configureApplication() }
        val offered = completeWith(client, offer)
        val first = obj(tap(client, offered))["job_id"]!!.jsonPrimitive.content

        val again = tap(client, offered)
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(first, obj(again)["job_id"]!!.jsonPrimitive.content)
        assertTrue(obj(again)["deduped"]!!.jsonPrimitive.boolean)

        // The tailor run fails terminally → the next tap is a fresh request, not the dead one.
        val token = obj(client.get("/api/queue/claim"))["claim_token"]!!.jsonPrimitive.content
        client.post("/api/jobs/$first/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"SKIP","fit_score":0,"error":"tailor exploded","claim_token":"$token"}""")
        }
        val retry = tap(client, offered)
        assertEquals(HttpStatusCode.Accepted, retry.status)
        assertNotEquals(first, obj(retry)["job_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only a finished offer can be tailored`() = testApplication {
        application { configureApplication() }
        assertEquals(HttpStatusCode.NotFound, tap(client, "no-such-job").status)

        val skipped = completeWith(client, """{"pipeline_action":"SKIP","fit_score":30}""")
        assertEquals(HttpStatusCode.Conflict, tap(client, skipped).status, "a skip has no record to tailor")

        val tailored = completeWith(client, """{"pipeline_action":"TAILOR","fit_score":88}""")
        assertEquals(HttpStatusCode.Conflict, tap(client, tailored).status, "already tailored")

        // A result that errored is not an offer, even if it carried a record.
        val errored = completeWith(client, offer.removeSuffix("}") + ""","error":"notes failed"}""")
        assertEquals(HttpStatusCode.Conflict, tap(client, errored).status, "only a DONE offer can be tailored")

        // Last: it stays queued, so a later completeWith would claim it instead.
        val pending = obj(submitJob(client, "Pending Co", "https://jobs.example.com/qa/pending"))["job_id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Conflict, tap(client, pending).status, "not finished yet")
    }
}
