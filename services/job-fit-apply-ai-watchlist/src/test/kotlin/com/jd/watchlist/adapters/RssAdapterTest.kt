package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class RssAdapterTest {

    @Test
    fun `Taleo Business Edition feed - inline description and taleo location fields`() {
        val feed = "https://phf.tbe.taleo.net/phf02/ats/servlet/Rss?org=COSTCO&cws=41"
        val costco = Employer(company = "Costco IT", ats = "rss", feed = feed)
        val adapter = RssAdapter(FakeHttp(gets = mapOf(feed to resource("taleo_tbe.xml"))))
        val p = adapter.list(costco).single()
        assertEquals("Quality Engineer - Costco Travel", p.title)
        assertEquals("Issaquah, WA", p.location)
        assertTrue(p.url.endsWith("rid=10965"))
        assertTrue(adapter.describe(costco, p)!!.startsWith("Costco Travel IT is hiring"))
    }

    @Test
    fun `Avature feed - empty item description is fetched from the posting page via the selector`() {
        val feed = "https://careers.example.com/en_US/careers/SearchCareer/feed/"
        val link = "https://careers.example.com/careers/JobDetail/Senior-Quality-Engineer/63957"
        val lulu = Employer(company = "lululemon", ats = "rss", feed = feed, detailSelector = ".article__content--rich-text")
        val adapter = RssAdapter(FakeHttp(gets = mapOf(feed to resource("avature.xml"), link to resource("avature_detail.html"))))
        val p = adapter.list(lulu).single()
        assertEquals(link, p.externalId)
        assertEquals("", p.location)
        val text = adapter.describe(lulu, p)!!
        assertTrue(text.startsWith("Description & Requirements"), text)
        assertTrue("Playwright" in text)
        assertTrue("Sign in" !in text && "Privacy" !in text, "only the selected container")
    }

    @Test
    fun `NEOGOV feed with a byte-order mark and namespaced location parses`() {
        val feed = "https://www.governmentjobs.com/SearchEngine/JobsFeed?agency=bellevuewa"
        val p = RssAdapter(FakeHttp(gets = mapOf(feed to resource("neogov.xml"))))
            .list(Employer(company = "City of Bellevue", ats = "rss", feed = feed)).single()
        assertEquals("IT Quality Assurance Analyst", p.title)
        assertEquals("Bellevue, WA", p.location)
    }

    @Test
    fun `short teaser without a detail selector is returned as-is`() {
        val feed = "https://www.governmentjobs.com/SearchEngine/JobsFeed?agency=bellevuewa"
        val e = Employer(company = "City of Bellevue", ats = "rss", feed = feed)
        val adapter = RssAdapter(FakeHttp(gets = mapOf(feed to resource("neogov.xml"))))
        assertEquals("Short teaser.", adapter.describe(e, adapter.list(e).single()))
    }

    @Test
    fun `items with neither guid nor link are dropped rather than sharing an empty id`() {
        val feed = "https://example.com/feed"
        val xml = """<rss version="2.0"><channel>
            <item><title>QA Engineer</title><description>no id</description></item>
            <item><title>SDET</title><link>https://example.com/jobs/2</link></item>
            </channel></rss>"""
        val postings = RssAdapter(FakeHttp(gets = mapOf(feed to xml))).list(Employer(company = "X", ats = "rss", feed = feed))
        assertEquals(listOf("https://example.com/jobs/2"), postings.map { it.externalId })
    }

    @Test
    fun `feeds with a DOCTYPE are rejected - no external entities`() {
        val feed = "https://evil.example/feed"
        assertFails { RssAdapter(FakeHttp(gets = mapOf(feed to resource("doctype.xml")))).list(Employer(company = "X", ats = "rss", feed = feed)) }
    }
}
