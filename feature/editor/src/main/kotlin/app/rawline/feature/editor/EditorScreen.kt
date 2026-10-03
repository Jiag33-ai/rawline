package app.rawline.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.core.render.EditorGlView
import app.rawline.core.render.Stage
import app.rawline.core.ui.ChipButton
import app.rawline.core.ui.Histogram
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Everything a tab needs. Tabs provided by other features (masking, remove) receive this too. */
class TabContext(val state: EditorState, val hist: IntArray?)

class EditorTab(val id: String, val title: String, val content: @Composable (TabContext) -> Unit)

/** Converts between view pixels and normalised positions in the shown (output) image. */
class PhotoMapper(private val session: app.rawline.core.render.EditorSession, val viewW: Float, val viewH: Float) {
    fun toImage(x: Float, y: Float): androidx.compose.ui.geometry.Offset? = session.mapPoint(x, y, viewW, viewH)?.let { androidx.compose.ui.geometry.Offset(it[0], it[1]) }
    fun toView(nx: Float, ny: Float): androidx.compose.ui.geometry.Offset = session.pointToView(nx, ny, viewW, viewH).let { androidx.compose.ui.geometry.Offset(it[0], it[1]) }
    val outW: Int get() = session.outputSize[0]
    val outH: Int get() = session.outputSize[1]
}

/** One finger tool input on the photo (brush strokes, dragging handles). Two finger pinch and pan still zoom the photo. */
class ToolGestures(
    val onDown: (androidx.compose.ui.geometry.Offset) -> Boolean,
    val onMove: (androidx.compose.ui.geometry.Offset) -> Unit,
    val onUp: (cancelled: Boolean) -> Unit,
)

/** What the photo area is doing besides zoom and pan. */
enum class PhotoMode { NONE, PICK_WB, TARGET_MIXER }

