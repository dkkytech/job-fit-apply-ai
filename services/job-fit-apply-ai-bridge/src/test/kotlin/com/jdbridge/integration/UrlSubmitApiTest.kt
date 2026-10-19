package com.jdbridge.integration

import com.jdbridge.*
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers POST /api/urls — a posting known only by its link (JD_URL). The Processor scrapes it, so
 * the bridge only validates, dedups (per-source window) and enqueues.
 */
class UrlSubmitApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private suspend fun submit(client: io.ktor.client.HttpClient, body: String) = client.post("/api/urls") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `POST urls enqueues JD_URL and claim returns the type and the hints`() = testApplication {
        application { configureApplication() }
        val res = submit(client, """{"url":"https://jobs.example.com/qa/1","source":"EMAIL","company":"Acme","role_title":"SDET","location":"Seattle, WA"}""")
        assertEquals(HttpStatusCode.Accepted, res.status)

        val claim = json.parseToJsonElement(client.get("/api/queue/claim").bodyAsText()).jsonObject
        assertEquals(WorkItemType.JD_URL, claim["type"]!!.jsonPrimitive.content)
        val record = claim["jd_record"]!!.jsonObject
        assertEquals("https://jobs.example.com/qa/1", record["url"]!!.jsonPrimitive.content)
        assertEquals("EMAIL", record["source"]!!.jsonPrimitive.content)
        assertEquals("Acme", record["company"]!!.jsonPrimitive.content)
        assertEquals("SDET", record["role_title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non-http or relative URLs are rejected 422`() = testApplication {
        application { configureApplication() }
        listOf("not a url", "/jobs/1", "mailto:jobs@example.com", "ftp://example.com/x", "https://").forEach {
            assertEquals(HttpStatusCode.UnprocessableEntity, submit(client, """{"url":"$it"}""").status, it)
        }
    }

    @Test
    fun `the same link dedups, by url or by explicit key`() = testApplication {
        application { configureApplication() }
        val first = json.parseToJsonElement(submit(client, """{"url":"https://jobs.example.com/qa/2"}""").bodyAsText()).jsonObject
        val again = submit(client, """{"url":"https://jobs.example.com/qa/2"}""")
        assertEquals(HttpStatusCode.OK, again.status)
        val againBody = json.parseToJsonElement(again.bodyAsText()).jsonObject
        assertTrue(againBody["deduped"]!!.jsonPrimitive.boolean)
        assertEquals(first["job_id"], againBody["job_id"])

        submit(client, """{"url":"https://jobs.example.com/qa/3?utm=a","idempotency_key":"alert:acme:3"}""")
        val tracked = submit(client, """{"url":"https://jobs.example.com/qa/3?utm=b","idempotency_key":"alert:acme:3"}""")
        assertTrue(json.parseToJsonElement(tracked.bodyAsText()).jsonObject["deduped"]!!.jsonPrimitive.boolean,
            "tracking-param variants of one posting dedup when the caller supplies a stable key")
    }

    @Test
    fun `dedup uses the submitting source's window`() = testApplication {
        application { configureApplication() }
        val id = json.parseToJsonElement(
            submit(client, """{"url":"https://jobs.example.com/qa/4","source":"CAREER_SITE"}""").bodyAsText()
        ).jsonObject["job_id"]!!.jsonPrimitive.content
        val tenDaysAgo = System.currentTimeMillis() / 1000L - 10L * 24 * 3600
        transaction { Jobs.update({ Jobs.id eq id }) { it[Jobs.createdAt] = tenDaysAgo } }

        val career = json.parseToJsonElement(submit(client, """{"url":"https://jobs.example.com/qa/4","source":"CAREER_SITE"}""").bodyAsText()).jsonObject
        assertTrue(career["deduped"]!!.jsonPrimitive.boolean, "CAREER_SITE: 60-day window")
        val email = json.parseToJsonElement(submit(client, """{"url":"https://jobs.example.com/qa/4","source":"EMAIL"}""").bodyAsText()).jsonObject
        assertFalse(email["deduped"]?.jsonPrimitive?.boolean ?: false, "EMAIL: default 72h window has passed")
    }

    @Test
    fun `isHttpUrl accepts absolute http(s) URLs only`() {
        assertTrue(isHttpUrl("https://jobs.example.com/a?b=c"))
        assertTrue(isHttpUrl(" HTTP://example.com/x "))
        assertFalse(isHttpUrl("example.com/x"))
        assertFalse(isHttpUrl("javascript:alert(1)"))
        assertFalse(isHttpUrl("https:///nohost"))
    }
}
