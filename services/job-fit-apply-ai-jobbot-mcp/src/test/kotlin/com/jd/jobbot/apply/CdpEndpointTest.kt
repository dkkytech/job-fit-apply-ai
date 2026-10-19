package com.jd.jobbot.apply

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the CDP endpoint derivation the apply browser depends on.
 *
 * Chrome's DevTools HTTP server rejects any `Host` that is not an IP literal or localhost, so the
 * configured service name (`http://jobbot-browser:9223`) cannot be used as-is: it is resolved to
 * an address first, and the WebSocket URL Chrome echoes back is re-pointed at that address. Both
 * transforms are load-bearing — losing either breaks Apply entirely — and until now nothing
 * covered them, because the only test that touches a real browser is opt-in and skipped by default.
 */
class CdpEndpointTest {
    private val chromeVersionBody = """
        {
           "Browser": "Chrome/154.0.8037.57",
           "Protocol-Version": "1.3",
           "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
           "V8-Version": "15.4.80.11",
           "WebKit-Version": "537.36 (@abc123)",
           "webSocketDebuggerUrl": "ws://127.0.0.1:9222/devtools/browser/32c9411c-bdde-4c36-858d-6a797ccaf6e7"
        }
    """.trimIndent()

    // --- base(): the service name must become an address -------------------------------------

    @Test
    fun `a service name resolves to its address`() {
        val base = CdpEndpoint.base("http://jobbot-browser:9223") { InetAddress.getByName("172.26.0.3") }
        assertEquals("http://172.26.0.3:9223", base)
    }

    @Test
    fun `the resolved base never carries the service name`() {
        // The exact configuration that fails against Chrome if left unresolved.
        val base = CdpEndpoint.base("http://jobbot-browser:9223") { InetAddress.getByName("172.26.0.3") }
        assertFalse(base.contains("jobbot-browser"), "a Host of $base is rejected by DevTools")
    }

    @Test
    fun `an address is left as-is`() {
        assertEquals("http://172.26.0.3:9223", CdpEndpoint.base("http://172.26.0.3:9223"))
    }

    @Test
    fun `localhost resolves without touching the network`() {
        // DNS-free: getByName("localhost") reads /etc/hosts, so this holds in CI too.
        assertEquals("http://127.0.0.1:9223", CdpEndpoint.base("http://localhost:9223"))
    }

    @Test
    fun `the scheme and port survive resolution`() {
        val base = CdpEndpoint.base("http://jobbot-browser:9999") { InetAddress.getByName("10.1.2.3") }
        assertEquals("http", base.substringBefore("://"))
        assertEquals("http://10.1.2.3:9999", base)
    }

    // --- wsFromVersionBody(): the echoed authority must be re-pointed --------------------------

    @Test
    fun `a loopback websocket url is re-pointed at the resolved address`() {
        // Chrome echoes the authority it was reached on. Reaching it via the resolved IP but
        // receiving a 127.0.0.1 url would send the client to its own loopback, not the browser.
        val ws = CdpEndpoint.wsFromVersionBody(chromeVersionBody, "http://172.26.0.3:9223")
        assertEquals("ws://172.26.0.3:9223/devtools/browser/32c9411c-bdde-4c36-858d-6a797ccaf6e7", ws)
    }

    @Test
    fun `the browser path is preserved so the session still targets the right target`() {
        val ws = CdpEndpoint.wsFromVersionBody(chromeVersionBody, "http://172.26.0.3:9223")
        assertTrue(ws.endsWith("/devtools/browser/32c9411c-bdde-4c36-858d-6a797ccaf6e7"), ws)
    }

    @Test
    fun `a websocket url already on the resolved authority is unchanged`() {
        val body = """{"webSocketDebuggerUrl": "ws://172.26.0.3:9223/devtools/browser/abc"}"""
        assertEquals(
            "ws://172.26.0.3:9223/devtools/browser/abc",
            CdpEndpoint.wsFromVersionBody(body, "http://172.26.0.3:9223"),
        )
    }

    @Test
    fun `a secure websocket url is re-pointed too`() {
        // Chrome answers wss:// when the endpoint is reached over TLS; the rewrite must not
        // silently skip it and hand the client an unreachable authority.
        val body = """{"webSocketDebuggerUrl": "wss://127.0.0.1:9223/devtools/browser/abc"}"""
        assertEquals(
            "ws://172.26.0.3:9223/devtools/browser/abc",
            CdpEndpoint.wsFromVersionBody(body, "http://172.26.0.3:9223"),
        )
    }

    @Test
    fun `a body without a websocket url fails loudly`() {
        val e = assertFailsWith<IllegalStateException> {
            CdpEndpoint.wsFromVersionBody("""{"Browser": "Chrome/154.0.8037.57"}""", "http://172.26.0.3:9223")
        }
        assertTrue(e.message!!.contains("webSocketDebuggerUrl"), e.message)
    }

    // --- the two together: what wsEndpoint() hands Playwright ---------------------------------

    @Test
    fun `the derived endpoint targets the address, never the service name`() {
        val base = CdpEndpoint.base("http://jobbot-browser:9223") { InetAddress.getByName("172.26.0.3") }
        val ws = CdpEndpoint.wsFromVersionBody(chromeVersionBody, base)
        assertEquals("ws://172.26.0.3:9223/devtools/browser/32c9411c-bdde-4c36-858d-6a797ccaf6e7", ws)
        assertFalse(ws.contains("jobbot-browser"), ws)
        assertFalse(ws.contains("127.0.0.1"), ws)
    }

    @Test
    fun `the default resolver is the real one, not a test-only stub`() {
        // No injected resolver: the production default must resolve an address on its own.
        assertEquals("http://127.0.0.1:9223", CdpEndpoint.base("http://localhost:9223"))
    }
}
