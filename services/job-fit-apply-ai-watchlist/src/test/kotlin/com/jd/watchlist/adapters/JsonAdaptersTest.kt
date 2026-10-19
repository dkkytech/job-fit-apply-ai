package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GreenhouseAdapterTest {
    private val http = FakeHttp(gets = mapOf("https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true" to resource("greenhouse.json")))
    private val acme = Employer(company = "Acme", ats = "greenhouse", board = "acme")

    @Test
    fun `lists postings with unescaped description and office locations`() {
        val postings = GreenhouseAdapter(http).list(acme)
        assertEquals(2, postings.size)
        val sdet = postings.first()
        assertEquals("4011", sdet.externalId)
        assertEquals("Senior SDET", sdet.title)
        assertEquals("https://boards.greenhouse.io/acme/jobs/4011", sdet.url)
        assertTrue("Seattle" in sdet.location, sdet.location)              // only present under offices
        assertTrue("Build test automation." in sdet.description!!)
        assertTrue("\nKotlin" in sdet.description!!, "list items keep line breaks")
        assertTrue('<' !in sdet.description!!, "no markup left")
        assertNull(postings[1].description)
    }

    @Test
    fun `missing board is a configuration error`() {
        assertFailsWith<IllegalArgumentException> { GreenhouseAdapter(http).list(acme.copy(board = null)) }
    }
}

class LeverAdapterTest {
    private val http = FakeHttp(gets = mapOf("https://api.lever.co/v0/postings/acme?mode=json" to resource("lever.json")))

    @Test
    fun `joins description parts and all locations`() {
        val postings = LeverAdapter(http).list(Employer(company = "Acme", ats = "lever", board = "acme"))
        val qa = postings.first()
        assertEquals("a1b2", qa.externalId)
        assertEquals("Seattle, WA | Bellevue, WA", qa.location)
        listOf("We test things.", "What you'll do", "Write tests", "Benefits apply.").forEach { assertTrue(it in qa.description!!, it) }
        assertTrue("remote" in postings[1].location.lowercase())
    }
}

class AshbyAdapterTest {
    private val http = FakeHttp(gets = mapOf("https://api.ashbyhq.com/posting-api/job-board/acme" to resource("ashby.json")))

    @Test
    fun `skips unlisted postings and includes secondary locations`() {
        val postings = AshbyAdapter(http).list(Employer(company = "Acme", ats = "ashby", board = "acme"))
        assertEquals(listOf("u-1"), postings.map { it.externalId })
        assertEquals("Everett, WA | Remote", postings.single().location)
        assertEquals("Fusion test rigs.", postings.single().description)
    }
}
