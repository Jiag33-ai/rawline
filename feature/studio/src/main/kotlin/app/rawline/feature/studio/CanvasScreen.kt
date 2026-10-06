package app.rawline.feature.studio

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.render.PhotoImport
import app.rawline.core.studio.render.Phase
import app.rawline.core.studio.render.Rgb
import app.rawline.core.studio.render.StudioExporter
import app.rawline.core.studio.render.StudioGl
import app.rawline.core.studio.render.StudioPerf
import app.rawline.core.studio.render.StudioProjects
import app.rawline.core.studio.render.StudioGlView
import app.rawline.core.studio.render.StudioSession
import app.rawline.core.studio.render.StudioState
import app.rawline.core.studio.render.Tool
import app.rawline.core.ui.LocalValueFeedback
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.LrDropdown
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconButton
import app.rawline.core.ui.LrMenuItem
import app.rawline.core.ui.LocalLoader
import app.rawline.core.ui.LrTextButton
import app.rawline.core.ui.ValueFeedback
import app.rawline.core.ui.ValueFeedbackPill
import app.rawline.core.ui.blockPointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private enum class PickerTarget { FOREGROUND, BACKGROUND }

/**
 * The Studio canvas (spec 5): status strip, black canvas with the GL surface and the pointer layer, floating colour chips, tool options, tool rail. Landscape puts the tools on
 * the left and a 280 dp panel (layers, or the tool's options) on the right. Back closes the layers panel first, then saves and leaves.
 */
@Composable
fun StudioCanvasScreen(session: StudioSession, gl: StudioGl, projects: StudioProjects, perf: StudioPerf, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val state by session.state.collectAsStateWithLifecycle()
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val feedback = remember { ValueFeedback() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // autosave on pause (decision D8); the session returns at once and writes on its own thread
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { session.flush() }
    androidx.compose.runtime.DisposableEffect(session) {
        val cb = object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) session.trimMemory() }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Suppress("OVERRIDE_DEPRECATION") override fun onLowMemory() { session.trimMemory() }
        }
        context.registerComponentCallbacks(cb)
        onDispose { context.unregisterComponentCallbacks(cb) }
    }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // Leaving: save what is unsaved, then write the project's thumbnail for the home (a worker renders it through the compositor while the surface still exists), then go.
    // The thumbnail is best effort: it gets 3 seconds, and a failure only means the home shows the old one.
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            session.flush()
            val projectId = state.document.id
            val ready = state.phase == Phase.READY
            scope.launch {
                if (ready) {
                    // async + await, so the 3 second limit works even though the render itself blocks a worker; a late result is dropped (the session is released by then)
                    val work = async(Dispatchers.IO) {
                        runCatching {
                            val snap = session.exportSnapshot(2_000)
                            val jpeg = snap?.let { StudioExporter(session, perf).thumbnailJpeg(it) }
                            if (jpeg != null) projects.writeThumbnail(projectId, jpeg)
                        }.onFailure { perf.error("studio thumbnail: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                    withTimeoutOrNull(3_000) { work.await() }
                }
                onExit()
            }
        }
    }
    BackHandler(enabled = !leaving) { if (exportOpen) exportOpen = false else if (layersOpen) layersOpen = false else leave() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val d = state.document
            // decoding and scaling are file and codec work: off the main thread
            val px = withContext(Dispatchers.IO) { PhotoImport.decode(context, uri, d.width, d.height) }
            if (px == null) session.reportProblem("Could not read that picture.") else session.addPhotoLayer(px, "Photo")
        }
    }
    val addPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    CompositionLocalProvider(LocalValueFeedback provides feedback) {
        Box(modifier.fillMaxSize().background(Lr.Canvas)) {
            // The layout (and with it the GL surface) is ALWAYS composed: the session waits for the GL context to upload the layers before it says READY,
            // so a surface that only appeared once READY would wait for itself.
            if (landscape) Landscape(state, session, gl, layersOpen, { layersOpen = !layersOpen }, { picker = it }, addPhoto, leave, { exportOpen = true })
            else Portrait(state, session, gl, layersOpen, { layersOpen = !layersOpen }, { picker = it }, addPhoto, leave, { exportOpen = true })
            when (state.phase) {
                Phase.LOADING -> Box(Modifier.fillMaxSize().background(Lr.Canvas).blockPointerInput(), contentAlignment = Alignment.Center) { LocalLoader() }
                Phase.ERROR -> Column(Modifier.fillMaxSize().background(Lr.Canvas).blockPointerInput().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "Could not open the project.", color = Lr.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    LrTextButton(onClick = onExit) { Text("Back") }
                }
                Phase.READY -> {}
            }
            if (leaving) Box(Modifier.fillMaxSize().background(Lr.Canvas.copy(alpha = 0.6f)).blockPointerInput(), contentAlignment = Alignment.Center) { LocalLoader() }
            ValueFeedbackPill(feedback, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 52.dp))
            Notices(state, session, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 200.dp, start = 16.dp, end = 16.dp))
        }
    }
    if (exportOpen) ExportSheet(session, state.document.width, state.document.height, state.document.name, perf, onDismiss = { exportOpen = false })
    picker?.let { target ->
        val fg = target == PickerTarget.FOREGROUND
        ColourPickerDialog(
            title = if (fg) "Colour" else "Background colour", initial = if (fg) state.colour else state.background, recent = state.recent,
            onChange = { if (fg) session.setColour(it) else session.setBackground(it) }, onCommit = { session.pushRecent(it) },
            onSwap = { session.swapColours() }, onDismiss = { picker = null },
        )
    }
}

