package com.jd.jobbot.cli

import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.IdVerbHandler
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.apply.ApplyService
import com.jd.jobbot.apply.Credentials
import com.jd.jobbot.apply.FillAgent
import com.jd.jobbot.apply.JobContext
import com.jd.jobbot.apply.PlaywrightApplyBrowser
import com.jd.jobbot.apply.Verifier
import com.jd.jobbot.bridge.str
import com.jd.jobbot.jobs.JobRef
import com.jd.jobbot.llm.OpenAiCompatibleLlm
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.jd.jobbot.actions.ArchiveSupport
import com.jd.jobbot.actions.ReplySupport
import com.jd.jobbot.actions.TailorSupport
import com.jd.jobbot.templates.AlertTemplates
import com.jd.jobbot.jobs.JobIdentity
import com.jd.jobbot.actions.VerbHandler
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.bridge.TailorRequester
import com.jd.jobbot.gmail.GmailAuth
import com.jd.jobbot.gmail.GmailClient
import com.jd.jobbot.actions.ApplyNotImplemented
import com.jd.jobbot.actions.StatusReport
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.config.Config
import com.jd.jobbot.files.OutputFiles
import com.jd.jobbot.http.jobbotModule
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.mcp.JobbotTools
import com.jd.jobbot.profile.ProfileReader
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Duration

fun main() {
    val log = LoggerFactory.getLogger("jobbot-mcp")
    if (Config.API_TOKEN.isBlank()) log.error("JOBBOT_MCP_TOKEN is blank — every /plugin and /mcp request will be refused")
    if (Config.ALLOWED_USERS.isEmpty()) log.error("JOBBOT_TELEGRAM_ALLOWED_USERS is blank — every tap will be refused")

    val bridge = BridgeReadClient(Config.BRIDGE_URL)
    val lookup = JobLookup(bridge)
    val store = ActionStore(Paths.get(Config.STATE_DIR, "actions.db").toString())
    val tracks = TrackWriter(Config.BRIDGE_URL)
    val gmailAuth = if (Config.GMAIL_TOKEN_FILE.isNotBlank() && Config.GMAIL_CREDENTIALS_FILE.isNotBlank()) {
        GmailAuth(Paths.get(Config.GMAIL_TOKEN_FILE), Paths.get(Config.GMAIL_CREDENTIALS_FILE))
    } else {
        log.warn("Gmail not configured (JOBBOT_GMAIL_TOKEN_FILE / JOBBOT_GMAIL_CREDENTIALS_FILE) — archive/undo and get_job_email are off")
        null
    }
    val gmail = gmailAuth?.let { GmailClient(it) }
    val replies = gmail?.let { ReplySupport(it, lookup, bridge, tracks, store, Config.SEND_ENABLED, Config.MAX_SENDS_PER_DAY) }
    val outbox = Outbox(Paths.get(Config.STATE_DIR, "outbox.db").toString(), Paths.get(Config.STATE_DIR, "screenshots"))
    val files = OutputFiles(Config.OUTPUT_ROOT)
    val profile = ProfileReader(Config.RESUME_YAML, Config.CANDIDATE_PROFILE_YAML)
    val credentials = Credentials(Paths.get(Config.CREDENTIALS_FILE), Config.ACCOUNT_EMAIL)
    val applyService = if (Config.APPLY_ENABLED) {
        ApplyService(
            browser = PlaywrightApplyBrowser(Config.BROWSER_CDP_URL),
            fill = FillAgent(OpenAiCompatibleLlm(Config.LLM_URL, Config.FILL_MODEL), credentials, gmail?.let { Verifier(it) })::run,
            lookup = lookup,
            contextFor = { seq -> jobContext(seq, lookup, bridge, files, profile) },
            store = store,
            outbox = outbox,
            tracks = tracks,
            viewerUrl = Config.BROWSER_VIEWER_URL.ifBlank { null },
            credentials = credentials,
            reviewTtl = Duration.ofHours(Config.APPLY_REVIEW_TTL_HOURS),
            reminderAfter = Duration.ofHours(Config.APPLY_REMINDER_HOURS),
        ).also { svc ->
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "apply-sweep").apply { isDaemon = true } }
                .scheduleAtFixedRate({ runCatching { svc.sweep() }.onFailure { log.warn("apply sweep failed: {}", it.message) } }, 1, 1, TimeUnit.MINUTES)
        }
    } else {
        null
    }
    val handlers = buildMap<String, VerbHandler> {
        put("apply", applyService?.apply ?: ApplyNotImplemented)
        gmail?.let {
            val archive = ArchiveSupport(it, lookup, tracks, Config.HANDLED_VERBS, store)
            put("archive", archive.archive)
            put("undo", archive.undo)
        }
        replies?.let { put("reply", it.reply) }
        put("tailor", TailorSupport(TailorRequester(Config.BRIDGE_URL), Config.HANDLED_VERBS).tailor)
    }
    val taps = TapService(
        lookup = lookup,
        store = store,
        handlers = handlers,
        allowedUsers = Config.ALLOWED_USERS,
        handledVerbs = Config.HANDLED_VERBS,
        dryRun = Config.DRY_RUN,
        cardTtl = Duration.ofDays(Config.CARD_TTL_DAYS),
        approvals = replies,
        idHandlers = applyService?.let {
            mapOf("submit" to IdVerbHandler(it::submit), "discard" to IdVerbHandler(it::discard), "resume" to IdVerbHandler(it::resume))
        } ?: emptyMap(),
    )
    val status = StatusReport(bridge, store, Config.HANDLED_VERBS, Config.DRY_RUN, gmailAuth)
    val tools = JobbotTools(
        lookup = lookup,
        bridge = bridge,
        files = files,
        profile = profile,
        fitThreshold = Config.FIT_THRESHOLD,
        highFitScan = Config.HIGH_FIT_SCAN,
        tracks = tracks,
        gmail = gmail,
        replies = replies,
        jobKeyOf = { seq -> lookup.event(seq)?.let { JobIdentity.of(it) } },
        templates = Config.TEMPLATE_FILE.takeIf { it.isNotBlank() }?.let { AlertTemplates(Paths.get(it)) },
    )
    log.info("jobbot-mcp on :{} (dry_run={}, verbs={})", Config.PORT, Config.DRY_RUN, Config.HANDLED_VERBS)
    embeddedServer(CIO, port = Config.PORT) {
        jobbotModule(
            Config.API_TOKEN, Config.ALLOWED_HOSTS, taps, status, tools,
            approvals = { id -> replies?.approvalPrompt(id) },
            outbox = outbox,
            accounts = if (Config.APPLY_ENABLED) ({ accountsText(credentials) }) else null,
        )
    }.start(wait = true)
}

