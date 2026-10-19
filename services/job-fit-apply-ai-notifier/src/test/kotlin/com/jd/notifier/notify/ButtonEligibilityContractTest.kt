package com.jd.notifier.notify

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.notifier.bridge.CompletedEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The notifier decides which action buttons to send; the JobBot plugin re-checks the same rules
 * on every tap. Both read `docker/jobbot/contract/button_eligibility.json`, so the two cannot
 * drift: a rule change that only one side makes fails the other side's suite.
 */
@DisplayName("Button eligibility (shared contract with the JobBot plugin)")
class ButtonEligibilityContractTest {

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun contract(): Path {
        // Gradle runs tests from the service directory; walk up to the repo root.
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("docker/jobbot/contract/button_eligibility.json")
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("docker/jobbot/contract/button_eligibility.json not found above ${Paths.get("").toAbsolutePath()}")
    }

    @TestFactory
    fun cases(): List<DynamicTest> {
        val root = mapper.readTree(Files.readString(contract()))
        assertEquals(
            TelegramButtons.Action.entries.map { it.verb },
            root.path("verbs").map { it.asText() },
            "the contract's verb list must match TelegramButtons.Action",
        )
        val notifier = Notifier(
            client = NotificationClient("", "", "", ""),
            actions = TelegramButtons.Action.entries.toSet(),
            links = ArtifactLinks(enabled = false, timeoutMs = 100, bridgeBase = "http://bridge:8765"),
        )
        val cases = root.path("cases").toList()
        assertTrue(cases.size >= 5, "contract has too few cases")
        return cases.map { case ->
            DynamicTest.dynamicTest(case.path("name").asText()) {
                val fields = case.path("event") as com.fasterxml.jackson.databind.node.ObjectNode
                fields.put("job_id", "contract")
                val event = mapper.treeToValue(fields, CompletedEvent::class.java)
                val expected = case.path("eligible").map { it.asText() }
                assertEquals(expected, notifier.actionsFor(event).map { it.verb })
            }
        }
    }
}