@Composable
private fun Portrait(state: StudioState, session: StudioSession, gl: StudioGl, layersOpen: Boolean, toggleLayers: () -> Unit, pick: (PickerTarget) -> Unit, addPhoto: () -> Unit, onExit: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StatusStrip(state, session, onExit, onExport, Modifier.statusBarsPadding())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CanvasSurface(session, gl, state)
            ColourChips(state, pick, Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
        if (layersOpen) LayersPanel(state, session, addPhoto, Modifier.fillMaxWidth().heightIn(max = 300.dp))
        else ToolOptions(state, session, columns = 2)
        ToolRail(state, session, layersOpen, toggleLayers, vertical = false, Modifier.navigationBarsPadding())
    }
}

@Composable
private fun Landscape(state: StudioState, session: StudioSession, gl: StudioGl, layersOpen: Boolean, toggleLayers: () -> Unit, pick: (PickerTarget) -> Unit, addPhoto: () -> Unit, onExit: () -> Unit, onExport: () -> Unit) {
    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        ToolRail(state, session, layersOpen, toggleLayers, vertical = true, Modifier.fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight()) {
            StatusStrip(state, session, onExit, onExport, Modifier.statusBarsPadding())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CanvasSurface(session, gl, state)
                ColourChips(state, pick, Modifier.align(Alignment.BottomStart).padding(12.dp))
            }
        }
        Box(Modifier.width(280.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
            if (layersOpen) LayersPanel(state, session, addPhoto, Modifier.fillMaxSize()) else ToolOptions(state, session, columns = 1)
        }
    }
}

/** 44 dp: close, project name, save state, undo, redo, overflow (swap colours, Export). */
@Composable
private fun StatusStrip(state: StudioState, session: StudioSession, onExit: () -> Unit, onExport: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(44.dp).background(Lr.Surface1), verticalAlignment = Alignment.CenterVertically) {
        LrIconButton(LrIcon.CLOSE, "Close and save", onExit)
        Column(Modifier.weight(1f)) {
            Text(state.document.name, style = MaterialTheme.typography.bodyMedium, color = Lr.TextPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                "${state.document.width} x ${state.document.height}, ${state.save.label}", style = MaterialTheme.typography.labelSmall,
                color = if (state.save == app.rawline.core.studio.render.SaveState.FAILED) Lr.Error else Lr.TextMuted, maxLines = 1,
            )
        }
        LrIconButton(LrIcon.UNDO, "Undo", { session.undo() }, enabled = state.canUndo)
        LrIconButton(LrIcon.REDO, "Redo", { session.redo() }, enabled = state.canRedo)
        Box {
            LrIconButton(LrIcon.MORE, "More", { menu = true })
            LrDropdown(menu, { menu = false }, width = 190.dp) {
                LrMenuItem("Swap colours", { menu = false; session.swapColours() })
                LrMenuItem("Export", { menu = false; onExport() }, enabled = state.phase == Phase.READY)
            }
        }
    }
}

