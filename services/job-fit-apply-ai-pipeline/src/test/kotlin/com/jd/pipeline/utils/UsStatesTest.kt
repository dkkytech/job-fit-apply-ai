package com.jd.pipeline.utils

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("UsStates")
class UsStatesTest {

    @Test
    @DisplayName("reads codes, full names, and zip-suffixed codes")
    fun readsCommonForms() {
        assertEquals(setOf("WA"), UsStates.statesIn("Bothell, WA"))
        assertEquals(setOf("WA"), UsStates.statesIn("Seattle, Washington, United States"))
        assertEquals(setOf("WA"), UsStates.statesIn("Seattle, WA 98101"))
        assertEquals(setOf("WA"), UsStates.statesIn("Seattle WA"))
        assertEquals(setOf("NC", "NJ"), UsStates.statesIn("Charlotte, NC, Mount Laurel, NJ"))
    }

    @Test
    @DisplayName("names no state for neighborhoods, countries, and remote")
    fun noStateWhenUnidentifiable() {
        assertEquals(emptySet(), UsStates.statesIn("South Lake Union"))
        assertEquals(emptySet(), UsStates.statesIn("United States"))
        assertEquals(emptySet(), UsStates.statesIn("Remote - US"))
        assertEquals(emptySet(), UsStates.statesIn("London, UK"))
    }

    @Test
    @DisplayName("Washington, DC and District of Columbia are DC, not WA")
    fun dcIsNotWashingtonState() {
        assertEquals(setOf("DC"), UsStates.statesIn("Washington, DC"))
        assertEquals(setOf("DC"), UsStates.statesIn("Washington, D.C."))
        assertEquals(setOf("DC"), UsStates.statesIn("Washington, District of Columbia"))
    }

    @Test
    @DisplayName("codeOf accepts a code in any case or a full name, and rejects non-states")
    fun codeOf() {
        assertEquals("WA", UsStates.codeOf("WA"))
        assertEquals("WA", UsStates.codeOf("wa"))
        assertEquals("WA", UsStates.codeOf("Washington"))
        assertEquals(null, UsStates.codeOf("non-US"))
        assertEquals(null, UsStates.codeOf("xx"))
    }

    @Test
    @DisplayName("a lowercase code counts only when it is the whole comma-separated part")
    fun lowercaseWholeTokenCodes() {
        assertEquals(setOf("TX"), UsStates.statesIn("Austin, tx"))
        assertEquals(setOf("WA"), UsStates.statesIn("Bellevue, wa 98004"))
        assertEquals(setOf("TX"), UsStates.statesIn("Austin, Tx"))
        // A two-letter word inside a phrase is not a state.
        assertEquals(emptySet(), UsStates.statesIn("Seattle or remote"))
        assertEquals(emptySet(), UsStates.statesIn("Hybrid, in office twice a week"))
    }
}
