package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OracleHcmAdapterTest {
    private val honeywell = Employer(company = "Honeywell Aerospace", ats = "oracle_hcm", host = "icfcjb.fa.ocs.oraclecloud.com", site = "CX_4001", search = listOf("Redmond"))
    private fun http() = FakeHttp(getHandler = { url ->
        when {
            "recruitingCEJobRequisitions?" in url -> resource("oracle_list.json")
            "recruitingCEJobRequisitionDetails" in url -> resource("oracle_detail.json")
            else -> null
        }
    })

    @Test
    fun `searches each area city by keyword with literal finder syntax and the quoted term encoded`() {
        val http = http()
        val postings = OracleHcmAdapter(http).list(honeywell)
        val list = http.requests.single()
        assertTrue("finder=findReqs;siteNumber=CX_4001,limit=25,offset=0,keyword=%22Redmond%22,sortBy=POSTING_DATES_DESC" in list, list)
        assertEquals(listOf("124886", "124901"), postings.map { it.externalId })
        assertEquals("https://icfcjb.fa.ocs.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_4001/job/124886", postings[0].url)
        assertTrue("Redmond, WA" in postings[1].location && "Remote" in postings[1].location, "secondary locations + remote count: ${postings[1].location}")
    }

    @Test
    fun `falls back to the default area cities and stops paging on a short page`() {
        val http = http()
        OracleHcmAdapter(http).list(honeywell.copy(search = null))
        assertEquals(OracleHcmAdapter.DEFAULT_AREA_TERMS.size, http.requests.size, "one short page per city")
    }

    @Test
    fun `describe joins description, responsibilities and qualifications from the ById detail`() {
        val http = http()
        val adapter = OracleHcmAdapter(http)
        val text = adapter.describe(honeywell, adapter.list(honeywell).first())!!
        assertTrue(http.requests.last().contains("finder=ById;Id=%22124886%22,siteNumber=CX_4001"), http.requests.last())
        listOf("Build automated test systems", "Own the HIL test framework", "Python test automation").forEach { assertTrue(it in text, it) }
    }
}

class EightfoldAdapterTest {
    private val starbucks = Employer(company = "Starbucks", ats = "eightfold", api = "pcsx", host = "starbucks.eightfold.ai", domain = "starbucks.com", location = "Seattle, WA")

    @Test
    fun `pcsx - searches within the location, pages until empty, makes relative URLs absolute`() {
        var pauses = 0
        val http = FakeHttp(getHandler = { url ->
            when {
                "/api/pcsx/search" in url && "start=0" in url -> resource("eightfold_pcsx.json")
                "/api/pcsx/search" in url -> resource("eightfold_pcsx_empty.json")
                "/api/pcsx/position_details" in url -> resource("eightfold_pcsx_detail.json")
                else -> null
            }
        })
        val adapter = EightfoldAdapter(http, listOf("quality"), pause = { pauses++ })
        val postings = adapter.list(starbucks)
        assertEquals(listOf("481077770111", "481077770222"), postings.map { it.externalId })
        assertEquals("https://starbucks.eightfold.ai/careers/job/481077770111", postings[0].url)
        assertEquals("Remote", postings[1].location, "remote work option counts as a location")
        val searches = http.requests.filter { "/api/pcsx/search" in it }
        assertEquals(2, searches.size, "second page is empty → stop")
        assertTrue(searches.all { "location=Seattle%2C%20WA" in it && "domain=starbucks.com" in it && "query=quality" in it })
        assertTrue("start=2" in searches[1], "start advances by the page size")
        assertEquals(1, pauses, "a pause between consecutive requests")
        assertTrue("Own release quality" in adapter.describe(starbucks, postings[0])!!)
    }

    @Test
    fun `a search still rate-limited after backoff keeps earlier results, a first-request 429 fails`() {
        val http = FakeHttp(getHandler = { url ->
            when {
                "query=test" in url && "start=0" in url -> resource("eightfold_pcsx.json")
                "query=test" in url -> resource("eightfold_pcsx_empty.json")
                "query=quality" in url -> throw com.jd.watchlist.http.HttpStatusException(url, 429)
                else -> null
            }
        })
        val postings = EightfoldAdapter(http, listOf("test", "quality", "sdet")).list(starbucks)
        assertEquals(2, postings.size, "the 'test' results survive the 429 on 'quality'")
        assertTrue(http.requests.none { "query=sdet" in it }, "stops searching once rate-limited")

        val limited = FakeHttp(getHandler = { url -> throw com.jd.watchlist.http.HttpStatusException(url, 429) })
        val e = kotlin.test.assertFailsWith<com.jd.watchlist.http.HttpStatusException> { EightfoldAdapter(limited, listOf("test")).list(starbucks) }
        assertEquals(429, e.status, "nothing found yet → the employer is reported as failing")
    }

