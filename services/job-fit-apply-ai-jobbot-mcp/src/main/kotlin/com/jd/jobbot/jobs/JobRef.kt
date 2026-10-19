package com.jd.jobbot.jobs

/**
 * A job reference as the user or the card writes it: `#J7663`, `J7663` or `7663`. The number is
 * the bridge's completed_seq, so it addresses exactly one completion.
 */
object JobRef {
    private val PATTERN = Regex("""^\s*#?[Jj]?(\d{1,18})\s*$""")
    private val IN_TEXT = Regex("""#J(\d{1,18})\b""")

    fun parse(ref: String?): Long? = ref?.let { PATTERN.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }

    /** Every `#J<seq>` in a block of text (a quoted card), in order, without duplicates. */
    fun findAll(text: String?): List<Long> =
        IN_TEXT.findAll(text.orEmpty()).mapNotNull { it.groupValues[1].toLongOrNull() }.distinct().toList()

    fun format(seq: Long) = "#J$seq"
}
