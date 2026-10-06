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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.minimumInteractiveComponentSize
import kotlin.math.roundToInt
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import app.rawline.core.ui.LocalValueFeedback
import app.rawline.core.ui.ValueFeedback
import app.rawline.core.ui.ValueFeedbackPill
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.LrMotion
import app.rawline.core.ui.LrDropdown
import app.rawline.core.ui.LrMenuItem
import app.rawline.core.ui.LrIconButton
import androidx.compose.foundation.layout.aspectRatio
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.core.render.EditorGlView
import app.rawline.core.render.Stage
import app.rawline.core.ui.ChipButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import app.rawline.core.ui.LrTabs
import app.rawline.core.ui.tapOrDoubleTapAt
import app.rawline.core.ui.blockPointerInput
import app.rawline.core.ui.LrOutlineButton
import app.rawline.core.ui.Histogram
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Everything a tab needs. Tabs provided by other features (masking, remove) receive this too. */
class TabContext(val state: EditorState, val hist: IntArray?)

class EditorTab(
    val id: String, val title: String,
    /** Runs when the tab stops being the active one (tool switch, panel close, Back, leaving the editor): clear tool-local state and previews. */
    val onExit: () -> Unit = {},
    val content: @Composable (TabContext) -> Unit,
)

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
    /** Copy / paste of looks from the top right menu. Null recipes mean nothing to paste yet. */
    copied: EditRecipe? = null,
    lastEdited: EditRecipe? = null,
    onCopy: () -> Unit = {},
    onPaste: (EditRecipe, String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    onAiDenoiseChanged: (Boolean) -> Unit = {},
    /** Studio builds only: makes a new Studio project from this photo as it looks now. Null (and no menu item) in a build without Studio. Develop's edit is not touched. */
    onOpenInStudio: (() -> Unit)? = null,
) {
    val session = state.session
    val ss by session.state.collectAsState()
    val hist by session.histogram.collectAsState()
    val status by session.status.collectAsState()
    val outputRev by session.outputRevision.collectAsState()
    val scope = rememberCoroutineScope()
    // Panel state survives a rotation (the activity is recreated): the open tool, tab and sub-view come back as they were.
    var tab by rememberSaveable { mutableStateOf("light") }
    var mode by remember { mutableStateOf(PhotoMode.NONE) }
    var mixBand by rememberSaveable { mutableIntStateOf(0) }
    var mixMode by rememberSaveable { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(0.5f) }
    var cy by remember { mutableFloatStateOf(0.5f) }
    var viewW by remember { mutableFloatStateOf(1f) }
    var viewH by remember { mutableFloatStateOf(1f) }
    var showHist by rememberSaveable { mutableStateOf(false) }
    // idle = floating dock over the canvas; open = focused editing (tray, category rail, master rail) or the crop workspace
    var open by rememberSaveable { mutableStateOf(false) }
    var lightSub by rememberSaveable { mutableStateOf("basic") }
    var cropSub by rememberSaveable { mutableStateOf("crop") }
    var dialActive by remember { mutableStateOf(false) }
    val isCrop = open && tab == "geometry"
    val feedback = remember { ValueFeedback() }

    LaunchedEffect(open, tab) { mode = PhotoMode.NONE }
    val extraTabsNow by androidx.compose.runtime.rememberUpdatedState(extraTabs)
    // after a rotation, do not reopen into a tool owned by another feature (its own state is gone); the built-in tools reopen as they were
    LaunchedEffect(Unit) { if (open && extraTabsNow.any { it.id == tab }) open = false }
    DisposableEffect(open, tab) {
        val id = if (open) tab else ""
        onDispose { extraTabsNow.firstOrNull { it.id == id }?.onExit?.invoke() }
    }
    LaunchedEffect(isCrop) { session.setCropMode(isCrop); if (isCrop) { zoom = 1f; cx = .5f; cy = .5f; session.setView(1f, .5f, .5f) } }
    LaunchedEffect(Unit) { session.requestHistogram() }
    DisposableEffect(Unit) { onDispose { session.setCropMode(false); session.setBefore(false) } }

    // read the recipe so a crop, rotate or flip recomposes with the new output size
    state.recipe.geometry; outputRev
    val ow = session.outputSize[0].coerceAtLeast(1).toFloat(); val oh = session.outputSize[1].coerceAtLeast(1).toFloat()
    fun clampView(z: Float, x: Float, y: Float): Triple<Float, Float, Float> {
        val zz = z.coerceIn(1f, 16f)
        val fit = minOf(viewW / ow, viewH / oh)
        val s = fit * zz
        val visW = (minOf(viewW, ow * s) / (ow * s)); val visH = (minOf(viewH, oh * s) / (oh * s))
        return Triple(zz, x.coerceIn(visW / 2, 1 - visW / 2), y.coerceIn(visH / 2, 1 - visH / 2))
    }

    val tabNow by androidx.compose.runtime.rememberUpdatedState(tab)
    val openNow by androidx.compose.runtime.rememberUpdatedState(open)
    val lightSubNow by androidx.compose.runtime.rememberUpdatedState(lightSub)
    val modeNow by androidx.compose.runtime.rememberUpdatedState(mode)
    val zoomNow by androidx.compose.runtime.rememberUpdatedState(zoom)
    val gesturesNow by androidx.compose.runtime.rememberUpdatedState(toolGestures)
    val swipeNow by androidx.compose.runtime.rememberUpdatedState(onSwipePhoto)
    // set by the main photo handler when it takes the gesture (tool, zoom pan, colour target); the swipe watcher then stands down
    val mainClaimed = remember { BooleanArray(1) }
    // true when the last press on the photo was a hold that showed the original: that release is not a tap
    val heldForBefore = remember { BooleanArray(1) }
    // the last tap closed the panel: a double tap that follows reopens it (a double tap zooms, it does not close)
    val tapClosed = remember { BooleanArray(1) }
    // a tool owns the photo only while its panel is open; closing the panel hands the photo back (tool gestures, handles, hold-for-before, swipe)
    fun activeTabNow() = if (open) tab else ""
    /**
     * Decided once, at the first finger down (before any tool's own down handler can clear its state): sideways flicks change photo only
     * when nothing else owns one finger. Any open tool (crop, masking, remove) keeps the flick, whatever page it is on.
     */
    fun swipeAllowedAtDown(): Boolean {
        if (modeNow != PhotoMode.NONE || zoomNow > 1.01f) return false
        if (openNow && (tabNow == "geometry" || extraTabsNow.any { it.id == tabNow })) return false
        return gesturesNow(activeTabNow(), PhotoMapper(session, viewW, viewH)) == null
    }

    val photoArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Lr.Black).onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }) {
            // always composed under the GL surface: it is the shared element that morphs to and from the loupe and library thumbnail
            if (placeholder != null) {
                Image(placeholder.asImageBitmap(), photo.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().then(app.rawline.core.ui.LocalSharedPhoto.current(photo.id)))
            }
            AndroidView(factory = { EditorGlView(it, session) }, modifier = Modifier.fillMaxSize())
            if (ss.stage == Stage.LOADING) {
                Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    app.rawline.core.ui.LocalLoader(size = 24.dp)
                    Text(ss.message, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (ss.stage == Stage.ERROR) Text(ss.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            // Zoom, pan, double tap and hold-for-before. Disabled while cropping.
            if (!isCrop) {
                Box(
                    Modifier.fillMaxSize()
                        // Hold for the original. Outside the tap handler on purpose: that one sees (and consumes) the lift first.
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    heldForBefore[0] = false
                                    if (modeNow == PhotoMode.NONE && gesturesNow(activeTabNow(), PhotoMapper(session, viewW, viewH)) == null) {
                                        // hold for a moment to see the original; a quick tap or pinch start does nothing
                                        val released = kotlinx.coroutines.withTimeoutOrNull(350) { tryAwaitRelease() }
                                        if (released == null) { heldForBefore[0] = true; session.setBefore(true); try { tryAwaitRelease() } finally { session.setBefore(false) } }
                                    }
                                },
                            )
                        }
                        // Taps fire the moment the finger lifts (no 300 ms wait for a possible second tap); a double tap zooms.
                        .tapOrDoubleTapAt(
                            onTap = { p ->
                                tapClosed[0] = false
                                if (heldForBefore[0]) return@tapOrDoubleTapAt
                                if (modeNow == PhotoMode.NONE && !isCrop && openNow && !(tabNow == "light" && lightSubNow == "curve") && gesturesNow(activeTabNow(), PhotoMapper(session, viewW, viewH)) == null) { open = false; tapClosed[0] = true }
                                if (modeNow == PhotoMode.PICK_WB) {
                                    val m = session.mapPoint(p.x, p.y, viewW, viewH) ?: return@tapOrDoubleTapAt
                                    scope.launch {
                                        val rgb = session.sample(m[0], m[1]) ?: return@launch
                                        val a = state.recipe.adjust
                                        val (t, ti) = AutoTools.wbFromSample(rgb, a.temp, a.tint)
                                        state.edit("White balance pick") { it.copy(adjust = it.adjust.copy(temp = t, tint = ti)) }
                                        mode = PhotoMode.NONE
                                    }
                                }
                            },
                            onDoubleTap = { p ->
                                if (tapClosed[0]) { open = true; tapClosed[0] = false }   // the first tap of this double tap closed the panel
                                if (zoom > 1.01f) { zoom = 1f; cx = .5f; cy = .5f } else {
                                    val m = session.mapPoint(p.x, p.y, viewW, viewH)
                                    val (z, x, y) = clampView(2.5f, m?.get(0) ?: .5f, m?.get(1) ?: .5f)
                                    zoom = z; cx = x; cy = y
                                }
                                session.setView(zoom, cx, cy)
                            },
                        )
                        // One finger swipe sideways = next / previous photo. Watches the raw events first and never consumes them,
                        // so nothing else on the photo can swallow it.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val allowed = swipeAllowedAtDown()
                                mainClaimed[0] = false
                                var dx = 0f; var dy = 0f; var multi = false
                                do {
                                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    if (ev.changes.size >= 2) multi = true
                                    ev.changes.firstOrNull()?.let { dx += it.position.x - it.previousPosition.x; dy += it.position.y - it.previousPosition.y }
                                } while (ev.changes.any { it.pressed })
                                val far = kotlin.math.abs(dx) > 90.dp.toPx() && kotlin.math.abs(dx) > 1.6f * kotlin.math.abs(dy)
                                if (far && !multi && allowed && !mainClaimed[0]) swipeNow(if (dx < 0) 1 else -1)
                            }
                        }
                        .pointerInput(ow, oh, mode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var targetBand = -1
                                val tool = gesturesNow(activeTabNow(), PhotoMapper(session, viewW, viewH))
                                var toolClaimed = tool != null && mode == PhotoMode.NONE && tool.onDown(down.position)
                                if (toolClaimed) { down.consume(); mainClaimed[0] = true }
                                try {
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
                                            mainClaimed[0] = true
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
                                        mainClaimed[0] = true
                                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (ev.changes.any { it.pressed })
                                if (toolClaimed) { toolClaimed = false; tool?.onUp(false) }
                                if (mode == PhotoMode.TARGET_MIXER) state.commit("Colour mixer target")
                                } finally {
                                    // the gesture coroutine can be cancelled mid-drag (mode or size change): release the tool
                                    if (toolClaimed) tool?.onUp(true)
                                }
                            }
                        },
                )
            }
            val fit = session.fitRect(viewW, viewH)
            if (isCrop) CropOverlay(state, fit, session.baseAspect(), fineGrid = dialActive)
            tabOverlay(if (open) tab else "", PhotoMapper(session, viewW, viewH, zoom, cx, cy))
            status?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Lr.ValuePill, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)) }
            if (mode != PhotoMode.NONE) {
                Text(
                    if (mode == PhotoMode.PICK_WB) "Tap something that should be grey" else "Drag up or down on a colour",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0xAA000000)).padding(8.dp), color = Color.White,
                )
            }
        }
    }

    // major modes (idle dock and master rail) and the Edit categories (category rail)
    val modes = remember(extraTabs) {
        buildList {
            add(Tool("auto", "Actions", LrIcon.AUTO)); add(Tool("presets", "Presets", LrIcon.PRESETS)); add(Tool("geometry", "Crop", LrIcon.CROP)); add(Tool("edit", "Edit", LrIcon.EDIT))
            extraTabs.forEach { add(Tool(it.id, it.title, if (it.id == "remove") LrIcon.HEALING else LrIcon.MASKING)) }
        }
    }
    val sections = listOf(Tool("light", "Light", LrIcon.LIGHT), Tool("colour", "Colour", LrIcon.COLOR), Tool("effects", "Effects", LrIcon.EFFECTS), Tool("detail", "Detail", LrIcon.DETAIL), Tool("optics", "Optics", LrIcon.OPTICS))
    val sectionIds = sections.map { it.id }
    var curveChannel by rememberSaveable { mutableIntStateOf(0) }
    var colourSub by rememberSaveable { mutableStateOf("basic") }
    var menu by remember { mutableStateOf(false) }

    val autoLevel: (() -> Unit)? = placeholder?.let { b -> { val a = AutoTools.autoLevel(b); state.edit("Auto level") { it.withStraighten(a, session.baseAspect(), lens = session.cropLens(it)) } } }
    val autoPerspective: (() -> Unit)? = placeholder?.let { b -> { val (v, h) = AutoTools.autoPerspective(b); state.edit("Auto perspective") { it.copy(geometry = it.geometry.copy(keystoneV = v, keystoneH = h)) } } }

    val panelBody: @Composable (String) -> Unit = { tab ->
        val ctx = TabContext(state, hist)
        when (tab) {
            "auto" -> AutoPanel(state, session, photo, placeholder)
            "light" -> Column {
                if (lightSub == "curve") CurvePanel(state, AdjustTarget.Global, curveChannel, { curveChannel = it }, { lightSub = "basic" })
                else LightPanel(state, AdjustTarget.Global, onCurve = { lightSub = "curve" })
            }
            "colour" -> Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LrOutlineButton("B & W", { val on = state.recipe.adjust.saturation <= -99f; state.edit("Black and white") { r -> r.copy(adjust = r.adjust.copy(saturation = if (on) 0f else -100f, vibrance = if (on) r.adjust.vibrance else 0f)) } }, Modifier.minimumInteractiveComponentSize(), active = state.recipe.adjust.saturation <= -99f, small = true)
                    Spacer(Modifier.weight(1f))
                    LrOutlineButton("Grading", { colourSub = if (colourSub == "grade") "basic" else "grade" }, Modifier.minimumInteractiveComponentSize(), active = colourSub == "grade", small = true)
                    LrOutlineButton("Mix", { colourSub = if (colourSub == "mix") "basic" else "mix" }, Modifier.minimumInteractiveComponentSize(), active = colourSub == "mix", small = true)
                }
                when (colourSub) {
                    "mix" -> MixerPanel(state, AdjustTarget.Global, mixBand, { mixBand = it }, mixMode, { mixMode = it }, { mode = PhotoMode.TARGET_MIXER })
                    "grade" -> GradingPanel(state, AdjustTarget.Global)
                    else -> PanelColumn {
                        app.rawline.core.ui.PanelHeader("Colour", Resets.colourBasicsModified(state.recipe.adjust), { editAdjust(state, AdjustTarget.Global, "Reset colour") { Resets.colourBasics(it) } })
                        ColourBasicsPanel(state, AdjustTarget.Global, header = false,
                            onAutoWb = { scope.launch { session.baseStats()?.let { s -> val (t, ti) = AutoTools.autoWb(s); state.edit("Auto white balance") { r -> r.copy(adjust = r.adjust.copy(temp = t, tint = ti)) } } } },
                            onPickWb = { mode = PhotoMode.PICK_WB })
                    }
                }
            }
            "effects" -> EffectsPanel(state, AdjustTarget.Global)
            "detail" -> DetailPanel(state, onAiDenoiseChanged)
            "optics" -> OpticsPanel(state, session.lens?.name, photo.lens)
            "geometry" -> GeometryPanel(state, session.baseAspect(), cropSub, onAutoLevel = autoLevel, onAutoPerspective = autoPerspective, onStraightening = { dialActive = it })
            "presets" -> PresetsPanel(state, userPresets, onSavePreset, onDeletePreset)
            "history" -> HistoryPanel(state, onSnapshot)
            "" -> {}
            else -> extraTabs.firstOrNull { it.id == tab }?.content?.invoke(ctx)
        }
    }

    val autoLight = { scope.launch { session.baseStats()?.let { st -> val a = AutoTools.autoLight(st)
        state.edit("Auto") { r -> r.copy(adjust = r.adjust.copy(exposure = a.exposure, contrast = a.contrast, highlights = a.highlights, shadows = a.shadows, whites = a.whites, blacks = a.blacks)) } } }; Unit }
    val inEdit = open && tab in sectionIds
    val config = LocalConfiguration.current
    // Landscape: the tool controls sit in a side column so the photo keeps the full height; portrait stacks them under the photo.
    val landscape = config.screenWidthDp > config.screenHeightDp
    // A portrait photo would shrink to a sliver above a full-height tray, so there the edit tray floats over the photo, see-through.
    // The tone curve graph is drawn over the photo, so it never sits under the floating tray: there the photo shrinks above a stacked tray.
    val overlayTray = inEdit && oh > ow && !landscape && !(tab == "light" && lightSub == "curve")
    // never let the tray take more than about 40 percent of the screen height
    val trayCap = (config.screenHeightDp * 0.40f).dp
    val tray: @Composable (Float) -> Unit = { alpha ->
        Box(
            Modifier.fillMaxWidth().background(Lr.Surface2.copy(alpha = alpha)).blockPointerInput().then(
                if (landscape) Modifier.fillMaxHeight()
                else if (inEdit) Modifier.heightIn(max = minOf(if (alpha < 1f) 250.dp else 270.dp, trayCap))
                else Modifier.height(minOf(300.dp, trayCap)),
            ),
        ) {
            // category switch: outgoing 100ms fade with a 4dp shift left, incoming 140ms fade from 4dp right (spec 9.5)
            val shift = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.roundToPx() }
            androidx.compose.animation.AnimatedContent(
                tab, label = "tray",
                transitionSpec = {
                    ((fadeIn(tween(140, easing = LrMotion.enter)) + slideInHorizontally(tween(140, easing = LrMotion.enter)) { shift }) togetherWith
                        (fadeOut(tween(100)) + slideOutHorizontally(tween(100)) { -shift }))
                        .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
                },
            ) { t -> panelBody(t) }
        }
    }
    // What the crop tool started from. Saved across a rotation; cancelling steps back to that history entry (no new entry when unchanged).
    var entryGeo by rememberSaveable(stateSaver = GeometrySaver) { mutableStateOf(state.recipe.geometry) }
    var entryIndex by rememberSaveable { mutableIntStateOf(state.historyIndex) }
    var cropEntered by rememberSaveable { mutableStateOf(false) }
    val cropCancelled = remember { BooleanArray(1) }
    LaunchedEffect(isCrop) {
        if (isCrop) {
            if (!cropEntered) { entryGeo = state.recipe.geometry; entryIndex = state.historyIndex; cropEntered = true; cropCancelled[0] = false }
        } else if (cropEntered) {
            cropEntered = false
            // leaving by Done (or any tool switch): store the crop that is actually rendered, so a later reader of the recipe sees the same box
            if (!cropCancelled[0] && ss.stage == Stage.READY) {
                fun fitted(r: EditRecipe) = fitStoredCrop(r.geometry, r.optics, session.orientationValue, session.sourceWidth, session.sourceHeight, session.cropLens(r))
                if (fitted(state.recipe) !== state.recipe.geometry) state.edit("Fit crop") { it.copy(geometry = fitted(it)) }
            }
            cropCancelled[0] = false
        }
    }
    val cancelCrop = {
        cropCancelled[0] = true
        state.apply(planCropCancel(state.history.map { it.recipe }, state.historyIndex, state.recipe, entryIndex, entryGeo))
        open = false
    }
    // closing a panel puts its sub views back to their first page
    LaunchedEffect(open) { if (!open) { lightSub = "basic"; cropSub = "crop" } }
    androidx.activity.compose.BackHandler(enabled = open) {
        if (isCrop) cancelCrop()
        else if (tab == "light" && lightSub == "curve") lightSub = "basic"
        else open = false
    }
    fun selectMode(t: Tool) {
        mode = PhotoMode.NONE
        val id = if (t.id == "edit") (if (tab in sectionIds) tab else "light") else t.id
        if (open && (tab == id || (t.id == "edit" && tab in sectionIds))) open = false else { tab = id; open = true }
    }
    val cropStatus = run {
        val gg = state.recipe.geometry
        "${CropMath.label(gg.aspect)}  ·  ${(gg.cropW * ow).roundToInt()} × ${(gg.cropH * oh).roundToInt()}"
    }

    // the controls under (portrait) or beside (landscape) the photo
    val controlStack: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        if (isCrop) {
            Box((if (landscape) Modifier.weight(1f) else Modifier.height(CropPanelHeight)).fillMaxWidth().background(Lr.Surface1)) {
                GeometryPanel(state, session.baseAspect(), cropSub, onAutoLevel = autoLevel, onAutoPerspective = autoPerspective, onStraightening = { dialActive = it })
            }
            CropConfirmationBar(cropSub, { cropSub = it }, { cancelCrop() }, { open = false })
        } else {
            // parameter tray: straight edge, no handle, open controls on the surface (floats over the photo instead for portrait photos)
            if (!overlayTray) Box(if (landscape) Modifier.weight(1f) else Modifier) { tray(1f) }
            if (inEdit) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Lr.BorderSubtle))
                CategoryRail(sections, tab, { mode = PhotoMode.NONE; tab = it.id }, { autoLight() })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Lr.BorderDefault))
            CompactMasterRail(modes, { t -> if (t.id == "edit") tab in sectionIds else tab == t.id }, { selectMode(it) })
        }
    }

    CompositionLocalProvider(LocalValueFeedback provides feedback) {
    val canvasAndOverlays: @Composable BoxScope.() -> Unit = {
            // ---- canvas: pure black, contain fit. In idle state it leaves room for the dock. ----
            photoArea(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = if (open || landscape) 0.dp else LrDim.idleDock + 20.dp))

            // The tone curve is drawn before the top bar so the bar's buttons stay on top of it (a tall photo reaches under the bar).
            if (open && tab == "light" && lightSub == "curve") {
                val d = androidx.compose.ui.platform.LocalDensity.current
                val fr = session.fitRect(viewW, viewH)
                val pad = 24.dp
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    with(d) {
                        CurveGraph(state, AdjustTarget.Global, hist, curveChannel,
                            Modifier.offset(fr[0].toDp() - pad, fr[1].toDp() - pad).size(fr[2].toDp() + pad * 2, fr[3].toDp() + pad * 2), inset = pad)
                    }
                }
            }

            if (isCrop) {
                // crop workspace: the live aspect and pixel size, centred
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(LrDim.topBar).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CropStatusPill(cropStatus) }
                }
            } else {
                // ---- top utility bar: transparent over the canvas ----
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(LrDim.topBar).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LrIconButton(LrIcon.BACK, "Back", onBack, hit = LrDim.touch)
                    Spacer(Modifier.weight(1f))
                    LrIconButton(LrIcon.UNDO, "Undo", { state.undo() }, enabled = state.canUndo, hit = LrDim.touch)
                    LrIconButton(LrIcon.REDO, "Redo", { state.redo() }, enabled = state.canRedo, hit = LrDim.touch)
                    LrIconButton(LrIcon.SHARE, "Add to export queue", onExport, hit = LrDim.touch)
                    Box {
                        LrIconButton(LrIcon.MORE, "More", { menu = true }, hit = LrDim.touch)
                        LrDropdown(menu, { menu = false }) {
                            LrMenuItem("Copy edits", { menu = false; onCopy() }, LrIcon.COPY)
                            if (copied != null) LrMenuItem("Paste edits", { menu = false; onPaste(copied, "Pasted edits") }, LrIcon.COPY)
                            if (lastEdited != null) LrMenuItem("Paste from last", { menu = false; onPaste(lastEdited, "Pasted from last edit") }, LrIcon.COPY)
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Lr.Divider))
                            LrMenuItem(if (showHist) "Hide histogram" else "Show histogram", { menu = false; showHist = !showHist }, LrIcon.HISTOGRAM)
                            LrMenuItem("Versions and history", { menu = false; tab = "history"; open = true }, LrIcon.VERSIONS)
                            LrMenuItem("Export settings", { menu = false; onExportSettings() }, LrIcon.DOWNLOAD)
                            if (onOpenInStudio != null) LrMenuItem("Open in Studio", { menu = false; onOpenInStudio() })
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Lr.Divider))
                            // Look version: an edit made before the look update keeps its old rendering until the person moves it (one undoable step)
                            if (state.canUpdateLook) {
                                LrMenuItem("Look: earlier (made before the look update)", {}, LrIcon.INFO, enabled = false)
                                LrMenuItem("Update look", { menu = false; state.updateLook(); feedback.note("Look updated. Undo to go back.") }, LrIcon.REFRESH)
                            } else LrMenuItem("Look: current", {}, LrIcon.INFO, enabled = false)
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Lr.Divider))
                            LrMenuItem("Reset all edits", { menu = false; state.reset() }, LrIcon.RESET)
                        }
                    }
                }
            }
            ValueFeedbackPill(feedback, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = if (isCrop) LrDim.topBar else 14.dp))
            if (showHist && !isCrop) Histogram(hist, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 12.dp).width(120.dp).height(54.dp))

            FlatVisibility(
                overlayTray, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(LrMotion.panel, easing = LrMotion.standard)) { it / 3 } + fadeIn(tween(LrMotion.normal, easing = LrMotion.enter)),
                exit = slideOutVertically(tween(LrMotion.panel - 20, easing = LrMotion.standard)) { it / 3 } + fadeOut(tween(100)),
            ) { tray(0.8f) }
            // ---- idle dock ----
            FlatVisibility(
                !open, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).blockPointerInput(),
                enter = fadeIn(tween(LrMotion.normal, easing = LrMotion.enter)), exit = fadeOut(tween(LrMotion.instant)),
            ) { FloatingMasterDock(modes, { selectMode(it) }) }
    }
    if (landscape) {
        Row(Modifier.fillMaxSize().background(Lr.Canvas).navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxHeight(), content = canvasAndOverlays)
            if (open) Column(Modifier.width(360.dp).fillMaxHeight().background(Lr.Surface1).statusBarsPadding()) { controlStack() }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Lr.Canvas).navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth(), content = canvasAndOverlays)
            // ---- focused editing: parameter tray, category rail, master rail (fixed, stacked) ----
            if (isCrop) Column { controlStack() }
            else Box(Modifier.fillMaxWidth()) {
                FlatVisibility(
                    open,
                    // Slide and fade only: animating the size would resize the photo (and its GL surface) every frame.
                    enter = slideInVertically(tween(LrMotion.panel, easing = LrMotion.standard)) { it / 3 } + fadeIn(tween(LrMotion.normal, easing = LrMotion.enter)),
                    exit = slideOutVertically(tween(LrMotion.panel - 20, easing = LrMotion.standard)) { it / 3 } + fadeOut(tween(100)),
                ) { Column { controlStack() } }
            }
        }
    }
    }
}

