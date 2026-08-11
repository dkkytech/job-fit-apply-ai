package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the Jobright __NEXT_DATA__ schema drift that silently dropped salary (and every
 * other structured field). The job object moved to `pageProps.dataSource.jobResult` with renamed
 * fields (`salaryDesc`/`minSalary`/`maxSalary`, `jobSeniority`, `minYearsOfExperience`,
 * `jdCoreSkills`, …), so the old `pageProps.job` + `salaryMin` lookup extracted nothing.
 *
 * Fixture is the real __NEXT_DATA__ captured from the Qualitest "Test Automation Lead" posting
 * whose report.md showed a blank Salary despite the page listing $122K/yr – $126K/yr.
 */
@DisplayName("ScrapeJdNode — Jobright __NEXT_DATA__ structured extraction")
class ScrapeJdJobrightStructuredDataTest {

    private val node = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called") })

    private val fixture: String =
        javaClass.getResourceAsStream("/scrape/jobright_next_data.json")!!.readBytes().decodeToString()

    @Test
    @DisplayName("extracts salary from the current dataSource.jobResult schema")
    fun extractsSalary() {
        val result = node.applyJobrightStructuredData(JDState(), fixture)
        assertEquals("\$122K/yr - \$126K/yr", result.salaryRange)
    }

    @Test
    @DisplayName("extracts seniority, YOE, employment type, remote policy, and location")
    fun extractsStructuredFields() {
        val result = node.applyJobrightStructuredData(JDState(), fixture)
        assertEquals("Lead/Staff", result.seniorityLevel)
        assertEquals(9, result.yoeRequired)
        assertEquals("Full-time", result.employmentType)
        assertEquals("Remote", result.remotePolicy)
        assertEquals("United States", result.location)
    }

    @Test
    @DisplayName("extracts tech stack from jdCoreSkills objects")
    fun extractsTechStack() {
        val result = node.applyJobrightStructuredData(JDState(), fixture)
        assertTrue(result.techStack.containsAll(listOf("GitHub Actions", "Azure DevOps", "Test Automation")), result.techStack.toString())
    }

    @Test
    @DisplayName("assembles jd_text from summary + responsibilities + qualifications")
    fun assemblesJdText() {
        val result = node.applyJobrightStructuredData(JDState(), fixture)
        assertTrue(result.jdText.contains("QualityAI"), result.jdText.take(200))
        assertTrue(result.jdText.contains("Responsibilities"), "should include a responsibilities section")
        assertTrue(result.jdText.contains("Qualifications"), "should include a qualifications section")
    }

    @Test
    @DisplayName("does not overwrite fields already populated from the email scan")
    fun respectsExistingValues() {
        val preset = JDState(salaryRange = "\$200K", seniorityLevel = "Principal")
        val result = node.applyJobrightStructuredData(preset, fixture)
        assertEquals("\$200K", result.salaryRange)
        assertEquals("Principal", result.seniorityLevel)
    }

    @Test
    @DisplayName("returns the same instance (no fields set) when the job node is absent")
    fun noJobNodeIsNoOp() {
        val empty = """{"props":{"pageProps":{"dataSource":{}}}}"""
        val input = JDState()
        val result = node.applyJobrightStructuredData(input, empty)
        assertTrue(result === input, "unchanged state should be returned by reference for the log signal")
    }
}
