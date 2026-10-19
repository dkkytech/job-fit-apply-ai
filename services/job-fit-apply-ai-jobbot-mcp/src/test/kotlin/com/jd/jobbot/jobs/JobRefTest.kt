package com.jd.jobbot.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobRefTest {
    @Test
    fun `accepts the card form, the bare J form and the bare number`() {
        assertEquals(7663L, JobRef.parse("#J7663"))
        assertEquals(7663L, JobRef.parse("J7663"))
        assertEquals(7663L, JobRef.parse("j7663"))
        assertEquals(7663L, JobRef.parse(" 7663 "))
    }

    @Test
    fun `rejects everything that is not a single positive reference`() {
        listOf(null, "", "#J", "#J0", "J-3", "7663 and 7664", "../7663", "#J12a").forEach { assertNull(JobRef.parse(it), "$it") }
    }

    @Test
    fun `finds every card reference in quoted text, once each`() {
        val quoted = "High-fit: Acme — Staff SDET — 80\n#J7663\nalso see #J12 and #J7663 again"
        assertEquals(listOf(7663L, 12L), JobRef.findAll(quoted))
        assertEquals(emptyList(), JobRef.findAll(null))
    }
}
