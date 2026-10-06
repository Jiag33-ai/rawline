package app.rawline

import app.rawline.core.model.Photo

/** How long the library list waits between updates: slower while the indexer is writing a row every few milliseconds. */
object ListThrottle {
    const val IDLE_MS = 250L
    const val INDEXING_MS = 1000L
    fun delayMs(indexing: Boolean) = if (indexing) INDEXING_MS else IDLE_MS
}

/**
 * Coalesces device rescan requests. A burst of MediaStore changes (a 100 photo export fires several per photo) used to cancel and
 * restart the scan each time, cutting a running index short; now requests during a pass only mark another pass as wanted.
 */
class RescanGate {
    private var running = false
    private var pending: Long? = null

    /** @return true when the caller must start the worker (none is running). */
    @Synchronized fun request(delayMs: Long): Boolean {
        pending = minOf(pending ?: Long.MAX_VALUE, delayMs)
        if (running) return false
        running = true
        return true
    }

    /** The worker asks for its next pass: the delay to wait first, or null when nothing more is wanted (the worker then stops). */
    @Synchronized fun next(): Long? {
        val d = pending
        pending = null
        if (d == null) running = false
        return d
    }

    /** For a worker that dies with an error, so later requests can start a new one. */
    @Synchronized fun release() { running = false }
}

/**
 * The viewer and the editor browse the list that was on screen when they were opened. A rating or flag change under a filter
 * (or an edit that makes a photo leave "Unedited") must not remove the photo from under the user or shift the next one in.
 */
object Browse {
    /** The frozen order, with each photo's current data; a photo that was deleted from the library drops out. */
    fun resolve(frozenIds: List<Long>, live: List<Photo>, fallback: List<Photo>): List<Photo> {
        if (frozenIds.isEmpty()) return fallback
        val byId = HashMap<Long, Photo>(live.size * 2)
        for (p in live) byId[p.id] = p
        return frozenIds.mapNotNull { byId[it] }
    }

    fun neighbours(list: List<Photo>, id: Long): List<Photo> {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return emptyList()
        return listOfNotNull(list.getOrNull(i + 1), list.getOrNull(i + 2), list.getOrNull(i - 1))
    }

    /** The photo [delta] places from [id] and its index, or null at either end or when [id] is not in the list. */
    fun step(list: List<Photo>, id: Long, delta: Int): Pair<Int, Photo>? {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        val j = i + delta
        return list.getOrNull(j)?.let { j to it }
    }
}

/** The queue shows what is next at the top: waiting and running jobs oldest first (the order they run), then finished ones newest first. */
object QueueOrder {
    fun sort(jobs: List<app.rawline.core.data.ExportJobEntity>): List<app.rawline.core.data.ExportJobEntity> {
        val (active, done) = jobs.partition { it.status == 0 || it.status == 1 }
        return active.sortedBy { it.id } + done.sortedByDescending { it.id }
    }
}
