package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuccessFactorsAdapterTest {
    private val wipro = Employer(company = "Wipro", ats = "successfactors", host = "careers.wipro.com", location = "Washington")
    private val sent = mutableListOf<String>()
    private val http = FakeHttp(
        posts = mapOf("https://careers.wipro.com/services/recruiting/v1/jobs" to { body: String ->
            sent += body
            if ("\"pageNumber\":0" in body && "\"keywords\":\"test\"" in body) resource("successfactors_p0.json") else resource("successfactors_empty.json")
        }),
        gets = mapOf("https://careers.wipro.com/job/Senior-QA-Engineer/209080-en_US/" to resource("successfactors_detail.html")),
    )

    @Test
    fun `searches each term within the employer's location, page by page, and dedupes across terms`() {
        val postings = SuccessFactorsAdapter(http, listOf("test", "quality")).list(wipro)
        assertEquals(listOf("209080", "204701"), postings.map { it.externalId })
        assertEquals("Bellevue, USA-WA, USA, 98008", postings[0].location, "markup stripped")
        assertEquals("Redmond, USA-WA, USA | Seattle, USA-WA, USA", postings[1].location)
        assertEquals("https://careers.wipro.com/job/Senior-QA-Engineer/209080-en_US/", postings[0].url)
        assertEquals("https://careers.wipro.com/job/SDET-II/204701-en_US/", postings[1].url, "no locale listed → en_US")
        assertTrue(sent.all { "\"location\":\"Washington\"" in it })
        // test: page 0 hit, page 1 empty → stop; quality: page 0 empty → stop.
        assertEquals(3, sent.size)
    }

    @Test
    fun `the description is the posting page's schema_org description`() {
        val p = SuccessFactorsAdapter(http, listOf("test")).list(wipro).first()
        val text = SuccessFactorsAdapter(http, listOf("test")).describe(wipro, p)!!
        assertTrue("Own the test strategy." in text && "Playwright" in text, text)
    }

    @Test
    fun `missing host is a configuration error`() {
        assertFailsWith<IllegalArgumentException> { SuccessFactorsAdapter(http, listOf("test")).list(wipro.copy(host = null)) }
    }
}

class UkgAdapterTest {
    private val base = "https://recruiting.ultipro.com/SOU1036SOUND/JobBoard/dcc5dbea-875e-4cd1-bfd2-8e046cecc54f"
    private val st = Employer(company = "Sound Transit", ats = "ukg", tenant = "SOU1036SOUND", board = "dcc5dbea-875e-4cd1-bfd2-8e046cecc54f")
    private val http = FakeHttp(
        posts = mapOf("$base/JobBoardView/LoadSearchResults" to { _: String -> resource("ukg_search.json") }),
        gets = mapOf("$base/OpportunityDetail?opportunityId=80e4be40-6517-43c0-b716-1f8a85c86afd" to resource("ukg_detail.html")),
    )

    @Test
    fun `lists every opportunity with its locations, and reads the description from the detail page`() {
        val postings = UkgAdapter(http).list(st)
        assertEquals(listOf("Test Engineer, Rail Systems", "Design Manager"), postings.map { it.title })
        assertEquals("Seattle, WA, USA", postings[0].location)
        assertEquals("", postings[1].location)
        val text = UkgAdapter(http).describe(st, postings[0])!!
        assertTrue("Plan and run system tests for light rail." in text, text)
        assertTrue("scripts end with });" in text, "nested objects and a '});' inside the text don't cut the JSON short: $text")
    }

    @Test
    fun `a detail page without the opportunity JSON has no description`() {
        val bare = FakeHttp(posts = mapOf("$base/JobBoardView/LoadSearchResults" to { _: String -> resource("ukg_search.json") }), getHandler = { "<html></html>" })
        assertNull(UkgAdapter(bare).describe(st, UkgAdapter(bare).list(st)[0]))
    }
}

class PaylocityAdapterTest {
    private val evo = Employer(company = "evo", ats = "paylocity", board = "7299ce4a-4f80-42f8-9f29-92a9a49755f9")
    private val http = FakeHttp(gets = mapOf(
        "https://recruiting.paylocity.com/recruiting/jobs/All/7299ce4a-4f80-42f8-9f29-92a9a49755f9" to resource("paylocity_list.html"),
        "https://recruiting.paylocity.com/Recruiting/Jobs/Details/4575285" to resource("paylocity_detail.html"),
    ))

    @Test
    fun `lists pageData jobs - the teaser is not the description, the detail page is`() {
        val postings = PaylocityAdapter(http).list(evo)
        assertEquals(listOf("4575285", "4403912"), postings.map { it.externalId })
        assertEquals("Seattle, WA", postings[0].location)
        assertEquals("Remote", postings[1].location)
        assertNull(postings[0].description, "the ~110-char list teaser is never used")
        val text = PaylocityAdapter(http).describe(evo, postings[0])!!
        assertTrue("The QA Analyst owns regression testing" in text && "Apply" !in text, text)
    }

    @Test
    fun `a page without pageData is an error, not an empty board`() {
        val broken = FakeHttp(getHandler = { "<html>maintenance</html>" })
        assertFailsWith<IllegalStateException> { PaylocityAdapter(broken).list(evo) }
    }
}

class ZohoRecruitAdapterTest {
    private val ziply = Employer(company = "Ziply Fiber", ats = "zoho", host = "ziplyfiber.zohorecruit.com", site = "Careers")
    private val http = FakeHttp(gets = mapOf("https://ziplyfiber.zohorecruit.com/jobs/Careers" to resource("zoho.html")))

    @Test
    fun `lists published jobs from the hidden jobs input, description inline`() {
        val postings = ZohoRecruitAdapter(http).list(ziply)
        val job = postings.single()
        assertEquals("403102000052161152", job.externalId)
        assertEquals("Kirkland, Washington, United States", job.location)
        assertEquals("https://ziplyfiber.zohorecruit.com/jobs/Careers/403102000052161152/Network-Test-Engineer", job.url)
        assertEquals("Validate fiber network gear.", job.description)
    }

    @Test
    fun `a page without the jobs input is an error`() {
        assertFailsWith<IllegalStateException> { ZohoRecruitAdapter(FakeHttp(getHandler = { "<html></html>" })).list(ziply) }
    }
}

class AtlassianAdapterTest {
    private val http = FakeHttp(gets = mapOf("https://www.atlassian.com/endpoint/careers/listings" to resource("atlassian.json")))

    @Test
    fun `one posting per page, text joined from its sections`() {
        val postings = AtlassianAdapter(http).list(Employer(company = "Atlassian", ats = "atlassian"))
        assertEquals(listOf("26643", "25480"), postings.map { it.externalId }, "the same page listed for another region is one posting")
        assertEquals("Remote - Remote | Seattle - United States - Seattle, WA", postings[0].location)
        assertTrue("Break things safely." in postings[0].description!! && "Pen tests" in postings[0].description!!)
        assertEquals("https://www.atlassian.com/apply/25480", postings[1].url, "falls back to applyUrl")
        assertNull(postings[1].description)
    }
}
