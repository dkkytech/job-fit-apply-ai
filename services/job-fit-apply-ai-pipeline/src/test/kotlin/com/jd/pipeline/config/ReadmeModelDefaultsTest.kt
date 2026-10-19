package com.jd.pipeline.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The README's Models table is what someone cloning the repo reads to learn which model each node
 * uses when `.env` names none. It drifted once (it claimed `qwen3.5:9b-q4_K_M` long after the
 * code moved to oMLX ids), so pin it to the defaults in Config.kt. Both are read as source text,
 * so a developer's own `.env` can't make this flaky.
 */
@DisplayName("README model defaults match Config.kt")
class ReadmeModelDefaultsTest {

    private val config = Files.readString(Path.of("src/main/kotlin/com/jd/pipeline/config/Config.kt"))
    private val readme = Files.readString(Path.of("README.md"))

    /** `val SCAN_MODEL: String = get("SCAN_MODEL", "Qwen…")` → "Qwen…"; `get("SCRAPE_MODEL", SCAN_MODEL)` → "same as SCAN_MODEL". */
    private fun codeDefaults(): Map<String, String> =
        Regex("""val (\w+_MODEL): String = get\("\1", (?:"([^"]*)"|(\w+))\)""").findAll(config)
            .associate { m -> m.groupValues[1] to (m.groupValues[2].ifEmpty { "same as ${m.groupValues[3]}" }) }
            .filterKeys { it != "LOCAL_FALLBACK_MODEL" }   // opt-in, documented with the fallback, blank by default

    /** Rows of the Models table: | `VAR` | `default` or same as `OTHER` | used by |. */
    private fun readmeDefaults(): Map<String, String> {
        val section = readme.substringAfter("### Models").substringBefore("\n### ")
        return Regex("""^\| `(\w+_MODEL)` \| (?:`([^`]+)`|same as `(\w+)`) \|""", RegexOption.MULTILINE).findAll(section)
            .associate { m -> m.groupValues[1] to (m.groupValues[2].ifEmpty { "same as ${m.groupValues[3]}" }) }
    }

    @Test
    fun `every node model's default in the README is the one Config_kt uses`() {
        val code = codeDefaults()
        assertTrue(code.size >= 7, "found too few *_MODEL defaults in Config.kt: $code")
        assertEquals(code, readmeDefaults(), "README Models table vs Config.kt defaults")
    }
}
