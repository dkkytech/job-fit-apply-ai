package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Report fields (remote / salary / employment type / seniority / YOE / skills) extracted from the
 * structured data job sites embed, and kept when the LLM reads the same page less precisely.
 * Fixtures are trimmed from real pages saved in pipeline-output.
 */
@DisplayName("ScrapeJdNode — structured report fields")
class ScrapeJdStructuredFieldsTest {

    private val desc = "Design, build and maintain automated test frameworks for web and API services. ".repeat(3)

    // Dice: flattened baseSalary (no `value` wrapper, no unit), TELECOMMUTE, CONTRACTOR.
    private val diceHtml = """
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"JobPosting",
          "title":"(Senior QA Engineer SDET)","hiringOrganization":{"@type":"Organization","name":"Arcadia Talent Advisory LLC"},
          "baseSalary":{"@type":"MonetaryAmount","currency":"USD","minValue":55,"maxValue":65},
          "employmentType":"CONTRACTOR","jobLocationType":"TELECOMMUTE","description":"<p>$desc</p>"}</script>
    """.trimIndent()

    // Built In: spec-shaped baseSalary, experience in months, skills string.
    private val builtInHtml = """
        <script type="application/ld+json">{"@type":"JobPosting","title":"Senior SDET",
          "hiringOrganization":{"name":"Akamai Technologies"},
          "baseSalary":{"@type":"MonetaryAmount","currency":"USD","value":{"@type":"QuantitativeValue",
            "minValue":121400,"maxValue":218600,"unitText":"YEAR"}},
          "employmentType":"FULL_TIME","jobLocationType":"TELECOMMUTE",
          "experienceRequirements":{"@type":"OccupationalExperienceRequirements","monthsOfExperience":60},
          "skills":"Java, Selenium, Kubernetes","description":"$desc"}</script>
    """.trimIndent()

    private fun node(vararg replies: String): Pair<ScrapeJdNode, AtomicInteger> {
        val calls = AtomicInteger(0)
        val node = ScrapeJdNode(llm = LlmCaller { replies[minOf(calls.getAndIncrement(), replies.size - 1)] })
        node.verbose = false
        return node to calls
    }

    @Test
    @DisplayName("Dice: flattened salary is read as hourly, TELECOMMUTE → remote, CONTRACTOR → Contract")
    fun diceFacts() {
        val facts = assertNotNull(node("{}").first.parseJobPostingFacts(diceHtml))
        assertEquals("\$55 - \$65/hr", facts.salaryRange)
        assertEquals("remote", facts.remotePolicy)
        assertEquals("Contract", facts.employmentType)
    }

    @Test
    @DisplayName("Built In: nested salary, months of experience, and skills string")
    fun builtInFacts() {
        val facts = assertNotNull(node("{}").first.parseJobPostingFacts(builtInHtml))
        assertEquals("\$121K - \$219K/yr", facts.salaryRange)
        assertEquals("Full-time", facts.employmentType)
        assertEquals(5, facts.yoeRequired)
        assertEquals(listOf("Java", "Selenium", "Kubernetes"), facts.skills)
    }

    @Test
    @DisplayName("JSON-LD values survive an LLM that says 'unknown' or misreads the salary")
    fun jsonLdBeatsLlm() {
        val (n, _) = node(
            """{"role_title":"Senior QA Engineer SDET","remote_policy":"unknown","salary_range":"$1",
               "employment_type":"Full-time","seniority_level":"Senior","yoe_required":7,
               "tech_stack":["Selenium"],"jd_text":"$desc"}"""
        )
        val out = n.parseJobPage(
            JDState(jobUrl = "https://www.dice.com/job-detail/x"), "https://www.dice.com/job-detail/x",
            content = "visible text", rawHtml = diceHtml,
        )
        assertEquals("remote", out.remotePolicy)
        assertEquals("\$55 - \$65/hr", out.salaryRange)
        assertEquals("Contract", out.employmentType)
        // Not in this page's JSON-LD, so the LLM's reading stands.
        assertEquals("Senior", out.seniorityLevel)
        assertEquals(7, out.yoeRequired)
        assertEquals(listOf("Selenium"), out.techStack)
    }

    @Test
    @DisplayName("an LLM 'unknown' no longer overwrites a value the email card supplied")
    fun llmUnknownDoesNotClobber() {
        val (n, _) = node("""{"remote_policy":"unknown","salary_range":"null","jd_text":"$desc"}""")
        val out = n.parseJobPage(
            JDState(jobUrl = "https://jobs.example.com/1", remotePolicy = "hybrid", salaryRange = "\$150K"),
            "https://jobs.example.com/1", content = "visible text",
        )
        assertEquals("hybrid", out.remotePolicy)
        assertEquals("\$150K", out.salaryRange)
    }

    @Test
    @DisplayName("Jobright: __NEXT_DATA__ fields beat the LLM, not just fill its blanks")
    fun jobrightNextDataBeatsLlm() {
        val nextData = """{"props":{"pageProps":{"dataSource":{"jobResult":{
            "jobSeniority":"Lead/Staff","workModel":"Remote","salaryDesc":"${'$'}101K/yr - ${'$'}127K/yr",
            "employmentType":"Full-time","minYearsOfExperience":6,
            "jdCoreSkills":[{"skill":"Test Automation Frameworks"}],"jobSummary":"$desc"}}}}}"""
        val (n, _) = node("""{"remote_policy":"unknown","seniority_level":"Mid-level","jd_text":"short"}""")
        val url = "https://jobright.ai/jobs/info/6a63ab620c8e2b4f36dcf19f"
        val out = n.parseJobPage(JDState(jobUrl = url), url, content = "PAGE_JSON_DATA:\n$nextData\n\nvisible")
        assertEquals("Remote", out.remotePolicy)
        assertEquals("\$101K/yr - \$127K/yr", out.salaryRange)
        assertEquals("Full-time", out.employmentType)
        assertEquals("Lead/Staff", out.seniorityLevel)
        assertEquals(6, out.yoeRequired)
        assertEquals(listOf("Test Automation Frameworks"), out.techStack)
        assertEquals(desc.trim(), out.jdText)
    }

    @Test
    @DisplayName("tolerates chatter around the LLM's JSON and retries once on an unparseable reply")
    fun llmReplyRecovery() {
        val (n, calls) = node(
            """_title": "Senior SDET", "company": "Acme"}""",          // glm dropped the opening
            "json\n{\"employment_type\":\"Contract\",\"jd_text\":\"$desc\"}",
        )
        val out = n.parseJobPage(JDState(jobUrl = "https://a.co/1"), "https://a.co/1", content = "visible text")
        assertEquals(2, calls.get())
        assertEquals("Contract", out.employmentType)
        assertNull(n.parseLlmJson("no json here"))
    }

    @Test
    @DisplayName("LinkedIn: page text is cut before the 'More jobs' recommendations")
    fun linkedInRecommendationsTrimmed() {
        val text = listOf(
            "Qualtrics", "Staff Software Engineer - Agent Platform", "Hybrid", "Full-time",
            "About the job", "Build the agent platform.", "More jobs",
            "Staff Software Engineer, Agent Platform", "Replit", "\$250K/yr - \$365K/yr",
        ).joinToString("\n")
        val trimmed = node("{}").first.trimLinkedInRecommendations(text)
        assertEquals(true, trimmed.endsWith("Build the agent platform."))
        assertEquals(false, trimmed.contains("\$250K"))
    }
}
