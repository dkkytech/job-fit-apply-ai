package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkableAdapterTest {
    @Test
    fun `reads the widget with inline descriptions, every location and remote`() {
        val http = FakeHttp(gets = mapOf("https://apply.workable.com/api/v1/widget/accounts/xplore-inc?details=true" to resource("workable.json")))
        val postings = WorkableAdapter(http).list(Employer(company = "Xplore", ats = "workable", board = "xplore-inc"))
        assertEquals(listOf("CD9CC1E9F6", "AB12"), postings.map { it.externalId })
        assertEquals("Bellevue, Washington, United States | Redmond, Washington, United States", postings[0].location)
        assertEquals("Qualify spacecraft avionics.", postings[0].description)
        assertTrue("Remote" in postings[1].location)
        assertEquals("https://apply.workable.com/xplore-inc/j/AB12/", postings[1].url, "missing url falls back to the board link")
    }
}

class GemAdapterTest {
    @Test
    fun `reads posts with plain-text content and office locations`() {
        val http = FakeHttp(gets = mapOf("https://api.gem.com/job_board/v0/wyzecam-com/job_posts" to resource("gem.json")))
        val p = GemAdapter(http).list(Employer(company = "Wyze", ats = "gem", board = "wyzecam-com")).single()
        assertEquals("SDET, Smart Home", p.title)
        assertEquals("Kirkland, United States | Kirkland, WA", p.location)
        assertEquals("Own firmware test automation.", p.description)
    }
}

class RipplingAdapterTest {
    @Test
    fun `lists without descriptions and reads the role section from the job document`() {
        val board = "https://api.rippling.com/platform/api/ats/v1/board/zap-energy-careers/jobs"
        val http = FakeHttp(gets = mapOf(board to resource("rippling_list.json"), "$board/d97bdb74" to resource("rippling_detail.json")))
        val zap = Employer(company = "Zap Energy", ats = "rippling", board = "zap-energy-careers")
        val adapter = RipplingAdapter(http)
        val p = adapter.list(zap).single()
        assertEquals("Everett, WA", p.location)
        assertNull(p.description)
        val text = adapter.describe(zap, p)!!
        assertEquals("Design and run high-voltage test campaigns.", text, "company boilerplate is left out")
    }
}

class AdpAdapterTest {
    private val feed = "https://workforcenow.adp.com/mascsr/default/careercenter/public/events/staffing/v1/job-requisitions?cid=abc&ccId=123_4&lang=en_US&locale=en_US"
    private val rocketdyne = Employer(company = "Rocketdyne", ats = "adp", feed = feed)

    @Test
    fun `pages with skip and top until totalNumber and builds the public posting link`() {
        val http = FakeHttp(gets = mapOf("$feed&\$skip=0&\$top=20" to resource("adp_p1.json")))
        val postings = AdpAdapter(http).list(rocketdyne)
        assertEquals(1, http.requests.size, "totalNumber reached on the first page")
        assertEquals(listOf("Redmond, WA", "Mason, OH"), postings.map { it.location })
        assertEquals(
            "https://workforcenow.adp.com/mascsr/default/mdf/recruitment/recruitment.html?cid=abc&ccId=123_4&jobId=76400324_1&lang=en_US",
            postings[0].url,
        )
    }

    @Test
    fun `describe reads requisitionDescription from the per-requisition document`() {
        val detail = "https://workforcenow.adp.com/mascsr/default/careercenter/public/events/staffing/v1/job-requisitions/76400324_1?cid=abc&ccId=123_4&lang=en_US&locale=en_US"
        val http = FakeHttp(gets = mapOf("$feed&\$skip=0&\$top=20" to resource("adp_p1.json"), detail to resource("adp_detail.json")))
        val adapter = AdpAdapter(http)
        assertEquals("Run hot-fire test campaigns for in-space propulsion.", adapter.describe(rocketdyne, adapter.list(rocketdyne).first()))
    }
}

class PhenomAdapterTest {
    @Test
    fun `refineSearch per term with a state filter, jobDetail for the description`() {
        val http = FakeHttp(posts = mapOf("https://careers.teksystems.com/widgets" to { body: String ->
            if ("\"ddoKey\":\"jobDetail\"" in body) resource("phenom_detail.json") else resource("phenom_search.json")
        }))
        val tek = Employer(company = "TEKsystems", ats = "phenom", host = "careers.teksystems.com", location = "Washington")
        val adapter = PhenomAdapter(http, listOf("qa"))
        val postings = adapter.list(tek)
        val search = http.requests.single()
        assertTrue("\"ddoKey\":\"refineSearch\"" in search && "\"keywords\":\"qa\"" in search, search)
        assertTrue("\"selected_fields\":{\"state\":[\"Washington\"]}" in search, search)
        assertEquals(listOf("Seattle, Washington, USA", "Redmond, Washington, USA"), postings.map { it.location }, "falls back to cityStateCountry")
        assertEquals("https://careers.teksystems.com/us/en/job/JP-006338959", postings[0].url)
        assertEquals("Contract SDET role on a Redmond platform team.", adapter.describe(tek, postings[0]))
    }
}

class TikTokAdapterTest {
    @Test
    fun `POSTs with the website-path header and city codes, joins description and requirements`() {
        val http = FakeHttp(posts = mapOf("https://api.lifeattiktok.com/api/v1/public/supplier/search/job/posts" to { _: String -> resource("tiktok.json") }))
        val postings = TikTokAdapter(http, listOf("test")).list(Employer(company = "TikTok", ats = "tiktok", location = "CT_157, CT_999"))
        assertEquals("tiktok", http.postHeaders.single()["website-path"])
        assertTrue("\"location_code_list\":[\"CT_157\",\"CT_999\"]" in http.requests.single(), http.requests.single())
        assertEquals("https://lifeattiktok.com/search/7668837276597127477", postings[0].url)
        assertEquals("Build AI test automation.\n\nQualifications\n3+ years of Python.", postings[0].description)
        assertEquals("Manual and automated testing.", postings[1].description, "empty requirement is omitted")
        assertEquals("Seattle", postings[0].location)
    }

    @Test
    fun `defaults to Seattle when no city codes are configured`() {
        val http = FakeHttp(posts = mapOf("https://api.lifeattiktok.com/api/v1/public/supplier/search/job/posts" to { _: String -> """{"data":{"count":0,"job_post_list":[]}}""" }))
        TikTokAdapter(http, listOf("qa")).list(Employer(company = "TikTok", ats = "tiktok"))
        assertTrue("\"location_code_list\":[\"${TikTokAdapter.SEATTLE}\"]" in http.requests.single())
    }
}
