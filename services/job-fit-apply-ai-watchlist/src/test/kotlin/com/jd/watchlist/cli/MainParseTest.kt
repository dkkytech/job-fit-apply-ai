package com.jd.watchlist.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MainParseTest {
    @Test
    fun `parses commands`() {
        assertEquals(Command.Once, Main.parse(arrayOf("--once")))
        assertEquals(Command.DryRun, Main.parse(arrayOf("--dry-run")))
        assertEquals(Command.DryRun, Main.parse(arrayOf("--once", "--dry-run")), "dry-run wins: never submit by accident")
        assertEquals(Command.Usage, Main.parse(emptyArray()))
    }
}
