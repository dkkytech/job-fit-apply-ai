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

    @Test
    @DisplayName("extracts report metadata from Jobright raw HTML when __NEXT_DATA__ omits it")
    fun extractsMetadataFromRawHtml() {
        // Salesforce's captured Jobright page has these values in an embedded client-data payload,
        // outside __NEXT_DATA__. The report must retain every value rather than showing "—".
        val rawHtml = """
            <html><head>
              <script type="application/ld+json">{
                "@type":"JobPosting",
                "description":"${"A".repeat(120)}",
                "jobLocation":{"address":{"addressLocality":"Bellevue","addressRegion":"WA"}},
                "baseSalary":{"value":{"minValue":148000,"maxValue":224000,"unitText":"YEAR"}},
                "employmentType":"FULL_TIME"
              }</script>
              <script>
                window.__JOBRIGHT_DATA__={"salaryDesc":"${'$'}148K/yr - ${'$'}224K/yr","workModel":"Hybrid","jobSeniority":"Senior Level"};
              </script>
            </head></html>
        """.trimIndent()

        val result = node.applyJobrightRawPageMetadata(JDState(), rawHtml)

        assertEquals("${'$'}148K/yr - ${'$'}224K/yr", result.salaryRange)
        assertEquals("Hybrid", result.remotePolicy)
        assertEquals("Full-time", result.employmentType)
        assertEquals("Senior Level", result.seniorityLevel)
        assertEquals("Bellevue, WA", result.location)
    }

    @Test
    @DisplayName("uses JSON-LD salary and multi-location fallbacks when client data is absent")
    fun usesJsonLdMetadataFallbacks() {
        val rawHtml = """
            <script type="application/ld+json">{
              "@type":"JobPosting",
              "jobLocation":[
                {"address":{"addressLocality":"Seattle","addressRegion":"WA"}},
                {"address":{"addressLocality":"Portland","addressRegion":"OR"}}
              ],
              "baseSalary":{"value":{"minValue":165000}},
              "employmentType":["CONTRACT"]
            }</script>
        """.trimIndent()

        val result = node.applyJobrightRawPageMetadata(JDState(), rawHtml)

        assertEquals("${'$'}165K+", result.salaryRange)
        assertEquals("Contract", result.employmentType)
        assertEquals("Seattle, WA", result.location)
        assertEquals("unknown", result.remotePolicy)
        assertTrue(result.seniorityLevel.isBlank())
    }

    @Test
    @DisplayName("does not overwrite any authoritative metadata with raw Jobright values")
    fun preservesAllExistingReportMetadata() {
        val rawHtml = """
            <script type="application/ld+json">{
              "@type":"JobPosting",
              "jobLocation":{"address":{"addressLocality":"Bellevue","addressRegion":"WA"}},
              "baseSalary":{"value":{"minValue":148000,"maxValue":224000}},
              "employmentType":"FULL_TIME"
            }</script>
            <script>
              window.__JOBRIGHT_DATA__={"salaryDesc":"${'$'}148K/yr - ${'$'}224K/yr","workModel":"Hybrid","jobSeniority":"Senior Level","jobLocation":"Bellevue, WA"};
            </script>
        """.trimIndent()
        val existing = JDState(
            salaryRange = "${'$'}250K - ${'$'}300K",
            remotePolicy = "Remote",
            employmentType = "Part-time",
            seniorityLevel = "Principal",
            location = "Austin, TX",
        )

        val result = node.applyJobrightRawPageMetadata(existing, rawHtml)

        assertEquals(existing.salaryRange, result.salaryRange)
        assertEquals(existing.remotePolicy, result.remotePolicy)
        assertEquals(existing.employmentType, result.employmentType)
        assertEquals(existing.seniorityLevel, result.seniorityLevel)
        assertEquals(existing.location, result.location)
    }
}