@Composable
private fun AutoPanel(state: EditorState, session: app.rawline.core.render.EditorSession, photo: Photo, placeholder: Bitmap?) = PanelColumn {
    val scope = rememberCoroutineScope()
    @Composable fun Action(icon: LrIcon, title: String, hint: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().height(LrDim.presetRow).clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            LrIconView(icon, Lr.IconPrimary, size = 22.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = Lr.TextPrimary)
                Text(hint, style = MaterialTheme.typography.labelSmall, color = Lr.TextMuted)
            }
        }
    }
    Action(LrIcon.LIGHT, "Auto tone", "Exposure, contrast, highlights, shadows") {
        scope.launch { session.baseStats()?.let { s ->
            val a = AutoTools.autoLight(s)
            state.edit("Auto tone") { r -> r.copy(adjust = r.adjust.copy(exposure = a.exposure, contrast = a.contrast, highlights = a.highlights, shadows = a.shadows, whites = a.whites, blacks = a.blacks)) }
        } }
    }
    Action(LrIcon.COLOR, "Auto white balance", "Neutralise the colour cast") {
        scope.launch { session.baseStats()?.let { s -> val (t, ti) = AutoTools.autoWb(s); state.edit("Auto white balance") { r -> r.copy(adjust = r.adjust.copy(temp = t, tint = ti)) } } }
    }
    Action(LrIcon.CROP, "Auto level", "Straighten the horizon") {
        placeholder?.let { b -> val a = AutoTools.autoLevel(b); state.edit("Auto level") { it.withStraighten(a, session.baseAspect(), lens = session.cropLens(it)) } }
    }
    Action(LrIcon.GEOMETRY, "Auto perspective", "Correct converging lines") {
        placeholder?.let { b -> val (v, h) = AutoTools.autoPerspective(b); state.edit("Auto perspective") { it.copy(geometry = it.geometry.copy(keystoneV = v, keystoneH = h)) } }
    }
    Action(LrIcon.RESET, "Reset all edits", "Back to the original") { state.reset() }
}
