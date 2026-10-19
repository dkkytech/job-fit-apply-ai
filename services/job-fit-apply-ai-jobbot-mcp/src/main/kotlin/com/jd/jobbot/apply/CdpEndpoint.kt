package com.jd.jobbot.apply

import java.net.InetAddress
import java.net.URI

/**
 * How the CDP endpoint for the apply browser is derived from `JOBBOT_BROWSER_CDP_URL`.
 *
 * Chrome's DevTools HTTP server refuses any `Host` that is not an IP literal or `localhost` — a
 * DNS-rebinding guard — so connecting straight to `http://jobbot-browser:9223` fails with
 * `500 Host header is specified and is not an IP address or localhost`. The service name is
 * therefore resolved to its address before the request, and the WebSocket URL Chrome hands back
 * (it echoes whatever authority it was reached on) is re-pointed at that same address.
 *
 * Both transforms live here, apart from the socket, so they can be pinned by unit tests: this
 * path is what stands between the service and a working apply browser, and nothing else covers it.
 */
internal object CdpEndpoint {
    private val WS_URL = Regex("""webSocketDebuggerUrl"\s*:\s*"([^"]+)"""")
    private val WS_AUTHORITY = Regex("""^wss?://[^/]+""")

    /** `http://jobbot-browser:9223` -> `http://172.26.0.3:9223`. */
    fun base(cdpUrl: String, resolve: (String) -> InetAddress = InetAddress::getByName): String {
        val uri = URI(cdpUrl)
        return "${uri.scheme}://${resolve(uri.host).hostAddress}:${uri.port}"
    }

    /**
     * The `webSocketDebuggerUrl` from a `/json/version` body, re-pointed at [base].
     *
     * Chrome answers with the authority it was reached on, so a body fetched via the resolved IP
     * already carries that IP; re-pointing is what keeps a body that echoes something else (e.g.
     * `127.0.0.1`) reachable from another container.
     */
    fun wsFromVersionBody(body: String, base: String): String {
        val ws = WS_URL.find(body)?.groupValues?.get(1)
            ?: error("apply browser /json/version had no webSocketDebuggerUrl")
        val authority = URI(base).authority
        return ws.replaceFirst(WS_AUTHORITY, "ws://$authority")
    }
}
