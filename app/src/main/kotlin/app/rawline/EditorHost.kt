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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loads the saved edit, runs the editor and writes edits back to the catalogue. */
@Composable
fun EditorHost(photo: Photo, graph: Graph, neighbors: List<Photo>, onExport: (Photo) -> Unit, onExportSettings: (Photo) -> Unit, onSwipe: (Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val denoiser = remember(photo.id) { app.rawline.core.ml.Denoiser(context, graph.modelStore) }
    val session = remember(photo.id) { EditorSession(context, graph.rawPrefetch, PerfLog::record) { h, a, p -> denoiser.run(h, a, p) } }
    var state by remember(photo.id) { mutableStateOf<EditorState?>(null) }
    var placeholder by remember(photo.id) { mutableStateOf<Bitmap?>(graph.previews.peek(photo.id)?.let { toSoftware(it) }) }
    val userPresets = remember { mutableStateListOf<Preset>() }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var masking by remember(photo.id) { mutableStateOf<MaskingFeature?>(null) }
    var remove by remember(photo.id) { mutableStateOf<RemoveFeature?>(null) }
    var healer by remember(photo.id) { mutableStateOf<Healer?>(null) }
    var aiMasks by remember(photo.id) { mutableStateOf<AiMasksImpl?>(null) }

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
        masking = MaskingFeature(st, session, graph.maskStore, scope, { edge -> session.renderFrame(edge) }, AiMasksImpl(context, graph.modelStore, session).also { aiMasks = it })
        session.load(photo)
        masking?.restore(saved)
        healer = Healer(context, session, graph.modelStore, graph.patchStore).also { remove = RemoveFeature(st, session, it, scope); session.onContextRestored = { it.resendOverlay() } }
        userPresets.clear()
        graph.catalog.presets().forEach { userPresets.add(Preset(it.name, runCatching { EditRecipe.fromJson(it.json) }.getOrDefault(EditRecipe()), false, it.id)) }
    }
    DisposableEffect(photo.id) {
        onDispose { session.release(); denoiser.release(); healer?.release(); aiMasks?.release() }
    }
    // Write a pending debounced edit as soon as the app is paused or stopped, so it is not lost if the process dies.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, photo.id) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val job = saveJob
                val s = state
                if (s != null && job != null && job.isActive) { job.cancel(); saveJob = graph.appScope.launch { graph.catalog.saveRecipe(photo, s.recipe) } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(o)
        onDispose { lifecycleOwner.lifecycle.removeObserver(o) }
    }
    val committed = state?.let { it.history[it.historyIndex].recipe.detail }
    LaunchedEffect(committed?.aiDenoise, committed?.aiDenoiseAmount) {
        if (committed != null && session.state.value.stage == app.rawline.core.render.Stage.READY) session.reloadSource()
    }

    val st = state ?: return
    // Leaving the editor waits for the edit to be written; a dimmed canvas with a spinner shows only if that takes noticeable time.
    var saving by remember { mutableStateOf(false) }
    val showSaving by androidx.compose.runtime.produceState(false, saving) { if (saving) { delay(150); value = true } else value = false }
    // If the write fails (disk full, database error) stay in the editor so Back can try again instead of freezing.
    val leave = { if (!saving) { saving = true; saveJob?.cancel(); scope.launch { try { graph.catalog.saveRecipe(photo, st.recipe); onBack() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Throwable) { app.rawline.core.cache.PerfLog.error("save ${photo.name}: ${e.message}"); saving = false } } }; Unit }
    androidx.activity.compose.BackHandler { leave() }
    val mk = masking ?: return
    val rm = remove ?: return
    val ss by session.state.collectAsState()
    // Keep the repair overlay in step with the recipe (open, undo, redo, snapshots) once the source is on the GPU.
    // Once this photo is on screen, decode its neighbours quietly so swiping to them opens fast.
    LaunchedEffect(ss.stage, neighbors) { if (ss.stage == app.rawline.core.render.Stage.READY) neighbors.forEach { graph.rawPrefetch.prefetch(it) } }
    LaunchedEffect(st.recipe.heals, ss.stage) { if (ss.stage == app.rawline.core.render.Stage.READY) healer?.sync(st.recipe.heals) }
    Box(Modifier.fillMaxSize()) {
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
        onSwipePhoto = { d -> saveJob?.cancel(); scope.launch { graph.catalog.saveRecipe(photo, st.recipe); onSwipe(d) } },
        onBack = { leave() },
    )
    if (showSaving) Box(Modifier.fillMaxSize().background(app.rawline.core.ui.Lr.OverlayHeavy).clickable(enabled = true, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}, contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            app.rawline.core.ui.LocalLoader(size = 32.dp, color = androidx.compose.ui.graphics.Color.White)
            androidx.compose.material3.Text("Saving your edits…", color = androidx.compose.ui.graphics.Color.White, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
    }
    }
}

private fun toSoftware(b: Bitmap): Bitmap = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
