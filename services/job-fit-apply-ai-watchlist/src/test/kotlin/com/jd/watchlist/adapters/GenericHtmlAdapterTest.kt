package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenericHtmlAdapterTest {
    private val apex = Employer(
        company = "Apex Systems", ats = "html",
        feed = "https://www.apexsystems.com/search-results-usa?page={page}&query={term}",
        linkPattern = "/job/(\\d+)_usa/", cardSelector = "tr", pages = 3,
    )
    private val apexHttp = FakeHttp(gets = mapOf(
        "https://www.apexsystems.com/search-results-usa?page=1&query=QA" to resource("html_apex_p1.html"),
        "https://www.apexsystems.com/search-results-usa?page=2&query=QA" to resource("html_apex_p2.html"),
        "https://www.apexsystems.com/job/3043817_usa/android-test-engineer" to resource("html_apex_detail.html"),
    ))

    @Test
    fun `list - links matching the pattern, id from the group, location from the card, stop on a page with nothing new`() {
        val postings = GenericHtmlAdapter(apexHttp, listOf("QA")).list(apex)
        assertEquals(listOf("3043817", "3055198"), postings.map { it.externalId }, "the #apply link and the pager are not postings")
        assertEquals("Android Test Engineer", postings[0].title, "an 'Apply' anchor never replaces a real title")
        assertEquals("Redmond WA 7/24/2026", postings[0].location)
        assertEquals("https://www.apexsystems.com/job/3043817_usa/android-test-engineer", postings[0].url, "absolute, no fragment")
        assertEquals(2, apexHttp.requests.size, "page 2 added nothing new: page 3 is never fetched")
    }

    @Test
    fun `detail - JSON-LD JobPosting gives the description and location`() {
        val p = GenericHtmlAdapter(apexHttp, listOf("QA")).list(apex).first()
        val d = GenericHtmlAdapter(apexHttp, listOf("QA")).detail(apex, p)
        assertEquals("Own Espresso suites for a Redmond client.", d.text)
        assertEquals("Redmond, WA, US", d.location)
    }

    @Test
    fun `a title selector picks the title out of a link that wraps the whole card`() {
        val asc = Employer(company = "Ascendion", ats = "html", feed = "https://ascendion.com/careers/",
            linkPattern = "/job-inner-page/\\?post=(\\d+)", cardSelector = ".crs-job", titleSelector = ".crs-job__title", detailSelector = ".jd-container")
        val http = FakeHttp(gets = mapOf(
            "https://ascendion.com/careers/" to resource("html_ascendion_list.html"),
            "https://ascendion.com/job-inner-page/?post=333201" to resource("html_ascendion_detail.html"),
        ))
        val postings = GenericHtmlAdapter(http, emptyList()).list(asc)
        assertEquals(listOf("Manufacturing Test Infrastructure Engineer", "Hardware Validation Engineer"), postings.map { it.title })
        assertTrue("Seattle, Washington" in postings[0].location && "Manufacturing" !in postings[0].location, postings[0].location)
        val d = GenericHtmlAdapter(http, emptyList()).detail(asc, postings[0])
        assertEquals("Build the test racks for our Seattle lab and automate their health checks end to end.", d.text, "detail selector when there is no JSON-LD")
        assertNull(d.location)
    }

    @Test
    fun `a list without locations - the posting page's label gives it, and a template JSON-LD address is ignored`() {
        val ex = Employer(company = "Experis", ats = "html", feed = "https://www.experis.com/en/all-jobs",
            linkPattern = "/en/job/(\\d+)/", locationLabel = "Location:", detailSelector = ".details-rich-text")
        val http = FakeHttp(gets = mapOf(
            "https://www.experis.com/en/all-jobs" to resource("html_experis_list.html"),
            "https://www.experis.com/en/job/413962/api-qa-lead" to resource("html_experis_qa_lead.html"),
            "https://www.experis.com/en/job/412331/automotive-vehicle-quality-engineer" to resource("html_experis_auto.html"),
            "https://www.experis.com/en/job/405025/quality-assurance-manager" to resource("html_experis_qam.html"),
        ))
        val adapter = GenericHtmlAdapter(http, emptyList())
        val postings = adapter.list(ex)
        assertTrue(postings.all { it.location.isBlank() })
        assertEquals("Seattle, Washington (Onsite)", adapter.detail(ex, postings[0]).location)
        assertEquals("Auburn Hills, MI (Onsite)", adapter.detail(ex, postings[1]).location)
        val qam = adapter.detail(ex, postings[2])
        assertNull(qam.location, "[addressLocality], [addressRegion] is an unfilled template, and 'US' alone is no location")
        assertTrue(qam.text!!.startsWith("Lead QA for a process-equipment maker"), "the JSON-LD description still counts")
    }

    @Test
    fun `a template JSON-LD address - the Location label, else the street address, gives the place`() {
        // Experis fills streetAddress ("Redmond, WA") and leaves the locality fields as template
        // text; its "Location:" label is empty. Reading the street address keeps Quincy, MA (and
        // Mexico, California, New Jersey…) from passing the location filter as "unknown".
        val ex = Employer(company = "Experis", ats = "html", feed = "https://www.experis.com/en/all-jobs",
            linkPattern = "/en/job/(\\d+)/", locationLabel = "Location:", detailSelector = ".details-rich-text")
        val http = FakeHttp(gets = mapOf(
            "https://www.experis.com/en/job/1/redmond" to resource("html_experis_street_redmond.html"),
            "https://www.experis.com/en/job/2/quincy" to resource("html_experis_street_quincy.html"),
            "https://www.experis.com/en/job/3/full" to resource("html_experis_street_full.html"),
            "https://www.experis.com/en/job/4/office" to resource("html_experis_street_office.html"),
        ))
        val adapter = GenericHtmlAdapter(http, emptyList())
        fun at(id: String, slug: String) = adapter.detail(ex, com.jd.watchlist.model.Posting("Experis", id, "QA", "", "https://www.experis.com/en/job/$id/$slug"))
        assertEquals("Redmond, WA, US", at("1", "redmond").location)
        val quincy = at("2", "quincy")
        assertEquals("Quincy, MA, US", quincy.location)
        assertEquals("Seattle, WA, US", at("3", "full").location, "a filled-in city and region win over a street address")
        assertEquals("Seattle, WA, USA (Onsite)", at("4", "office").location, "the Location label wins over the street address (the recruiting office)")
        assertFalse(com.jd.watchlist.filter.PostingFilter(com.jd.watchlist.config.Config.TITLE_INCLUDE, com.jd.watchlist.config.Config.TITLE_EXCLUDE).locationMatches(quincy.location!!))
    }

    @Test
    fun `missing feed or link pattern is a configuration error`() {
        assertFailsWith<IllegalArgumentException> { GenericHtmlAdapter(apexHttp, emptyList()).list(apex.copy(feed = null)) }
        assertFailsWith<IllegalArgumentException> { GenericHtmlAdapter(apexHttp, emptyList()).list(apex.copy(linkPattern = null)) }
    }
}
