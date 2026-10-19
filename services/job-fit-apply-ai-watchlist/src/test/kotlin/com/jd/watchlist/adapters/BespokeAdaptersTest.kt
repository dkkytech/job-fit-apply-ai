package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleAdapterTest {
    private val apple = Employer(company = "Apple", ats = "apple", location = "seattle-SEA")

    private fun http() = FakeHttp(getHandler = { url ->
        when {
            url.startsWith("https://jobs.apple.com/en-us/search") -> resource("apple_search.html")
            url.startsWith("https://jobs.apple.com/en-us/details/200685550/") -> resource("apple_detail.html")
            else -> null
        }
    })

    @Test
    fun `parses the hydration JSON, stops at totalRecords, and builds detail links`() {
        val http = http()
        val postings = AppleAdapter(http, listOf("test", "qa")).list(apple)
        assertEquals(listOf("200685550", "200685551"), postings.map { it.externalId })
        assertEquals("Software Engineer in Test – Maps", postings[0].title, "non-ASCII survives the double decode")
        assertEquals("https://jobs.apple.com/en-us/details/200685550/software-engineer-in-test-maps", postings[0].url)
        assertTrue("\"Quoted\" and </script>-safe" in postings[0].description!!, "escaped quotes decode")
        assertEquals("Remote", postings[1].location)
        assertNull(postings[1].description)
        assertEquals(2, http.requests.size, "one page per term: totalRecords reached on page 1 for each term")
        assertTrue(http.requests.all { "location=seattle-SEA" in it })
    }

    @Test
    fun `describe joins the detail page's summary, description and qualifications`() {
        val adapter = AppleAdapter(http(), listOf("test"))
        val text = adapter.describe(apple, adapter.list(apple).first())!!
        listOf("Build test infrastructure", "Own the end-to-end test strategy.", "Minimum Qualifications", "5 years of test automation")
            .forEach { assertTrue(it in text, it) }
        assertTrue("Preferred Qualifications" !in text, "empty sections are omitted")
    }

    @Test
    fun `a page without hydration data yields nothing rather than an error`() {
        assertNull(AppleAdapter.hydration("<html>maintenance</html>"))
        val postings = AppleAdapter(FakeHttp(getHandler = { "<html>maintenance</html>" }), listOf("test")).list(apple)
        assertEquals(emptyList(), postings)
    }
}

class CapgeminiAdapterTest {
    @Test
    fun `searches the US site per term with HTML or stripped descriptions`() {
        val http = FakeHttp(getHandler = { url -> if (url.startsWith("https://cg-jobstream-api.azurewebsites.net/api/job-search?country_code=us-en&search=test")) resource("capgemini.json") else null })
        val postings = CapgeminiAdapter(http, listOf("test")).list(Employer(company = "Capgemini", ats = "capgemini", location = "us-en"))
        assertEquals(listOf("cg-1", "cg-2"), postings.map { it.externalId })
        assertEquals("Lead a QE squad.", postings[0].description)
        assertEquals("Plain text only.", postings[1].description, "falls back to description_stripped")
        assertEquals("https://www.capgemini.com/us-en/jobs/cg-2/", postings[1].url, "falls back to wp_url")
    }
}

class JobsynAdapterTest {
    @Test
    fun `sends X-Origin for the career site and links via the guid redirect`() {
        val http = FakeHttp(getHandler = { url -> if (url.startsWith("https://prod-search-api.jobsyn.org/api/v1/solr/search?q=quality")) resource("jobsyn.json") else null })
        val p = JobsynAdapter(http, listOf("quality")).list(Employer(company = "Alaska Airlines", ats = "jobsyn", host = "careers.alaskaair.com")).single()
        assertEquals("careers.alaskaair.com", http.getHeaders.single()["X-Origin"])
        assertEquals("2026-20169", p.externalId)
        assertEquals("https://careers.alaskaair.com/4427AEB636F14E0FAFB5B4CA01A372E5/job/", p.url)
        assertEquals("Seatac, WA", p.location)
        assertEquals("Test crew scheduling systems.", p.description)
    }
}

class EpamAdapterTest {
    @Test
    fun `reads jobs with city and country locations and SEO links`() {
        val http = FakeHttp(getHandler = { url -> if (url.startsWith("https://careers.epam.com/api/jobs/v2/search/careers?lang=en&q=test")) resource("epam.json") else null })
        val postings = EpamAdapter(http, listOf("test")).list(Employer(company = "EPAM Systems", ats = "epam"))
        assertEquals("United States", postings[0].location, "a null city is dropped")
        assertEquals("Seattle, United States", postings[1].location)
        assertEquals("https://careers.epam.com/en/vacancy/qa-lead-blt7c2d_en", postings[1].url)
        assertEquals("Automate everything.", postings[0].description)
    }
}

class GridDynamicsAdapterTest {
    private val page = "https://www.griddynamics.com/careers/discover-openings"
    private val gd = Employer(company = "Grid Dynamics", ats = "griddynamics", feed = page)
    private val http = FakeHttp(gets = mapOf(
        page to resource("griddynamics_page.html"),
        "https://www.griddynamics.com/careers/vacancy/60001" to resource("griddynamics_vacancy.html"),
    ))

    @Test
    fun `lists only openings with a US location, read from the data-vacancies JSON`() {
        val postings = GridDynamicsAdapter(http).list(gd)
        assertEquals(listOf("60001", "60003"), postings.map { it.externalId }, "London and Turkey Remote are not listed")
        assertEquals("Senior QA Automation Engineer", postings[0].title, "trimmed")
        assertEquals("US Remote, US", postings[0].location)
        assertEquals("Kirkland, WA, United States | Hyderabad, India", postings[1].location)
        assertEquals("https://www.griddynamics.com/careers/vacancy/60001", postings[0].url)
    }

    @Test
    fun `the description is the vacancy page's content block, not the apply form`() {
        val adapter = GridDynamicsAdapter(http)
        val text = adapter.describe(gd, adapter.list(gd).first())!!
        assertTrue("build test frameworks for a retail client" in text, text)
        assertFalse("Upload your CV" in text, text)
    }

    @Test
    fun `a page without the vacancies attribute is an error, not an empty board`() {
        assertFailsWith<IllegalStateException> { GridDynamicsAdapter(FakeHttp(gets = mapOf(page to "<html></html>"))).list(gd) }
    }
}
