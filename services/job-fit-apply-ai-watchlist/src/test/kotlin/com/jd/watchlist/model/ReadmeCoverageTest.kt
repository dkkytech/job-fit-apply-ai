package com.jd.watchlist.model

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.adapters.Adapters
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** The README's coverage table is computed from employers.yaml and the adapter registry, not typed. */
class ReadmeCoverageTest {

    @Test
    fun `the README coverage counts match the catalog`() {
        val all = EmployerCatalog.load("")
        val adapters = Adapters.registry(FakeHttp(), emptyList()).keys
        val expected = linkedMapOf(
            "Polled" to all.count { it.enabled && it.ats in adapters },
            "Feed did not verify (`enabled: false`)" to all.count { !it.enabled },
            "No adapter for their platform" to all.count { it.enabled && it.ats !in adapters },
            "Total" to all.size,
        )
        val readme = Files.readString(Path.of("README.md"))
        val table = expected.keys.associateWith { label ->
            Regex("""^\| ${Regex.escape(label)} \| (\d+) \|$""", RegexOption.MULTILINE).find(readme)?.groupValues?.get(1)?.toInt()
        }
        assertEquals<Map<String, Int?>>(expected, table, "update the Coverage table in README.md")
    }
}
