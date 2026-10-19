package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which failures skip a whole site, for how long, and DataDome detection. Fixtures are trimmed from
 * real pages saved in pipeline-output (Monster, September 2026).
 */
@DisplayName("ScrapeJdNode — site skipping and bot-check detection")
class ScrapeJdDomainSkipTest {

    private var now = 0L
    private val node = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called") },
        domainSkipTtlMs = 30 * 60_000L, nanoTime = { now }).apply { verbose = false }

    @Test
    @DisplayName("only site-wide blocks skip the site; a dead posting (404) does not")
    fun onlySiteWideBlocksSkipTheSite() {
        assertFalse(ScrapeJdNode.PageContent("", "", "HTTP 404").blocksDomain, "one expired Dice posting must not skip every Dice job")
        assertFalse(ScrapeJdNode.PageContent("", "", "tracking link resolved to Monster search results page").blocksDomain)
        assertTrue(ScrapeJdNode.PageContent("", "", "HTTP 403", isBotBlock = true).blocksDomain)
        assertTrue(ScrapeJdNode.PageContent("", "", "HTTP 429", isRateLimited = true).blocksDomain)
        assertTrue(ScrapeJdNode.PageContent("", "", "Cloudflare browser challenge", isCaptchaBlock = true).blocksDomain)
    }

    @Test
    @DisplayName("a skipped site comes back after the TTL instead of staying skipped until restart")
    fun skippedSiteLapses() {
        node.batchBlockedDomains.add("www.dice.com")
        node.batchAuthExpiredDomains.add("www.linkedin.com")
        now += 31L * 60_000 * 1_000_000
        assertFalse("www.dice.com" in node.batchBlockedDomains)
        assertFalse(node.batchLinkedInSessionExpired)
    }

    private val dataDomeChallenge = """
        <html lang="en"><head><title>monster.com</title><style>#cmsg{animation: A 1.5s;}</style></head>
        <body style="margin:0"><script data-cfasync="false">var dd={'rt':'c','host':'geo.captcha-delivery.com'}</script>
        <script data-cfasync="false" src="https://ct.captcha-delivery.com/c.js"></script>
        <iframe src="https://geo.captcha-delivery.com/captcha/?initialCid=AHrl&amp;t=fe" title="DataDome CAPTCHA"
          width="100%" height="100%"></iframe></body></html>
    """.trimIndent()

    // A real Monster job page also loads DataDome's tag and an auto-passing interstitial frame.
    private val normalMonsterPage = """
        <html><head><title>Embedded Software Engineers - ZP Group | Monster</title>
        <script async="" src="https://datadome.monster.com/tags.js"></script></head>
        <body><iframe src="https://geo.captcha-delivery.com/interstitial/?initialCid=AHrl"></iframe>
        <main><h1>Embedded Software Engineers</h1><p>${"Design and test embedded firmware for flight systems. ".repeat(60)}</p></main>
        </body></html>
    """.trimIndent()

    @Test
    @DisplayName("recognizes Monster's DataDome CAPTCHA page")
    fun detectsDataDomeChallenge() {
        assertEquals("DataDome CAPTCHA", node.detectCaptchaInHtml(dataDomeChallenge))
    }

    @Test
    @DisplayName("does not flag a normal Monster job page that loads DataDome's tag and interstitial")
    fun normalMonsterPageIsNotAChallenge() {
        assertNull(node.detectCaptchaInHtml(normalMonsterPage))
    }
}
