package com.jd.pipeline.utils

/**
 * Finds US states named in free-text job locations ("Bothell, WA", "Seattle, Washington 98101",
 * "Charlotte, NC, Mount Laurel, NJ"). Only comma/semicolon-separated tokens that ARE a state (code
 * or full name) count, so a city or neighborhood never matches by accident — a location naming no
 * state yields an empty set, and callers treat that as "can't tell" rather than "elsewhere".
 */
object UsStates {

    private val NAMES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
        "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia",
        "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois",
        "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana",
        "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota",
        "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
        "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma", "OR" to "Oregon",
        "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
        "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia",
        "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
        "PR" to "Puerto Rico",
    )
    private val BY_NAME = NAMES.entries.associate { (code, name) -> name.lowercase() to code }

    // "WA", "wa", "WA 98101", "WA 98101-1234". Any case: the code must be the WHOLE comma-separated
    // part, so "tx" in "Austin, tx" counts but a two-letter word inside a phrase ("or remote") can't.
    private val CODE_TOKEN = Regex("^([A-Za-z]{2})(?:\\s+\\d{5}(?:-\\d{4})?)?$")
    // "Seattle WA" / "Seattle WA 98101" — a code trailing a multi-word token without a comma.
    // Uppercase only: lowercase here would read "Seattle or" as Oregon.
    private val TRAILING_CODE = Regex("\\s([A-Z]{2})(?:\\s+\\d{5}(?:-\\d{4})?)?$")

    /** Two-letter code (any case) or full state name for a whole value such as the model's office_state, or null. */
    fun codeOf(token: String): String? {
        val t = token.trim().replace(".", "")
        if (t.length == 2 && t.uppercase() in NAMES) return t.uppercase()
        return BY_NAME[t.lowercase()]
    }

    /** Every US state named in [location]. Empty when none can be identified. */
    fun statesIn(location: String): Set<String> {
        val tokens = location.split(',', ';', '|', '/', '\n').map { it.trim().removeSuffix(".").trim() }
            .filter { it.isNotEmpty() }
        val found = linkedSetOf<String>()
        tokens.forEachIndexed { i, raw ->
            val token = raw.replace(".", "")
            val next = tokens.getOrNull(i + 1)?.replace(".", "")?.trim().orEmpty()
            // "Washington, DC" / "Washington, District of Columbia": the first "Washington" is the city.
            if (token.equals("Washington", ignoreCase = true) &&
                (next.equals("DC", ignoreCase = true) || next.equals("District of Columbia", ignoreCase = true))) {
                return@forEachIndexed
            }
            val code = CODE_TOKEN.find(token)?.groupValues?.get(1)?.uppercase()?.takeIf { it in NAMES }
                ?: BY_NAME[token.lowercase()]
                ?: BY_NAME[token.lowercase().replace(Regex("\\s+\\d{5}(?:-\\d{4})?$"), "")]
                ?: TRAILING_CODE.find(token)?.groupValues?.get(1)?.takeIf { it in NAMES }
            if (code != null) found.add(code)
        }
        return found
    }
}
