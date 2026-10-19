package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class JobStatusApiTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `GET unknown job_id returns 404 with detail`() = testApplication {
        application { configureApplication() }
        val response = client.get("/api/jobs/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body.containsKey("detail"))
    }

    @Test
    fun `GET pending job returns status pending and no artifacts`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking { enqueue(defaultJdJson(), null, null) }

        val response = client.get("/api/jobs/$jobId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("pending", body["status"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("artifacts"), "artifacts should be absent for pending job")
    }

    @Test
    fun `GET done job has fit_score and artifacts`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking { enqueueAndComplete(fitScore = 82, pipelineAction = "TAILOR") }

        val response = client.get("/api/jobs/$jobId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("done", body["status"]!!.jsonPrimitive.content)
        assertEquals(82, body["fit_score"]!!.jsonPrimitive.int)
        val artifacts = body["artifacts"]!!.jsonObject
        assertTrue(artifacts["resume_pdf"]!!.jsonPrimitive.content.contains(jobId))
    }

    @Test
    fun `GET error job has error field and no artifacts`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking { enqueueAndComplete(error = "Score too low", fitScore = 30, pipelineAction = "SKIP") }

        val response = client.get("/api/jobs/$jobId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("error", body["status"]!!.jsonPrimitive.content)
        assertNotNull(body["error"])
        assertTrue(body["error"]!!.jsonPrimitive.content.isNotEmpty())
        assertFalse(body.containsKey("artifacts"), "artifacts should be absent for error job")
    }

    @Test
    fun `artifacts field is absent when job is claimed`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking {
            val id = enqueue(defaultJdJson(), null, null)
            claimNext()
            id
        }

        val response = client.get("/api/jobs/$jobId")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("claimed", body["status"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("artifacts"), "artifacts should be absent for claimed job")
    }

    @Test
    fun `error field is absent on done job`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking { enqueueAndComplete() }

        val response = client.get("/api/jobs/$jobId")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("done", body["status"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("error"), "error should be absent on done job")
    }

    @Test
    fun `track_id, title and company from the result reach job status and the completed feed`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking { enqueue(defaultJdJson(), null, null).also { claimNext() } }

        val post = client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"TAILOR","fit_score":81,"company":"Acme Corp",
                       "role_title":"Staff SDET","track_id":42}""")
        }
        assertEquals(HttpStatusCode.OK, post.status)

        val status = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals(42, status["track_id"]!!.jsonPrimitive.int)
        assertEquals("Staff SDET", status["title"]!!.jsonPrimitive.content)
        assertEquals("Acme Corp", status["company"]!!.jsonPrimitive.content)

        val feed = Json.parseToJsonElement(client.get("/api/jobs/completed?all=true").bodyAsText()).jsonArray
        val event = feed.map { it.jsonObject }.single { it["job_id"]!!.jsonPrimitive.content == jobId }
        assertEquals(42, event["track_id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `track_id, title and company are absent before a result lands`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking { enqueue(defaultJdJson(), null, null) }

        val body = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertFalse(body.containsKey("track_id"))
        assertFalse(body.containsKey("title"))
        assertFalse(body.containsKey("company"))
    }

    @Test
    fun `GET job with fit_score 0 returns 0 not null`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking { enqueueAndComplete(fitScore = 0, pipelineAction = "SKIP", includeCoverLetter = false) }

        val response = client.get("/api/jobs/$jobId")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0, body["fit_score"]!!.jsonPrimitive.int)
    }
}
