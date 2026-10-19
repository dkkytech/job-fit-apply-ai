package com.jd.watchlist.filter

import com.jd.watchlist.model.Posting

/**
 * Cheap pre-filter that runs before anything reaches the bridge (and so before any LLM tokens are
 * spent): the title must look like a QE role, and the location must be the Seattle area, somewhere
 * in Washington, remote, or unknown. Unknown passes because the Processor's WA-state location gate
 * makes the final call and fails open the same way.
 */
class PostingFilter(titleInclude: String, titleExclude: String) {
    private val include = Regex(titleInclude, RegexOption.IGNORE_CASE)
    private val exclude = titleExclude.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) }

    fun titleMatches(title: String): Boolean =
        include.containsMatchIn(title) && exclude?.containsMatchIn(title) != true

    fun locationMatches(location: String): Boolean {
        if (location.isBlank()) return true
        return location.split('|', ';').any { part ->
            (AREA.containsMatchIn(part) && !DC.containsMatchIn(part) && !OTHER_STATE.containsMatchIn(part)) || REMOTE.containsMatchIn(part)
        }
    }

    fun matches(p: Posting): Boolean = titleMatches(p.title) && locationMatches(p.location)

    companion object {
        private val AREA = Regex(
            """seattle|bellevue|redmond|kirkland|bothell|issaquah|everett|renton|\bkent\b|tacoma|tukwila|lynnwood|""" +
                """mukilteo|woodinville|sammamish|auburn|federal way|seatac|mountlake|snoqualmie|arlington, wa|""" +
                """\bWA\b|washington""",
            RegexOption.IGNORE_CASE,
        )
        private val DC = Regex("""\bD\.?C\b|district of columbia""", RegexOption.IGNORE_CASE)
        private val REMOTE = Regex("""\bremote\b""", RegexOption.IGNORE_CASE)

        /**
         * Another state named alongside a Seattle-area city name: Everett MA, Auburn Hills MI,
         * Redmond OR, Bellevue NE. Codes are matched upper-case only, so "or"/"in"/"me" in prose
         * don't count.
         */
        private val OTHER_STATE = Regex(
            """\b(AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WV|WI|WY)\b|""" +
                """(?i:\b(alabama|alaska|arizona|arkansas|california|colorado|connecticut|delaware|florida|georgia|hawaii|idaho|illinois|indiana|iowa|kansas|""" +
                """kentucky|louisiana|maine|maryland|massachusetts|michigan|minnesota|mississippi|missouri|montana|nebraska|nevada|new hampshire|new jersey|""" +
                """new mexico|new york|north carolina|north dakota|ohio|oklahoma|oregon|pennsylvania|rhode island|south carolina|south dakota|tennessee|""" +
                """texas|utah|vermont|virginia|west virginia|wisconsin|wyoming)\b)""",
        )
    }
}
