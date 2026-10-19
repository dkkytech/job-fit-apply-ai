package com.jd.jobbot.profile

import java.nio.file.Files
import java.nio.file.Paths

/** The candidate's resume and profile YAML, mounted read-only from the same files the processor uses. */
class ProfileReader(private val resumePath: String, private val profilePath: String, private val maxChars: Int = 60_000) {
    data class Profile(val resumeYaml: String?, val candidateProfileYaml: String?)

    fun read(): Profile = Profile(load(resumePath), load(profilePath))

    private fun load(path: String): String? {
        val p = Paths.get(path)
        if (!Files.isRegularFile(p)) return null
        return Files.readString(p).take(maxChars)
    }
}
