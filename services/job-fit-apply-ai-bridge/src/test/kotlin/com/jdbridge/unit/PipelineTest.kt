package com.jdbridge.unit

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Tests for the queue-backed worker protocol:
 *   POST /api/jobs → GET /api/queue/claim → POST /api/jobs/{id}/result → GET /api/jobs/{id}
 */
class QueueClaimTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `claim returns 204 when queue is empty`() = testApplication {
        application { configureApplication() }
        val response = client.get("/api/queue/claim")
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `claim returns 200 with jd_record after submit`() = testApplication {
        application { configureApplication() }
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${minimalJdText()}","role_title":"SDET"}""")
        }
        assertEquals(HttpStatusCode.Accepted, submitResp.status)

        val claimResp = client.get("/api/queue/claim")
        assertEquals(HttpStatusCode.OK, claimResp.status)
        val body = Json.parseToJsonElement(claimResp.bodyAsText()).jsonObject
        assertNotNull(body["job_id"])
        assertNotNull(body["jd_record"])
        assertTrue(body["jd_record"]!!.jsonObject.containsKey("jd_text"))
    }

    @Test
    fun `claimed job shows status claimed`() = testApplication {
        application { configureApplication() }
        client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${minimalJdText()}"}""")
        }
        val claimed = client.get("/api/queue/claim")
        val jobId = Json.parseToJsonElement(claimed.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content

        val status = client.get("/api/jobs/$jobId")
        val body = Json.parseToJsonElement(status.bodyAsText()).jsonObject
        assertEquals("claimed", body["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `second claim returns 204 when only one job was enqueued`() = testApplication {
        application { configureApplication() }
        client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${minimalJdText()}"}""")
        }
        client.get("/api/queue/claim")  // first claim
        val second = client.get("/api/queue/claim")
        assertEquals(HttpStatusCode.NoContent, second.status)
    }
}

class ResultPostTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `POST result transitions job to done`() = testApplication {
        application { configureApplication() }
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${minimalJdText()}"}""")
        }
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        client.get("/api/queue/claim")

        val resultResp = client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"TAILOR","fit_score":85,"strengths":[],"is_duplicate":false,"has_cover_letter":false}""")
        }
        assertEquals(HttpStatusCode.OK, resultResp.status)

        val status = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals("done", status["status"]!!.jsonPrimitive.content)
        assertEquals(85, status["fit_score"]!!.jsonPrimitive.int)
    }

    @Test
    fun `POST result with error transitions job to error`() = testApplication {
        application { configureApplication() }
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${minimalJdText()}"}""")
        }
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        client.get("/api/queue/claim")

        client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"SKIP","fit_score":30,"error":"Score too low"}""")
        }

        val status = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals("error", status["status"]!!.jsonPrimitive.content)
    }
}

class DedupTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `submitting same job_url twice returns same job_id with deduped=true`() = testApplication {
        application { configureApplication() }
        val body = """{"jd_text":"${minimalJdText()}","job_url":"https://example.com/job/1"}"""
        val first  = client.post("/api/jobs") { contentType(ContentType.Application.Json); setBody(body) }
        val second = client.post("/api/jobs") { contentType(ContentType.Application.Json); setBody(body) }

        val firstId  = Json.parseToJsonElement(first.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        val secondBody = Json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(firstId, secondBody["job_id"]!!.jsonPrimitive.content)
        assertTrue(secondBody["deduped"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `submitting same idempotency_key twice returns same job_id`() = testApplication {
        application { configureApplication() }
        val body = """{"jd_text":"${minimalJdText()}","idempotency_key":"email-abc-123"}"""
        val first  = client.post("/api/jobs") { contentType(ContentType.Application.Json); setBody(body) }
        val second = client.post("/api/jobs") { contentType(ContentType.Application.Json); setBody(body) }

        val firstId  = Json.parseToJsonElement(first.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        val secondBody = Json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(firstId, secondBody["job_id"]!!.jsonPrimitive.content)
    }
}
