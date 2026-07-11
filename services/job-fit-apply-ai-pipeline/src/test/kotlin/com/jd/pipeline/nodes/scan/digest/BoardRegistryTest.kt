package com.jd.pipeline.nodes.scan.digest

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("BoardRegistry")
class BoardRegistryTest {

    @Nested
    @DisplayName("groupFor — exact domain matches")
    inner class ExactDomainMatches {

        @Test fun linkedin()          { assertEquals("linkedin",             BoardRegistry.groupFor("linkedin.com")?.key) }
        @Test fun glassdoor()         { assertEquals("glassdoor",            BoardRegistry.groupFor("glassdoor.com")?.key) }
        @Test fun jobleads()          { assertEquals("jobleads",             BoardRegistry.groupFor("jobleads.com")?.key) }
        @Test fun lensa()             { assertEquals("lensa",                BoardRegistry.groupFor("lensa.com")?.key) }
        @Test fun jobright()          { assertEquals("jobright",             BoardRegistry.groupFor("jobright.ai")?.key) }
        @Test fun wellfound()         { assertEquals("wellfound",            BoardRegistry.groupFor("wellfound.com")?.key) }
        @Test fun wellfoundHi()       { assertEquals("wellfound",            BoardRegistry.groupFor("hi.wellfound.com")?.key) }
        @Test fun monster()           { assertEquals("monster",              BoardRegistry.groupFor("monster.com")?.key) }
        @Test fun monsterNotif()      { assertEquals("monster",              BoardRegistry.groupFor("notifications.monster.com")?.key) }
        @Test fun workday()           { assertEquals("workday",              BoardRegistry.groupFor("workday.com")?.key) }
        @Test fun myWorkdayJobs()     { assertEquals("workday",              BoardRegistry.groupFor("myworkdayjobs.com")?.key) }
        @Test fun greenhouse()        { assertEquals("ats",                  BoardRegistry.groupFor("greenhouse.io")?.key) }
        @Test fun lever()             { assertEquals("ats",                  BoardRegistry.groupFor("lever.co")?.key) }
        @Test fun welcomeToJungle()   { assertEquals("welcome_to_the_jungle",BoardRegistry.groupFor("welcometothejungle.com")?.key) }
    }

    @Nested
    @DisplayName("groupFor — subdomain resolution")
    inner class SubdomainResolution {

        @Test
        @DisplayName("resolves subdomain of a known domain")
        fun subdomainOfKnown() {
            // notifications.monster.com is explicitly listed, but also tests suffix matching
            val group = BoardRegistry.groupFor("mail.greenhouse.io")
            assertNotNull(group)
            assertEquals("ats", group.key)
        }

        @Test
        @DisplayName("resolves deeply nested subdomain")
        fun deepSubdomain() {
            val group = BoardRegistry.groupFor("jobs.lever.co")
            assertNotNull(group)
            assertEquals("ats", group.key)
        }
    }

    @Nested
    @DisplayName("groupFor — unknown and edge cases")
    inner class UnknownAndEdgeCases {

        @Test
        @DisplayName("returns null for completely unknown domain")
        fun unknownDomain() {
            assertNull(BoardRegistry.groupFor("example.com"))
        }

        @Test
        @DisplayName("returns null for null input")
        fun nullInput() {
            assertNull(BoardRegistry.groupFor(null))
        }

        @Test
        @DisplayName("returns null for blank string")
        fun blankInput() {
            assertNull(BoardRegistry.groupFor(""))
        }

        @Test
        @DisplayName("is case-insensitive")
        fun caseInsensitive() {
            assertNotNull(BoardRegistry.groupFor("LinkedIn.COM"))
        }
    }

    @Nested
    @DisplayName("registry completeness")
    inner class RegistryCompleteness {

        @Test
        @DisplayName("every group has a strategy")
        fun everyGroupHasStrategy() {
            val strategyKeys = BoardRegistry.STRATEGIES.keys
            for (group in BoardRegistry.GROUPS) {
                assertTrue(group.key in strategyKeys, "No strategy for group '${group.key}'")
            }
        }

        @Test
        @DisplayName("DOMAINS set contains all individual group domains")
        fun domainsSetComplete() {
            for (group in BoardRegistry.GROUPS) {
                for (domain in group.domains) {
                    assertTrue(domain in BoardRegistry.DOMAINS, "Missing domain: $domain")
                }
            }
        }

        @Test
        @DisplayName("LinkedIn is marked as serialScrape")
        fun linkedInSerialScrape() {
            val linkedin = BoardRegistry.GROUPS.first { it.key == "linkedin" }
            assertTrue(linkedin.serialScrape)
        }
    }
}
