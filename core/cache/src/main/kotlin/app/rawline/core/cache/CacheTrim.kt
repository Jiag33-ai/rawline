package app.rawline.core.cache

/** Which cache files to delete so a directory stays under a size cap (oldest use first), and which leftovers are stale. */
object CacheTrim {
    class Entry(val name: String, val size: Long, val lastModified: Long)

    /** Trim down to this share of the cap, so the next few writes do not each trigger another trim. */
    const val TARGET = 0.9

    /** Names to delete: stale temp files, then the least recently used files until the total fits. */
    fun plan(entries: List<Entry>, maxBytes: Long, now: Long, staleTmpMs: Long = 10 * 60 * 1000L): List<String> {
        val out = ArrayList<String>()
        val (tmp, real) = entries.partition { it.name.endsWith(".tmp") }
        tmp.filter { now - it.lastModified > staleTmpMs }.forEach { out.add(it.name) }
        var total = real.sumOf { it.size }
        if (total <= maxBytes) return out
        val goal = (maxBytes * TARGET).toLong()
        for (e in real.sortedBy { it.lastModified }) {
            if (total <= goal) break
            out.add(e.name); total -= e.size
        }
        return out
    }
}
