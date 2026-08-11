package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.client.SteelSigninSession
import com.jd.pipeline.config.Config

/**
 * Long-lived Steel sign-in / capture. Opens a Steel session, parks it on the site's login page,
 * prints its interactive debug URL (tailnet), and polls — merging cookies into the persisted
 * storageState store on every cycle — until the login wall clears or the window elapses.
 *
 * Fills the gap where `--test-steel` closes its session too fast to sign into. Because the boards
 * share Google SSO, one sign-in refreshes them all.
 *
 * Capture no longer depends on a TTY or on the user remembering to confirm: every poll persists, so
 * a sign-in is saved even if the terminal is detached or the window expires. Pressing ENTER is an
 * optional early exit when stdin happens to be interactive, so a plain `docker exec` works too:
 *
 *   docker exec jobfit-processor /app/bin/job-fit-apply-ai-pipeline --steel-signin linkedin
 */
object SteelSigninCommandHandler {

    fun run(cmd: Command.SteelSignin) {
        val base = Config.STEEL_BASE_URL
        if (base.isBlank()) {
            println("[steel-signin] Steel is disabled — set STEEL_BASE_URL (e.g. http://steel:3000) first.")
            return
        }

        // An argument may be a full URL, a site label ("linkedin"), or a bare host — loginUrlFor
        // resolves the latter two; anything already absolute is used as-is.
        val arg = cmd.url?.takeIf { it.isNotBlank() }
        val loginUrl = arg?.let { if (it.startsWith("http://") || it.startsWith("https://")) it else SteelSigninSession.loginUrlFor(it) }
        if (arg != null && loginUrl == null) {
            println("[steel-signin] ⚠️ Could not resolve '$arg' to a login URL — opening a blank session instead.")
        }

        val signin = SteelSigninSession()
        val opened = try {
            signin.open(loginUrl)
        } catch (e: Exception) {
            println("[steel-signin] ❌ Could not create a Steel session at $base: ${e.message}")
            runCatching { signin.close() }
            return
        }

        try {
            val minutes = opened.windowMs / 60_000
            println("[steel-signin] Session ${opened.sessionId} created (stays open $minutes min).")
            opened.startUrl?.let { println("[steel-signin] Parked on: $it") }
            println("[steel-signin] 📱 Open this on your phone (over Tailscale) and sign in:")
            println("[steel-signin]    ${opened.debugUrl ?: "${Config.STEEL_UI_URL.ifBlank { base }} — open the live session viewer"}")
            println("[steel-signin] Sign into every board you use — they share your Google SSO, so one login covers them all.")
            println("[steel-signin] Capturing automatically every ${Config.STEEL_SIGNIN_POLL_MS / 1000}s — no need to confirm.")
            println("[steel-signin] (Press ENTER to finish early.)")

            val result = signin.awaitCapture(
                stopRequested = enterPressed(),
                onPoll = { elapsedMs, cookies ->
                    println("[steel-signin] …${elapsedMs / 1000}s — $cookies cookies captured")
                },
            )

            when {
                result.merges == 0 ->
                    println("[steel-signin] ⚠️ Could not export the session context — nothing captured.")
                result.timedOut ->
                    println("[steel-signin] ⚠️ Window elapsed still on a login page. Merged ${result.cookies} cookies anyway — re-run if the board is still logged out.")
                result.signedIn ->
                    println("[steel-signin] ✅ Signed in — merged ${result.cookies} cookies into ${Config.STEEL_STORAGE_STATE_PATH}")
                else ->
                    println("[steel-signin] ✅ Merged ${result.cookies} cookies into ${Config.STEEL_STORAGE_STATE_PATH}")
            }
        } finally {
            // Exports one last time, then releases the session.
            signin.close()
            println("[steel-signin] Session released. Scrapers will inject this signed-in context on their next run.")
        }
    }

    /**
     * An early-exit predicate backed by a daemon thread waiting on stdin. ENTER is a convenience, not
     * a requirement — when stdin is not interactive (a plain `docker exec`, a cron), `readLine`
     * returns null at once and the predicate simply never fires, leaving the poll loop to run its
     * course. Daemon so a blocked read can never hold the JVM open.
     */
    private fun enterPressed(): () -> Boolean {
        val pressed = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread { runCatching { if (readlnOrNull() != null) pressed.set(true) } }
            .apply { isDaemon = true }
            .start()
        return { pressed.get() }
    }
}
