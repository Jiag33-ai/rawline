package app.rawline

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.rawline.core.cache.PerfLog
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.core.render.EditorSession
import app.rawline.core.ml.AiMasksImpl
import app.rawline.feature.masking.MaskingFeature
import app.rawline.feature.editor.EditorScreen
import app.rawline.feature.editor.EditorState
import app.rawline.feature.editor.EditorTab
import app.rawline.feature.editor.Preset
import app.rawline.feature.editor.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads the saved edit, runs the editor and writes edits back to the catalogue. */
@Composable
fun EditorHost(photo: Photo, graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(photo.id) { EditorSession(context, graph.rawPrefetch, PerfLog::record) }
    var state by remember(photo.id) { mutableStateOf<EditorState?>(null) }
    var placeholder by remember(photo.id) { mutableStateOf<Bitmap?>(graph.previews.peek(photo.id)?.let { toSoftware(it) }) }
    val userPresets = remember { mutableStateListOf<Preset>() }
    var exporting by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var masking by remember(photo.id) { mutableStateOf<MaskingFeature?>(null) }

    LaunchedEffect(photo.id) {
        if (placeholder == null) placeholder = graph.previews.load(photo)?.let { toSoftware(it) }
        val saved = graph.catalog.loadRecipe(photo) ?: EditRecipe()
        val st = EditorState(saved, session) { r ->
            // Debounced write so quick successive edits do one database write.
            saveJob?.cancel()
            saveJob = scope.launch { delay(400); graph.catalog.saveRecipe(photo, r) }
        }
        graph.catalog.snapshots(photo).forEach { st.snapshots.add(Snapshot(it.id, it.name, runCatching { EditRecipe.fromJson(it.json) }.getOrDefault(EditRecipe()))) }
        state = st
        masking = MaskingFeature(st, session, graph.maskStore, scope, { edge -> session.renderFrame(edge) }, AiMasksImpl(context, graph.modelStore, session))
        session.load(photo)
        masking?.restore(saved)
        userPresets.clear()
        graph.catalog.presets().forEach { userPresets.add(Preset(it.name, runCatching { EditRecipe.fromJson(it.json) }.getOrDefault(EditRecipe()), false, it.id)) }
    }
    DisposableEffect(photo.id) { onDispose { session.release() } }

    val st = state ?: return
    val mk = masking ?: return
    EditorScreen(
        photo = photo, state = st, placeholder = placeholder, extraTabs = listOf(mk.tab),
        tabOverlay = { id, mapper -> with(mk) { Overlay(id, mapper) } },
        toolGestures = { id, mapper -> mk.gestures(id, mapper) },
        userPresets = userPresets,
        onSavePreset = { name, r -> scope.launch { graph.catalog.addPreset(name, r); userPresets.clear(); graph.catalog.presets().forEach { userPresets.add(Preset(it.name, EditRecipe.fromJson(it.json), false, it.id)) } } },
        onDeletePreset = { p -> scope.launch { graph.catalog.deletePreset(p.id); userPresets.remove(p) } },
        onSnapshot = { name -> scope.launch { val id = graph.catalog.addSnapshot(photo, name, st.recipe); st.addSnapshot(id, name) } },
        onExport = { exporting = true },
        onBack = { saveJob?.cancel(); scope.launch(Dispatchers.IO) { graph.catalog.saveRecipe(photo, st.recipe) }; onBack() },
    )
}

private fun toSoftware(b: Bitmap): Bitmap = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
