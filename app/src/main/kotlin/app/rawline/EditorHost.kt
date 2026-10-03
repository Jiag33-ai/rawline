package app.rawline

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.rawline.core.cache.PerfLog
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.core.render.EditorSession
import app.rawline.core.ml.AiMasksImpl
import app.rawline.core.ml.Healer
import app.rawline.feature.masking.MaskingFeature
import app.rawline.feature.remove.RemoveFeature
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
fun EditorHost(photo: Photo, graph: Graph, neighbors: List<Photo>, onExport: (Photo) -> Unit, onExportSettings: (Photo) -> Unit, onSwipe: (Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val denoiser = remember { app.rawline.core.ml.Denoiser(context, graph.modelStore) }
    val session = remember(photo.id) { EditorSession(context, graph.rawPrefetch, PerfLog::record) { h, a, p -> denoiser.run(h, a, p) } }
    var state by remember(photo.id) { mutableStateOf<EditorState?>(null) }
    var placeholder by remember(photo.id) { mutableStateOf<Bitmap?>(graph.previews.peek(photo.id)?.let { toSoftware(it) }) }
    val userPresets = remember { mutableStateListOf<Preset>() }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var masking by remember(photo.id) { mutableStateOf<MaskingFeature?>(null) }
    var remove by remember(photo.id) { mutableStateOf<RemoveFeature?>(null) }
    var healer by remember(photo.id) { mutableStateOf<Healer?>(null) }

    LaunchedEffect(photo.id) {
        if (placeholder == null) placeholder = graph.previews.load(photo)?.let { toSoftware(it) }
        val saved = graph.catalog.loadRecipe(photo) ?: EditRecipe()
        val st = EditorState(saved, session) { r ->
            // Debounced write so quick successive edits do one database write.
            saveJob?.cancel()
            saveJob = graph.appScope.launch { delay(400); graph.catalog.saveRecipe(photo, r) }
            masking?.persist(r)
        }
        graph.catalog.snapshots(photo).forEach { st.snapshots.add(Snapshot(it.id, it.name, runCatching { EditRecipe.fromJson(it.json) }.getOrDefault(EditRecipe()))) }
        state = st
        masking = MaskingFeature(st, session, graph.maskStore, scope, { edge -> session.renderFrame(edge) }, AiMasksImpl(context, graph.modelStore, session))
        session.load(photo)
        masking?.restore(saved)
        healer = Healer(context, session, graph.modelStore, graph.patchStore).also { remove = RemoveFeature(st, session, it, scope) }
        userPresets.clear()
        graph.catalog.presets().forEach { userPresets.add(Preset(it.name, runCatching { EditRecipe.fromJson(it.json) }.getOrDefault(EditRecipe()), false, it.id)) }
    }
    DisposableEffect(photo.id) { onDispose { session.release() } }
    val committed = state?.let { it.history[it.historyIndex].recipe.detail }
    LaunchedEffect(committed?.aiDenoise, committed?.aiDenoiseAmount) {
        if (committed != null && session.state.value.stage == app.rawline.core.render.Stage.READY) session.reloadSource()
    }

    val st = state ?: return
    androidx.activity.compose.BackHandler { saveJob?.cancel(); graph.appScope.launch { graph.catalog.saveRecipe(photo, st.recipe) }; onBack() }
    val mk = masking ?: return
    val rm = remove ?: return
    val ss by session.state.collectAsState()
    // Keep the repair overlay in step with the recipe (open, undo, redo, snapshots) once the source is on the GPU.
    // Once this photo is on screen, decode its neighbours quietly so swiping to them opens fast.
    LaunchedEffect(ss.stage, neighbors) { if (ss.stage == app.rawline.core.render.Stage.READY) neighbors.forEach { graph.rawPrefetch.prefetch(it) } }
    LaunchedEffect(st.recipe.heals, ss.stage) { if (ss.stage == app.rawline.core.render.Stage.READY) healer?.sync(st.recipe.heals) }
    EditorScreen(
        photo = photo, state = st, placeholder = placeholder, extraTabs = listOf(mk.tab, rm.tab),
        tabOverlay = { id, mapper -> with(mk) { Overlay(id, mapper) }; with(rm) { Overlay(id, mapper) } },
        toolGestures = { id, mapper -> mk.gestures(id, mapper) ?: rm.gestures(id, mapper) },
        userPresets = userPresets,
        onSavePreset = { name, r -> scope.launch { graph.catalog.addPreset(name, r); userPresets.clear(); graph.catalog.presets().forEach { userPresets.add(Preset(it.name, runCatching { EditRecipe.fromJson(it.json) }.getOrDefault(EditRecipe()), false, it.id)) } } },
        onDeletePreset = { p -> scope.launch { graph.catalog.deletePreset(p.id); userPresets.remove(p) } },
        onSnapshot = { name -> scope.launch { val id = graph.catalog.addSnapshot(photo, name, st.recipe); st.addSnapshot(id, name) } },
        onExport = { saveJob?.cancel(); graph.appScope.launch { graph.catalog.saveRecipe(photo, st.recipe); kotlinx.coroutines.withContext(Dispatchers.Main) { onExport(photo) } } },
        onExportSettings = { onExportSettings(photo) },
        onSwipePhoto = { d -> saveJob?.cancel(); graph.appScope.launch { graph.catalog.saveRecipe(photo, st.recipe) }; onSwipe(d) },
        onBack = { saveJob?.cancel(); graph.appScope.launch { graph.catalog.saveRecipe(photo, st.recipe) }; onBack() },
    )
}

private fun toSoftware(b: Bitmap): Bitmap = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
