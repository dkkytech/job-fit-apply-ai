package com.jd.jobbot.bridge

import com.jd.jobbot.support.FakeHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackWriterTest {
    private val bridge = FakeHttp().also { it.on("POST /api/tracks/") { FakeHttp.Resp(201, "{}") } }
    @AfterTest fun stop() = bridge.close()

    @Test
    fun `events and status changes are posted as source jobbot`() {
        val w = TrackWriter(bridge.url)
        w.addEvent(41, "archived", "Archived")
        w.setStatus(41, "applied")
        val (event, status) = bridge.requests.map { it.path to Json.parseToJsonElement(it.body).jsonObject }
        assertEquals("/api/tracks/41/events", event.first)
        assertEquals("archived", event.second["kind"]!!.jsonPrimitive.content)
        assertEquals("jobbot", event.second["source"]!!.jsonPrimitive.content)
        assertEquals("/api/tracks/41/status", status.first)
        assertEquals("applied", status.second["status"]!!.jsonPrimitive.content)
        assertEquals("jobbot", status.second["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown kinds and statuses never reach the bridge`() {
        val w = TrackWriter(bridge.url)
        assertFailsWith<IllegalArgumentException> { w.addEvent(1, "deleted", null) }
        assertFailsWith<IllegalArgumentException> { w.setStatus(1, "hired") }
        assertTrue(bridge.requests.isEmpty())
    }

    @Test
    fun `bridge errors surface`() {
        bridge.on("POST /api/tracks/") { FakeHttp.Resp(404, """{"error":"Track 1 not found"}""") }
        assertEquals(404, assertFailsWith<BridgeReadClient.BridgeException> { TrackWriter(bridge.url).setStatus(1, "applied") }.status)
    }
}
