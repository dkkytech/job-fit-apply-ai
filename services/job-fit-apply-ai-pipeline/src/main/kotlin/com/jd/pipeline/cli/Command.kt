package com.jd.pipeline.cli

sealed class Command {
    object Test : Command()
    object TestResume : Command()
    object TestCoverLetter : Command()
    object TestSupabase : Command()
    object TestGmail : Command()
    object Reauth : Command()
    object CheckToken : Command()
    data class ScanTuner(val file: String?, val maxIterations: Int, val debug: Boolean) : Command()
    data class ScrapeJdTuner(val file: String?, val maxIterations: Int) : Command()
    data class ResumeGen(val path: String) : Command()
    data class InitProfile(val path: String) : Command()
    data class SingleEmail(
        val subject: String,
        val expectedData: String?,
        val expectedDataFile: String?,
        val maxIterations: Int,
        val debug: Boolean,
    ) : Command()
    object SignedIn : Command()
    object JSearch : Command()
    data class Batch(val maxEmails: Int, val debug: Boolean) : Command()
    data class TokenFromUrl(val redirectUrl: String) : Command()
    object Worker : Command()
}