/** Everything the fill loop needs about one job; null when there is no posting URL to apply at. */
internal fun jobContext(seq: Long, lookup: JobLookup, bridge: BridgeReadClient, files: OutputFiles, profile: ProfileReader): JobContext? {
    val e = lookup.event(seq) ?: return null
    val url = e.str("job_url") ?: return null
    val artifact = e.str("artifact_url")
    fun file(name: String) = (files.read(artifact, name) as? OutputFiles.Result.Text)?.text
    val p = profile.read()
    return JobContext(
        ref = JobRef.format(seq), company = e.str("company"), title = e.str("role_title"), jobUrl = url,
        accountEmail = Config.ACCOUNT_EMAIL,
        profileYaml = listOfNotNull(p.candidateProfileYaml, p.resumeYaml).joinToString("\n---\n").ifBlank { null },
        resumeYaml = file("tailored_resume.yaml"),
        coverLetter = file("cover_letter.txt"),
        resumePdf = e.str("job_id")?.let { runCatching { bridge.resumePdf(it) }.getOrNull() },
    )
}

/** /jdaccounts: every site JobBot has an account on — never a password. */
internal fun accountsText(credentials: Credentials): String {
    val all = credentials.list()
    if (all.isEmpty()) return "JobBot hasn't created any site accounts yet."
    return "Site accounts (${all.size}):\n" + all.entries.joinToString("\n") { (site, e) ->
        "• $site — ${if (e.method == "google") "Sign in with Google" else "password"} (${e.status})" +
            (e.created_for?.let { " for $it" } ?: "") + ", ${e.created_at.take(10)}"
    }
}

