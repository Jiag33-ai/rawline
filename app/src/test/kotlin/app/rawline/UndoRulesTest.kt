package app.rawline

import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoRulesTest {
    private fun p(id: Long, rating: Int = 0, flag: Int = 0, label: Int = 0) = Photo(id, "f", "u$id", "p$id.jpg", 1, 1, Kind.IMAGE, true, rating = rating, flag = flag, label = label)

    @Test fun onlyPhotosThatReallyChangeCount() {
        val list = listOf(p(1, rating = 3), p(2, rating = 4), p(3, rating = 3))
        assertEquals(listOf(2L), UndoRules.changed(list, UndoField.RATING, 3).map { it.id })
        assertTrue(UndoRules.changed(list.filter { it.rating == 3 }, UndoField.RATING, 3).isEmpty())   // same tap again: nothing to undo
    }

    @Test fun messagesUseCorrectPlurals() {
        assertEquals("Rated 1 photo 1 star", UndoRules.message(UndoField.RATING, 1, 1))
        assertEquals("Rated 12 photos 4 stars", UndoRules.message(UndoField.RATING, 4, 12))
        assertEquals("Cleared the rating on 2 photos", UndoRules.message(UndoField.RATING, 0, 2))
        assertEquals("Picked 1 photo", UndoRules.message(UndoField.FLAG, 1, 1))
        assertEquals("Rejected 3 photos", UndoRules.message(UndoField.FLAG, -1, 3))
        assertEquals("Unflagged 3 photos", UndoRules.message(UndoField.FLAG, 0, 3))
        assertEquals("Labelled 2 photos red", UndoRules.message(UndoField.LABEL, 1, 2))
        assertEquals("Removed the label from 1 photo", UndoRules.message(UndoField.LABEL, 0, 1))
    }

    @Test fun restoreGroupsPutEachOldValueBack() {
        val e = UndoEntry(UndoField.RATING, 5, listOf(p(1, rating = 0), p(2, rating = 3), p(3, rating = 0)))
        val groups = UndoRules.restoreGroups(e).associate { it.first to it.second.map { p -> p.id } }
        assertEquals(mapOf(0 to listOf(1L, 3L), 3 to listOf(2L)), groups)
    }

    @Test fun flagGroupsUseTheFlagNotTheRating() {
        val e = UndoEntry(UndoField.FLAG, 1, listOf(p(1, rating = 5, flag = -1), p(2, rating = 5, flag = 0)))
        assertEquals(setOf(-1, 0), UndoRules.restoreGroups(e).map { it.first }.toSet())
    }
}
