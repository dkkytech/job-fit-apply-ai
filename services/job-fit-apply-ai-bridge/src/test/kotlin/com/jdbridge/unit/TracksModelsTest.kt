package com.jdbridge.unit

import com.jdbridge.TrackDto
import com.jdbridge.TrackEventCreate
import com.jdbridge.TrackEventDto
import com.jdbridge.TrackStatusUpdate
import com.jdbridge.TracksStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the tracks API contract. The frontend's `Track` type depends on
 * these exact snake_case field names and null handling, so these guard the wire format.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class TracksModelsTest {

    private val json = Json { explicitNulls = false; encodeDefaults = true }

    @Test
    fun `TrackDto serializes with snake_case keys the frontend expects`() {
        val dto = TrackDto(
            id = 42,
            company = "Acme",
            role_title = "Staff SDET",
            location = "Remote",
            remote_policy = "remote",
            fit_score = 82.5,
            job_url = "https://x/y",
            artifact_url = null,
            tech_stack = listOf("Kotlin", "Postgres"),
            status = "backlog",
            created_at = "2026-07-04T18:52:25.512204Z",
            duplicate = false,
        )
        val obj = Json.parseToJsonElement(json.encodeToString(TrackDto.serializer(), dto)).jsonObject

        assertEquals(42, obj["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("Acme", obj["company"]!!.jsonPrimitive.content)
        assertEquals("Staff SDET", obj["role_title"]!!.jsonPrimitive.content)
        assertEquals(82.5, obj["fit_score"]!!.jsonPrimitive.content.toDouble())
        assertEquals(listOf("Kotlin", "Postgres"), obj["tech_stack"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, obj["duplicate"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2026-07-04T18:52:25.512204Z", obj["created_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `null artifact_url is omitted (explicitNulls=false), matching the server config`() {
        val dto = TrackDto(
            id = 1, company = "Acme", status = "backlog",
            created_at = "2026-07-04T00:00:00Z", duplicate = false,
        )
        val obj = Json.parseToJsonElement(json.encodeToString(TrackDto.serializer(), dto)).jsonObject
        assertNull(obj["artifact_url"], "null fields should be omitted, not sent as null")
    }

    @Test
    fun `TrackStatusUpdate deserializes a status body`() {
        val req = json.decodeFromString(TrackStatusUpdate.serializer(), """{"status":"applied"}""")
        assertEquals("applied", req.status)
    }

    @Test
    fun `TrackStatusUpdate ignores unknown keys when configured to`() {
        val lenient = Json { ignoreUnknownKeys = true }
        val req = lenient.decodeFromString(
            TrackStatusUpdate.serializer(),
            """{"status":"applied","extra_field":"ignored"}""",
        )
        assertEquals("applied", req.status)
    }

    @Test
    fun `TrackDto decodes from JSON with every field present`() {
        val decoded = json.decodeFromString(
            TrackDto.serializer(),
            """{"id":7,"company":"Acme","role_title":"Staff SDET","location":"Remote",
                "remote_policy":"remote","fit_score":91.0,"job_url":"https://x/y",
                "artifact_url":"https://x/report","tech_stack":["Kotlin","Postgres"],
                "status":"backlog","created_at":"2026-07-04T18:52:25.512204Z","duplicate":true}""",
        )
        assertEquals(7, decoded.id)
        assertEquals("Acme", decoded.company)
        assertEquals("Staff SDET", decoded.role_title)
        assertEquals("Remote", decoded.location)
        assertEquals("remote", decoded.remote_policy)
        assertEquals(91.0, decoded.fit_score)
        assertEquals("https://x/y", decoded.job_url)
        assertEquals("https://x/report", decoded.artifact_url)
        assertEquals(listOf("Kotlin", "Postgres"), decoded.tech_stack)
        assertTrue(decoded.duplicate)
    }

    @Test
    fun `TrackDto decodes with nullable fields omitted`() {
        val decoded = json.decodeFromString(
            TrackDto.serializer(),
            """{"id":8,"company":"Acme","status":"backlog","created_at":"2026-07-04T00:00:00Z","duplicate":false}""",
        )
        assertNull(decoded.role_title)
        assertNull(decoded.location)
        assertNull(decoded.remote_policy)
        assertNull(decoded.fit_score)
        assertNull(decoded.job_url)
        assertNull(decoded.artifact_url)
        assertNull(decoded.tech_stack)
    }

    @Test
    fun `TrackDto missing a required field throws`() {
        assertFailsWith<Exception> {
            json.decodeFromString(TrackDto.serializer(), """{"company":"Acme","status":"backlog"}""")
        }
    }

    @Test
    fun `ALLOWED_STATUSES matches the UI's status set`() {
        assertEquals(
            setOf("backlog", "duplicate", "applied", "interested",
                  "skipped", "interviewing", "rejected", "offer"),
            TracksStore.ALLOWED_STATUSES,
        )
        assertTrue("bogus" !in TracksStore.ALLOWED_STATUSES)
    }

    // ── Track events ───────────────────────────────────────────────────────────

    @Test
    fun `TrackStatusUpdate without a source defaults to frontend (backlog UI body)`() {
        val req = json.decodeFromString(TrackStatusUpdate.serializer(), """{"status":"applied"}""")
        assertEquals("frontend", req.source)
    }

    @Test
    fun `ALLOWED_EVENT_KINDS is the agreed whitelist`() {
        assertEquals(
            setOf("status_changed", "note", "email_received", "email_sent", "reply_drafted",
                  "archived", "unarchived", "applied", "application_filled", "application_submitted",
                  "account_created", "interview", "rejected", "offer"),
            TracksStore.ALLOWED_EVENT_KINDS,
        )
    }

    @Test
    fun `sourceError rejects blank and over-long sources`() {
        assertNull(TracksStore.sourceError("jobbot"))
        assertNull(TracksStore.sourceError("x".repeat(TracksStore.MAX_SOURCE_LENGTH)))
        assertTrue(TracksStore.sourceError("  ")!!.contains("blank"))
        assertTrue(TracksStore.sourceError("x".repeat(TracksStore.MAX_SOURCE_LENGTH + 1))!!.contains("32"))
    }

    @Test
    fun `TrackEventDto serializes snake_case with details verbatim and null summary omitted`() {
        val dto = TrackEventDto(
            id = 9, track_id = 3, occurred_at = "2026-10-01T12:00:00Z", kind = "status_changed",
            source = "frontend", details = buildJsonObject { put("from", "backlog"); put("to", "applied") },
        )
        val obj = Json.parseToJsonElement(json.encodeToString(TrackEventDto.serializer(), dto)).jsonObject
        assertEquals(3, obj["track_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("2026-10-01T12:00:00Z", obj["occurred_at"]!!.jsonPrimitive.content)
        assertEquals("applied", obj["details"]!!.jsonObject["to"]!!.jsonPrimitive.content)
        assertNull(obj["summary"])
    }

    @Test
    fun `TrackEventCreate requires kind and source`() {
        val ok = json.decodeFromString(TrackEventCreate.serializer(), """{"kind":"note","source":"jobbot"}""")
        assertNull(ok.summary)
        assertNull(ok.details)
        assertFailsWith<Exception> {
            json.decodeFromString(TrackEventCreate.serializer(), """{"kind":"note"}""")
        }
    }
}
