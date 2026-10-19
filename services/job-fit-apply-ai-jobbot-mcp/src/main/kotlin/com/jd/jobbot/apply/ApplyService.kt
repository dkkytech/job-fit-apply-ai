package com.jd.jobbot.apply

import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.InlineButton
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.Outcomes
import com.jd.jobbot.actions.TapContext
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapResponse
import com.jd.jobbot.actions.VerbHandler
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.bridge.str
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.jobs.JobRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Apply workflow (Phase 4). A tap on Apply queues a fill in the apply browser; the result is
 * posted (screenshot + every filled field) under ✅ Submit / ✖ Discard. Only a ✅ Submit tap
 * clicks the site's submit button, and only if the form still matches what the card showed.
 * A filled form waits at most [reviewTtl] (4 h), with a reminder at [reminderAfter] (3 h).
 * Fills run one at a time on a single worker.
 */
class ApplyService(
    private val browser: ApplyBrowser,
    /** FillAgent.run: fill the page for the job; `false` continues a page already in progress. */
    private val fill: (ApplyPage, JobContext, Boolean) -> FillResult,
    private val lookup: JobLookup,
    private val contextFor: (Long) -> JobContext?,
    private val store: ActionStore,
    private val outbox: Outbox,
    private val tracks: TrackWriter?,
    private val viewerUrl: String?,
    /** To activate a password created for a site once an application there is confirmed. */
    private val credentials: Credentials? = null,
    private val reviewTtl: Duration = Duration.ofHours(4),
    private val reminderAfter: Duration = Duration.ofHours(3),
    private val clock: Clock = Clock.systemUTC(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "apply-fill").apply { isDaemon = true } },
) {
    private val log = LoggerFactory.getLogger(ApplyService::class.java)
    private val pages = ConcurrentHashMap<Long, ApplyPage>()
    /** Fills queued or running in this process. One left "queued"/"filling" by an earlier process is dead. */
    private val running = ConcurrentHashMap.newKeySet<Long>()
    private val fmt = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.of("America/Los_Angeles"))

    /** The card's Apply tap. */
    val apply = VerbHandler { ctx, store ->
        alreadyApplied(ctx)?.let { return@VerbHandler it }
        val latest = store.latest(FILL, ctx.jobKey)
        if (latest != null && isOrphan(latest)) {
            // Its tab or worker died with an earlier JobBot process: nothing can continue it, so start over.
            store.update(latest.id, EXPIRED)
        } else when (latest?.status) {
            QUEUED, FILLING -> return@VerbHandler TapResponse(Outcomes.ALREADY, toast = "Already filling this one.")
            Outcomes.AWAITING_APPROVAL -> return@VerbHandler TapResponse(Outcomes.ALREADY, toast = "It's filled and waiting for your review above.")
            NEEDS_HUMAN -> return@VerbHandler TapResponse(Outcomes.ALREADY, toast = "It's waiting on you — see the hand-off message above.")
            Outcomes.DONE -> return@VerbHandler TapResponse(Outcomes.ALREADY, toast = "Already submitted ${fmt.format(Instant.ofEpochMilli(latest.updatedAt))}.")
        }
        val fill = store.insert(FILL, ctx.jobKey, ctx.request.seq, QUEUED, ctx.request.chatId, ctx.request.messageId?.toString())
        start(fill.id, fresh = true)
        TapResponse(
            Outcomes.DONE,
            toast = "Filling ${ctx.ref}…",
            reply = "Filling the application for ${ctx.event.str("company") ?: ctx.ref} in the apply browser. " +
                "I'll post it here for review — nothing is submitted until you tap ✅ Submit.",
        )
    }

    /** ✅ Submit under a filled form. */
    fun submit(id: Long, req: TapRequest): TapResponse {
        val fill = store.byId(id)?.takeIf { it.verb == FILL } ?: return gone("Unknown application.")
        when (fill.status) {
            Outcomes.DONE -> return TapResponse(Outcomes.ALREADY, toast = "Already submitted.", actionRow = emptyList())
            UNCONFIRMED -> return TapResponse(Outcomes.ALREADY, toast = "Submit was already clicked — check the viewer.", actionRow = emptyList())
            EXPIRED, CANCELLED, FAILED -> return gone("This application is no longer open. Tap Apply on the card to start again.")
        }
        if (fill.status != Outcomes.AWAITING_APPROVAL) return TapResponse(Outcomes.REFUSED, toast = "Not ready to submit yet.")
        if (Duration.ofMillis(clock.millis() - readyAt(fill)) > reviewTtl) return expire(fill.id, "The review window (${reviewTtl.toHours()} h) has passed.")
        val page = pages[id] ?: return gone("The browser tab for this application is gone (JobBot restarted?). Tap Apply on the card to fill it again.")

        val snap = page.snapshot()
        val approved = JSON.parseToJsonElement(fill.details ?: "{}").jsonObject["fingerprint"]?.jsonPrimitive?.content
        if (approved != fingerprint(FillAgent.fieldsOf(snap))) {
            // Someone (Richard in the viewer, or the site) changed the form since the review card.
            val ready = FillResult.Ready(FillAgent.fieldsOf(snap), page.screenshot(), "The form changed since the last review.")
            store.update(id, SUPERSEDED)
            postReview(store.insert(FILL, fill.jobKey, fill.seq, Outcomes.AWAITING_APPROVAL, fill.chatId, null, reviewDetails(ready)).also { pages[it.id] = page; pages.remove(id) }.id, fill.seq, ready)
            return TapResponse(Outcomes.REFUSED, toast = "The form changed — nothing submitted. A fresh review is below.", actionRow = emptyList())
        }
        val submits = snap.elements.filter { (it.kind == "button" || it.kind == "input") && SitePolicy.isSubmitControl(it.text.ifBlank { it.label }, it.type, it.formFilled) }
        if (submits.size != 1) {
            return TapResponse(
                Outcomes.REFUSED,
                toast = if (submits.isEmpty()) "I can't find the submit button — finish it in the viewer." else "More than one submit button — finish it in the viewer.",
            )
        }
        store.update(id, SUBMITTING)
        page.click(submits.single().id)
        page.settle()
        val after = page.snapshot()
        val shot = runCatching { page.screenshot() }.getOrNull()
        // Only a confirmation message counts. A URL change alone could be a login redirect or an
        // error page, and marking the track "applied" on that would be a false record.
        if (!CONFIRMATION.containsMatchIn(after.text)) {
            store.update(id, UNCONFIRMED, buildJsonObject { put("submitted_url", after.url) }.toString())
            outbox.post(
                "I clicked Submit for ${JobRef.format(fill.seq)} but didn't see a confirmation. The tab is still open — " +
                    "check it${viewerUrl?.let { " in the viewer: $it" } ?: " in the viewer"}. The track was not changed.",
                photo = shot,
            )
            return TapResponse(Outcomes.DONE, toast = "Clicked Submit — couldn't confirm. Check the viewer.", actionRow = emptyList())
        }
        store.update(id, Outcomes.DONE, buildJsonObject { put("submitted_url", after.url); put("confirmed", true) }.toString())
        pages.remove(id)?.close()
        // A confirmed submission proves the account works: a password created for this site is now active.
        listOfNotNull(Credentials.siteKey(snap.url), Credentials.siteKey(after.url)).distinct().forEach { site ->
            if (credentials?.get(site)?.status == Credentials.PENDING) credentials.activate(site)
        }
        recordApplied(fill.seq)
        outbox.post("Submitted: ${JobRef.format(fill.seq)}.", photo = shot)
        return TapResponse(Outcomes.DONE, toast = "Submitted.", actionRow = emptyList())
    }

    /** ✖ Discard: close the tab; nothing was submitted. */
    fun discard(id: Long, req: TapRequest): TapResponse {
        val fill = store.byId(id)?.takeIf { it.verb == FILL } ?: return gone("Unknown application.")
        if (fill.status == Outcomes.DONE) return TapResponse(Outcomes.ALREADY, toast = "Already submitted.", actionRow = emptyList())
        store.update(id, CANCELLED)
        pages.remove(id)?.close()
        return TapResponse(Outcomes.DONE, toast = "Discarded. Nothing was submitted.", actionRow = emptyList())
    }

    /** ▶ Continue after Richard finished a hand-off step in the viewer. */
    fun resume(id: Long, req: TapRequest): TapResponse {
        val fill = store.byId(id)?.takeIf { it.verb == FILL } ?: return gone("Unknown application.")
        if (fill.status != NEEDS_HUMAN) return TapResponse(Outcomes.REFUSED, toast = "Nothing to continue.")
        if (pages[id] == null) return expire(id, "The browser tab for this application is gone (JobBot restarted?).")
        store.update(id, QUEUED)
        start(id, fresh = false)
        return TapResponse(Outcomes.DONE, toast = "Continuing…", actionRow = emptyList())
    }

    /** Remind at 3 h, expire at 4 h. Called every minute. */
    fun sweep() {
        store.recent(200).filter { it.verb == FILL && it.status == Outcomes.AWAITING_APPROVAL }.forEach { fill ->
            val age = Duration.ofMillis(clock.millis() - readyAt(fill))
            val details = runCatching { JSON.parseToJsonElement(fill.details ?: "{}").jsonObject }.getOrNull()
            when {
                age > reviewTtl -> {
                    expire(fill.id, "")
                    outbox.post("The filled application for ${JobRef.format(fill.seq)} expired after ${reviewTtl.toHours()} h without a decision. Nothing was submitted.")
                }
                age > reminderAfter && details?.get("reminded") == null -> {
                    store.update(fill.id, fill.status, buildJsonObject { details?.forEach { (k, v) -> put(k, v) }; put("reminded", true) }.toString())
                    outbox.post(
                        "Reminder: the application for ${JobRef.format(fill.seq)} is filled and waiting. It expires in ${(reviewTtl - age).toMinutes()} min.",
                        listOf(InlineButton("✅ Submit", "submit:${fill.id}"), InlineButton("✖ Discard", "discard:${fill.id}")),
                    )
                }
            }
        }
    }

    private fun start(id: Long, fresh: Boolean) {
        running += id
        worker.submit { try { runFill(id, fresh) } finally { running -= id } }
    }

    /** An open fill this process can no longer act on: no live worker, or no tab to continue or submit. */
    private fun isOrphan(fill: ActionStore.Action): Boolean = when (fill.status) {
        QUEUED, FILLING -> fill.id !in running
        NEEDS_HUMAN, Outcomes.AWAITING_APPROVAL -> fill.id !in running && pages[fill.id] == null
        else -> false
    }

    internal fun runFill(id: Long, fresh: Boolean) {
        val fill = store.byId(id) ?: return
        val job = contextFor(fill.seq)
        if (job == null) {
            store.update(id, FAILED); outbox.post("Couldn't start ${JobRef.format(fill.seq)}: no posting URL or job data.")
            return
        }
        store.update(id, FILLING)
        val page = pages[id] ?: runCatching { browser.open { url -> SitePolicy.navigationAllowed(url, job.jobUrl, job.company) } }
            .getOrElse {
                store.update(id, FAILED)
                outbox.post("Couldn't open the apply browser for ${job.ref}: ${it.message}")
                return
            }.also { pages[id] = it }
        val result = runCatching { fill(page, job, fresh) }
            .getOrElse { FillResult.Failed("${it.javaClass.simpleName}: ${it.message}", null) }
        // Discarded (or expired) while the fill ran: never resurrect it with a review.
        if (store.byId(id)?.status in setOf(CANCELLED, EXPIRED, SUPERSEDED)) {
            pages.remove(id)?.close()
            return
        }
        when (result) {
            is FillResult.Ready -> {
                store.update(id, Outcomes.AWAITING_APPROVAL, reviewDetails(result))
                postReview(id, fill.seq, result)
            }
            is FillResult.NeedsHuman -> {
                store.update(id, NEEDS_HUMAN)
                outbox.post(
                    "${job.ref} needs you: ${result.reason}" + (viewerUrl?.let { "\nOpen the apply browser: $it" } ?: "") +
                        "\nTap ▶ Continue when you're done there.",
                    listOf(InlineButton("▶ Continue", "resume:$id"), InlineButton("✖ Discard", "discard:$id")),
                    result.screenshot,
                )
            }
            is FillResult.Failed -> {
                store.update(id, FAILED)
                store.recordError("fill ${job.ref}", result.reason)
                pages.remove(id)?.close()
                outbox.post("Couldn't fill ${job.ref}: ${result.reason}", photo = result.screenshot)
            }
        }
    }

    private fun postReview(id: Long, seq: Long, ready: FillResult.Ready) {
        val fields = ready.fields.joinToString("\n") { (k, v) -> "• $k: $v" }.ifBlank { "(no fields read)" }
        outbox.post(
            "Ready to submit ${JobRef.format(seq)} — review every field. Tap ✅ Submit to submit exactly this " +
                "(within ${reviewTtl.toHours()} h).${ready.note.takeIf { it.isNotBlank() }?.let { "\nNote: $it" } ?: ""}\n\n$fields" +
                (viewerUrl?.let { "\n\nViewer: $it" } ?: ""),
            listOf(InlineButton("✅ Submit", "submit:$id"), InlineButton("✖ Discard", "discard:$id")),
            ready.screenshot,
        )
    }

    private fun reviewDetails(ready: FillResult.Ready) = buildJsonObject {
        put("fingerprint", fingerprint(ready.fields))
        put("fields", ready.fields.size)
        put("ready_at", clock.millis())
    }.toString()

    /** When the form became ready for review — the review window's start (updates must not reset it). */
    private fun readyAt(fill: ActionStore.Action): Long =
        runCatching { JSON.parseToJsonElement(fill.details ?: "{}").jsonObject["ready_at"]?.jsonPrimitive?.content?.toLong() }
            .getOrNull() ?: fill.updatedAt

    private fun alreadyApplied(ctx: TapContext): TapResponse? {
        val status = runCatching { lookup.find(ctx.request.seq)?.track?.str("status") }.getOrNull()
        return if (status == "applied") TapResponse(Outcomes.ALREADY, toast = "Already applied (the track says so).") else null
    }

    private fun recordApplied(seq: Long) {
        val writer = tracks ?: return
        runCatching {
            val found = lookup.find(seq) ?: return
            if (found.trackMatch !in setOf("track_id", "artifact_url")) return
            val trackId = found.track?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: return
            writer.setStatus(trackId, "applied")
            writer.addEvent(trackId, "application_submitted", "Submitted from Telegram", buildJsonObject { put("ref", JobRef.format(seq)) })
        }.onFailure { store.recordError("track applied ${JobRef.format(seq)}", it.message ?: "") }
    }

    private fun expire(id: Long, why: String): TapResponse {
        store.update(id, EXPIRED)
        pages.remove(id)?.close()
        return gone("$why Nothing was submitted. Tap Apply on the card to fill it again.".trim())
    }

    private fun gone(toast: String) = TapResponse(Outcomes.REFUSED, toast = toast, actionRow = emptyList())

    companion object {
        const val FILL = "fill"
        const val QUEUED = "queued"
        const val FILLING = "filling"
        const val NEEDS_HUMAN = "needs_human"
        const val SUBMITTING = "submitting"
        /** Submit was clicked but no confirmation appeared: Richard checks the tab. */
        const val UNCONFIRMED = "submitted_unconfirmed"
        const val EXPIRED = "expired"
        const val CANCELLED = "cancelled"
        const val SUPERSEDED = "superseded"
        const val FAILED = "failed"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val CONFIRMATION = Regex("""(?i)thank you|application (has been )?(received|submitted)|we('ve| have) received|successfully submitted""")

        fun fingerprint(fields: List<Pair<String, String>>): String =
            MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\u0000") { "${it.first}\u0001${it.second}" }.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
