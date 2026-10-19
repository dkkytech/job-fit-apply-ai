package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for the tracks API against the real Dockerized Postgres
 * (docker compose `db`). Runs in an isolated, self-provisioned `jobfit_test`
 * database so it never touches real data, and applies the project's actual
 * `db/init/001_schema.sql` (also validating that file). Skips automatically when
 * the container is unreachable, so it is CI-safe.
 *
 * (Testcontainers would be the usual choice, but the CI sandbox here blocks the
 *  test JVM's Docker socket while allowing TCP to the mapped port — so we target
 *  the running container directly, matching the pipeline's PostgresGatewayLiveTest.)
 */
class TracksApiTest {

    companion object {
        private const val TEST_DB = "jobfit_test"

        private val base: URI = URI(System.getenv("DATABASE_URL")
            ?: "postgresql://jobfit:jobfit@localhost:5432/jobfit")
        private val creds = base.userInfo?.split(":", limit = 2) ?: listOf("jobfit", "jobfit")
        private val host = base.host
        private val port = if (base.port > 0) base.port else 5432
        private val adminDb = base.path.trimStart('/')

        private fun jdbc(db: String) = "jdbc:postgresql://$host:$port/$db?stringtype=unspecified"
        fun conn(db: String): Connection =
            DriverManager.getConnection(jdbc(db), creds[0], creds.getOrElse(1) { "" })

        @BeforeAll
        @JvmStatic
        fun provision() {
            val reachable = runCatching { conn(adminDb).use { it.isValid(2) } }.getOrDefault(false)
            assumeTrue(reachable, "Postgres container not reachable — skipping tracks integration test")

            // Create the isolated test database if it doesn't exist yet.
            conn(adminDb).use { c ->
                val exists = c.createStatement().use { st ->
                    st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '$TEST_DB'").use { it.next() }
                }
                if (!exists) c.createStatement().use { it.execute("CREATE DATABASE $TEST_DB") }
            }

            // Apply the real schema (idempotent — 001_schema.sql uses IF NOT EXISTS).
            val schema = listOf(File("../../db/init/001_schema.sql"), File("db/init/001_schema.sql"))
                .firstOrNull { it.exists() }
                ?: error("Could not locate db/init/001_schema.sql from ${File(".").absolutePath}")
            conn(TEST_DB).use { c -> c.createStatement().use { it.execute(schema.readText()) } }

            // Point TracksStore at the test database before it is first used.
            System.setProperty("DATABASE_URL",
                "postgresql://${creds[0]}:${creds.getOrElse(1) { "" }}@$host:$port/$TEST_DB")
        }
    }

    // Seeds two tracks (ids 1 and 2, status backlog). TRUNCATE … CASCADE also empties track_events.
    @BeforeEach
    fun setup() {
        assumeTrue(runCatching { conn(TEST_DB).use { it.isValid(2) } }.getOrDefault(false))
        // SQLite side (the app module wires its own job queue on startup).
        useTempStoreDir()
        initTestDb()
        // Fresh, deterministic tracks each test.
        conn(TEST_DB).use { c ->
            c.createStatement().use { st ->
                st.execute("TRUNCATE tracks RESTART IDENTITY CASCADE;")
                st.execute(
                    """
                    INSERT INTO tracks
                      (id, company, role_title, location, remote_policy, fit_score,
                       job_url, artifact_url, tech_stack, status, duplicate)
                    VALUES
                      (1,'Acme','Staff SDET','Remote','remote',82.5,'https://x','',
                       '{Kotlin,Postgres}','backlog',false),
                      (2,'Globex','Engineer','NYC','onsite',70,'https://y','',
                       '{Java}','backlog',false);
                    """.trimIndent(),
                )
            }
        }
    }

    // ── TracksStore (JDBC) directly ────────────────────────────────────────────

    @Test
    fun `TracksStore list returns rows with typed fields`() = runBlocking {
        val rows = TracksStore.list()
        assertEquals(2, rows.size)
        val acme = rows.first { it.id == 1 }
        assertEquals("Acme", acme.company)
        assertEquals(82.5, acme.fit_score)
        assertEquals(listOf("Kotlin", "Postgres"), acme.tech_stack)
        assertEquals(false, acme.duplicate)
        assertTrue(acme.created_at.isNotBlank())
    }

    @Test
    fun `TracksStore updateStatus changes status and reports missing rows`() = runBlocking {
        assertTrue(TracksStore.updateStatus(1, "applied"))
        assertEquals("applied", TracksStore.list().first { it.id == 1 }.status)
        assertEquals(false, TracksStore.updateStatus(999999, "applied"))
    }

    @Test
    fun `TracksStore list maps NULL nullable columns to null fields`() = runBlocking {
        // company/status/duplicate/created_at are NOT NULL in the schema; every other
        // column (including tech_stack) can be NULL — exercise toTrackDto's null paths.
        conn(TEST_DB).use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    INSERT INTO tracks (id, company, role_title, location, remote_policy,
                                         fit_score, job_url, artifact_url, tech_stack, status, duplicate)
                    VALUES (3, 'Blank Co', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'backlog', false);
                    """.trimIndent(),
                )
            }
        }
        val row = TracksStore.list().first { it.id == 3 }
        assertEquals("Blank Co", row.company)
        assertNull(row.role_title)
        assertNull(row.location)
        assertNull(row.remote_policy)
        assertNull(row.fit_score)
        assertNull(row.job_url)
        assertNull(row.artifact_url)
        assertNull(row.tech_stack)
        assertEquals("backlog", row.status)
    }

    // ── HTTP routes ────────────────────────────────────────────────────────────

    @Test
    fun `GET api tracks returns the seeded rows`() = testApplication {
        application { configureApplication() }
        val res = client.get("/api/tracks")
        assertEquals(HttpStatusCode.OK, res.status)
        val arr = Json.parseToJsonElement(res.bodyAsText()).jsonArray
        assertEquals(2, arr.size)
        val first = arr.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.int == 1 }
        assertEquals("Acme", first["company"]!!.jsonPrimitive.content)
        assertEquals(2, first["tech_stack"]!!.jsonArray.size)
    }

    @Test
    fun `POST status updates the row`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/1/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"interested"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("interested", runBlocking { TracksStore.list().first { it.id == 1 }.status })
    }

    @Test
    fun `POST invalid status returns 422`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/1/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"bogus"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
    }

    @Test
    fun `POST status for unknown id returns 404`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/999999/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"applied"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `POST status for non-integer id returns 400`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/abc/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"applied"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    // ── Track events (application history) ─────────────────────────────────────

    private suspend fun ApplicationTestBuilder.events(trackId: Any, query: String = ""): HttpResponse =
        client.get("/api/tracks/$trackId/events$query")

    private suspend fun ApplicationTestBuilder.eventList(trackId: Int, query: String = ""): List<JsonObject> =
        Json.parseToJsonElement(events(trackId, query).bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun ApplicationTestBuilder.postEvent(trackId: Any, body: String): HttpResponse =
        client.post("/api/tracks/$trackId/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun eventCount(): Int = conn(TEST_DB).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM track_events").use { it.next(); it.getInt(1) }
        }
    }

    @Test
    fun `POST status appends a status_changed event from the frontend`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/1/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"applied"}""")   // the backlog UI's exact body — no source
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val event = eventList(1).single()
        assertEquals("status_changed", event["kind"]!!.jsonPrimitive.content)
        assertEquals("frontend", event["source"]!!.jsonPrimitive.content)
        assertEquals(1, event["track_id"]!!.jsonPrimitive.int)
        val details = event["details"]!!.jsonObject
        assertEquals("backlog", details["from"]!!.jsonPrimitive.content)
        assertEquals("applied", details["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST status records an explicit source`() = testApplication {
        application { configureApplication() }
        client.post("/api/tracks/1/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"interested","source":"jobbot"}""")
        }
        assertEquals("jobbot", eventList(1).single()["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST status to the current status records no event`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/1/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"backlog"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(eventList(1).isEmpty())
    }

    @Test
    fun `POST status with a blank source returns 422 and changes nothing`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/1/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"applied","source":" "}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
        assertEquals("backlog", runBlocking { TracksStore.list().first { it.id == 1 }.status })
        assertEquals(0, eventCount())
    }

    @Test
    fun `POST events creates an event and GET lists them newest first`() = testApplication {
        application { configureApplication() }
        val created = postEvent(1, """{"kind":"note","summary":"Recruiter is Jane","source":"jobbot"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals("note", body["kind"]!!.jsonPrimitive.content)
        assertEquals("Recruiter is Jane", body["summary"]!!.jsonPrimitive.content)
        assertEquals("jobbot", body["source"]!!.jsonPrimitive.content)
        assertEquals(1, body["track_id"]!!.jsonPrimitive.int)
        assertTrue(body["id"]!!.jsonPrimitive.long > 0)
        assertTrue(body["occurred_at"]!!.jsonPrimitive.content.isNotBlank())
        assertNull(body["details"], "absent details are omitted, not null")

        assertEquals(HttpStatusCode.Created, postEvent(1, """
            {"kind":"archived","source":"jobbot",
             "details":{"gmail":{"thread_id":"t-1","labels":["JD_Archived"]},"auto":true}}
        """.trimIndent()).status)

        val listed = eventList(1)
        assertEquals(listOf("archived", "note"), listed.map { it["kind"]!!.jsonPrimitive.content })
        val details = listed.first()["details"]!!.jsonObject
        assertEquals("t-1", details["gmail"]!!.jsonObject["thread_id"]!!.jsonPrimitive.content)
        assertEquals(true, details["auto"]!!.jsonPrimitive.boolean)
        // Events belong to their own track only.
        assertTrue(eventList(2).isEmpty())
    }

    @Test
    fun `GET events honours limit and clamps it to 1-500`() = testApplication {
        application { configureApplication() }
        repeat(3) { postEvent(1, """{"kind":"note","summary":"n$it","source":"jobbot"}""") }

        assertEquals(listOf("n2", "n1"), eventList(1, "?limit=2").map { it["summary"]!!.jsonPrimitive.content })
        assertEquals(1, eventList(1, "?limit=0").size)
        assertEquals(3, eventList(1, "?limit=100000").size)
        assertEquals(3, eventList(1, "?limit=abc").size)
    }

    @Test
    fun `POST events with a kind outside the whitelist returns 400 and stores nothing`() = testApplication {
        application { configureApplication() }
        val res = postEvent(1, """{"kind":"deleted","source":"jobbot"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("Invalid kind"))
        assertEquals(0, eventCount())
    }

    @Test
    fun `POST events validates source and body shape with 400`() = testApplication {
        application { configureApplication() }
        assertEquals(HttpStatusCode.BadRequest, postEvent(1, """{"kind":"note","source":""}""").status)
        assertEquals(HttpStatusCode.BadRequest, postEvent(1, """{"kind":"note","source":"${"x".repeat(33)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, postEvent(1, """{"kind":"note"}""").status)
        assertEquals(HttpStatusCode.BadRequest, postEvent(1, """{"source":"jobbot"}""").status)
        assertEquals(HttpStatusCode.BadRequest, postEvent(1, """not json""").status)
        assertEquals(0, eventCount())
    }

    @Test
    fun `events routes return 404 for an unknown track and 400 for a non-integer id`() = testApplication {
        application { configureApplication() }
        assertEquals(HttpStatusCode.NotFound, events(999999).status)
        assertEquals(HttpStatusCode.NotFound, postEvent(999999, """{"kind":"note","source":"jobbot"}""").status)
        assertEquals(HttpStatusCode.BadRequest, events("abc").status)
        assertEquals(HttpStatusCode.BadRequest, postEvent("abc", """{"kind":"note","source":"jobbot"}""").status)
        assertEquals(0, eventCount())
    }

    @Test
    fun `GET events for a track with no history returns an empty list`() = testApplication {
        application { configureApplication() }
        val res = events(2)
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(0, Json.parseToJsonElement(res.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `deleting a track cascades to its events`() = runBlocking {
        TracksStore.addEvent(1, "note", null, "jobbot", null)
        assertEquals(1, eventCount())
        conn(TEST_DB).use { c -> c.createStatement().use { it.execute("DELETE FROM tracks WHERE id = 1") } }
        assertEquals(0, eventCount())
    }

    @Test
    fun `ensureSchema creates track_events idempotently (startup run twice)`() = runBlocking {
        // Already created by 001_schema.sql — the startup DDL must be a no-op over it…
        TracksStore.ensureSchema()
        // …and must build it from scratch on a database that predates it (production).
        conn(TEST_DB).use { c -> c.createStatement().use { it.execute("DROP TABLE track_events") } }
        TracksStore.ensureSchema()
        TracksStore.ensureSchema()

        conn(TEST_DB).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT 1 FROM pg_indexes WHERE tablename = 'track_events' " +
                        "AND indexname = 'idx_track_events_track_occurred'",
                ).use { assertTrue(it.next(), "index missing after ensureSchema") }
            }
        }
        val event = TracksStore.addEvent(2, "applied", "Applied via ATS", "jobbot", null)
        assertEquals("applied", event?.kind)
        assertEquals(listOf("applied"), TracksStore.listEvents(2, 100)!!.map { it.kind })
    }
}
