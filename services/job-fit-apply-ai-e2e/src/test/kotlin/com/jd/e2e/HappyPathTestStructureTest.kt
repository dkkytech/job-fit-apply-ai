package com.jd.e2e

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Guards the test lifecycle contract: infrastructure belongs in setup; the transaction is a test. */
@DisplayName("HappyPath E2E lifecycle structure")
class HappyPathTestStructureTest {

    @Test
    fun `BeforeAll starts only the harness and the workflow is a scenario test`() {
        val methods = HappyPathE2ETest::class.java.declaredMethods.associateBy { it.name }

        val setup = methods["startHarness"]
        assertNotNull(setup, "expected a dedicated startHarness setup method")
        assertNotNull(setup.getAnnotation(BeforeAll::class.java), "startHarness must be @BeforeAll")
        assertNull(setup.getAnnotation(Test::class.java), "startHarness must not be a test")

        val scenario = methods["tailoredJobCompletesEndToEnd"]
        assertNotNull(scenario, "the end-to-end transaction must be a scenario-level test")
        assertNotNull(scenario.getAnnotation(Test::class.java), "scenario must be @Test")
        assertNull(scenario.getAnnotation(BeforeAll::class.java), "scenario must not be @BeforeAll")

        assertNull(methods["runTheWholeFlow"], "the workflow must not remain hidden in @BeforeAll")
    }
}
