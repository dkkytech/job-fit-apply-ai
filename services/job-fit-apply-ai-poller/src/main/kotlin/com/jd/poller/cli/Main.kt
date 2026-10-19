package com.jd.poller.cli

import com.jd.poller.config.PollerConfig
import com.jd.poller.gmail.GmailAuth
import com.jd.poller.health.Heartbeat

/**
 * Poller entrypoint. Owns ALL Gmail interaction for the system (Phase 1):
 *   --poll            run the intake + write-back loops (the long-running service)
 *   --health          exit 0 if the loops are alive (fresh heartbeat) and Gmail auth is not
 *                     known dead — container healthcheck
 *   --reauth          browser-free OAuth: print URL, paste the redirect URL back
 *   --check-token     report token status (exit 0 = VALID)
 *   --token-from-url  headless OAuth: exchange a pasted redirect URL for a token
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        when (val cmd = parse(args)) {
            is PollerCommand.Poll -> PollerService().run()
            is PollerCommand.Health -> {
                val problem = healthProblem()
                if (problem == null) println("[health] ok")
                else {
                    System.err.println("[health] $problem")
                    kotlin.system.exitProcess(1)
                }
            }
            is PollerCommand.Reauth -> {
                GmailAuth.deleteToken()
                GmailAuth.generateToken()
            }
            is PollerCommand.CheckToken -> {
                val result = GmailAuth.checkTokenStatus()
                println("[check-token] ${result.status}: ${result.message}")
                result.emailAddress?.let { println("[check-token] account: $it") }
                if (result.status != GmailAuth.TokenStatus.VALID) kotlin.system.exitProcess(1)
            }
            is PollerCommand.TokenFromUrl -> {
                val ok = GmailAuth.exchangeRedirectUrlForToken(cmd.redirectUrl)
                if (!ok) kotlin.system.exitProcess(1)
            }
            is PollerCommand.Usage -> printUsage()
        }
    }

    /** Container healthcheck: healthy iff the heartbeat is fresh and Gmail auth is not known dead. */
    internal fun healthy(now: Long = System.currentTimeMillis()): Boolean = healthProblem(now = now) == null

    /** Why the poller is unhealthy, or null when it is healthy. */
    internal fun healthProblem(
        heartbeat: Heartbeat = Heartbeat.fromConfig(PollerConfig.HEARTBEAT_FILE),
        maxAgeMs: Long = PollerConfig.HEALTH_MAX_AGE_MS,
        now: Long = System.currentTimeMillis(),
    ): String? {
        heartbeat.authFailure()?.let { return it }
        if (!heartbeat.isFresh(maxAgeMs, now)) return "stale/missing heartbeat at ${PollerConfig.HEARTBEAT_FILE}"
        return null
    }

    internal fun parse(args: Array<String>): PollerCommand {
        var i = 0
        var redirectUrl: String? = null
        var poll = false
        var health = false
        var reauth = false
        var checkToken = false
        while (i < args.size) {
            when (args[i]) {
                "--poll" -> poll = true
                "--health" -> health = true
                "--reauth" -> reauth = true
                "--check-token" -> checkToken = true
                "--token-from-url" -> {
                    if (i + 1 < args.size) { redirectUrl = args[i + 1]; i++ }
                }
            }
            i++
        }
        return when {
            redirectUrl != null -> PollerCommand.TokenFromUrl(redirectUrl)
            reauth -> PollerCommand.Reauth
            checkToken -> PollerCommand.CheckToken
            health -> PollerCommand.Health
            poll -> PollerCommand.Poll
            else -> PollerCommand.Usage
        }
    }

    private fun printUsage() {
        println(
            """
            Usage: poller <command>

              --poll                 Run the intake + write-back loops (long-running service)
              --health               Exit 0 if the loops are alive and Gmail auth is OK (container healthcheck)
              --reauth               Browser-free OAuth: print URL, paste the redirect URL back
              --check-token          Report Gmail token status (exit 0 = VALID)
              --token-from-url <url> Headless OAuth: exchange a pasted redirect URL for a token
            """.trimIndent()
        )
    }
}

sealed interface PollerCommand {
    object Poll : PollerCommand
    object Health : PollerCommand
    object Reauth : PollerCommand
    object CheckToken : PollerCommand
    data class TokenFromUrl(val redirectUrl: String) : PollerCommand
    object Usage : PollerCommand
}
