package app.rawline.feature.library

import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import app.rawline.core.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GridRowsTest {
    private val zone = ZoneId.of("Australia/Adelaide")
    private fun at(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun photo(id: Long, takenAt: Long) = Photo(id, "f", "u$id", "p$id.jpg", 1, 1, Kind.IMAGE, true, takenAt = takenAt)

    @Test fun groupsByDayWithAustralianHeadingsAndCounts() {
        val rows = GridRows.build(listOf(photo(3, at(2026, 10, 3, 15)), photo(2, at(2026, 10, 3, 9)), photo(1, at(2026, 10, 2, 22))), SortOrder.NEWEST, zone)
        val heads = rows.filterIsInstance<GridRow.Head>()
        assertEquals(listOf("3 October 2026", "2 October 2026"), heads.map { it.label })
        assertEquals(listOf(2, 1), heads.map { it.count })
        assertEquals(5, rows.size)
        assertTrue(rows[0] is GridRow.Head && rows[1] is GridRow.Pic && rows[3] is GridRow.Head)
    }

    @Test fun otherSortsAreAPlainGrid() {
        val rows = GridRows.build(listOf(photo(1, 1000), photo(2, 2000)), SortOrder.NAME, zone)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is GridRow.Pic })
    }

    @Test fun indexOfPhotoCountsHeadings() {
        val rows = GridRows.build(listOf(photo(5, at(2026, 1, 1, 1)), photo(6, at(2026, 1, 1, 2))), SortOrder.OLDEST, zone)
        assertEquals(1, GridRows.indexOfPhoto(rows, 5))
        assertEquals(-1, GridRows.indexOfPhoto(rows, 99))
    }

    @Test fun emptyListHasNoRows() { assertTrue(GridRows.build(emptyList(), SortOrder.NEWEST, zone).isEmpty()) }

    @Test fun restorePointFindsThePhotoOrStartsAtTheTop() {
        val rows = GridRows.build(listOf(photo(5, at(2026, 1, 2, 1)), photo(6, at(2026, 1, 1, 2)), photo(7, at(2026, 1, 1, 1))), SortOrder.NEWEST, zone)
        assertEquals(GridRows.indexOfPhoto(rows, 7), GridRows.restoreIndex(rows, 7))
        assertEquals(0, GridRows.restoreIndex(rows, 99)); assertEquals(0, GridRows.restoreIndex(rows, null)); assertEquals(0, GridRows.restoreIndex(emptyList(), 1))
    }

    @Test fun topPhotoIsTheFirstPhotoAtOrAfterTheFirstVisibleRow() {
        val rows = GridRows.build(listOf(photo(5, at(2026, 1, 2, 1)), photo(6, at(2026, 1, 1, 2))), SortOrder.NEWEST, zone)   // Head, 5, Head, 6
        assertEquals(5L, GridRows.topPhotoId(rows, 0)); assertEquals(5L, GridRows.topPhotoId(rows, 1)); assertEquals(6L, GridRows.topPhotoId(rows, 2))
        assertNull(GridRows.topPhotoId(rows, 4)); assertNull(GridRows.topPhotoId(emptyList(), 0)); assertEquals(5L, GridRows.topPhotoId(rows, -3))
    }
}
