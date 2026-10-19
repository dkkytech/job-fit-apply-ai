package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkdayAdapterTest {
    private val api = "https://acme.wd1.myworkdayjobs.com/wday/cxs/acme/EXT"
    private val acme = Employer(company = "Acme Aero", ats = "workday", host = "acme.wd1.myworkdayjobs.com", tenant = "acme", site = "EXT")

    /** Facet probe (limit 1, no search) → facets; any search → the two fixture postings. */
    private fun http() = FakeHttp(
        gets = mapOf("$api/job/USA---Everett-WA/Test---Evaluation-Engineer_JR1" to resource("workday_detail.json")),
        posts = mapOf("$api/jobs" to { body -> if ("\"limit\":1," in body) resource("workday_facets.json") else resource("workday_search.json") }),
    )

    @Test
    fun `selects Seattle-area location facets and skips DC and country facets`() {
        val facets = WorkdayAdapter.areaFacets(com.jd.watchlist.adapters.json.readTree(resource("workday_facets.json")))
        assertEquals(mapOf("locations" to listOf("loc-everett", "loc-seattle")), facets)
    }

    @Test
    fun `searches each term within area facets and dedups postings across terms`() {
        val http = http()
        val postings = WorkdayAdapter(http, listOf("test", "quality")).list(acme)
        assertEquals(listOf("JR1", "JR2"), postings.map { it.externalId })
        assertEquals("https://acme.wd1.myworkdayjobs.com/EXT/job/USA---Everett-WA/Test---Evaluation-Engineer_JR1", postings[0].url)
        val searches = http.requests.filter { "\"searchText\":\"test\"" in it || "\"searchText\":\"quality\"" in it }
        assertEquals(2, searches.size, "fixture page is short, so one page per term")
        assertTrue(searches.all { "loc-everett" in it && "loc-seattle" in it && "loc-dc" !in it })
    }

    @Test
    fun `multi-location postings inside area facets are labelled Washington`() {
        val postings = WorkdayAdapter(http(), listOf("test")).list(acme)
        assertEquals("Washington (2 Locations)", postings[1].location)
        assertEquals("", WorkdayAdapter.locationOf("3 Locations", areaFiltered = false))
        assertEquals("USA - Everett, WA", WorkdayAdapter.locationOf("USA - Everett, WA", areaFiltered = true))
    }

    @Test
    fun `public URLs on shared myworkdaysite hosts include the recruiting tenant path`() {
        val uw = Employer(company = "UW", ats = "workday", host = "wd5.myworkdaysite.com", tenant = "uw", site = "UWHires")
        assertEquals("https://wd5.myworkdaysite.com/recruiting/uw/UWHires/job/Seattle-WA/QA_E-1", WorkdayAdapter.publicUrl(uw, "/job/Seattle-WA/QA_E-1"))
        assertEquals("https://acme.wd1.myworkdayjobs.com/EXT/job/X_1", WorkdayAdapter.publicUrl(acme, "/job/X_1"))
    }

    @Test
    fun `describe fetches the posting detail and strips markup`() {
        val adapter = WorkdayAdapter(http(), listOf("test"))
        val posting = adapter.list(acme).first()
        val text = adapter.describe(acme, posting)!!
        assertTrue("Plan and run flight test campaigns." in text)
        assertTrue("Instrumentation" in text)
        assertTrue('<' !in text)
    }
}
