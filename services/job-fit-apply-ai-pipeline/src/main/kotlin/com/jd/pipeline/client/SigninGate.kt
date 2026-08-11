package com.jd.pipeline.client

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide flag: an interactive sign-in ([SteelSigninSession]) currently owns the Steel browser.
 *
 * Steel keeps ONE shared Chrome, and a session reusing that browser refreshes its primary page —
 * the documented cause of the scraper's TargetClosedError churn. A sign-in session is held for
 * [com.jd.pipeline.config.Config.STEEL_SIGNIN_WINDOW_MS] (30 min), so letting a scrape batch drive
 * tabs at the same time would have them killing each other's pages for half an hour.
 *
 * While the gate is held, [SteelBrowser.isAvailable] reports false and the scraper takes the
 * fallback it already has for a down backend (plain HTTP, else email-only JD text). Degrading a
 * batch is the better trade: the alternative is a sign-in that keeps getting its login page closed.
 *
 * The Processor loop and the sign-in server share one JVM, so a static flag is sufficient — there is
 * no cross-process case to coordinate. Counted rather than boolean so overlapping sign-ins (a
 * double-tapped alert link) can't have the first one to finish open the gate under the second.
 */
object SigninGate {
    private val held = AtomicInteger(0)

    /** True while at least one interactive sign-in holds the browser. */
    fun isActive(): Boolean = held.get() > 0

    /** Run [block] with the gate held, releasing it even if [block] throws. */
    fun <T> holding(block: () -> T): T {
        held.incrementAndGet()
        return try {
            block()
        } finally {
            held.decrementAndGet()
        }
    }
}