@Composable
fun EditorScreen(
    photo: Photo,
    state: EditorState,
    placeholder: Bitmap?,
    extraTabs: List<EditorTab>,
    /** Drawn over the photo for the active tab (mask handles, brush, remove). Gets the fit rectangle (left, top, w, h) of the image in px. */
    tabOverlay: @Composable BoxScope.(tabId: String, mapper: PhotoMapper) -> Unit,
    /** One finger input for the active tab, or null when the tab has none. */
    toolGestures: (tabId: String, mapper: PhotoMapper) -> ToolGestures? = { _, _ -> null },
    userPresets: List<Preset>,
    onSavePreset: (String, EditRecipe) -> Unit,
    onDeletePreset: (Preset) -> Unit,
    onSnapshot: (String) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    onAiDenoiseChanged: (Boolean) -> Unit = {},
) {
    val session = state.session
    val ss by session.state.collectAsState()
    val hist by session.histogram.collectAsState()
    val status by session.status.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("light") }
    var mode by remember { mutableStateOf(PhotoMode.NONE) }
    var mixBand by remember { mutableIntStateOf(0) }
    var mixMode by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(0.5f) }
    var cy by remember { mutableFloatStateOf(0.5f) }
    var viewW by remember { mutableFloatStateOf(1f) }
    var viewH by remember { mutableFloatStateOf(1f) }
    var showHist by remember { mutableStateOf(true) }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    LaunchedEffect(tab) { session.setCropMode(tab == "geometry"); if (tab == "geometry") { zoom = 1f; cx = .5f; cy = .5f; session.setView(1f, .5f, .5f) } }
    LaunchedEffect(Unit) { session.requestHistogram() }
    DisposableEffect(Unit) { onDispose { session.setCropMode(false); session.setBefore(false) } }

    val ow = ss.outW.coerceAtLeast(1).toFloat(); val oh = ss.outH.coerceAtLeast(1).toFloat()
    fun clampView(z: Float, x: Float, y: Float): Triple<Float, Float, Float> {
        val zz = z.coerceIn(1f, 16f)
        val fit = minOf(viewW / ow, viewH / oh)
        val s = fit * zz
        val visW = (minOf(viewW, ow * s) / (ow * s)); val visH = (minOf(viewH, oh * s) / (oh * s))
        return Triple(zz, x.coerceIn(visW / 2, 1 - visW / 2), y.coerceIn(visH / 2, 1 - visH / 2))
    }

    val photoArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color(0xFF2A2A2A)).onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }) {
            if (ss.stage != Stage.READY && placeholder != null) {
                Image(placeholder.asImageBitmap(), photo.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            AndroidView(factory = { EditorGlView(it, session) }, modifier = Modifier.fillMaxSize())
            if (ss.stage == Stage.LOADING) {
                Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.height(24.dp).width(24.dp), strokeWidth = 2.dp)
                    Text(ss.message, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (ss.stage == Stage.ERROR) Text(ss.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            // Zoom, pan, double tap and hold-for-before. Disabled while cropping.
            if (tab != "geometry") {
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(ow, oh, mode) {
                            detectTapGestures(
                                onDoubleTap = { p ->
                                    if (zoom > 1.01f) { zoom = 1f; cx = .5f; cy = .5f } else {
                                        val m = session.mapPoint(p.x, p.y, viewW, viewH)
                                        val (z, x, y) = clampView(2.5f, m?.get(0) ?: .5f, m?.get(1) ?: .5f)
                                        zoom = z; cx = x; cy = y
                                    }
                                    session.setView(zoom, cx, cy)
                                },
                                onPress = { if (mode == PhotoMode.NONE && toolGestures(tab, PhotoMapper(session, viewW, viewH)) == null) { session.setBefore(true); tryAwaitRelease(); session.setBefore(false) } },
                                onTap = { p ->
                                    if (mode == PhotoMode.PICK_WB) {
                                        val m = session.mapPoint(p.x, p.y, viewW, viewH) ?: return@detectTapGestures
                                        scope.launch {
                                            val rgb = session.sample(m[0], m[1]) ?: return@launch
                                            val a = state.recipe.adjust
                                            val (t, ti) = AutoTools.wbFromSample(rgb, a.temp, a.tint)
                                            state.edit("White balance pick") { it.copy(adjust = it.adjust.copy(temp = t, tint = ti)) }
                                            mode = PhotoMode.NONE
                                        }
                                    }
                                },
                            )
                        }
                        .pointerInput(ow, oh, mode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var targetBand = -1
                                val tool = toolGestures(tab, PhotoMapper(session, viewW, viewH))
                                var toolClaimed = tool != null && mode == PhotoMode.NONE && tool.onDown(down.position)
                                if (toolClaimed) down.consume()
                                if (mode == PhotoMode.TARGET_MIXER) {
                                    val m = session.mapPoint(down.position.x, down.position.y, viewW, viewH)
                                    if (m != null) scope.launch {
                                        val rgb = session.sample(m[0], m[1]) ?: return@launch
                                        val hsv = FloatArray(3)
                                        android.graphics.Color.RGBToHSV((rgb[0] * 255).toInt(), (rgb[1] * 255).toInt(), (rgb[2] * 255).toInt(), hsv)
                                        mixBand = bandForHue(hsv[0] / 360f)
                                    }
                                    targetBand = mixBand
                                }
                                do {
                                    val ev = awaitPointerEvent()
                                    val multi = ev.changes.size >= 2
                                    if (toolClaimed && multi) { tool?.onUp(true); toolClaimed = false }
                                    if (toolClaimed) {
                                        ev.changes.firstOrNull()?.let { c -> if (c.pressed) { tool?.onMove(c.position); c.consume() } }
                                    } else if (mode == PhotoMode.TARGET_MIXER && !multi) {
                                        val dy = ev.changes.firstOrNull()?.let { it.position.y - it.previousPosition.y } ?: 0f
                                        if (abs(dy) > 0f) {
                                            val band = mixBand
                                            val delta = -dy * 0.35f
                                            state.live { r ->
                                                val a = r.adjust
                                                fun upd(l: List<Float>) = l.toMutableList().also { it[band] = (it[band] + delta).coerceIn(-100f, 100f) }
                                                r.copy(adjust = when (mixMode) { 0 -> a.copy(mixHue = upd(a.mixHue)); 1 -> a.copy(mixSat = upd(a.mixSat)); else -> a.copy(mixLum = upd(a.mixLum)) })
                                            }
                                            ev.changes.forEach { it.consume() }
                                        }
                                    } else if (multi || zoom > 1.01f) {
                                        val z = ev.calculateZoom(); val pan = ev.calculatePan()
                                        val fit = minOf(viewW / ow, viewH / oh)
                                        val s = fit * zoom
                                        val nz = zoom * z
                                        val (zz, x, y) = clampView(nz, cx - pan.x / (ow * s), cy - pan.y / (oh * s))
                                        zoom = zz; cx = x; cy = y
                                        session.setView(zoom, cx, cy)
                                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (ev.changes.any { it.pressed })
                                if (toolClaimed) tool?.onUp(false)
                                if (mode == PhotoMode.TARGET_MIXER) state.commit("Colour mixer target")
                            }
                        },
                )
            }
            val fit = session.fitRect(viewW, viewH)
            if (tab == "geometry") CropOverlay(state, fit, ow / oh)
            tabOverlay(tab, PhotoMapper(session, viewW, viewH))
            status?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xAA000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) }
            if (showHist) Histogram(hist, Modifier.align(Alignment.TopEnd).padding(8.dp).width(120.dp).height(54.dp))
            if (mode != PhotoMode.NONE) {
                Text(
                    if (mode == PhotoMode.PICK_WB) "Tap something that should be grey" else "Drag up or down on a colour",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0xAA000000)).padding(8.dp), color = Color.White,
                )
            }
        }
    }

    val tabs = remember(extraTabs) { listOf(EditorTab("auto", "Auto") {}, EditorTab("light", "Light") {}, EditorTab("curve", "Curve") {}, EditorTab("colour", "Colour") {}, EditorTab("mixer", "Mixer") {},
        EditorTab("grade", "Grade") {}, EditorTab("effects", "Effects") {}, EditorTab("detail", "Detail") {}, EditorTab("optics", "Optics") {}, EditorTab("geometry", "Geometry") {}) + extraTabs +
        listOf(EditorTab("presets", "Presets") {}, EditorTab("history", "History") {}) }

    val panel: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEach { t -> ChipButton(t.title, tab == t.id, { tab = t.id; mode = PhotoMode.NONE }) }
            }
            val ctx = TabContext(state, hist)
            when (tab) {
                "auto" -> AutoPanel(state, session, photo, placeholder)
                "light" -> LightPanel(state, AdjustTarget.Global, onAuto = { scope.launch { session.baseStats()?.let { s -> state.edit("Auto") { r -> r.copy(adjust = AutoTools.autoLight(s).let { a -> r.adjust.copy(exposure = a.exposure, contrast = a.contrast, highlights = a.highlights, shadows = a.shadows, whites = a.whites, blacks = a.blacks) }) } } } })
                "curve" -> CurvePanel(state, AdjustTarget.Global, hist)
                "colour" -> PanelColumn {
                    ColourBasicsPanel(state, AdjustTarget.Global,
                        onAutoWb = { scope.launch { session.baseStats()?.let { s -> val (t, ti) = AutoTools.autoWb(s); state.edit("Auto white balance") { r -> r.copy(adjust = r.adjust.copy(temp = t, tint = ti)) } } } },
                        onPickWb = { mode = PhotoMode.PICK_WB })
                }
                "mixer" -> MixerPanel(state, AdjustTarget.Global, mixBand, { mixBand = it }, mixMode, { mixMode = it }, { mode = PhotoMode.TARGET_MIXER })
                "grade" -> GradingPanel(state, AdjustTarget.Global)
                "effects" -> EffectsPanel(state, AdjustTarget.Global)
                "detail" -> DetailPanel(state, onAiDenoiseChanged)
                "optics" -> OpticsPanel(state)
                "geometry" -> GeometryPanel(state, ow / oh, onAutoLevel = {
                    placeholder?.let { b -> val a = AutoTools.autoLevel(b); state.edit("Auto level") { it.copy(geometry = it.geometry.copy(angle = a)) } }
                }, onAutoPerspective = null)
                "presets" -> PresetsPanel(state, userPresets, onSavePreset, onDeletePreset)
                "history" -> HistoryPanel(state, onSnapshot)
                else -> extraTabs.firstOrNull { it.id == tab }?.content?.invoke(ctx)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(photo.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { state.undo() }, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = { state.redo() }, enabled = state.canRedo) { Text("Redo") }
            TextButton(onClick = { showHist = !showHist }) { Text(if (showHist) "Hide hist" else "Hist") }
            TextButton(onClick = onExport) { Text("Export") }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                photoArea(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(380.dp).fillMaxHeight())
            }
        } else {
            photoArea(Modifier.fillMaxWidth().weight(1f))
            panel(Modifier.fillMaxWidth().height(320.dp))
        }
    }
}

