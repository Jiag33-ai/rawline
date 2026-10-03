package app.rawline.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import app.rawline.core.model.EditRecipe
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

    fun reset() { edit("Reset") { EditRecipe() } }

    fun addSnapshot(id: Long, name: String) { snapshots.add(0, Snapshot(id, name, recipe)) }
    fun applySnapshot(s: Snapshot) { edit("Snapshot ${s.name}") { s.recipe } }
}
