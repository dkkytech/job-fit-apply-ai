package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("ScrapeJdNode — JSON-LD JobPosting extraction")
class ScrapeJdJsonLdTest {

    private val node = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called") })

    private val longDesc =
        "We are seeking a Staff Cyber Software Engineer to design and build secure " +
        "backend services. Responsibilities include threat modeling, building CI/CD " +
        "security gates, and writing automated security tests in Java and Python. " +
        "Requirements: 8+ years experience, AWS, Kubernetes, and strong knowledge of OWASP."

    @Test
    @DisplayName("extracts title, company, location, salary, and full description")
    fun extractsJobPosting() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            {"@type":"JobPosting","title":"Staff Cyber Software Engineer",
             "hiringOrganization":{"name":"GEICO"},
             "jobLocation":{"address":{"addressLocality":"Seattle","addressRegion":"WA"}},
             "baseSalary":{"value":{"minValue":"110000","maxValue":"230000","unitText":"YEAR"}},
             "employmentType":"FULL_TIME",
             "description":"<p>$longDesc</p>"}
            </script></head><body>thin visible text</body></html>
        """.trimIndent()
        val result = node.extractJobPostingJsonLd(html)
        assertNotNull(result)
        assertTrue(result!!.contains("GEICO"), result)
        assertTrue(result.contains("Staff Cyber Software Engineer"))
        assertTrue(result.contains("Seattle, WA"))
        assertTrue(result.contains("Salary: \$110K - \$230K/yr"), result)
        assertTrue(result.contains("threat modeling"), "description body should be included")
    }

    @Test
    @DisplayName("finds JobPosting nested inside @graph")
    fun findsInsideGraph() {
        val html = """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebSite","name":"Board"},
              {"@type":"JobPosting","title":"SDET","hiringOrganization":{"name":"Acme"},
               "description":"<div>$longDesc</div>"}
            ]}
            </script>
        """.trimIndent()
        val result = node.extractJobPostingJsonLd(html)
        assertNotNull(result)
        assertTrue(result!!.contains("Acme"))
        assertTrue(result.contains("SDET"))
    }

    @Test
    @DisplayName("returns null when description is too thin to be a real JD")
    fun nullForThinDescription() {
        val html = """
            <script type="application/ld+json">
            {"@type":"JobPosting","title":"X","description":"<p>Apply now.</p>"}
            </script>
        """.trimIndent()
        assertNull(node.extractJobPostingJsonLd(html))
    }

    @Test
    @DisplayName("returns null when there is no JobPosting JSON-LD")
    fun nullWhenAbsent() {
        assertNull(node.extractJobPostingJsonLd("<html><body><p>No structured data</p></body></html>"))
    }
}
