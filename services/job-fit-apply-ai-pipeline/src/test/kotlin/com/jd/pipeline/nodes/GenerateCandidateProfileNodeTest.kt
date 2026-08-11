package com.jd.pipeline.nodes

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Unit tests for [GenerateCandidateProfileNode] (the LLM-free `--init-profile` node).
 *
 * Covers résumé-YAML parsing and the `__TODO__` detection + editor-pass loop over
 * the YAML config.
 */
@DisplayName("GenerateCandidateProfileNodeTest")
class GenerateCandidateProfileNodeTest {

    private val node = GenerateCandidateProfileNode()

    // ── résumé YAML parsing ────────────────────────────────────────────────────

    @Test
    @DisplayName("parseResume reads demographics, experience (with categorised bullets), and skill groups")
    fun parsesResumeYaml(@TempDir tempDir: Path) {
        val path = tempDir.resolve("resume.yaml")
        Files.writeString(path, sampleResumeYaml())

        val resume = node.parseResume(path)

        assertEquals("Jane Doe", resume.demographics.fullName)
        assertEquals(1, resume.experience.size)
        assertEquals("SDET", resume.experience[0].role)
        assertEquals("Impact", resume.experience[0].bullets[0].category)
        assertEquals("Built things.", resume.experience[0].bullets[0].text)
        assertEquals("Core", resume.skills[0].label)
        assertContains(resume.skills[0].items, "Kotlin")
    }

    @Test
    @DisplayName("parseResume throws a clear error on malformed YAML")
    fun rejectsMalformedResume(@TempDir tempDir: Path) {
        val path = tempDir.resolve("resume.yaml")
        Files.writeString(path, "experience:\n  - role: [unterminated")
        val ex = assertFails { node.parseResume(path) }
        assertContains(ex.message ?: "", "Failed to parse résumé YAML")
    }

    // ── editor pass over candidate_profile.yaml ────────────────────────────────

    @Test
    @DisplayName("interactiveEdit short-circuits when no __TODO__ markers remain")
    fun shortCircuitsWhenClean(@TempDir tempDir: Path) {
        val path = tempDir.resolve("candidate_profile.yaml")
        Files.writeString(path, profileConfigYaml())

        var editorCalls = 0
        val n = GenerateCandidateProfileNode(openEditor = { editorCalls++ })
        val config = n.interactiveEdit(path)

        assertEquals(0, editorCalls, "editor must not be invoked when the config is already clean")
        assertEquals("US Citizen", config.preferences.visaStatus)
        assertEquals("Senior SDET", config.scoring.targetTitle)
    }

    @Test
    @DisplayName("interactiveEdit invokes the editor when __TODO__ markers are present")
    fun invokesEditor(@TempDir tempDir: Path) {
        val path = tempDir.resolve("candidate_profile.yaml")
        Files.writeString(path, profileConfigYaml(withTodoVisa = true))

        var editorCalls = 0
        val n = GenerateCandidateProfileNode(openEditor = {
            editorCalls++
            Files.writeString(path, profileConfigYaml(withTodoVisa = false)) // user resolves the __TODO__
        })
        val config = n.interactiveEdit(path)

        assertEquals(1, editorCalls)
        assertEquals("US Citizen", config.preferences.visaStatus)
    }

    @Test
    @DisplayName("interactiveEdit hard-fails if __TODO__ markers remain after the editor closes")
    fun rejectsRemainingTodos(@TempDir tempDir: Path) {
        val path = tempDir.resolve("candidate_profile.yaml")
        Files.writeString(path, profileConfigYaml(withTodoVisa = true))

        val n = GenerateCandidateProfileNode(openEditor = { /* user changed nothing */ })
        val ex = assertFails { n.interactiveEdit(path) }
        assertContains(ex.message ?: "", "unresolved __TODO__")
    }

    @Test
    @DisplayName("findTodoFields returns the keys of every line still holding a __TODO__ sentinel")
    fun findsTodoFields() {
        val fields = node.findTodoFields(profileConfigYaml(withTodoVisa = true))
        assertContains(fields, "visa_status")
    }

    @Test
    @DisplayName("findTodoFields returns an empty list when no sentinels remain")
    fun findsNoTodoFieldsWhenClean() {
        assertEquals(emptyList(), node.findTodoFields(profileConfigYaml(withTodoVisa = false)))
    }

    @Test
    @DisplayName("interactiveEdit surfaces a schema error when the (clean) config does not parse")
    fun rejectsSchemaMismatch(@TempDir tempDir: Path) {
        val path = tempDir.resolve("candidate_profile.yaml")
        // No __TODO__ markers, so the editor is skipped and parseConfig runs directly — but the
        // YAML is not a valid CandidateProfileConfig, exercising the parse-error branch.
        Files.writeString(path, "scoring: \"this should be a mapping, not a string\"\n")

        val ex = assertFails { node.interactiveEdit(path) }
        assertContains(ex.message ?: "", "does not match the schema")
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun sampleResumeYaml() = """
        demographics:
          first_name: Jane
          last_name: Doe
          email: jane@example.com
          phone: "555"
          location: "Seattle, WA"
        summary: "Summary."
        experience:
          - role: SDET
            company: Acme
            start: 2020-01
            end: null
            bullets:
              - category: Impact
                text: Built things.
        education:
          - degree: B.S.
            field: Computer Science
            school: State U
            year: 2010
        skills:
          - label: Core
            items: [Kotlin, Java]
    """.trimIndent()

    private fun profileConfigYaml(withTodoVisa: Boolean = false): String {
        val visa = if (withTodoVisa) "\"__TODO__: e.g. 'US Citizen'\"" else "\"US Citizen\""
        return """
            scoring:
              target_title: "Senior SDET"
              years_experience: 10
              core_strengths: []
              languages: []
              domain_expertise: []
            preferences:
              willing_to_relocate: false
              visa_status: $visa
              preferred_work_arrangement: "Remote"
        """.trimIndent()
    }
}
