package com.jd.jobbot.bridge

import com.jd.jobbot.support.FakeBridge
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeReadClientTest {
    private val bridge = FakeBridge(events = listOf(FakeBridge.event(5), FakeBridge.event(7), FakeBridge.event(8)))
    private val client = BridgeReadClient(bridge.url)

    @AfterTest fun stop() = bridge.close()

    @Test
    fun `completedEvent asks for exactly one event after seq-1 and checks the seq`() {
        assertEquals(7L, client.completedEvent(7)!!.long("completed_seq"))
        assertTrue(bridge.requests.contains("GET /api/jobs/completed?since=6&limit=1&all=true"), "${bridge.requests}")
    }

    @Test
    fun `a gap in the feed is not mistaken for the next event`() {
        // since=5 returns seq 7 first; asking for 6 must not answer with 7.
        assertNull(client.completedEvent(6))
        assertNull(client.completedEvent(0))
    }

    @Test
    fun `head and pages read the feed`() {
        assertEquals(8L, client.headSeq())
        assertEquals(listOf(7L, 8L), client.completed(5, 200).map { it.long("completed_seq") })
    }

    @Test
    fun `non-2xx responses raise BridgeException with the status`() {
        bridge.failWith = 503
        val e = assertFailsWith<BridgeReadClient.BridgeException> { client.headSeq() }
        assertEquals(503, e.status)
    }

    @Test
    fun `only allowlisted read routes can be requested`() {
        listOf("/api/queue/claim", "/api/jobs/x/result", "/api/emails", "/api/tracks/1/status", "/api/jobs/x/artifacts")
            .forEach { assertFailsWith<IllegalArgumentException>(it) { client.get(it) } }
        assertTrue(bridge.requests.isEmpty(), "a refused route must not reach the bridge: ${bridge.requests}")
    }

    @Test
    fun `the client has no way to write`() {
        val names = BridgeReadClient::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("post", "put", "delete", "patch", "claim", "result", "writeback").forEach { verb ->
            assertTrue(names.none { it.startsWith(verb) }, "unexpected write-like method: ${names.filter { it.startsWith(verb) }}")
        }
    }
}
