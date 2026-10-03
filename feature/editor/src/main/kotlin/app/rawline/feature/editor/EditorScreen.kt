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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.core.render.EditorGlView
import app.rawline.core.render.Stage
import app.rawline.core.ui.ChipButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import app.rawline.core.ui.LrTabs
import app.rawline.core.ui.LrOutlineButton
import app.rawline.core.ui.Histogram
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Everything a tab needs. Tabs provided by other features (masking, remove) receive this too. */
class TabContext(val state: EditorState, val hist: IntArray?)

class EditorTab(val id: String, val title: String, val content: @Composable (TabContext) -> Unit)

/** Converts between view pixels and normalised positions in the shown (output) image. */
class PhotoMapper(private val session: app.rawline.core.render.EditorSession, val viewW: Float, val viewH: Float, val zoom: Float = 1f, val cx: Float = .5f, val cy: Float = .5f) {
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
    onExportSettings: () -> Unit = {},
    /** +1 next photo, -1 previous. Swipe sideways on the photo when nothing else is using one finger. */
    onSwipePhoto: (Int) -> Unit = {},
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
    var showHist by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(true) }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    LaunchedEffect(tab) { session.setCropMode(tab == "geometry"); if (tab == "geometry") { zoom = 1f; cx = .5f; cy = .5f; session.setView(1f, .5f, .5f) } }
    LaunchedEffect(Unit) { session.requestHistogram() }
    DisposableEffect(Unit) { onDispose { session.setCropMode(false); session.setBefore(false) } }

    // read the recipe so a crop, rotate or flip recomposes with the new output size
    state.recipe.geometry
    val ow = session.outputSize[0].coerceAtLeast(1).toFloat(); val oh = session.outputSize[1].coerceAtLeast(1).toFloat()
    fun clampView(z: Float, x: Float, y: Float): Triple<Float, Float, Float> {
        val zz = z.coerceIn(1f, 16f)
        val fit = minOf(viewW / ow, viewH / oh)
        val s = fit * zz
        val visW = (minOf(viewW, ow * s) / (ow * s)); val visH = (minOf(viewH, oh * s) / (oh * s))
        return Triple(zz, x.coerceIn(visW / 2, 1 - visW / 2), y.coerceIn(visH / 2, 1 - visH / 2))
    }

    val photoArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Lr.Black).onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }) {
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
                                onPress = {
                                    if (mode == PhotoMode.NONE && toolGestures(tab, PhotoMapper(session, viewW, viewH)) == null) {
                                        // hold for a moment to see the original; a quick tap or pinch start does nothing
                                        val released = kotlinx.coroutines.withTimeoutOrNull(350) { tryAwaitRelease() }
                                        if (released == null) { session.setBefore(true); tryAwaitRelease(); session.setBefore(false) }
                                    }
                                },
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
                                var swipeDx = 0f; var swipeDy = 0f; var multiSeen = false
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
                                    if (multi) multiSeen = true
                                    if (!multi && !toolClaimed) ev.changes.firstOrNull()?.let { swipeDx += it.position.x - it.previousPosition.x; swipeDy += it.position.y - it.previousPosition.y }
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
                                else if (!multiSeen && mode == PhotoMode.NONE && tool == null && zoom <= 1.01f && tab != "geometry" &&
                                    kotlin.math.abs(swipeDx) > 160f && kotlin.math.abs(swipeDx) > 2.2f * kotlin.math.abs(swipeDy)) onSwipePhoto(if (swipeDx < 0) 1 else -1)
                                if (mode == PhotoMode.TARGET_MIXER) state.commit("Colour mixer target")
                            }
                        },
                )
            }
            val fit = session.fitRect(viewW, viewH)
            if (tab == "geometry") CropOverlay(state, fit, session.baseAspect())
            tabOverlay(tab, PhotoMapper(session, viewW, viewH, zoom, cx, cy))
            status?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xAA000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) }
            if (mode != PhotoMode.NONE) {
                Text(
                    if (mode == PhotoMode.PICK_WB) "Tap something that should be grey" else "Drag up or down on a colour",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0xAA000000)).padding(8.dp), color = Color.White,
                )
            }
        }
    }

    class Tool(val id: String, val title: String, val icon: LrIcon)
    // top level modes (bottom row) and the Edit sections (row above it), as in Lightroom mobile
    val maskTab = extraTabs.firstOrNull { it.id != "remove" }
    val modes = remember(extraTabs) {
        buildList {
            add(Tool("auto", "Actions", LrIcon.AUTO)); add(Tool("presets", "Presets", LrIcon.PRESETS)); add(Tool("geometry", "Crop", LrIcon.CROP)); add(Tool("edit", "Edit", LrIcon.EDIT))
            extraTabs.forEach { add(Tool(it.id, it.title, if (it.id == "remove") LrIcon.HEALING else LrIcon.MASKING)) }
        }
    }
    val sections = listOf(Tool("light", "Light", LrIcon.LIGHT), Tool("colour", "Color", LrIcon.COLOR), Tool("effects", "Effects", LrIcon.EFFECTS), Tool("detail", "Detail", LrIcon.DETAIL), Tool("optics", "Optics", LrIcon.OPTICS))
    val sectionIds = sections.map { it.id }
    var lightSub by remember { mutableStateOf("basic") }
    var colourSub by remember { mutableStateOf("basic") }
    var menu by remember { mutableStateOf(false) }

    val panelBody: @Composable (String) -> Unit = { tab ->
        val ctx = TabContext(state, hist)
        when (tab) {
            "auto" -> AutoPanel(state, session, photo, placeholder)
            "light" -> Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    LrOutlineButton("Curve", { lightSub = if (lightSub == "curve") "basic" else "curve" }, icon = LrIcon.CURVE, active = lightSub == "curve")
                }
                if (lightSub == "curve") CurvePanel(state, AdjustTarget.Global, hist)
                else LightPanel(state, AdjustTarget.Global)
            }
            "colour" -> Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LrOutlineButton("B & W", { val on = state.recipe.adjust.saturation <= -99f; state.edit("Black and white") { r -> r.copy(adjust = r.adjust.copy(saturation = if (on) 0f else -100f, vibrance = if (on) r.adjust.vibrance else 0f)) } }, active = state.recipe.adjust.saturation <= -99f)
                    Spacer(Modifier.weight(1f))
                    LrOutlineButton("Grading", { colourSub = if (colourSub == "grade") "basic" else "grade" }, active = colourSub == "grade")
                    LrOutlineButton("Mix", { colourSub = if (colourSub == "mix") "basic" else "mix" }, active = colourSub == "mix")
                }
                when (colourSub) {
                    "mix" -> MixerPanel(state, AdjustTarget.Global, mixBand, { mixBand = it }, mixMode, { mixMode = it }, { mode = PhotoMode.TARGET_MIXER })
                    "grade" -> GradingPanel(state, AdjustTarget.Global)
                    else -> PanelColumn {
                        ColourBasicsPanel(state, AdjustTarget.Global,
                            onAutoWb = { scope.launch { session.baseStats()?.let { s -> val (t, ti) = AutoTools.autoWb(s); state.edit("Auto white balance") { r -> r.copy(adjust = r.adjust.copy(temp = t, tint = ti)) } } } },
                            onPickWb = { mode = PhotoMode.PICK_WB })
                    }
                }
            }
            "effects" -> EffectsPanel(state, AdjustTarget.Global)
            "detail" -> DetailPanel(state, onAiDenoiseChanged)
            "optics" -> OpticsPanel(state, session.lens?.name, photo.lens)
            "geometry" -> GeometryPanel(state, session.baseAspect(), onAutoLevel = {
                placeholder?.let { b -> val a = AutoTools.autoLevel(b); state.edit("Auto level") { it.copy(geometry = it.geometry.copy(angle = a)) } }
            }, onAutoPerspective = {
                placeholder?.let { b -> val (v, h) = AutoTools.autoPerspective(b); state.edit("Auto perspective") { it.copy(geometry = it.geometry.copy(keystoneV = v, keystoneH = h)) } }
            })
            "presets" -> PresetsPanel(state, userPresets, onSavePreset, onDeletePreset)
            "history" -> HistoryPanel(state, onSnapshot)
            "" -> {}
            else -> extraTabs.firstOrNull { it.id == tab }?.content?.invoke(ctx)
        }
    }

    val autoLight = { scope.launch { session.baseStats()?.let { st -> val a = AutoTools.autoLight(st)
        state.edit("Auto") { r -> r.copy(adjust = r.adjust.copy(exposure = a.exposure, contrast = a.contrast, highlights = a.highlights, shadows = a.shadows, whites = a.whites, blacks = a.blacks)) } } }; Unit }
    val inEdit = tab in sectionIds
    val panelShown = open && tab != ""
    // Edit sections float over the lower part of the photo; the other tools get their own space.
    val panelH = if (inEdit) 214.dp else 300.dp
    val overlap = if (inEdit) panelH * 0.37f else 0.dp
    val reserved = 60.dp + (if (inEdit) 58.dp else 0.dp) + (if (panelShown) panelH - overlap else 0.dp)

    Box(Modifier.fillMaxSize().background(Lr.Black)) {
        photoArea(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = reserved))
        // ---- top icons float over the photo ----
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TopIcon(LrIcon.BACK, "Back", true, onClick = onBack)
            TopIcon(LrIcon.UNDO, "Undo", state.canUndo) { state.undo() }
            TopIcon(LrIcon.REDO, "Redo", state.canRedo) { state.redo() }
            Spacer(Modifier.weight(1f))
            TopIcon(LrIcon.SHARE, "Add to export queue", true, onClick = onExport)
            Box {
                TopIcon(LrIcon.MORE, "More", true) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (showHist) "Hide histogram" else "Show histogram") }, onClick = { menu = false; showHist = !showHist })
                    DropdownMenuItem(text = { Text("Reset all edits") }, onClick = { menu = false; state.reset() })
                    DropdownMenuItem(text = { Text("Versions and history") }, onClick = { menu = false; tab = "history"; open = true })
                    DropdownMenuItem(text = { Text("Export settings") }, onClick = { menu = false; onExportSettings() })
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            AnimatedVisibility(
                visible = panelShown,
                enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(160)),
            ) {
                Column(
                    Modifier.fillMaxWidth().height(panelH).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(if (inEdit) Lr.PanelOverlay else Lr.Panel),
                ) {
                    // grab handle: drag down or tap to close
                    Box(
                        Modifier.fillMaxWidth().height(20.dp).pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy > 6f && tab != "geometry") open = false } }
                            .clickable(enabled = tab != "geometry") { open = false }.semantics { contentDescription = "Close panel" },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Lr.TrackOff)) }
                    Box(Modifier.weight(1f)) {
                        Crossfade(tab, animationSpec = tween(160), label = "panel") { t -> panelBody(t) }
                    }
                }
            }
            val modeBg by animateColorAsState(if (inEdit) Lr.Accent else Color.Transparent, tween(180), label = "mode")
            Column(Modifier.fillMaxWidth().background(if (inEdit && panelShown) Lr.PanelOverlay else Lr.Background)) {
                AnimatedVisibility(inEdit, enter = fadeIn(tween(160)) + expandVertically(tween(200)), exit = fadeOut(tween(120)) + shrinkVertically(tween(160))) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(68.dp).height(52.dp).clip(RoundedCornerShape(14.dp)).clickable { autoLight() }.semantics { contentDescription = "Auto" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            LrIconView(LrIcon.AUTO, Lr.TextDim, size = 24.dp); Text("Auto", style = MaterialTheme.typography.labelMedium, color = Lr.TextDim)
                        }
                        Box(Modifier.width(1.dp).height(30.dp).background(Lr.Separator))
                        sections.forEach { t ->
                            val on = tab == t.id && panelShown
                            val bg by animateColorAsState(if (on) Lr.Surface else Color.Transparent, tween(160), label = "sec")
                            Column(
                                Modifier.width(72.dp).height(52.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                                    .clickable { mode = PhotoMode.NONE; if (tab == t.id) open = !open else { tab = t.id; open = true } }.semantics { contentDescription = t.title },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                LrIconView(t.icon, if (tab == t.id) Lr.Text else Lr.TextDim, size = 24.dp)
                                Text(t.title, style = MaterialTheme.typography.labelMedium, color = if (tab == t.id) Lr.Text else Lr.TextDim, maxLines = 1)
                            }
                        }
                    }
                }
                if (inEdit) Box(Modifier.fillMaxWidth().height(1.dp).background(Lr.Separator))
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    modes.forEach { t ->
                        val on = if (t.id == "edit") inEdit else tab == t.id
                        val bg by animateColorAsState(if (on) Lr.Accent else Color.Transparent, tween(180), label = "m")
                        Box(
                            Modifier.weight(1f).height(46.dp).padding(horizontal = 5.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                                .clickable {
                                    mode = PhotoMode.NONE
                                    if (t.id == "edit") { if (inEdit) open = !open else { tab = "light"; open = true } }
                                    else if (tab == t.id && t.id != "geometry") open = !open else { tab = t.id; open = true }
                                }.semantics { contentDescription = t.title },
                            contentAlignment = Alignment.Center,
                        ) { LrIconView(t.icon, if (on) Color.White else Lr.TextDim, size = 25.dp) }
                    }
                }
            }
        }
        if (showHist) Histogram(hist, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 52.dp, end = 12.dp).width(120.dp).height(54.dp))
    }
}

@Composable
private fun TopIcon(icon: LrIcon, description: String, enabled: Boolean, tint: Color = Lr.Text, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 2.dp).size(44.dp).clip(CircleShape).background(Color(0x40000000)).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        LrIconView(icon, if (enabled) tint else Color(0x66FFFFFF), size = 24.dp)
    }
}

@Composable
private fun SubTabs(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (id, label) -> ChipButton(label, selected == id, { onSelect(id) }) }
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
        androidx.compose.material3.Button(onClick = {
            placeholder?.let { b -> val (v, h) = AutoTools.autoPerspective(b); state.edit("Auto perspective") { it.copy(geometry = it.geometry.copy(keystoneV = v, keystoneH = h)) } }
        }, modifier = Modifier.fillMaxWidth()) { Text("Auto perspective") }
        androidx.compose.material3.OutlinedButton(onClick = { state.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Reset all edits") }
    }
}