    @Test
    fun `v2 - reads positions and fetches job_description per posting`() {
        val symetra = Employer(company = "Symetra", ats = "eightfold", api = "v2", host = "symetra.eightfold.ai", domain = "symetra.com")
        val http = FakeHttp(getHandler = { url ->
            when {
                "/api/apply/v2/jobs?" in url && "start=0" in url -> resource("eightfold_v2.json")
                "/api/apply/v2/jobs?" in url -> """{"positions":[]}"""
                "/api/apply/v2/jobs/446718414598" in url -> resource("eightfold_v2_detail.json")
                else -> null
            }
        })
        val adapter = EightfoldAdapter(http, listOf("test"))
        val p = adapter.list(symetra).single()
        assertEquals("USA | Bellevue, WA, United States", p.location)
        assertEquals("https://symetra.eightfold.ai/careers/job/446718414598", p.url)
        assertTrue(http.requests.none { "location=" in it }, "no location filter configured → none sent")
        assertEquals("Lead our Playwright automation practice.", adapter.describe(symetra, p))
    }
}

class SmartRecruitersAdapterTest {
    @Test
    fun `pages until totalFound and reads description sections from the ref document`() {
        val ref = "https://api.smartrecruiters.com/v1/companies/Acme1/postings/744000155377769"
        val http = FakeHttp(
            gets = mapOf(
                "https://api.smartrecruiters.com/v1/companies/Acme1/postings?limit=100&offset=0" to resource("smartrecruiters_p1.json"),
                ref to resource("smartrecruiters_detail.json"),
            ),
        )
        val acme = Employer(company = "Acme", ats = "smartrecruiters", board = "Acme1")
        val adapter = SmartRecruitersAdapter(http)
        val postings = adapter.list(acme)
        assertEquals(1, http.requests.size, "offset reached totalFound → no second page")
        assertEquals("https://jobs.smartrecruiters.com/Acme1/744000155377769", postings[0].url)
        assertTrue("Seattle" in postings[0].location)
        assertTrue("Remote" in postings[1].location)
        val text = adapter.describe(acme, postings[0])!!
        assertTrue("Design and run test plans." in text && "Selenium" in text)
        assertTrue("We insure pets." !in text, "company boilerplate is left out")
    }
}

class JibeAdapterTest {
    @Test
    fun `adds each term as keywords to the configured location feed and reads inline descriptions`() {
        val feed = "https://careers.avalara.com/api/jobs?location=Seattle%2C+WA&stretch=30"
        val http = FakeHttp(getHandler = { url -> if (url.startsWith("$feed&keywords=")) resource("jibe.json") else null })
        val avalara = Employer(company = "Avalara", ats = "jibe", feed = feed)
        val postings = JibeAdapter(http, listOf("test", "quality")).list(avalara)
        assertEquals(listOf("17361", "17400"), postings.map { it.externalId }, "deduped across terms")
        assertEquals(listOf("$feed&keywords=test&limit=100&page=1", "$feed&keywords=quality&limit=100&page=1"), http.requests.map { it.removePrefix("GET ") })
        assertEquals("https://careers.avalara.com/jobs/17361?lang=en-us", postings[0].url)
        assertEquals("https://careersnoa-avalara.icims.com/jobs/17400/login", postings[1].url, "falls back to apply_url")
        assertTrue("Remote" in postings[1].location)
        assertTrue(postings[0].description!!.let { "Test our tax engine." in it && "Write automation." in it && "Java" in it })
    }

    @Test
    fun `a feed without a query string gets one`() {
        val http = FakeHttp(getHandler = { """{"jobs":[]}""" })
        JibeAdapter(http, listOf("qa")).list(Employer(company = "REI", ats = "jibe", feed = "https://www.rei.jobs/api/jobs"))
        assertEquals("GET https://www.rei.jobs/api/jobs?keywords=qa&limit=100&page=1", http.requests.single())
    }
}

class AmazonAdapterTest {
    @Test
    fun `filters to Washington newest-first and joins description with qualifications`() {
        val http = FakeHttp(getHandler = { url -> if ("search.json" in url) resource("amazon.json") else null })
        val postings = AmazonAdapter(http, listOf("quality")).list(Employer(company = "Amazon", ats = "amazon"))
        val url = http.requests.single()
        listOf("base_query=quality", "normalized_state_name%5B%5D=Washington", "normalized_country_code%5B%5D=USA", "sort=recent", "offset=0")
            .forEach { assertTrue(it in url, it) }
        assertEquals(listOf("10578031", "10578032"), postings.map { it.externalId })
        assertEquals("https://www.amazon.jobs/en/jobs/10578031/quality-assurance-engineer-amazon-leo", postings[0].url)
        assertEquals("Redmond, Washington, USA", postings[0].location)
        val d = postings[0].description!!
        assertTrue("Own release gates." in d && "BASIC QUALIFICATIONS" in d && "PREFERRED QUALIFICATIONS" in d, d)
        assertTrue("BASIC QUALIFICATIONS" !in postings[1].description!!, "empty sections are omitted")
        assertNull(postings[0].detailRef)
    }
}
