package com.jd.watchlist.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmployerCatalogTest {

    @Test
    fun `parses snake_case YAML and defaults enabled to true`() {
        val list = EmployerCatalog.parse(
            """
            - company: "Costco IT"
              ats: "rss"
              platform: "taleo_tbe"
              feed: "https://phf.tbe.taleo.net/phf02/ats/servlet/Rss?org=COSTCO&cws=41"
              detail_selector: ".x"
              qe_fit: "high"
            - company: "Walmart Global Tech"
              ats: "workday"
              enabled: false
            """.trimIndent()
        )
        assertEquals(2, list.size)
        assertEquals(".x", list[0].detailSelector)
        assertTrue(list[0].enabled)
        assertEquals("phf.tbe.taleo.net", list[0].boardKey)
        assertEquals(false, list[1].enabled)
    }

    @Test
    fun `boardKey prefers board, then workday tenant and site`() {
        assertEquals("acme", Employer(company = "Acme", ats = "greenhouse", board = "acme").boardKey)
        assertEquals("boeing/EXTERNAL_CAREERS", Employer(company = "Boeing", ats = "workday", tenant = "boeing", site = "EXTERNAL_CAREERS").boardKey)
        assertEquals("some-co", Employer(company = "Some Co", ats = "custom").boardKey)
        assertEquals("icfcjb.fa.ocs.oraclecloud.com/CX_4001", Employer(company = "Honeywell", ats = "oracle_hcm", host = "icfcjb.fa.ocs.oraclecloud.com", site = "CX_4001").boardKey)
        assertEquals("starbucks.eightfold.ai", Employer(company = "Starbucks", ats = "eightfold", host = "starbucks.eightfold.ai", domain = "starbucks.com").boardKey)
        // Workday keys are unchanged by the host fallback (tenant/site comes first), so live dedup keys hold.
        assertEquals("boeing/EXTERNAL_CAREERS", Employer(company = "Boeing", ats = "workday", host = "boeing.wd1.myworkdayjobs.com", tenant = "boeing", site = "EXTERNAL_CAREERS").boardKey)
        // A tenant without a site must not produce "boeing/null"; it falls through to the company slug.
        assertEquals("boeing-co", Employer(company = "Boeing Co", ats = "workday", tenant = "boeing").boardKey)
    }

    @Test
    fun `bundled employers yaml loads and every enabled adapter entry has its parameters`() {
        val all = EmployerCatalog.load("")
        assertTrue(all.size >= 200, "the whole watchlist is in the file (${all.size})")
        val required = mapOf(
            "greenhouse" to { e: Employer -> e.board }, "lever" to { e: Employer -> e.board }, "ashby" to { e: Employer -> e.board },
            "workday" to { e: Employer -> e.host?.let { e.tenant }?.let { e.site } }, "rss" to { e: Employer -> e.feed },
            "oracle_hcm" to { e: Employer -> e.host?.let { e.site } }, "eightfold" to { e: Employer -> e.host?.let { e.domain } },
            "smartrecruiters" to { e: Employer -> e.board }, "jibe" to { e: Employer -> e.feed }, "amazon" to { _: Employer -> "ok" },
            "workable" to { e: Employer -> e.board }, "gem" to { e: Employer -> e.board }, "rippling" to { e: Employer -> e.board },
            "adp" to { e: Employer -> e.feed }, "phenom" to { e: Employer -> e.host }, "tiktok" to { _: Employer -> "ok" },
            "icims" to { e: Employer -> e.feed }, "jobvite" to { e: Employer -> e.board },
            "apple" to { _: Employer -> "ok" }, "capgemini" to { _: Employer -> "ok" },
            "jobsyn" to { e: Employer -> e.host }, "epam" to { _: Employer -> "ok" },
            "successfactors" to { e: Employer -> e.host }, "ukg" to { e: Employer -> e.tenant?.let { e.board } },
            "paylocity" to { e: Employer -> e.board }, "zoho" to { e: Employer -> e.host }, "atlassian" to { _: Employer -> "ok" },
            "html" to { e: Employer -> e.feed?.let { e.linkPattern } },
            "kforce" to { e: Employer -> e.location }, "roberthalf" to { e: Employer -> e.feed },
            "jobdiva" to { e: Employer -> e.board },
            "insightglobal" to { e: Employer -> e.location }, "griddynamics" to { e: Employer -> e.feed },
        )
        assertEquals(
            com.jd.watchlist.adapters.Adapters.registry(com.jd.watchlist.FakeHttp(), emptyList()).keys, required.keys,
            "every shipped adapter has its required parameters checked here",
        )
        val broken = all.filter { it.enabled && it.ats in required && required.getValue(it.ats)(it).isNullOrBlank() }
        assertEquals(emptyList(), broken.map { it.company })
        assertEquals(all.size, all.map { it.company }.toSet().size, "company names are unique")
    }
}
