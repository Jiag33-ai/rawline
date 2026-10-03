package app.rawline.feature.masking

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.rawline.core.cache.MaskStore
import app.rawline.core.ml.MaskAi
import app.rawline.core.model.BrushStroke
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskComponent
import app.rawline.core.model.MaskOp
import app.rawline.core.model.MaskType
import app.rawline.core.render.EditorSession
import app.rawline.core.render.P
import app.rawline.core.ui.ChipButton
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.SectionTitle
import app.rawline.feature.editor.AdjSlider
import app.rawline.feature.editor.AdjustTarget
import app.rawline.feature.editor.ColourBasicsPanel
import app.rawline.feature.editor.CurvePanel
import app.rawline.feature.editor.EditorState
import app.rawline.feature.editor.EditorTab
import app.rawline.feature.editor.EffectsPanel
import app.rawline.feature.editor.GradingPanel
import app.rawline.feature.editor.LightPanel
import app.rawline.feature.editor.MixerPanel
import app.rawline.feature.editor.PanelColumn
import app.rawline.feature.editor.PhotoMapper
import app.rawline.feature.editor.ToolGestures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Masking tab, on-photo handles and brush. One instance per open photo.
 * @param reference software bitmap of the screen preview, used by edge-aware brush
 */
class MaskingFeature(
    private val state: EditorState,
    private val session: EditorSession,
    private val store: MaskStore,
    private val scope: CoroutineScope,
    private val reference: suspend (Int) -> Bitmap?,
    private val ai: MaskAi? = null,
) {
    val ui = MaskUi()
    private val brushLayers = HashMap<String, BrushLayer>()
    private var refPixels: IntArray? = null
    private var refDims = 0 to 0

    private fun layerDims() = BrushLayer.dims(session.baseAspect())

    private fun brushLayer(key: String): BrushLayer {
        val (w, h) = layerDims()
        brushLayers[key]?.let { if (it.w == w && it.h == h) return it }
        return BrushLayer(w, h, if (refDims == (w to h)) refPixels else null).also { brushLayers[key] = it }
    }

    /** Renders the frame at layer size so the edge-aware brush has a picture to compare colours against. */
    fun ensureReference() {
        scope.launch(Dispatchers.Default) {
            val (w, h) = layerDims()
            if (refPixels != null && refDims == (w to h)) return@launch
            val b = reference(maxOf(w, h)) ?: return@launch
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
            refPixels = px; refDims = w to h
            brushLayers.values.forEach { it.reference = px }
        }
    }

    private val savedHash = HashMap<String, Int>()

    /**
     * Keeps the saved alpha images of brush masks in step with the recipe (after commits, undo, redo). Export reads these,
     * so the exported mask is the very image the screen used.
     */
    fun persist(recipe: EditRecipe) {
        scope.launch(Dispatchers.Default) {
            recipe.masks.flatMap { it.components }.filter { it.type == MaskType.BITMAP && it.layerKey?.startsWith("brush_") == true }.forEach { c ->
                val key = c.layerKey ?: return@forEach
                val hash = c.strokes.hashCode()
                if (savedHash[key] == hash) return@forEach
                val l = brushLayer(key)
                l.renderAll(c.strokes)
                store.save(key, l.alpha, l.w, l.h)
                upload(key, l.alpha, l.w, l.h)
                savedHash[key] = hash
            }
        }
    }

    private fun upload(key: String, bytes: ByteArray, w: Int, h: Int) = session.setLayer(key, bytes.copyOf(), w, h)

    /** Re-creates GPU layers for every bitmap mask after opening a photo. */
    fun restore(recipe: EditRecipe) {
        scope.launch(Dispatchers.Default) {
            recipe.masks.forEach { m ->
                m.components.filter { it.type == MaskType.BITMAP }.forEach { c ->
                    val key = c.layerKey ?: return@forEach
                    if (c.strokes.isNotEmpty()) {
                        val l = brushLayer(key)
                        l.renderAll(c.strokes)
                        upload(key, l.alpha, l.w, l.h)
                    } else store.load(key)?.let { (a, w, h) -> upload(key, a, w, h) }
                }
            }
        }
    }

    private fun pf(p: Offset): Offset {
        val g = state.recipe.geometry
        return Offset(g.cropX + p.x * g.cropW, g.cropY + p.y * g.cropH)
    }
    private fun toOut(f: Offset): Offset {
        val g = state.recipe.geometry
        return Offset((f.x - g.cropX) / g.cropW, (f.y - g.cropY) / g.cropH)
    }

    private val masks get() = state.recipe.masks
    private val selMask: Mask? get() = masks.getOrNull(ui.selected)
    private val selComp: MaskComponent? get() = selMask?.components?.getOrNull(ui.selectedComp)

    private fun select(i: Int) { ui.selected = i; ui.selectedComp = 0; session.setShowMask(if (ui.showOverlay) i else -1); ui.sub = "mask" }

    private fun add(kind: MaskKind) {
        val m = MaskFactory.mask(kind, masks.size + 1)
        if (masks.size >= P.MAX_MASKS) return
        state.edit("Add ${kind.label} mask") { it.copy(masks = it.masks + m) }
        select(masks.lastIndex)
        if (kind == MaskKind.BRUSH) m.components[0].layerKey?.let { k -> val l = brushLayer(k); upload(k, l.alpha, l.w, l.h) }
    }

    private fun addAi(label: String, alpha: ByteArray, w: Int, h: Int) {
        val key = "ai_${MaskFactory.newId()}"
        store.save(key, alpha, w, h)
        upload(key, alpha, w, h)
        val m = Mask(MaskFactory.newId(), label, listOf(MaskComponent(MaskType.BITMAP, MaskOp.ADD, layerKey = key, label = label)))
        state.edit("Add $label mask") { it.copy(masks = it.masks + m) }
        select(masks.lastIndex)
    }

    private fun runAi(label: String, job: suspend (Int, Int) -> ByteArray?) {
        if (masks.size >= P.MAX_MASKS) return
        ui.busy = "Finding $label"
        scope.launch {
            val (w, h) = layerDims()
            val out = withContext(Dispatchers.Default) { runCatching { job(w, h) }.getOrNull() }
            ui.busy = null
            if (out != null) addAi(label, out, w, h) else ui.busy = null
        }
    }

    // ---------------- Tab ----------------

    val tab = EditorTab("masking", "Masking") { Content() }

    @Composable
    private fun Content() {
        LaunchedEffect(Unit) {
            ensureReference()
            if (ai != null) scope.launch(Dispatchers.Default) { runCatching { ai.prepare() } }
        }
        Column(Modifier.fillMaxWidth()) {
            Text(ui.busy ?: "", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ai != null) {
                    ChipButton("Subject", false, { runAi("Subject") { w, h -> ai.subject(w, h) } })
                    ChipButton("Sky", false, { runAi("Sky") { w, h -> ai.sky(w, h) } })
                    ChipButton("Background", false, { runAi("Background") { w, h -> ai.subject(w, h)?.let { a -> ByteArray(a.size) { i -> (255 - (a[i].toInt() and 255)).toByte() } } } })
                    ChipButton("People", false, {
                        if (masks.size < P.MAX_MASKS) {
                            ui.busy = "Finding people"
                            scope.launch {
                                val (w, h) = layerDims()
                                val parts = withContext(Dispatchers.Default) { runCatching { ai.people(w, h) }.getOrDefault(emptyList()) }
                                ui.busy = null
                                parts.take(P.MAX_MASKS - masks.size).forEach { (n, a) -> addAi(n, a, w, h) }
                            }
                        }
                    })
                    ChipButton("Object", false, { ui.pickingObject = true; ui.busy = "Tap the object in the photo" })
                }
                MaskKind.entries.forEach { k -> ChipButton(k.label, false, { add(k) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                masks.forEachIndexed { i, m -> ChipButton(m.name, ui.selected == i, { select(i) }) }
                if (masks.isEmpty()) Text("No masks yet. Choose a tool above.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            val m = selMask
            if (m != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("mask" to "Mask", "light" to "Light", "colour" to "Colour", "effects" to "Effects", "curve" to "Curve", "mixer" to "Mixer", "grade" to "Grade").forEach { (id, n) ->
                        ChipButton(n, ui.sub == id, { ui.sub = id })
                    }
                }
                val target = AdjustTarget({ r -> r.masks.getOrNull(ui.selected)?.adjust ?: app.rawline.core.model.Adjust() }, { r, a -> r.withMask(ui.selected) { it.copy(adjust = a) } }, isMask = true)
                when (ui.sub) {
                    "light" -> LightPanel(state, target)
                    "colour" -> PanelColumn { ColourBasicsPanel(state, target, null, null) }
                    "effects" -> EffectsPanel(state, target)
                    "curve" -> CurvePanel(state, target, null)
                    "mixer" -> MixerPanel(state, target, mixBand, { mixBand = it }, mixMode, { mixMode = it }, null)
                    "grade" -> GradingPanel(state, target)
                    else -> MaskSettings(m)
                }
            }
        }
    }

    private var mixBand by androidx.compose.runtime.mutableIntStateOf(0)
    private var mixMode by androidx.compose.runtime.mutableIntStateOf(0)

    @Composable
    private fun MaskSettings(m: Mask) = PanelColumn {
        val mi = ui.selected
        RawSlider("Amount", m.amount * 100f, 0f..100f, 100f, unit = "%",
            onChange = { v -> state.live { it.withMask(mi) { mm -> mm.copy(amount = v / 100f) } } }, onCommit = { state.commit("Mask amount") })
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton(if (m.invert) "Inverted" else "Invert", m.invert, { state.edit("Invert mask") { it.withMask(mi) { mm -> mm.copy(invert = !mm.invert) } } })
            ChipButton(if (m.visible) "Hide" else "Show", false, { state.edit("Toggle mask") { it.withMask(mi) { mm -> mm.copy(visible = !mm.visible) } } })
            ChipButton("Duplicate", false, { if (masks.size < P.MAX_MASKS) { val d = MaskFactory.duplicate(m); state.edit("Duplicate mask") { it.copy(masks = it.masks + d) }; restore(state.recipe); select(masks.lastIndex) } })
            ChipButton("Delete", false, {
                state.edit("Delete mask") { it.copy(masks = it.masks.filterIndexed { i, _ -> i != mi }) }
                select(-1)
            })
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Show mask overlay")
            Switch(ui.showOverlay, { ui.showOverlay = it; session.setShowMask(if (it) ui.selected else -1) })
        }
        SectionTitle("Parts of this mask")
        m.components.forEachIndexed { ci, c ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ChipButton(c.label.ifEmpty { c.type.name }, ui.selectedComp == ci, { ui.selectedComp = ci })
                MaskOp.entries.forEach { op -> ChipButton(op.name.lowercase().replaceFirstChar { it.uppercase() }, c.op == op, { state.edit("Mask part ${op.name}") { it.withComponent(mi, ci) { cc -> cc.copy(op = op) } } }) }
                ChipButton("Inv", c.invert, { state.edit("Invert part") { it.withComponent(mi, ci) { cc -> cc.copy(invert = !cc.invert) } } })
                if (m.components.size > 1) ChipButton("X", false, { state.edit("Remove part") { it.withMask(mi) { mm -> mm.copy(components = mm.components.filterIndexed { i, _ -> i != ci }) } }; ui.selectedComp = 0 })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add part:", modifier = Modifier.padding(top = 10.dp))
            MaskKind.entries.forEach { k ->
                ChipButton("+ ${k.label}", false, {
                    if (m.components.size < 6) {
                        state.edit("Add mask part") { it.withMask(mi) { mm -> mm.copy(components = mm.components + MaskFactory.component(k, MaskOp.ADD)) } }
                        ui.selectedComp = state.recipe.masks[mi].components.lastIndex
                        state.recipe.masks[mi].components.last().let { c -> if (c.type == MaskType.BITMAP) c.layerKey?.let { key -> val l = brushLayer(key); upload(key, l.alpha, l.w, l.h) } }
                    }
                })
            }
        }
        val c = selComp
        if (c != null) PartSettings(mi, ui.selectedComp, c)
    }

    @Composable
    private fun PartSettings(mi: Int, ci: Int, c: MaskComponent) {
        fun param(i: Int, default: Float) = c.params.getOrElse(i) { default }
        fun setParam(i: Int, v: Float) = state.live { it.withComponent(mi, ci) { cc -> cc.copy(params = cc.params.toMutableList().also { l -> while (l.size <= i) l.add(0f); l[i] = v }) } }
        when (c.type) {
            MaskType.BITMAP -> if (c.strokes.isNotEmpty() || c.layerKey?.startsWith("brush_") == true) {
                SectionTitle("Brush")
                RawSlider("Size", ui.brushSize * 100f, 1f..30f, 6f, decimals = 1, onChange = { ui.brushSize = it / 100f }, onCommit = {})
                RawSlider("Feather", ui.brushFeather * 100f, 0f..100f, 50f, onChange = { ui.brushFeather = it / 100f }, onCommit = {})
                RawSlider("Flow", ui.brushFlow * 100f, 5f..100f, 100f, onChange = { ui.brushFlow = it / 100f }, onCommit = {})
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipButton("Erase", ui.brushErase, { ui.brushErase = !ui.brushErase })
                    ChipButton("Auto mask", ui.brushAuto, { ui.brushAuto = !ui.brushAuto })
                    ChipButton("Clear brush", false, {
                        state.edit("Clear brush") { it.withComponent(mi, ci) { cc -> cc.copy(strokes = emptyList()) } }
                        c.layerKey?.let { k -> val l = brushLayer(k); l.clear(); upload(k, l.alpha, l.w, l.h) }
                    })
                }
            }
            MaskType.RADIAL -> {
                SectionTitle("Radial gradient")
                RawSlider("Feather", param(5, 0.5f) * 100f, 1f..100f, 50f, onChange = { setParam(5, it / 100f) }, onCommit = { state.commit("Radial feather") })
                RawSlider("Rotate", Math.toDegrees(param(4, 0f).toDouble()).toFloat(), -90f..90f, 0f, unit = "°", onChange = { setParam(4, Math.toRadians(it.toDouble()).toFloat()) }, onCommit = { state.commit("Radial rotate") })
                RawSlider("Width", param(2, 0.3f) * 100f, 2f..100f, 30f, onChange = { setParam(2, it / 100f) }, onCommit = { state.commit("Radial width") })
                RawSlider("Height", param(3, 0.3f) * 100f, 2f..100f, 30f, onChange = { setParam(3, it / 100f) }, onCommit = { state.commit("Radial height") })
            }
            MaskType.LINEAR -> { SectionTitle("Linear gradient"); Text("Drag the two handles on the photo. Effect is full at the end handle.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            MaskType.COLOR -> {
                SectionTitle("Colour range")
                Row(Modifier.padding(horizontal = 12.dp)) { ChipButton(if (ui.pickingColour) "Tap the photo..." else "Pick colour", ui.pickingColour, { ui.pickingColour = true }) }
                RawSlider("Range", param(3, 0.2f) * 100f, 1f..100f, 20f, onChange = { setParam(3, it / 100f) }, onCommit = { state.commit("Colour range") })
                RawSlider("Softness", param(4, 0.5f) * 100f, 0f..100f, 50f, onChange = { setParam(4, it / 100f) }, onCommit = { state.commit("Colour softness") })
            }
            MaskType.LUMINANCE -> {
                SectionTitle("Luminance range")
                RawSlider("From", param(0, 0.4f) * 100f, 0f..100f, 40f, onChange = { setParam(0, it / 100f) }, onCommit = { state.commit("Luminance from") })
                RawSlider("To", param(1, 0.9f) * 100f, 0f..100f, 90f, onChange = { setParam(1, it / 100f) }, onCommit = { state.commit("Luminance to") })
                RawSlider("Falloff", param(2, 0.15f) * 100f, 1f..50f, 15f, onChange = { setParam(2, it / 100f) }, onCommit = { state.commit("Luminance falloff") })
            }
        }
    }

    // ---------------- On photo ----------------

    private var dragging = -1
    private var currentStroke: BrushStroke? = null
    private var lastUpload = 0L

    fun gestures(tabId: String, mapper: PhotoMapper): ToolGestures? {
        if (tabId != "masking") return null
        val m = selMask ?: return if (ui.pickingObject) objectPicker(mapper) else null
        val c = selComp ?: return null
        val mi = ui.selected; val ci = ui.selectedComp
        if (ui.pickingObject) return objectPicker(mapper)
        return when (c.type) {
            MaskType.BITMAP -> if (c.layerKey?.startsWith("brush_") == true) brushGestures(mapper, mi, ci, c) else null
            MaskType.LINEAR -> ToolGestures(
                onDown = { pos ->
                    val a = handleView(mapper, c.params.getOrElse(0) { 0f }, c.params.getOrElse(1) { 0f }); val b = handleView(mapper, c.params.getOrElse(2) { 0f }, c.params.getOrElse(3) { 0f })
                    dragging = if (hypot(a.x - pos.x, a.y - pos.y) < 70f) 0 else if (hypot(b.x - pos.x, b.y - pos.y) < 70f) 1 else -1
                    dragging >= 0
                },
                onMove = { pos ->
                    val p = mapper.toImage(pos.x, pos.y)
                    if (p != null && dragging >= 0) { val f = pf(p); state.live { it.withComponent(mi, ci) { cc -> cc.copy(params = cc.params.toMutableList().also { l -> l[dragging * 2] = f.x; l[dragging * 2 + 1] = f.y }) } } }
                },
                onUp = { state.commit("Move gradient") },
            )
            MaskType.RADIAL -> ToolGestures(
                onDown = { pos ->
                    val hs = radialHandles(mapper, c)
                    dragging = hs.indexOfFirst { hypot(it.x - pos.x, it.y - pos.y) < 70f }
                    dragging >= 0
                },
                onMove = { pos ->
                    val p = mapper.toImage(pos.x, pos.y)
                    if (p != null && dragging >= 0) {
                        val f = pf(p)
                        val cx = c.params.getOrElse(0) { 0.5f }; val cy = c.params.getOrElse(1) { 0.5f }
                        val asp = session.baseAspect()
                        state.live {
                            it.withComponent(mi, ci) { cc ->
                                val l = cc.params.toMutableList()
                                when (dragging) {
                                    0 -> { l[0] = f.x; l[1] = f.y }
                                    1 -> l[2] = hypot((f.x - cx) * asp, f.y - cy).coerceAtLeast(0.01f)
                                    2 -> l[3] = hypot((f.x - cx) * asp, f.y - cy).coerceAtLeast(0.01f)
                                }
                                cc.copy(params = l)
                            }
                        }
                    }
                },
                onUp = { state.commit("Move radial") },
            )
            MaskType.COLOR -> if (ui.pickingColour) ToolGestures(
                onDown = { pos ->
                    val p = mapper.toImage(pos.x, pos.y)
                    if (p != null) scope.launch {
                        val rgb = session.sample(p.x, p.y)
                        if (rgb != null) {
                            // The shader compares gamma encoded colours of the edited image at that point
                            state.edit("Pick colour") { it.withComponent(mi, ci) { cc -> cc.copy(params = cc.params.toMutableList().also { l -> l[0] = rgb[0]; l[1] = rgb[1]; l[2] = rgb[2] }) } }
                        }
                        ui.pickingColour = false
                    }
                    true
                },
                onMove = {}, onUp = {},
            ) else null
            MaskType.LUMINANCE -> null
        }
    }

    private fun objectPicker(mapper: PhotoMapper) = ToolGestures(
        onDown = { pos ->
            val p = mapper.toImage(pos.x, pos.y)
            ui.pickingObject = false
            if (p != null && ai != null && masks.size < P.MAX_MASKS) {
                val f = pf(p)
                runAi("Object") { w, h -> ai.objectAt(f.x, f.y, w, h) }
            }
            true
        },
        onMove = {}, onUp = {},
    )

    private fun brushGestures(mapper: PhotoMapper, mi: Int, ci: Int, c: MaskComponent): ToolGestures {
        val key = c.layerKey ?: return ToolGestures({ false }, {}, {})
        return ToolGestures(
            onDown = { pos ->
                val p = mapper.toImage(pos.x, pos.y) ?: return@ToolGestures false
                val f = pf(p)
                currentStroke = BrushStroke(listOf(f.x, f.y), ui.brushSize, ui.brushFeather, ui.brushFlow, ui.brushErase, ui.brushAuto)
                val l = brushLayer(key)
                l.beginStroke(currentStroke!!)
                l.update(currentStroke!!)
                upload(key, l.alpha, l.w, l.h)
                true
            },
            onMove = { pos ->
                val p = mapper.toImage(pos.x, pos.y)
                val s = currentStroke
                if (p != null && s != null) {
                    val f = pf(p)
                    currentStroke = s.copy(points = s.points + listOf(f.x, f.y))
                    val l = brushLayer(key)
                    l.update(currentStroke!!)
                    val now = System.currentTimeMillis()
                    if (now - lastUpload > 33) { lastUpload = now; upload(key, l.alpha, l.w, l.h) }
                }
            },
            onUp = { cancelled ->
                val s = currentStroke
                currentStroke = null
                val l = brushLayer(key)
                l.endStroke()
                if (s != null && !cancelled) {
                    upload(key, l.alpha, l.w, l.h)
                    state.edit("Brush stroke") { it.withComponent(mi, ci) { cc -> cc.copy(strokes = cc.strokes + s) } }
                    savedHash[key] = state.recipe.masks[mi].components[ci].strokes.hashCode()
                    val snapshot = l.alpha.copyOf()
                    scope.launch(Dispatchers.IO) { store.save(key, snapshot, l.w, l.h) }
                } else if (s != null) { l.renderAll(state.recipe.masks[mi].components[ci].strokes); upload(key, l.alpha, l.w, l.h) }
            },
        )
    }

    private fun handleView(mapper: PhotoMapper, fx: Float, fy: Float): Offset { val o = toOut(Offset(fx, fy)); return mapper.toView(o.x, o.y) }

    private fun radialHandles(mapper: PhotoMapper, c: MaskComponent): List<Offset> {
        val cx = c.params.getOrElse(0) { 0.5f }; val cy = c.params.getOrElse(1) { 0.5f }
        val rx = c.params.getOrElse(2) { 0.3f }; val ry = c.params.getOrElse(3) { 0.3f }; val a = c.params.getOrElse(4) { 0f }
        val asp = session.baseAspect()
        val h1 = Offset(cx + rx * cos(a) / asp, cy + rx * sin(a)); val h2 = Offset(cx - ry * sin(a) / asp, cy + ry * cos(a))
        return listOf(handleView(mapper, cx, cy), handleView(mapper, h1.x, h1.y), handleView(mapper, h2.x, h2.y))
    }

    @Composable
    fun BoxScope.Overlay(tabId: String, mapper: PhotoMapper) {
        if (tabId != "masking") return
        val c = selComp ?: return
        // read state so the overlay redraws as the recipe changes
        val params = c.params
        Canvas(Modifier.fillMaxSize()) {
            when (c.type) {
                MaskType.LINEAR -> {
                    val a = handleView(mapper, params.getOrElse(0) { 0f }, params.getOrElse(1) { 0f }); val b = handleView(mapper, params.getOrElse(2) { 0f }, params.getOrElse(3) { 0f })
                    drawLine(Color.White, a, b, 3f)
                    drawCircle(Color(0xFF8AB4F8), 16f, a); drawCircle(Color.White, 16f, b, style = Stroke(4f))
                }
                MaskType.RADIAL -> {
                    val hs = radialHandles(mapper, c)
                    drawCircle(Color.White, 14f, hs[0], style = Stroke(3f))
                    drawLine(Color.White, hs[0], hs[1], 2f); drawLine(Color.White, hs[0], hs[2], 2f)
                    drawCircle(Color(0xFF8AB4F8), 14f, hs[1]); drawCircle(Color(0xFF8AB4F8), 14f, hs[2])
                }
                else -> {}
            }
        }
    }

}
