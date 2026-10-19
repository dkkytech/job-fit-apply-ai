package com.jd.jobbot.apply

import com.jd.jobbot.gmail.GmailClient
import java.time.Clock
import java.time.Duration

/**
 * Finds a site's verification email in the poller's inbox and returns its code or link. Only
 * mail from the site's own domain (or a known ATS mailer) that arrived after the signup started
 * counts, and a link must point back at the site — a verification step can never be steered to
 * an arbitrary URL. Polls, because the email takes a moment to arrive.
 */
open class Verifier(
    private val gmail: GmailClient,
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    open fun code(site: String, sinceMillis: Long, wait: Duration = Duration.ofMinutes(2)): String? =
        poll(site, sinceMillis, wait) { SitePolicy.verificationCode(it.subject.orEmpty() + "\n" + it.body) }

    open fun link(site: String, sinceMillis: Long, wait: Duration = Duration.ofMinutes(2)): String? =
        poll(site, sinceMillis, wait) { SitePolicy.verificationLink(it.body, site) }

    private fun <T> poll(site: String, sinceMillis: Long, wait: Duration, extract: (GmailClient.Message) -> T?): T? {
        val deadline = clock.millis() + wait.toMillis()
        while (true) {
            val found = gmail.search("newer_than:1d -in:sent", 10).asSequence()
                .map { gmail.message(it) }
                .filter { (it.internalDate ?: 0) >= sinceMillis - SKEW_MS }
                .filter { SitePolicy.verificationSenderAllowed(it.from.orEmpty(), site) }
                .mapNotNull(extract)
                .firstOrNull()
            if (found != null || clock.millis() >= deadline) return found
            sleep(POLL_MS)
        }
    }

    companion object {
        const val POLL_MS = 10_000L
        /** The mail server's clock and ours disagree by a little. */
        const val SKEW_MS = 60_000L
    }
}
