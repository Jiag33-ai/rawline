package app.rawline

import app.rawline.core.model.Photo
import app.rawline.core.ui.Plurals

enum class UndoField { RATING, FLAG, LABEL }

/** The last rating, flag or label change: what it set and the photos as they were before (only photos it really changed). */
class UndoEntry(val field: UndoField, val newValue: Int, val before: List<Photo>) {
    val message: String = UndoRules.message(field, newValue, before.size)
}

object UndoRules {
    private val labels = listOf("no label", "red", "yellow", "green", "blue", "purple")

    fun current(p: Photo, field: UndoField) = when (field) { UndoField.RATING -> p.rating; UndoField.FLAG -> p.flag; UndoField.LABEL -> p.label }

    /** The photos a change would really alter; an empty list means the tap changes nothing and there is nothing to undo. */
    fun changed(list: List<Photo>, field: UndoField, newValue: Int): List<Photo> = list.filter { current(it, field) != newValue }

    fun message(field: UndoField, newValue: Int, n: Int): String {
        val photos = Plurals.photos(n)
        return when (field) {
            UndoField.RATING -> if (newValue == 0) "Cleared the rating on $photos" else "Rated $photos ${Plurals.count(newValue, "star")}"
            UndoField.FLAG -> when (newValue) { 1 -> "Picked $photos"; -1 -> "Rejected $photos"; else -> "Unflagged $photos" }
            UndoField.LABEL -> if (newValue in 1..5) "Labelled $photos ${labels[newValue]}" else "Removed the label from $photos"
        }
    }

    /** How to put things back: for each old value, the photos that had it (one database write per group, not per photo). */
    fun restoreGroups(e: UndoEntry): List<Pair<Int, List<Photo>>> = e.before.groupBy { current(it, e.field) }.map { it.key to it.value }
}
