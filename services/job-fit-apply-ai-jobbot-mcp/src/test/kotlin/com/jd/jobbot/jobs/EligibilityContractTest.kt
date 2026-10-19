package com.jd.jobbot.jobs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * The same contract file the notifier's ButtonEligibilityContractTest reads: the button a card
 * shows and the button JobBot accepts must be decided by identical rules.
 */
class EligibilityContractTest {
    private fun contract(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            dir.resolve("docker/jobbot/contract/button_eligibility.json").takeIf { Files.exists(it) }?.let { return it }
            dir = dir.parent
        }
        error("button_eligibility.json not found")
    }

    @TestFactory
    fun cases(): List<DynamicTest> {
        val root = Json.parseToJsonElement(Files.readString(contract())).jsonObject
        assertEquals(Eligibility.VERBS, root["verbs"]!!.jsonArray.map { it.jsonPrimitive.content })
        return root["cases"]!!.jsonArray.map { c ->
            val case = c.jsonObject
            DynamicTest.dynamicTest(case["name"]!!.jsonPrimitive.content) {
                val expected = case["eligible"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(expected, Eligibility.eligibleVerbs(case["event"] as JsonObject))
            }
        }
    }
}
