package com.jd.watchlist.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.watchlist.WatchlistRunner
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watchlist's half of contract/posting_keys.json: each case's employer + posting id must key
 * the way the processor keys the same posting's link in an employer job-alert email
 * (PostingLinks.identify), so a posting found by both is processed once.
 */
class PostingKeyContractTest {

    private val mapper = ObjectMapper()

    @TestFactory
    fun cases(): List<DynamicTest> {
        val root = mapper.readTree(Files.readString(Path.of("contract/posting_keys.json")))
        val watchlistCases = root.path("cases").filter { it.has("employer") }
        assertTrue(watchlistCases.size >= 7, "too few watchlist cases in the contract")
        return watchlistCases.map { case ->
            DynamicTest.dynamicTest(case.path("name").asText()) {
                // The catalog's own parser (JSON is YAML), so the employer reads exactly as employers.yaml would.
                val employer = EmployerCatalog.parse("[${mapper.writeValueAsString(case.path("employer"))}]").single()
                assertEquals(case.path("key").asText(), WatchlistRunner.idempotencyKey(employer, case.path("external_id").asText()))
            }
        }
    }
}
