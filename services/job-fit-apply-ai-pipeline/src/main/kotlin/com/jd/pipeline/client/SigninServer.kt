package com.jd.pipeline.client

import com.jd.pipeline.config.Config
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * A tiny tailnet HTTP endpoint that creates a Steel sign-in session **when the user taps the link**.
 *
 * This is the fix for the dead re-auth link. A session lives 10 minutes and is released at batch
 * close, so linking a re-auth alert to the *scrape* session means linking to something that no
 * longer exists by the time a human reads Telegram, picks up a phone and taps — the live view then
 * has no page to sign into. Human latency cannot be covered by pre-creating a session; the session
 * has to be born at tap time. So the alert links here instead, and `GET /signin` does the work:
 *
 *   1. open a [SteelSigninSession] (30-min window) with the persisted cookies injected,
 *   2. park it on the site's login page,
 *   3. 302 the user straight into the interactive live view,
 *   4. keep merging cookies in the background until the wall clears, then ping them that it worked.
 *
 * Runs inside the Processor loop's JVM (see [com.jd.pipeline.cli.commands.ProcessorCommandHandler]),
 * which is already long-lived and already holds the Steel config and storage-state mount.
 *
 * **Exposure.** The endpoint creates browser sessions and hands out an unauthenticated live view, so
 * it binds to [Config.STEEL_SIGNIN_BIND_ADDR] — loopback by default, exactly like the `steel`
 * service's own port. Point it at the tailnet IP to use it from a phone, never at 0.0.0.0. Setting
 * [Config.STEEL_SIGNIN_TOKEN] additionally requires `?token=` on every request.
 */
