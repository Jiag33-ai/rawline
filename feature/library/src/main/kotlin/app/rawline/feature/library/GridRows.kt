package app.rawline.feature.library

import app.rawline.core.model.Photo
import app.rawline.core.model.SortOrder
import app.rawline.core.ui.DateText
import java.time.ZoneId

/** One row of the library grid: a date heading or a photo. */
sealed interface GridRow {
    /** [key] is unique within one list (the grid keys its items by it): the same day can head two runs when the order is held while capture times arrive. */
    class Head(val label: String, val count: Int, val key: String = label) : GridRow
    class Pic(val p: Photo) : GridRow
}

object GridRows {
    /**
     * Date headings like Lightroom ("3 October 2026" and a count) when sorted by date; a plain grid otherwise.
     * Built by the view model off the main thread, so the screen never groups tens of thousands of photos while drawing.
     */
    fun build(photos: List<Photo>, sort: SortOrder, zone: ZoneId = ZoneId.systemDefault()): List<GridRow> {
        if (sort != SortOrder.NEWEST && sort != SortOrder.OLDEST) return photos.map { GridRow.Pic(it) }
        fun day(p: Photo) = java.time.Instant.ofEpochMilli(if (p.takenAt > 0) p.takenAt else p.modified).atZone(zone).toLocalDate()
        val out = ArrayList<GridRow>(photos.size + 32)
        val seen = HashMap<String, Int>()
        var i = 0
        while (i < photos.size) {
            val d = day(photos[i]); var j = i
            while (j < photos.size && day(photos[j]) == d) j++
            val label = DateText.day(d); val n = seen.merge(label, 1, Int::plus)!!
            out.add(GridRow.Head(label, j - i, if (n == 1) label else "$label #$n"))
            for (k in i until j) out.add(GridRow.Pic(photos[k]))
            i = j
        }
        return out
    }

    /** Position of a photo in [rows] (headings count), or -1. */
    fun indexOfPhoto(rows: List<GridRow>, id: Long): Int = rows.indexOfFirst { it is GridRow.Pic && it.p.id == id }

    /** BK-120: the id of the first photo at or after the grid position [firstVisible] (a heading at the top stands for the photo under it), or null for an empty grid. */
    fun topPhotoId(rows: List<GridRow>, firstVisible: Int): Long? {
        for (i in firstVisible.coerceAtLeast(0) until rows.size) { val r = rows[i]; if (r is GridRow.Pic) return r.p.id }
        return null
    }

    /** BK-120: where the grid goes after a cold start: the saved photo's position (headings count), or 0 (the top) when nothing was saved or the photo has gone. */
    fun restoreIndex(rows: List<GridRow>, savedId: Long?): Int = if (savedId == null) 0 else indexOfPhoto(rows, savedId).coerceAtLeast(0)
}
