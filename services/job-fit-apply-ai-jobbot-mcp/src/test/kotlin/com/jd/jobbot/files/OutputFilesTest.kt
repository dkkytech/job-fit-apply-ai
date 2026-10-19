package com.jd.jobbot.files

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutputFilesTest {
    @TempDir lateinit var root: Path

    private fun job(folder: String, vararg files: Pair<String, String>): String {
        val dir = Files.createDirectories(root.resolve(folder))
        files.forEach { (name, text) -> Files.writeString(dir.resolve(name), text) }
        return "http://markserv:8081/$folder/"
    }

    @Test
    fun `reads an allowlisted file from the job's folder`() {
        val url = job("20261001_acme", "report.md" to "# Report")
        val r = OutputFiles(root.toString()).read(url, "report.md")
        assertIs<OutputFiles.Result.Text>(r)
        assertEquals("# Report", r.text)
    }

    @Test
    fun `lists only allowlisted files that exist`() {
        val url = job("20261001_acme", "report.md" to "x", "score_fit.txt" to "y", "secret.env" to "z")
        assertEquals(listOf("report.md", "score_fit.txt"), OutputFiles(root.toString()).available(url))
    }

    @Test
    fun `refuses names off the allowlist, including traversal attempts`() {
        val url = job("20261001_acme", "report.md" to "x")
        val files = OutputFiles(root.toString())
        listOf("../../etc/passwd", "secret.env", "Richard.pdf", "report.md/../x").forEach {
            assertIs<OutputFiles.Result.Missing>(files.read(url, it), it)
        }
    }

    @Test
    fun `refuses folders that would escape the root`() {
        Files.writeString(root.resolveSibling("report.md"), "outside")
        val files = OutputFiles(root.toString())
        listOf("http://m/../", "http://m/..", "http://m/%2e%2e/", "http://m/a%2Fb/").forEach {
            assertIs<OutputFiles.Result.Missing>(files.read(it, "report.md"), it)
        }
    }

    @Test
    fun `a symlink out of the root is not followed`() {
        val outside = Files.createTempDirectory("outside")
        Files.writeString(outside.resolve("report.md"), "outside secret")
        Files.createSymbolicLink(root.resolve("20261001_link"), outside)
        val files = OutputFiles(root.toString())
        assertIs<OutputFiles.Result.Missing>(files.read("http://m/20261001_link/", "report.md"))
        assertEquals(emptyList(), files.available("http://m/20261001_link/"), "listing must agree with reading")
    }

    @Test
    fun `a symlinked file out of the root is neither listed nor read`() {
        val outside = Files.createTempFile("secret", ".md")
        val dir = Files.createDirectories(root.resolve("20261001_mixed"))
        Files.writeString(dir.resolve("score_fit.txt"), "80")
        Files.createSymbolicLink(dir.resolve("report.md"), outside)
        val files = OutputFiles(root.toString())
        assertEquals(listOf("score_fit.txt"), files.available("http://m/20261001_mixed/"))
        assertIs<OutputFiles.Result.Missing>(files.read("http://m/20261001_mixed/", "report.md"))
    }

    @Test
    fun `large files are truncated at the cap`() {
        val url = job("20261001_big", "report.md" to "x".repeat(200))
        val r = OutputFiles(root.toString(), maxBytes = 50).read(url, "report.md")
        assertIs<OutputFiles.Result.Text>(r)
        assertEquals(50, r.text.length)
        assertTrue(r.truncated)
    }

    @Test
    fun `a job without artifacts has no folder`() {
        assertIs<OutputFiles.Result.Missing>(OutputFiles(root.toString()).read(null, "report.md"))
    }

    @Test
    fun `a broken symlink is reported missing, not thrown`() {
        val dir = Files.createDirectories(root.resolve("20261001_broken"))
        Files.createSymbolicLink(dir.resolve("report.md"), root.resolve("does-not-exist"))
        assertIs<OutputFiles.Result.Missing>(OutputFiles(root.toString()).read("http://m/20261001_broken/", "report.md"))
    }
}
