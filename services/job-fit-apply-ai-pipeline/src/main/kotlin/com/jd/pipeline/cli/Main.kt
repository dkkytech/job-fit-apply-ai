package com.jd.pipeline.cli

import com.jd.pipeline.cli.commands.*
import com.jd.pipeline.client.AlertService

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val command = CommandParser.parse(args)
        // --health is a container healthcheck probe — no banner, exit code is the contract.
        if (command == Command.Health) {
            runHealthCheck()
            return
        }
        CliOutput.printBanner()
        dispatch(command)
    }

    /** Exit 0 iff the processor loop's heartbeat is fresher than HEALTH_MAX_AGE_MIN. */
    private fun runHealthCheck() {
        val heartbeat = com.jd.pipeline.utils.Heartbeat.fromConfig(com.jd.pipeline.config.Config.HEARTBEAT_FILE)
        val maxAgeMs = com.jd.pipeline.config.Config.HEALTH_MAX_AGE_MIN * 60_000
        if (heartbeat.isFresh(maxAgeMs, System.currentTimeMillis())) {
            println("[health] ok")
        } else {
            System.err.println("[health] stale/missing heartbeat at ${com.jd.pipeline.config.Config.HEARTBEAT_FILE}")
            kotlin.system.exitProcess(1)
        }
    }

    private fun dispatch(command: Command) {
        when (command) {
            Command.Health -> Unit // handled in main() before the banner
            Command.Test -> TestCommandHandler.run()
            Command.TestResume -> TestResumeCommandHandler.run()
            Command.TestCoverLetter -> TestCoverLetterCommandHandler.run()
            Command.TestSupabase -> TestSupabaseCommandHandler.run()
            is Command.TestChrome -> TestChromeCommandHandler.run(command)
            is Command.TestSteel -> TestSteelCommandHandler.run(command)
            is Command.SteelSignin -> SteelSigninCommandHandler.run(command)
            is Command.ScrapeJdTuner -> ScrapeJdTunerCommandHandler.run(command)
            is Command.ResumeGen -> ResumeGenCommandHandler.run(command)
            is Command.InitProfile -> InitProfileCommandHandler.run(command)
            Command.SignedIn -> SignedInCommandHandler.run()
            Command.Processor -> ProcessorCommandHandler.run()
            is Command.NotifyTimeout -> AlertService().pipelineTimeout(command.minutes)
            Command.Usage -> printUsage()
        }
    }

    private fun printUsage() {
        // Gmail intake/write-back lives in the Poller service (Phase 1). The Processor is
        // Gmail-free: it only claims work items from the bridge and processes them.
        println(
            """
            Usage: pipeline <command>

            Long-running:
              --processor              Claim work items from the bridge and process them (scan/scrape/score/tailor)
              --health                 Exit 0 if the processor loop is alive (container healthcheck)

            One-shot / dev:
              --resume-gen <path>      Generate a tailored resume from a JD file
              --init-profile <path>    Scaffold a candidate profile
              --scrapetuner [file]     Tune the JD scraper
              --signed-in              Report signed-in scraping status
              --test, --test-resume, --test-coverletter, --test-supabase, --test-chrome [url], --test-steel [url]
              --steel-signin [site]    Open a Steel session parked on <site>'s login page, print the phone
                                       debug URL, and capture cookies automatically until you're signed in.
                                       Accepts a site name (linkedin), a host, or a full URL. No TTY needed.
              --notify-timeout <min>   Send a pipeline-timeout alert

            Note: email intake, drafts, and Gmail labeling are handled by the Poller service.
            """.trimIndent()
        )
    }
}
