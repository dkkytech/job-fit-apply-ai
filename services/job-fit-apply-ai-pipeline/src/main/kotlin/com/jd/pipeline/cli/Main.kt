package com.jd.pipeline.cli

import com.jd.pipeline.cli.commands.*

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        CliOutput.printBanner()
        val command = CommandParser.parse(args)
        dispatch(command)
    }

    private fun dispatch(command: Command) {
        when (command) {
            Command.Test -> TestCommandHandler.run()
            Command.TestResume -> TestResumeCommandHandler.run()
            Command.TestCoverLetter -> TestCoverLetterCommandHandler.run()
            Command.TestSupabase -> TestSupabaseCommandHandler.run()
            Command.TestGmail -> TestGmailCommandHandler.run()
            Command.Reauth -> ReauthCommandHandler.run()
            Command.CheckToken -> CheckTokenCommandHandler.run()
            is Command.ScanTuner -> ScanTunerCommandHandler.run(command)
            is Command.ScrapeJdTuner -> ScrapeJdTunerCommandHandler.run(command)
            is Command.ResumeGen -> ResumeGenCommandHandler.run(command)
            is Command.InitProfile -> InitProfileCommandHandler.run(command)
            is Command.SingleEmail -> SingleEmailCommandHandler.run(command)
            Command.SignedIn -> SignedInCommandHandler.run()
            Command.JSearch -> JSearchCommandHandler.run()
            is Command.TokenFromUrl -> TokenFromUrlCommandHandler.run(command)
            is Command.Batch -> BatchCommandHandler.run(command)
            Command.Worker -> WorkerCommandHandler.run()
        }
    }
}
