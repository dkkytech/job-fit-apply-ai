package com.jd.watchlist.filter

import com.jd.watchlist.config.Config
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostingFilterTest {
    private val filter = PostingFilter(Config.TITLE_INCLUDE, Config.TITLE_EXCLUDE)

    @Test
    fun `QE titles match, other engineering titles do not`() {
        listOf("SDET II", "Senior Quality Engineer", "QA Analyst", "Software Engineer in Test", "Test Automation Lead", "QA Automation Engineer",
            "Systems Engineer, Verification and Validation", "Staff QE - Payments").forEach {
            assertTrue(filter.titleMatches(it), it)
        }
        listOf("Software Engineer", "Product Manager", "Data Scientist", "Contest Coordinator", "Attestation Analyst", "Intelligent Automation Analyst").forEach {
            assertFalse(filter.titleMatches(it), it)
        }
    }

    @Test
    fun `exclusions win over inclusions`() {
        assertFalse(filter.titleMatches("QA Intern"))
        assertFalse(filter.titleMatches("Quality Technician"))
        assertFalse(filter.titleMatches("Clinical Quality Specialist"))
        // "Verification" in chip design and compliance is a different discipline.
        assertFalse(filter.titleMatches("Senior ASIC Design Verification Engineer"))
        assertFalse(filter.titleMatches("SLD FPGA Verification Engineer III"))
        assertFalse(filter.titleMatches("Director, Verification Program and AML Controls"))
        assertFalse(filter.titleMatches("Quality Control Microbiologist"))
        assertTrue(filter.titleMatches("Systems and Software Test Engineer IV"))
    }

    @Test
    fun `Seattle area, Washington, remote and unknown locations pass`() {
        listOf("Seattle, WA", "Bellevue, Washington, United States", "USA - Everett, WA", "Remote - US", "",
            "New York, NY | Seattle", "Kent, WA").forEach { assertTrue(filter.locationMatches(it), it) }
    }

    @Test
    fun `other locations fail, including Washington DC`() {
        listOf("Austin, TX", "Washington, DC", "Washington D.C.", "London, UK", "Hawthorne, CA")
            .forEach { assertFalse(filter.locationMatches(it), it) }
    }

    @Test
    fun `a Seattle-area city name in another state is not the Seattle area`() {
        listOf("Everett MA 10/8/2026", "Auburn Hills, MI (Onsite)", "Redmond, OR", "Bellevue, Nebraska", "Kent, Ohio", "Arlington, Texas")
            .forEach { assertFalse(filter.locationMatches(it), it) }
        listOf("Everett, WA", "LocationSeattle, Washington CategoryRetail", "Redmond WA 7/24/2026", "Auburn Hills, MI | Remote",
            "onsite in Redmond, WA Pay Range: \$21", "Seattle (or Bellevue)", "Bellevue, WA, US")
            .forEach { assertTrue(filter.locationMatches(it), it) }
    }
}