/** The GL surface with the pointer layer above it. The surface and the pointer box are the same size, so pointer pixels are surface pixels. */
@Composable
private fun CanvasSurface(session: StudioSession, gl: StudioGl, state: StudioState) {
    Box(Modifier.fillMaxSize().background(Lr.Canvas)) {
        AndroidView(factory = { StudioGlView(it, gl) }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().systemGestureExclusion().canvasInput(session)
                .semantics { contentDescription = "Drawing canvas, ${state.document.width} by ${state.document.height}" },
        )
    }
}

@Composable
private fun ColourChips(state: StudioState, pick: (PickerTarget) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(64.dp)) {
        Box(
            Modifier.align(Alignment.BottomEnd).size(40.dp).clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color(state.background.r, state.background.g, state.background.b))
                .border(1.dp, Lr.BorderStrong, RoundedCornerShape(4.dp)).clickable { pick(PickerTarget.BACKGROUND) }.semantics { contentDescription = "Background colour ${ColourHex.format(state.background)}" },
        )
        Box(
            Modifier.align(Alignment.TopStart).size(40.dp).clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color(state.colour.r, state.colour.g, state.colour.b))
                .border(1.dp, Lr.BorderStrong, RoundedCornerShape(4.dp)).clickable { pick(PickerTarget.FOREGROUND) }.semantics { contentDescription = "Colour ${ColourHex.format(state.colour)}" },
        )
    }
}

/** Layers, Brush, Eraser, Move, Scale. The selected tool's tile is #303030 with the accent on its icon (spec 5); Layers opens the panel and is not a tool. */
@Composable
private fun ToolRail(state: StudioState, session: StudioSession, layersOpen: Boolean, toggleLayers: () -> Unit, vertical: Boolean, modifier: Modifier = Modifier) {
    val items = @Composable {
        RailTile(StudioIcon.LAYERS, "Layers", layersOpen) { toggleLayers() }
        RailTile(StudioIcon.BRUSH, "Brush", state.tool == Tool.BRUSH) { session.setTool(Tool.BRUSH) }
        RailTile(StudioIcon.ERASER, "Eraser", state.tool == Tool.ERASER) { session.setTool(Tool.ERASER) }
        RailTile(StudioIcon.MOVE, "Move", state.tool == Tool.MOVE) { session.setTool(Tool.MOVE) }
        RailTile(StudioIcon.SCALE, "Scale", state.tool == Tool.SCALE) { session.setTool(Tool.SCALE) }
    }
    if (vertical) Column(modifier.width(56.dp).background(Lr.Surface1).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) { items() }
    else Row(modifier.fillMaxWidth().height(56.dp).background(Lr.Surface1).border(1.dp, Lr.BorderSubtle), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { items() }
}

@Composable
private fun RailTile(icon: StudioIcon, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.size(width = 56.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) Lr.SurfaceSelected else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).semantics { contentDescription = label; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        StudioIconView(icon, if (selected) Lr.Accent else Lr.IconPrimary, size = 22.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Lr.TextPrimary else Lr.TextSecondary, maxLines = 1)
    }
}

/** Messages from the session ("Layer is locked", the 10 layer cap, ...) and the one line "Recovered from autosave" on open. Each shows for a few seconds. */
@Composable
private fun Notices(state: StudioState, session: StudioSession, modifier: Modifier) {
    var recoveredShown by rememberSaveable { mutableStateOf(false) }
    var recoveredNow by remember { mutableStateOf(state.recovered && !recoveredShown) }
    LaunchedEffect(Unit) { if (recoveredNow) { recoveredShown = true; delay(3500); recoveredNow = false } }
    val msg = state.message
    // keyed on the message id: a new message restarts the timer, and the session forgets it afterwards (the effect never reads a stale message)
    LaunchedEffect(msg?.id) { if (msg != null) { delay(3500); session.consumeMessage(msg.id) } }
    val text = if (recoveredNow) "Recovered from autosave" else msg?.text
    if (text != null) Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Lr.Surface3).border(1.dp, Lr.BorderDefault, RoundedCornerShape(4.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = Lr.TextPrimary)
        }
    }
}
