package com.jd.pipeline.cli

import com.jd.pipeline.config.Config
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CommandParser] — pure logic, no I/O beyond temp files.
 */
@DisplayName("CommandParserTest")
class CommandParserTest {

    // ── single flags ─────────────────────────────────────────────────────────

    @Test
    fun testFlag()   = assertEquals(Command.Test, parse("--test"))
    @Test
    fun testResumeFlag() = assertEquals(Command.TestResume, parse("--test-resume"))
    @Test
    fun testCoverLetterFlag() = assertEquals(Command.TestCoverLetter, parse("--test-coverletter"))
    @Test
    fun testSupabaseFlag() = assertEquals(Command.TestSupabase, parse("--test-supabase"))
    @Test
    fun testGmailFlag() = assertEquals(Command.TestGmail, parse("--test-gmail"))
    @Test
    fun reauthFlag() = assertEquals(Command.Reauth, parse("--reauth"))
    @Test
    fun checkTokenFlag() = assertEquals(Command.CheckToken, parse("--check-token"))
    @Test
    fun jsearchFlag() = assertEquals(Command.JSearch, parse("--jsearch"))
    @Test
    fun signedInFlag() = assertEquals(Command.SignedIn, parse("--signed-in"))

    @Test
    fun resumeGenFlag() {
        val cmd = parse("--resume-gen", "path/to/file.docx")
        assertTrue(cmd is Command.ResumeGen)
        assertEquals("path/to/file.docx", cmd.path)
    }

    @Test
    fun initProfileFlag() {
        val cmd = parse("--init-profile", "path/to/file.pdf")
        assertTrue(cmd is Command.InitProfile)
        assertEquals("path/to/file.pdf", cmd.path)
    }

    // ── batch / single email ─────────────────────────────────────────────────

    @Test
    fun noArgsIsBatch() {
        val cmd = parse()
        assertTrue(cmd is Command.Batch)
        assertEquals(Config.GMAIL_MAX_EMAILS, cmd.maxEmails)
        assertFalse(cmd.debug)
    }

    @Test
    fun emailFlag() {
        val cmd = parse("--email", "user@test.com")
        assertTrue(cmd is Command.SingleEmail)
        assertEquals("user@test.com", cmd.subject)
        assertNull(cmd.expectedData)
        assertEquals(5, cmd.maxIterations)
        assertFalse(cmd.debug)
    }

    @Test
    fun emailWithExpectedData() {
        val cmd = parse("--email", "user@test.com", "--expected-data", "foo")
        assertTrue(cmd is Command.SingleEmail)
        assertEquals("user@test.com", cmd.subject)
        assertEquals("foo", cmd.expectedData)
    }

    @Test
    fun emailWithDebug() {
        val cmd = parse("--email", "user@test.com", "--debug")
        assertTrue(cmd is Command.SingleEmail)
        assertTrue(cmd.debug)
    }

    @Test
    fun maxEmailsFlag() {
        val cmd = parse("--max-emails", "10")
        assertTrue(cmd is Command.Batch)
        assertEquals(10, cmd.maxEmails)
    }

    @Test
    fun maxEmailFlagAlias() {
        val cmd = parse("--max-email", "7")
        assertTrue(cmd is Command.Batch)
        assertEquals(7, cmd.maxEmails)
    }

    // ── tuner flags ───────────────────────────────────────────────────────────

    @Test
    fun scanTunerFlag() {
        val cmd = parse("--scantuner")
        assertTrue(cmd is Command.ScanTuner)
        assertNull(cmd.file)
        assertEquals(5, cmd.maxIterations)
        assertFalse(cmd.debug)
    }

    @Test
    fun scanTunerWithFile() {
        val cmd = parse("--scantuner", "scan_data.json", "--max-iterations", "3")
        assertTrue(cmd is Command.ScanTuner)
        assertEquals("scan_data.json", cmd.file)
        assertEquals(3, cmd.maxIterations)
    }

    @Test
    fun scanTunerWithDebug() {
        val cmd = parse("--scantuner", "--debug")
        assertTrue(cmd is Command.ScanTuner)
        assertTrue(cmd.debug)
    }

    @Test
    fun scrapeJdTunerFlag() {
        val cmd = parse("--scrapetuner")
        assertTrue(cmd is Command.ScrapeJdTuner)
        assertNull(cmd.file)
        assertEquals(5, cmd.maxIterations)
    }

    @Test
    fun scrapeJdTunerWithFile() {
        val cmd = parse("--scrapetuner", "jd_data.json", "--max-iterations", "8")
        assertTrue(cmd is Command.ScrapeJdTuner)
        assertEquals("jd_data.json", cmd.file)
        assertEquals(8, cmd.maxIterations)
    }

    // ── priority / precedence ─────────────────────────────────────────────────

    @Test
    fun multipleTestFlags_testWinsOverTestResume() {
        // The when block checks `test` before `testResume`, so `test` always wins when both are present.
        assertEquals(Command.Test, parse("--test", "--test-resume"))
        assertEquals(Command.Test, parse("--test-resume", "--test"))
    }

    @Test
    fun priorityOrder_testResumeOverTestCoverLetter() {
        assertEquals(Command.TestResume, parse("--test-coverletter", "--test-resume"))
    }

    @Test
    fun priorityOrder_testCoverLetterOverTestSupabase() {
        assertEquals(Command.TestCoverLetter, parse("--test-supabase", "--test-coverletter"))
    }

    @Test
    fun priorityOrder_testSupabaseOverReauth() {
        assertEquals(Command.TestSupabase, parse("--reauth", "--test-supabase"))
    }

    @Test
    fun priorityOrder_reauthOverCheckToken() {
        assertEquals(Command.Reauth, parse("--check-token", "--reauth"))
    }

    @Test
    fun priorityOrder_checkTokenOverScanTuner() {
        assertEquals(Command.CheckToken, parse("--scantuner", "--check-token"))
    }

    @Test
    fun priorityOrder_scanTunerOverScrapeJdTuner() {
        assertEquals(Command.ScanTuner(null, 5, false), parse("--scrapetuner", "--scantuner"))
    }

    @Test
    fun priorityOrder_scrapeJdTunerOverResumeGen() {
        val cmd = parse("--resume-gen", "resume.docx", "--scrapetuner")
        assertTrue(cmd is Command.ScrapeJdTuner)
    }

    @Test
    fun priorityOrder_resumeGenOverInitProfile() {
        val cmd = parse("--init-profile", "profile.pdf", "--resume-gen", "resume.docx")
        assertTrue(cmd is Command.ResumeGen)
        assertEquals("resume.docx", cmd.path)
    }

    @Test
    fun priorityOrder_emailOverSignedIn() {
        val cmd = parse("--signed-in", "--email", "a@b.com")
        assertTrue(cmd is Command.SingleEmail)
    }

    @Test
    fun priorityOrder_signedInOverJSearch() {
        val cmd = parse("--jsearch", "--signed-in")
        assertEquals(Command.SignedIn, cmd)
    }

    @Test
    fun priorityOrder_jSearchOverBatch() {
        val cmd = parse("--max-emails", "3", "--jsearch")
        assertEquals(Command.JSearch, cmd)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parse(vararg args: String): Command = CommandParser.parse(args.toList().toTypedArray())
}