@Composable
private fun AutoPanel(state: EditorState, session: app.rawline.core.render.EditorSession, photo: Photo, placeholder: Bitmap?) = PanelColumn {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("One tap fixes. Each is one undo step.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.Button(onClick = {
            scope.launch { session.baseStats()?.let { s ->
                val a = AutoTools.autoLight(s)
                state.edit("Auto tone") { r -> r.copy(adjust = r.adjust.copy(exposure = a.exposure, contrast = a.contrast, highlights = a.highlights, shadows = a.shadows, whites = a.whites, blacks = a.blacks)) }
            } }
        }, modifier = Modifier.fillMaxWidth()) { Text("Auto tone") }
        androidx.compose.material3.Button(onClick = {
            scope.launch { session.baseStats()?.let { s -> val (t, ti) = AutoTools.autoWb(s); state.edit("Auto white balance") { r -> r.copy(adjust = r.adjust.copy(temp = t, tint = ti)) } } }
        }, modifier = Modifier.fillMaxWidth()) { Text("Auto white balance") }
        androidx.compose.material3.Button(onClick = {
            placeholder?.let { b -> val a = AutoTools.autoLevel(b); state.edit("Auto level") { it.copy(geometry = it.geometry.copy(angle = a)) } }
        }, modifier = Modifier.fillMaxWidth()) { Text("Auto level") }
        androidx.compose.material3.OutlinedButton(onClick = { state.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Reset all edits") }
    }
}
