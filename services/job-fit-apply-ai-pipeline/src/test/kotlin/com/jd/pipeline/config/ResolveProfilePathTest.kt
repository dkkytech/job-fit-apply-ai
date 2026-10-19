package com.jd.pipeline.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

import kotlin.test.assertEquals

/**
 * Tests for [resolveProfilePath] — the override-vs-default precedence shared by
 * `Config.RESUME_YAML_PATH` and `Config.CANDIDATE_PROFILE_PATH`.
 *
 * These are behaviour contracts, not change-detectors: they assert the relationship
 * between the two inputs and the output, never a specific in-tree path value.
 *
 * The `Config.*` properties themselves cannot be covered here — they are cached at
 * object-init and read the process environment, which a test cannot vary. The rule is
 * therefore extracted into a pure function and asserted directly.
 */
@DisplayName("resolveProfilePath")
class ResolveProfilePathTest {

    private val fallback: Path = Paths.get("/default/in-tree/resume.yaml")

    @Test
    @DisplayName("an explicit override wins over the fallback")
    fun overrideWins() {
        val resolved = resolveProfilePath("/custom/profile/base-resume.yaml", fallback)
        assertEquals(Paths.get("/custom/profile/base-resume.yaml"), resolved)
    }

    @Test
    @DisplayName("an unset override falls back to the in-tree default")
    fun unsetFallsBack() {
        assertEquals(fallback, resolveProfilePath(null, fallback))
    }

    @Test
    @DisplayName("a blank override is treated as unset rather than resolving to an empty path")
    fun blankFallsBack() {
        // Guards the real failure mode: an empty value from compose/env must not
        // produce a bogus relative path — it must mean "use the default".
        assertEquals(fallback, resolveProfilePath("", fallback))
        assertEquals(fallback, resolveProfilePath("   ", fallback))
    }

    @Test
    @DisplayName("an override is returned as the same path it was given")
    fun overrideRoundTrips() {
        val target = "/Users/someone/projects/profile/candidate_profile.yaml"
        assertEquals(Paths.get(target), resolveProfilePath(target, fallback))
    }

    @Test
    @DisplayName("the two profile files resolve independently — neither can shadow the other")
    fun profilesResolveIndependently() {
        // Why this matters: a single directory variable (the rejected JFAA_PROFILE_DIR
        // design) leaves precedence undefined when a directory and a file disagree.
        // Two independent file overrides have no such ambiguity — each answers only
        // its own question.
        val resumeDefault = Paths.get("/in-tree/resume.yaml")
        val profileDefault = Paths.get("/in-tree/candidate_profile.yaml")
        val resumeOverride = "/profile/base-resume.yaml"

        val resume = resolveProfilePath(resumeOverride, resumeDefault)
        val profile = resolveProfilePath(null, profileDefault)

        assertEquals(Paths.get(resumeOverride), resume)
        assertEquals(profileDefault, profile)
        // overriding one must not move the other
        assertEquals(profileDefault, resolveProfilePath(null, profileDefault))
    }
}
