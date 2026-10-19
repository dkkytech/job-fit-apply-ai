package com.jd.watchlist

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** Settings read from the root .env only reach the container if compose passes them through. */
class ComposeEnvTest {
    @Test
    fun `compose passes the schedule settings to the watchlist container`() {
        val env = ObjectMapper(YAMLFactory()).readTree(Files.readString(Path.of("../../docker-compose.yml")))
            .path("services").path("watchlist").path("environment")
        for (key in listOf("WATCHLIST_RUN_AT", "WATCHLIST_TZ", "WATCHLIST_INTERVAL_MS")) {
            assertEquals(key, Regex("""^\$\{(\w+):-""").find(env.path(key).asText())?.groupValues?.get(1), "$key is passed from the root .env")
        }
    }
}