class SigninServer(
    private val port: Int = Config.STEEL_SIGNIN_PORT,
    private val bindAddr: String = Config.STEEL_SIGNIN_BIND_ADDR,
    private val token: String = Config.STEEL_SIGNIN_TOKEN,
    private val alerts: AlertService = AlertService(),
    private val newSession: () -> SteelSigninSession = { SteelSigninSession() },
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(SigninServer::class.java)
    private var server: HttpServer? = null

    /** The sign-in currently in flight, if any. Steel has one Chrome — so at most one at a time. */
    private data class Active(val site: String, val debugUrl: String?)
    private val active = AtomicReference<Active?>(null)

    /** Bind and start serving. Throws if the port is taken. */
    fun start() {
        val s = HttpServer.create(InetSocketAddress(bindAddr, port), 0)
        s.executor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "signin-server").apply { isDaemon = true }
        }
        s.createContext("/signin") { ex -> handle(ex) { handleSignin(it) } }
        s.createContext("/health") { ex -> ex.respond(200, "ok\n") }
        s.start()
        server = s
        log.info("Sign-in endpoint listening on http://{}:{}/signin (public: {})",
            bindAddr, port, Config.STEEL_SIGNIN_PUBLIC_URL.ifBlank { "not advertised" })
    }

    override fun close() {
        server?.stop(0)
        server = null
    }

    /** Shared wrapper: enforce the token, and never let a handler throw a bare 500 with no log. */
    private fun handle(ex: HttpExchange, body: (HttpExchange) -> Unit) {
        try {
            if (token.isNotBlank() && ex.query("token") != token) {
                ex.respond(403, "forbidden\n")
                return
            }
            body(ex)
        } catch (e: Exception) {
            log.warn("Sign-in request failed: {}", e.message)
            runCatching { ex.respond(500, "sign-in failed: ${e.message}\n") }
        } finally {
            ex.close()
        }
    }

    /**
     * Open a sign-in session for `?site=` and redirect into its live view.
     *
     * A second tap while one is in flight redirects to the *existing* session rather than creating
     * another: alert links get double-tapped, and Steel's single Chrome would make the second session
     * close the first one's login page.
     *
     * `@Synchronized` because the check and the open must be atomic together. `open()` takes seconds
     * (createSession + CDP connect), which is well inside human double-tap range — so a plain
     * check-then-act would let the second request see an empty [active] and build a rival session
     * that closes the first one's login page. The second request instead waits out the first open()
     * and then takes the reuse path.
     */
    @Synchronized
    private fun handleSignin(ex: HttpExchange) {
        val site = ex.query("site")?.takeIf { it.isNotBlank() } ?: "the site"

        active.get()?.let { inFlight ->
            log.info("Sign-in for {} already in flight — reusing its live view", inFlight.site)
            ex.redirectOrExplain(inFlight.debugUrl)
            return
        }

        val loginUrl = SteelSigninSession.loginUrlFor(site)
        val session = newSession()
        val opened = try {
            session.open(loginUrl)
        } catch (e: Exception) {
            runCatching { session.close() }
            log.warn("Could not open a sign-in session for {}: {}", site, e.message)
            ex.respond(503, "Could not open a Steel sign-in session: ${e.message}\n")
            return
        }

        active.set(Active(site, opened.debugUrl))
        capture(site, session, opened)
        ex.redirectOrExplain(opened.debugUrl)
    }

    /**
     * Watch the sign-in to completion on a background thread so the HTTP response (the redirect the
     * user is waiting on) isn't held for the whole window. Holds [SigninGate] for the duration so the
     * scraper stands down instead of fighting over Steel's single Chrome, and always clears [active]
     * and closes the session — a leaked session would hold that Chrome until its idle timeout.
     */
    private fun capture(site: String, session: SteelSigninSession, opened: SteelSigninSession.Opened) {
        Thread {
            try {
                // close() is inside the gate on purpose: it exports and releases, and until the
                // release lands the session still holds Steel's Chrome. Opening the gate first would
                // let a scrape start against a session that is being torn down.
                SigninGate.holding {
                    try {
                        val result = session.awaitCapture()
                        when {
                            result.merges == 0 ->
                                log.warn("Sign-in for {} captured nothing — could not export the session context", site)
                            result.signedIn -> {
                                log.info("Sign-in for {} captured {} cookies", site, result.cookies)
                                alerts.reauthCaptured(site, result.cookies)
                            }
                            result.timedOut ->
                                log.warn("Sign-in window for {} elapsed still on a login page ({} cookies merged anyway)",
                                    site, result.cookies)
                        }
                    } finally {
                        runCatching { session.close() }
                        log.info("Sign-in session {} released", opened.sessionId)
                    }
                }
            } catch (e: Exception) {
                log.warn("Sign-in capture for {} failed: {}", site, e.message)
            } finally {
                active.set(null)
            }
        }.apply {
            name = "signin-capture"
            isDaemon = true
        }.start()
    }

    // ── request helpers ─────────────────────────────────────────────────────────

    private fun HttpExchange.query(key: String): String? =
        requestURI.rawQuery
            ?.split('&')
            ?.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
            ?.firstOrNull { java.net.URLDecoder.decode(it[0], StandardCharsets.UTF_8) == key }
            ?.let { java.net.URLDecoder.decode(it[1], StandardCharsets.UTF_8) }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** 302 into the live view, or explain why there isn't one (Steel gave us no debug URL). */
    private fun HttpExchange.redirectOrExplain(debugUrl: String?) {
        if (debugUrl == null) {
            respond(200, "Session is open, but Steel returned no live-view URL. Open the Steel UI directly.\n")
            return
        }
        responseHeaders.add("Location", debugUrl)
        sendResponseHeaders(302, -1)
    }

    companion object {
        /**
         * The tap-to-sign-in URL to put in a re-auth alert, or null when the endpoint isn't
         * advertised ([Config.STEEL_SIGNIN_PUBLIC_URL] blank) — in which case the caller falls back
         * to the old, short-lived debug link.
         */
        fun signinUrl(
            site: String,
            publicUrl: String = Config.STEEL_SIGNIN_PUBLIC_URL,
            token: String = Config.STEEL_SIGNIN_TOKEN,
        ): String? {
            val base = publicUrl.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
            val q = StringBuilder("?site=").append(URLEncoder.encode(site, StandardCharsets.UTF_8))
            if (token.isNotBlank()) q.append("&token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8))
            return "$base/signin$q"
        }
    }
}
