package com.jd.pipeline.utils

/**
 * A set of hosts whose entries lapse [ttlMs] after they were (last) added. Used for the scraper's
 * "skip this site" lists: the Processor is one long-lived loop that never resets them, so entries
 * must expire on their own or a single block would last until the next restart.
 */
class ExpiringHostSet(
    private val ttlMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) : AbstractMutableSet<String>() {

    private val addedAt = LinkedHashMap<String, Long>()

    private fun prune() {
        val now = nanoTime()
        addedAt.entries.removeIf { now - it.value >= ttlMs * 1_000_000 }
    }

    override val size: Int
        get() { prune(); return addedAt.size }

    /** Adds [element], or restarts its timer when already present. */
    override fun add(element: String): Boolean {
        prune()
        val isNew = element !in addedAt
        addedAt[element] = nanoTime()
        return isNew
    }

    override fun iterator(): MutableIterator<String> { prune(); return addedAt.keys.iterator() }
}
