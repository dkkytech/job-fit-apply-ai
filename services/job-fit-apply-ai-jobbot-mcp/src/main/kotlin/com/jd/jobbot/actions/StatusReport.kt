package com.jd.jobbot.actions

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.gmail.GmailAuth
import com.jd.jobbot.jobs.JobRef
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The `/jdstatus` text: what JobBot is doing and whether anything is broken. Deterministic — no LLM. */
class StatusReport(
    private val bridge: BridgeReadClient,
    private val store: ActionStore,
    private val handledVerbs: Set<String>,
    private val dryRun: Boolean,
    private val gmail: GmailAuth? = null,
    private val zone: ZoneId = ZoneId.of("America/Los_Angeles"),
) {
    private val fmt = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(zone)

    fun text(): String = buildString {
        appendLine("JobBot status")
        appendLine("Mode: ${if (dryRun) "DRY RUN (taps are logged, nothing is done)" else "live"}")
        appendLine("Buttons handled: ${handledVerbs.sorted().joinToString().ifEmpty { "none" }}")
        val head = runCatching { bridge.headSeq() }
        appendLine(
            head.fold(
                { "Latest JFAA job: ${JobRef.format(it)}" },
                { "Latest JFAA job: bridge unreachable (${it.message?.take(80)})" },
            ),
        )
        appendLine(
            "Gmail: " + when (val s = gmail?.state) {
                null -> "not configured"
                GmailAuth.State.Ok -> "ok"
                is GmailAuth.State.NeedsReauth -> "NEEDS RE-AUTH (${s.reason}) → docker compose run --rm poller --reauth"
                is GmailAuth.State.Unavailable -> "unavailable (${s.reason})"
            },
        )
        appendLine("Last sweep: not run by JobBot (inbox sweeps stay with Muse)")
        val pending = store.pendingCounts()
        appendLine(
            "Pending: " + if (pending.isEmpty()) "none" else pending.entries.joinToString { "${it.value} ${it.key.replace('_', ' ')}" },
        )
        val recent = store.recent(5)
        if (recent.isNotEmpty()) {
            appendLine("Recent actions:")
            recent.forEach { appendLine("• ${fmt.format(Instant.ofEpochMilli(it.updatedAt))} ${it.verb} ${JobRef.format(it.seq)} — ${it.status.replace('_', ' ')}") }
        }
        val errors = store.recentErrors(5)
        appendLine(if (errors.isEmpty()) "Recent errors: none" else "Recent errors:")
        errors.forEach { appendLine("• ${fmt.format(Instant.ofEpochMilli(it.at))} ${it.context}: ${it.message.take(160)}") }
    }.trimEnd()
}
