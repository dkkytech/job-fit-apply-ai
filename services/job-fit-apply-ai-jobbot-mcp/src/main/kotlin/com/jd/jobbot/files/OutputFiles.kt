package com.jd.jobbot.files

import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reads a job's pipeline output files, read-only, from the mounted pipeline-output root.
 *
 * The folder comes from the tail of the event's artifact_url, and a name must be on [ALLOWED]:
 * the model picks the name, so the path must never be free-form. The resolved path is checked
 * against the root after canonicalization so `..` and symlinks cannot escape it.
 */
class OutputFiles(root: String, private val maxBytes: Int = 64 * 1024) {
    private val root: Path = Paths.get(root).toAbsolutePath().normalize()

    sealed interface Result {
        data class Text(val name: String, val text: String, val truncated: Boolean) : Result
        data class Missing(val reason: String) : Result
    }

    fun folderOf(artifactUrl: String?): String? =
        artifactUrl?.trim()?.trimEnd('/')?.substringAfterLast('/')
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it }

    fun available(artifactUrl: String?): List<String> {
        val dir = dirOf(artifactUrl) ?: return emptyList()
        return ALLOWED.filter { name ->
            val file = dir.resolve(name)
            Files.isRegularFile(file) && runCatching { file.toRealPath().startsWith(realRoot()) }.getOrDefault(false)
        }
    }

    fun read(artifactUrl: String?, name: String): Result {
        if (name !in ALLOWED) return Result.Missing("'$name' is not a readable job file; allowed: ${ALLOWED.joinToString()}")
        val dir = dirOf(artifactUrl) ?: return Result.Missing("this job has no output folder")
        val file = dir.resolve(name)
        if (!Files.isRegularFile(file)) return Result.Missing("$name does not exist for this job")
        // The folder is live pipeline output: a file can vanish or be swapped between checks.
        val bytes = try {
            val real = file.toRealPath()
            if (!real.startsWith(realRoot())) return Result.Missing("$name resolves outside the output root")
            Files.newInputStream(real).use { it.readNBytes(maxBytes + 1) }
        } catch (e: java.io.IOException) {
            return Result.Missing("$name could not be read (${e.javaClass.simpleName})")
        }
        val truncated = bytes.size > maxBytes
        val text = String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8)
        return Result.Text(name, text, truncated)
    }

    /**
     * The job's folder, canonicalized: a folder that is (or passes through) a symlink out of the
     * root is no folder at all, so listing and reading agree on what exists.
     */
    private fun dirOf(artifactUrl: String?): Path? {
        val folder = folderOf(artifactUrl) ?: return null
        val dir = root.resolve(folder).normalize()
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) return null
        val real = runCatching { dir.toRealPath() }.getOrNull() ?: return null
        return real.takeIf { it.startsWith(realRoot()) }
    }

    private fun realRoot(): Path = runCatching { root.toRealPath() }.getOrDefault(root)

    companion object {
        val ALLOWED = listOf(
            "report.md", "score_fit.txt", "cover_letter.txt", "metadata.json", "meta.json",
            "job_description.txt", "gap_analysis.json", "ats_report.txt", "tailored_summary.txt",
            "tailored_bullets.txt", "tailored_resume.yaml",
        )
    }
}
