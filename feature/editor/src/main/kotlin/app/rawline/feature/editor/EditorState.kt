package app.rawline.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Geometry
import app.rawline.core.model.Look
import app.rawline.core.render.EditorSession

data class HistoryEntry(val label: String, val recipe: EditRecipe)
data class Snapshot(val id: Long, val name: String, val recipe: EditRecipe)

/**
 * The edit in progress. Slider drags call [live] (no history entry, preview only); lifting the finger calls [commit],
 * which adds one history step. Undo and redo walk the history.
 */
class EditorState(
    initial: EditRecipe,
    val session: EditorSession,
    private val onSave: (EditRecipe) -> Unit = {},
) {
    var recipe by mutableStateOf(initial)
        private set
    val history = mutableStateListOf(HistoryEntry("Open", initial))
    var historyIndex by mutableIntStateOf(0)
        private set
    val snapshots = mutableStateListOf<Snapshot>()

    val canUndo get() = historyIndex > 0
    val canRedo get() = historyIndex < history.lastIndex

    init { session.setRecipe(initial) }

    fun live(f: (EditRecipe) -> EditRecipe) {
        recipe = f(recipe)
        session.setRecipe(recipe)
    }

    fun commit(label: String) {
        if (recipe == history[historyIndex].recipe) return
        while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
        history.add(HistoryEntry(label, recipe))
        if (history.size > 200) history.removeAt(0)
        historyIndex = history.lastIndex
        onSave(recipe)
    }

    fun edit(label: String, f: (EditRecipe) -> EditRecipe) { live(f); commit(label) }

    fun undo() { if (canUndo) jump(historyIndex - 1) }
    fun redo() { if (canRedo) jump(historyIndex + 1) }

    fun jump(i: Int) {
        historyIndex = i.coerceIn(0, history.lastIndex)
        recipe = history[historyIndex].recipe
        session.setRecipe(recipe)
        onSave(recipe)
    }

    /** Carries out a [planCropCancel] result. */
    fun apply(plan: CropCancelPlan) {
        when (plan) {
            CropCancelPlan.Nothing -> {}
            is CropCancelPlan.Jump -> jump(plan.index)
            is CropCancelPlan.Live -> live { plan.recipe }
            is CropCancelPlan.Edit -> edit("Cancel crop") { it.copy(geometry = plan.geometry) }
        }
    }

    /** Reset builds a new edit, so the photo moves to the current look (as an unedited photo does). */
    fun reset() { edit("Reset") { EditRecipe() } }

    /** True for an edit saved under an earlier look version (the editor then offers "Update look"). */
    val canUpdateLook get() = recipe.lookVersion != Look.CURRENT

    /** Moves the edit to the current look. One history step, so Undo goes back to the earlier look; nothing else in the recipe changes. */
    fun updateLook() { if (canUpdateLook) edit("Update look") { it.withCurrentLook() } }

    fun addSnapshot(id: Long, name: String) { snapshots.add(0, Snapshot(id, name, recipe)) }
    fun applySnapshot(s: Snapshot) { edit("Snapshot ${s.name}") { s.recipe } }
}

/** What cancelling the crop tool has to do to put the picture back as it was on entry. */
sealed interface CropCancelPlan {
    object Nothing : CropCancelPlan
    /** Step back along the history to the entry (later steps stay as redo). Adds no entry. */
    data class Jump(val index: Int) : CropCancelPlan
    /** Uncommitted live change only: restore the recipe without a history entry. */
    data class Live(val recipe: EditRecipe) : CropCancelPlan
    /** The entry is no longer in the history (the editor was rebuilt, for instance by a rotation): write the saved geometry back. */
    data class Edit(val geometry: Geometry) : CropCancelPlan
}

/**
 * Cancel crop: back to the history entry that was current on entry ([entryIndex], checked against [entryGeo] because the history can
 * be rebuilt or trimmed), and never a new history entry when nothing changed.
 */
fun planCropCancel(history: List<EditRecipe>, historyIndex: Int, recipe: EditRecipe, entryIndex: Int, entryGeo: Geometry): CropCancelPlan {
    if (entryIndex in history.indices && history[entryIndex].geometry == entryGeo) {
        return when {
            historyIndex != entryIndex -> CropCancelPlan.Jump(entryIndex)
            recipe != history[entryIndex] -> CropCancelPlan.Live(history[entryIndex])
            else -> CropCancelPlan.Nothing
        }
    }
    return if (recipe.geometry == entryGeo) CropCancelPlan.Nothing else CropCancelPlan.Edit(entryGeo)
}

/** Saves a [Geometry] across a rotation (the activity is recreated). */
val GeometrySaver = androidx.compose.runtime.saveable.Saver<Geometry, List<Any>>(
    save = { g -> listOf(g.cropX, g.cropY, g.cropW, g.cropH, g.angle, g.rotate90, g.flipH, g.flipV, g.keystoneV, g.keystoneH, g.aspect) },
    restore = { l -> Geometry(l[0] as Float, l[1] as Float, l[2] as Float, l[3] as Float, l[4] as Float, l[5] as Int, l[6] as Boolean, l[7] as Boolean, l[8] as Float, l[9] as Float, l[10] as String) },
)
