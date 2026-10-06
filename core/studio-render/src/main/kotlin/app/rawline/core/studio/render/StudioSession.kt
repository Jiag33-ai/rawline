package app.rawline.core.studio.render

import app.rawline.core.studio.model.Action
import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.BlendMode
import app.rawline.core.studio.model.CanvasView
import app.rawline.core.studio.model.Dirty
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.Fs
import app.rawline.core.studio.model.InputEvent
import app.rawline.core.studio.model.InputRouter
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.LayerOps
import app.rawline.core.studio.model.PixelContainer
import app.rawline.core.studio.model.PixelDelta
import app.rawline.core.studio.model.Placement
import app.rawline.core.studio.model.ProjectStore
import app.rawline.core.studio.model.RawPixels
import app.rawline.core.studio.model.Step
import app.rawline.core.studio.model.StrokePoint
import app.rawline.core.studio.model.StrokeReference
import app.rawline.core.studio.model.StrokeWalker
import app.rawline.core.studio.model.StudioHistory
import app.rawline.core.studio.model.Stamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One open Studio project: document, linear history, the CPU copy of the active layer, the GPU layers and the autosave.
 *
 * Threads (spec 4 of the S1b task): the UI calls the public functions from the main thread and they return at once. Everything that
 * changes session state (decode, history, stroke commit, thumbnails) runs on ONE model thread ([StudioEnv.model]). Every compositor call goes through
 * [GpuExecutor.post] to the GL thread (the model thread waits for the few results it needs, the GL thread never waits for anything). Layer encoding and file writes run on
 * the saver thread. Nothing here touches a file or a codec on the main or GL thread.
 *
 * [initialPixels] are layers that are not on disk yet (a new project). [recovered] is shown once ("Recovered from autosave").
 */
class StudioSession(
    private val fs: Fs,
    private val root: String,
    private val gl: GpuExecutor,
    private val env: StudioEnv,
    initial: Document,
    initialPixels: Map<String, RawPixels> = emptyMap(),
    private val onDisk: Boolean = true,
    recovered: Boolean = false,
    appVersion: String = "0",
    activeId: String? = null,
) : SurfaceListener {
    private val store = ProjectStore(fs, root, appVersion)
    private val _state = MutableStateFlow(StudioState(document = initial, activeId = activeId ?: initial.layers.last().common.id, recovered = recovered))
    val state: StateFlow<StudioState> = _state

    // ---- model thread state -----------------------------------------------------------------------------------------------
    private val history = StudioHistory(initial)
    private var working: Document? = null                        // a drag or slider in progress: shown, not yet in the history
    private val files = HashMap<String, String?>()               // pixel file of each layer as last saved (the document in the history may carry older names)
    private val slots = LinkedHashMap<String, Int>()             // layer id -> compositor slot
    private var activeId: String = _state.value.activeId
    private var activePixels: RawPixels? = null                  // CPU copy of the active layer only (decision D4)
    private val dirty = LinkedHashSet<String>()                  // layers whose pixels changed since the last save began
    private val unsaved = HashMap<String, RawPixels>()           // pixels held in memory that are not on disk yet; arrays here are never written to
    private val inFlight = HashMap<String, RawPixels>()          // snapshots a running save is writing
    private val graveyard = LinkedHashMap<String, ByteArray>()   // encoded pixels of layers taken off the stack (delete, undo of add), so undo can bring them back
    private val lost = HashSet<String>()
    private val blank = HashSet<String>()                        // layers known to be fully transparent
    private val thumbs = HashMap<String, Thumb>()
    private var needsSave = false
    private var saving = false
    private var saveAgain = false
    private var saveAgainNow = false
    private var timerArmed = false
    private var lastSaveStart = Long.MIN_VALUE / 2
    private var lastModified = initial.modified
    private var saveFailed = false
    private var view = CanvasView()
    private var surfaceW = 0
    private var surfaceH = 0
    private var viewFitted = false
    @Volatile private var drag: Any? = null
    private var scaleBase: Triple<Int, Int, Float>? = null       // placement when a numeric or slider scale began
    private var trimmedToast = false
    @Volatile private var released = false
    private val messageId = AtomicLong()
    private val router = InputRouter()                           // main thread only (onInput)
    private val inputStamp = AtomicLong(0)                       // uptime of the newest input whose stamps the GL thread has drawn; read by the renderer for studio_input_to_pixel_ms

    private class PaintStroke(val layerId: String, val slot: Int, val brush: Brush, val colour: FloatArray, val walker: StrokeWalker) { val stamps = ArrayList<Stamp>() }
    private class MoveDrag(val layerId: String, val sx: Float, val sy: Float, val x: Int, val y: Int, val scale: Float)
    private class ScaleDrag(val layerId: String, val x: Int, val y: Int, val scale: Float, val fx: Float, val fy: Float, var ratio: Float = 1f)

    init {
        for (l in initial.layers) if (l is Layer.Pixel) files[l.common.id] = if (onDisk) l.pixelsFile else null
        for ((id, px) in initialPixels) { unsaved[id] = px; dirty += id }
        for (l in initial.layers) if (l is Layer.Pixel && l.pixelsFile == null && l.common.id !in initialPixels) blank += l.common.id
        gl.listener = this
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------------------

    /** Loads every layer to the GPU (waits for the GL context), then publishes READY. A new project is saved at once so it exists on disk. */
    fun start() = model {
        try {
            uploadAll()
            _state.update { it.copy(phase = Phase.READY) }
            publish()
            updateFrame()
            if (!onDisk || needsSaveAtStart()) markDirty(now = true)
        } catch (t: Throwable) {
            env.error("studio open: ${t.javaClass.simpleName}: ${t.message}")
            _state.update { it.copy(phase = Phase.ERROR, error = t.message ?: "Could not open the project") }
        }
    }

    private fun needsSaveAtStart() = dirty.isNotEmpty()

    /** On pause and Back: write what changed now, not in five seconds. Returns at once. */
    fun flush() = model { if (needsSave || dirty.isNotEmpty()) markDirty(now = true) }

    /** The screen is gone. A last save is started if anything is unsaved; the pools wind themselves down when idle. */
    fun release() {
        model { if (needsSave || dirty.isNotEmpty()) markDirty(now = true); released = true }
        gl.listener = null
    }

    // ---- surface callbacks (GL thread) -------------------------------------------------------------------------------------------

    override fun onSurfaceSize(w: Int, h: Int) = model {
        if (w <= 0 || h <= 0 || (w == surfaceW && h == surfaceH)) return@model
        surfaceW = w; surfaceH = h
        val d = curDoc()
        view = CanvasView.fit(d.width, d.height, w, h)
        viewFitted = true
        publishZoom(); updateFrame()
    }

    override fun onContextRestored() = model {
        if (_state.value.phase != Phase.READY) return@model
        drag = null   // the stroke buffer died with the context; the router resets itself on the next pointer up
        try { uploadAll(); updateFrame() } catch (t: Throwable) { env.error("studio context restore: ${t.message}"); toast("Could not restore the picture after the screen was reset.") }
    }

    // ---- tool settings (any thread, applied at once) -----------------------------------------------------------------------------

    fun setTool(t: Tool) { if (drag == null) _state.update { it.copy(tool = t) } }
    /** Sets the settings of the active tool (brush or eraser). Values are coerced into range by the caller. */
    fun setBrush(b: Brush) = _state.update { if (it.tool == Tool.ERASER) it.copy(eraser = b.copy(erase = true)) else it.copy(brush = b.copy(erase = false)) }
    /** Changes the settings of the active tool from what they are NOW (no stale copy in the caller: two sliders moved in a row cannot undo each other). */
    fun updateBrush(f: (Brush) -> Brush) = _state.update { if (it.tool == Tool.ERASER) it.copy(eraser = f(it.eraser).copy(erase = true)) else it.copy(brush = f(it.brush).copy(erase = false)) }
    /** The colour changes while a picker is dragged: it is not remembered until [pushRecent]. */
    fun setColour(c: Rgb) = _state.update { it.copy(colour = c) }
    /** Puts [c] first in the 8 recent colours (kept in memory for the project; persisting them is S2). */
    fun pushRecent(c: Rgb) = _state.update { it.copy(recent = (listOf(c) + it.recent.filter { r -> r != c }).take(8)) }
    fun setBackground(c: Rgb) = _state.update { it.copy(background = c) }
    fun swapColours() = _state.update { it.copy(colour = it.background, background = it.colour) }
    /** Shows a message from the UI side (a picture that could not be read). */
    fun reportProblem(text: String) = toast(text)
    fun consumeMessage(id: Long) = _state.update { if (it.message?.id == id) it.copy(message = null) else it }

    // ---- input (main thread) ------------------------------------------------------------------------------------------------------

    /** One pointer event in screen pixels. Routed here (cheap, main thread) and handled on the model thread. */
    fun onInput(e: InputEvent) {
        if (released) return
        val actions = router.onEvent(e)
        if (actions.isEmpty()) return
        val t = e.timeMs
        model { for (a in actions) handle(a, t) }
    }

    fun takeInputStamp(): Long = inputStamp.getAndSet(0)

    // ---- layer operations (any thread) --------------------------------------------------------------------------------------------

    fun selectLayer(id: String) = model {
        if (drag != null || id == activeId || curDoc().layer(id) == null) return@model
        cancelWorking(); activate(id); publish()
    }

    fun addLayer() = model {
        guarded {
            val d = history.document
            val l = Layer.Pixel(LayerCommon(newId(), "Layer ${d.layers.size + 1}"), d.width, d.height)
            blank += l.common.id
            addLayerInternal(d, l, null)
        }
    }

    /** A photo or other picture as a new layer on top, centred on the canvas. [px] is already scaled to fit by the caller. */
    fun addPhotoLayer(px: RawPixels, name: String) = model {
        guarded {
            val d = history.document
            val l = Layer.Pixel(LayerCommon(newId(), name.take(40).ifEmpty { "Photo" }, x = (d.width - px.w) / 2, y = (d.height - px.h) / 2), px.w, px.h)
            addLayerInternal(d, l, px)
        }
    }

    fun duplicateLayer(id: String) = model {
        guarded {
            val d = history.document
            if (d.layer(id) !is Layer.Pixel) return@guarded
            val px = pixelsOf(id)
            val newId = newId()
            val copy = LayerOps.duplicate(d, id, newId)   // checks the 10 layer cap
            val l = copy.layer(newId) as Layer.Pixel
            if (!guardAllows(d, l.width to l.height)) return@guarded
            if (px == null) blank += newId
            applyDocument(copy, if (px != null) mapOf(newId to RawPixels(px.w, px.h, px.rgba.copyOf())) else emptyMap(), newActive = newId)
        }
    }

    fun deleteLayer(id: String) = model {
        guarded {
            val d = history.document
            val next = LayerOps.delete(d, id)
            val newActive = if (id == activeId) next.layers[(d.indexOf(id) - 1).coerceAtLeast(0).coerceAtMost(next.layers.size - 1)].common.id else null
            applyDocument(next, emptyMap(), newActive)
        }
    }

    /** [up] moves toward the top of the stack. */
    fun moveLayer(id: String, up: Boolean) = model {
        guarded {
            val d = history.document
            val i = d.indexOf(id)
            val to = if (up) i + 1 else i - 1
            if (i < 0 || to < 0 || to >= d.layers.size) return@guarded
            applyDocument(LayerOps.move(d, id, to), emptyMap(), null)
        }
    }

    fun setVisible(id: String, v: Boolean) = model { guarded { applyDocument(LayerOps.setVisible(history.document, id, v), emptyMap(), null) } }
    fun setLocked(id: String, v: Boolean) = model { guarded { applyDocument(LayerOps.setLocked(history.document, id, v), emptyMap(), null) } }
    fun setBlend(id: String, m: BlendMode) = model { guarded { applyDocument(LayerOps.setBlend(history.document, id, m), emptyMap(), null) } }

    /** A slider is moving: show the opacity, do not record it yet. */
    fun previewOpacity(id: String, percent: Int) = model { guarded { working = LayerOps.setOpacity(history.document, id, percent); updateFrame(); publishDoc() } }

    /** The slider was released: one history entry for the whole drag. */
    fun commitPreview() = model { guarded { commitWorking(); publish() } }

    /**
     * Scale of the active layer in percent (25 to 400) about the layer's own centre, shown at once and recorded by [commitPreview]. The slider and the typed value both
     * come through here (a typed value is a preview and a commit in a row), so a drag is one history entry. Every step is computed from the placement at the start of the drag (no rounding drift).
     */
    fun previewScale(percent: Float) = model {
        guarded {
            val l = layer(activeId) as? Layer.Pixel ?: return@guarded
            if (!editable(l)) return@guarded
            val base = scaleBase ?: Triple(l.common.x, l.common.y, l.common.scale).also { scaleBase = it }
            val cx = base.first + l.width * base.third / 2f; val cy = base.second + l.height * base.third / 2f
            val (x, y, sc) = Placement.scaleAbout(base.first, base.second, base.third, percent / 100f, cx, cy)
            working = LayerOps.setPlacement(history.document, l.common.id, x, y, sc)
            updateFrame(); publishDoc()
        }
    }

    fun undo() = model { guarded { if (drag != null) return@guarded; cancelWorking(); applyStep(history.undo()) } }
    fun redo() = model { guarded { if (drag != null) return@guarded; cancelWorking(); applyStep(history.redo()) } }

    /** Memory pressure: drop what can be rebuilt. Undo steps older than the newest 50 MB go, with one notice. */
    fun trimMemory() = model {
        graveyard.clear()
        if (history.trimBytes(50L * 1024 * 1024) && !trimmedToast) { trimmedToast = true; toast("Older undo steps were cleared to free memory") }
        publish()
    }

    // ---- actions (model thread) ----------------------------------------------------------------------------------------------------

    private fun handle(a: Action, timeMs: Long) {
        try {
            when (a) {
                is Action.StrokeStart -> strokeStart(a)
                is Action.StrokeMove -> strokeMove(a, timeMs)
                Action.StrokeEnd -> strokeEnd()
                Action.StrokeCancel -> strokeCancel()
                is Action.GestureStart -> gestureStart(a)
                is Action.GestureUpdate -> gestureUpdate(a)
                Action.GestureEnd -> gestureEnd()
            }
        } catch (t: Throwable) {
            env.error("studio input ${a::class.simpleName}: ${t.javaClass.simpleName}: ${t.message}")
            drag = null
            toast("Something went wrong with that gesture.")
        }
    }

    private fun strokeStart(a: Action.StrokeStart) {
        if (_state.value.phase != Phase.READY) return
        val l = layer(activeId) as? Layer.Pixel ?: return
        val s = _state.value
        when (s.tool) {
            Tool.BRUSH, Tool.ERASER -> {
                if (!editable(l)) return
                val brush = (if (s.tool == Tool.ERASER) s.eraser else s.brush).copy(erase = s.tool == Tool.ERASER)
                val slot = slots[activeId] ?: return
                ensureActivePixels()
                val c = s.colour.array()
                val ok = gpuCall { it.beginStroke(slot, c[0], c[1], c[2], brush.opacity.toFloat(), brush.erase, brush.hardness.toFloat(), brush.flow.toFloat()) } == true
                if (!ok) { toast("Could not start a stroke."); return }
                val st = PaintStroke(activeId, slot, brush, c, StrokeWalker(brush))
                drag = st
                addPoint(st, a.x, a.y, a.pressure, 0L)
            }
            Tool.MOVE -> {
                if (!editable(l)) return
                drag = MoveDrag(activeId, a.x, a.y, l.common.x, l.common.y, l.common.scale)
            }
            Tool.SCALE -> {}
        }
    }

    private fun strokeMove(a: Action.StrokeMove, timeMs: Long) {
        when (val d = drag) {
            is PaintStroke -> addPoint(d, a.x, a.y, a.pressure, timeMs)
            is MoveDrag -> {
                val nx = d.x + Math.round((a.x - d.sx) / view.zoom); val ny = d.y + Math.round((a.y - d.sy) / view.zoom)
                working = LayerOps.setPlacement(history.document, d.layerId, nx, ny, d.scale)
                updateFrame()
            }
            else -> {}
        }
    }

    private fun strokeEnd() {
        when (val d = drag) {
            is PaintStroke -> { drag = null; commitStroke(d) }
            is MoveDrag -> { drag = null; commitWorking(); publish() }
            else -> {}
        }
    }

    private fun strokeCancel() {
        when (val d = drag) {
            is PaintStroke -> { drag = null; gpuAsync { it.endStroke() }; gl.requestRender() }   // no history entry
            is MoveDrag -> { drag = null; cancelWorking() }
            else -> {}
        }
    }

    private fun gestureStart(a: Action.GestureStart) {
        if (_state.value.tool != Tool.SCALE) return
        val l = layer(activeId) as? Layer.Pixel ?: return
        if (!editable(l)) return
        drag = ScaleDrag(activeId, l.common.x, l.common.y, l.common.scale, view.toDocX(a.cx), view.toDocY(a.cy))
    }

    private fun gestureUpdate(a: Action.GestureUpdate) {
        val d = drag
        if (d is ScaleDrag) {
            // the accumulated ratio is clamped so a pinch past the limit does not wind up; position comes from the values at the start of the gesture (no rounding drift)
            d.ratio = (d.ratio * a.scale).coerceIn(0.25f / d.scale, 4f / d.scale)
            val (x, y, s) = Placement.scaleAbout(d.x, d.y, d.scale, d.scale * d.ratio, d.fx, d.fy)
            working = LayerOps.setPlacement(history.document, d.layerId, x, y, s)
            updateFrame()
        } else if (d == null && _state.value.tool != Tool.SCALE) {
            val doc = curDoc()
            view = view.gestured(a.dx, a.dy, a.scale, a.cx, a.cy).clamped(doc.width, doc.height, surfaceW.coerceAtLeast(1), surfaceH.coerceAtLeast(1))
            publishZoom(); updateFrame()
        }
    }

    private fun gestureEnd() {
        if (drag is ScaleDrag) { drag = null; commitWorking(); publish() }
    }

    // ---- stroke ---------------------------------------------------------------------------------------------------------------------

    private fun addPoint(st: PaintStroke, sx: Float, sy: Float, pressure: Float, timeMs: Long) {
        val c = (curDoc().layer(st.layerId) ?: return).common
        // screen -> document -> layer pixels (the layer may be moved and scaled, decision D7)
        val lx = (view.toDocX(sx) - c.x) / c.scale; val ly = (view.toDocY(sy) - c.y) / c.scale
        val fresh = st.walker.add(StrokePoint(lx.toDouble(), ly.toDouble(), pressure.toDouble()))
        if (fresh.isEmpty()) return
        st.stamps += fresh
        val xyr = FloatArray(fresh.size * 3)
        for (i in fresh.indices) { xyr[i * 3] = fresh[i].x.toFloat(); xyr[i * 3 + 1] = fresh[i].y.toFloat(); xyr[i * 3 + 2] = fresh[i].radius.toFloat() }
        val n = fresh.size
        gpuAsync { g ->
            val t0 = env.nanos()
            g.addStamps(xyr, n)
            env.report("studio_stroke_stamp_ms", (env.nanos() - t0) / 1_000_000)
            if (timeMs > 0) inputStamp.set(timeMs)
        }
        gl.requestRender()
    }

    private fun commitStroke(st: PaintStroke) {
        val l = layer(st.layerId) as? Layer.Pixel
        val px = activePixels
        if (l == null || px == null || activeId != st.layerId) { gpuAsync { it.endStroke() }; return }
        val rect = Dirty.rect(st.stamps, l.width, l.height)
        if (rect[2] == 0) { gpuAsync { it.endStroke() }; gl.requestRender(); return }
        val t0 = env.nanos()
        val cov = FloatArray(rect[2] * rect[3])
        val ok = gpuCall { it.readStroke(rect[0], rect[1], rect[2], rect[3], cov) } == true
        if (!ok) { gpuAsync { it.endStroke() }; toast("Could not finish that stroke."); gl.requestRender(); return }
        val before = PixelDelta.cut(px.rgba, l.width, rect[0], rect[1], rect[2], rect[3])
        StrokeReference.commitRect(px.rgba, l.width, rect, cov, st.colour, st.brush)   // coverage of the rectangle only: no layer sized array
        val after = PixelDelta.cut(px.rgba, l.width, rect[0], rect[1], rect[2], rect[3])
        // one GPU job: the baked rectangle goes in and the live stroke goes away before the next frame, so the stroke is never drawn twice
        gpuAsync { g -> g.updateRegion(st.slot, rect[0], rect[1], rect[2], rect[3], after); g.endStroke() }
        history.commitStroke(st.layerId, PixelDelta(rect[0], rect[1], rect[2], rect[3], before, after))
        blank -= st.layerId
        dirty += st.layerId
        thumbs[st.layerId] = Thumbs.make(px)
        env.report("studio_commit_ms", (env.nanos() - t0) / 1_000_000)
        markDirty()
        publish()
        gl.requestRender()
        gauges()
    }

    // ---- undo and redo ------------------------------------------------------------------------------------------------------------

    private fun applyStep(step: Step?) {
        when (step) {
            null -> return
            is Step.SetPixels -> {
                if (activeId != step.layerId) activate(step.layerId)
                val l = layer(step.layerId) as Layer.Pixel
                val px = activePixels ?: return
                step.delta.apply(px.rgba, l.width, step.bytes)
                val slot = slots[step.layerId] ?: return
                val d = step.delta
                gpuAsync { it.updateRegion(slot, d.x, d.y, d.w, d.h, step.bytes) }   // only that rectangle goes to the GPU
                blank -= step.layerId
                dirty += step.layerId
                thumbs[step.layerId] = Thumbs.make(px)
                markDirty()
            }
            is Step.SetDocument -> {
                syncSlots(step.document)
                ensureActiveValid(step.document)
                markDirty()
            }
        }
        publish(); updateFrame(); gauges()
    }

    // ---- documents -----------------------------------------------------------------------------------------------------------------

    private fun curDoc(): Document = working ?: history.document

    private fun layer(id: String): Layer? = curDoc().layer(id)

    private fun editable(l: Layer.Pixel): Boolean {
        if (l.common.locked) { toast("Layer is locked"); return false }
        if (!l.common.visible) { toast("Layer is hidden"); return false }
        return true
    }

    /** Puts a finished drag or slider into the history as one entry. */
    private fun commitWorking() {
        val w = working ?: return
        working = null; scaleBase = null
        history.commitDocument(w)
        markDirty()
    }

    private fun cancelWorking() {
        if (working == null) return
        working = null; scaleBase = null
        updateFrame(); publish()
    }

    private fun addLayerInternal(d: Document, l: Layer.Pixel, px: RawPixels?) {
        val next = LayerOps.add(d, l)   // the 10 layer cap message
        if (!guardAllows(d, l.width to l.height)) return
        applyDocument(next, if (px != null) mapOf(l.common.id to px) else emptyMap(), l.common.id)
    }

    private fun guardAllows(d: Document, extra: Pair<Int, Int>): Boolean {
        val sizes = d.layers.filterIsInstance<Layer.Pixel>().map { it.width to it.height } + extra
        val ok = MemoryGuard.allows(sizes, surfaceW.takeIf { it > 0 } ?: MemoryGuard.DEFAULT_OUT_W, surfaceH.takeIf { it > 0 } ?: MemoryGuard.DEFAULT_OUT_H)
        if (!ok) toast(MemoryGuard.MESSAGE)
        return ok
    }

    /**
     * The one way a layer stack change enters the session: GPU slots first (so a failed upload changes nothing), then the history entry, then the active layer.
     * [newPixels] are pixels for layers that are not on disk (a photo, a duplicate); a null value means blank.
     */
    private fun applyDocument(next: Document, newPixels: Map<String, RawPixels>, newActive: String?) {
        if (drag != null) { toast("Finish the stroke first."); return }
        for ((id, px) in newPixels) { unsaved[id] = px; dirty += id; blank -= id }
        syncSlots(next)
        history.commitDocument(next)
        working = null; scaleBase = null
        if (newActive != null) activate(newActive, take = newPixels[newActive])
        ensureActiveValid(next)
        markDirty()
        publish(); updateFrame(); gauges()
    }

    /** Makes the GPU layers match [doc]: slots of layers that left the stack are freed (their pixels kept for undo), layers that came back are uploaded. */
    private fun syncSlots(doc: Document) {
        val ids = doc.layers.map { it.common.id }.toSet()
        for ((id, slot) in slots.toList()) if (id !in ids) {
            stash(id)
            gpuAsync { it.removeLayer(slot) }
            slots.remove(id)
        }
        for (l in doc.layers) if (l is Layer.Pixel && l.common.id !in slots) {
            val slot = freeSlot() ?: throw IllegalStateException("no free layer slot")
            val px = restoreFromGraveyard(l) ?: pixelsOf(l.common.id)
            val full = px ?: RawPixels(l.width, l.height, ByteArray(l.width * l.height * 4))
            if (px == null) blank += l.common.id
            upload(slot, full)
            slots[l.common.id] = slot
            thumbs[l.common.id] = Thumbs.make(full)
        }
    }

    private fun ensureActiveValid(doc: Document) {
        if (doc.layer(activeId) != null) return
        // the active layer left the stack: its CPU copy goes (stashed above), the nearest layer takes over
        activePixels = null
        activate(doc.layers.last().common.id)
    }

    private fun freeSlot(): Int? = (0 until 16).firstOrNull { it !in slots.values }

    /** Keeps the pixels of a layer that is leaving the stack in encoded form, within 256 MB, oldest first out. */
    private fun stash(id: String) {
        val l = history.document.layer(id) as? Layer.Pixel ?: return
        val px = if (id == activeId) activePixels else (unsaved[id] ?: inFlight[id])
        val bytes: ByteArray? = when {
            px != null -> PixelContainer.encode(px)
            files[id] != null -> fs.read("$root/${files[id]}")
            else -> null
        }
        if (id == activeId) activePixels = null
        dirty -= id; unsaved.remove(id)
        if (bytes == null) { blank += id; return }
        graveyard.remove(id)
        graveyard[id] = bytes
        var total = graveyard.values.sumOf { it.size.toLong() }
        val it = graveyard.entries.iterator()
        while (total > 256L * 1024 * 1024 && it.hasNext()) { val e = it.next(); total -= e.value.size; lost += e.key; it.remove() }
        if (l.common.id in lost) toast("Pixels of a removed layer were cleared to free memory")
    }

    private fun restoreFromGraveyard(l: Layer.Pixel): RawPixels? {
        val id = l.common.id
        val bytes = graveyard.remove(id)
        if (bytes == null) {
            if (id in lost) { lost -= id; toast("The pixels of that layer were cleared to free memory"); blank += id }
            return null
        }
        val px = try { PixelContainer.decode(bytes) } catch (e: Exception) { env.error("studio graveyard decode: ${e.message}"); null } ?: return null
        files[id] = null   // the file of the old life of this layer may be gone; save it again under a new name
        unsaved[id] = px; dirty += id; blank -= id
        return px
    }

    // ---- pixels -----------------------------------------------------------------------------------------------------------------------

    /** Newest pixels of a layer: the active copy, a snapshot not yet on disk, or the saved file. Null for a layer that is still transparent. Callers must not write into the result unless it is [activePixels]. */
    private fun pixelsOf(id: String): RawPixels? {
        if (id == activeId) activePixels?.let { return it }
        unsaved[id]?.let { return it }
        inFlight[id]?.let { return it }
        val l = history.document.layer(id) as? Layer.Pixel ?: return null
        val file = files[id] ?: return null
        return store.load(l.copy(pixelsFile = file))
    }

    private fun ensureActivePixels() {
        if (activePixels != null) return
        activePixels = loadForEditing(activeId)
    }

    /** Pixels of a layer the user is about to paint on: a private copy when the source must stay untouched, a blank array for a transparent layer. */
    private fun loadForEditing(id: String): RawPixels {
        val l = history.document.layer(id) as Layer.Pixel
        val src = pixelsOf(id)
        val shared = unsaved[id] === src || inFlight[id] === src
        return when {
            src == null -> RawPixels(l.width, l.height, ByteArray(l.width * l.height * 4))
            shared -> RawPixels(src.w, src.h, src.rgba.copyOf())
            else -> src
        }
    }

    /** Makes [id] the active layer: the old one's CPU copy is handed to the saver if it changed, then dropped. */
    private fun activate(id: String, take: RawPixels? = null) {
        if (id == activeId && activePixels != null) return
        releaseActive()
        activeId = id
        // [take]: the pixels of a layer that was just made (a photo): the active copy IS that array, so a 12 MP picture is not held twice
        activePixels = if (take != null && unsaved[id] === take) take.also { unsaved.remove(id) } else loadForEditing(id)
        val px = activePixels!!
        if (id !in thumbs) thumbs[id] = Thumbs.make(px)
    }

    private fun releaseActive() {
        val px = activePixels ?: return
        val id = activeId
        activePixels = null
        if (id in dirty && history.document.layer(id) != null) {
            unsaved[id] = px   // no copy: from here on nobody writes into it
            markDirty(now = true)
        }
    }

    private fun upload(slot: Int, px: RawPixels) {
        val ok = gpuCall(120_000) { it.setLayerImage(slot, px.rgba, px.w, px.h) } == true
        if (!ok) throw IllegalStateException("Could not put the layer on the GPU. The picture may be too large for the memory that is free.")
    }

    /** Every layer to its slot, one at a time (one decoded layer in memory beside the active one). Used at open and after the GL context was lost. */
    private fun uploadAll() {
        val d = history.document
        slots.clear()
        for (l in d.layers) if (l is Layer.Pixel) {
            val slot = freeSlot() ?: throw IllegalStateException("no free layer slot")
            val px = if (l.common.id == activeId) (activePixels ?: loadForEditing(l.common.id).also { activePixels = it }) else pixelsOf(l.common.id)
            val full = px ?: RawPixels(l.width, l.height, ByteArray(l.width * l.height * 4))
            if (px == null) blank += l.common.id
            upload(slot, full)
            slots[l.common.id] = slot
            thumbs[l.common.id] = Thumbs.make(full)
        }
        gauges()
    }

    // ---- autosave (decision D8) ---------------------------------------------------------------------------------------------------

    private fun markDirty(now: Boolean = false) {
        needsSave = true
        if (saving) { saveAgain = true; if (now) saveAgainNow = true; publishSave(); return }
        val wait = if (now) 0L else lastSaveStart + SAVE_EVERY_MS - env.clock()
        if (wait <= 0L) startSave()
        else {
            publishSave()
            if (!timerArmed) {
                timerArmed = true
                env.later(wait) { env.model.execute { timerArmed = false; if (needsSave && !saving && !released) startSave() } }
            }
        }
    }

    private fun startSave() {
        needsSave = false; saving = true; saveAgain = false
        lastSaveStart = env.clock()
        lastModified = maxOf(env.clock(), lastModified + 1)
        val base = history.document
        val doc = base.copy(modified = lastModified, layers = base.layers.map { if (it is Layer.Pixel) it.copy(pixelsFile = files[it.common.id]) else it })
        val ids = doc.layers.map { it.common.id }.toSet()
        val changed = dirty.filter { it in ids }.toSet()
        dirty.clear()
        val snaps = HashMap<String, RawPixels>()
        for (id in changed) {
            val live = if (id == activeId) activePixels else null   // a layer just released has no live copy: its array is in unsaved
            val src = live?.let { RawPixels(it.w, it.h, it.rgba.copyOf()) } ?: unsaved[id] ?: inFlight[id]
            if (src != null) snaps[id] = src
        }
        inFlight.putAll(snaps)
        publishSave()
        env.saver.execute {
            val t0 = env.nanos()
            try {
                val saved = store.save(doc, { p -> snaps[p.common.id] }, changed)
                env.report("studio_autosave_ms", (env.nanos() - t0) / 1_000_000)
                env.model.execute { onSaved(saved, snaps) }
            } catch (t: Throwable) {
                env.error("studio save: ${t.javaClass.simpleName}: ${t.message}")
                env.model.execute { onSaveFailed(changed, snaps) }
            }
        }
    }

    private fun onSaved(saved: Document, snaps: Map<String, RawPixels>) {
        for (l in saved.layers) if (l is Layer.Pixel) files[l.common.id] = l.pixelsFile
        for ((id, px) in snaps) { inFlight.remove(id); if (unsaved[id] === px) unsaved.remove(id) }
        saving = false; saveFailed = false
        if (saveAgain || needsSave || dirty.isNotEmpty()) { val urgent = saveAgainNow; saveAgain = false; saveAgainNow = false; markDirty(now = urgent) } else publishSave()
        gauges()
    }

    private fun onSaveFailed(changed: Set<String>, snaps: Map<String, RawPixels>) {
        for ((id, px) in snaps) { inFlight.remove(id); if (id != activeId) unsaved.putIfAbsent(id, px) }
        dirty += changed
        saving = false; saveFailed = true
        toast("Could not save. Will try again.")
        markDirty()   // the next try waits for the five second spacing
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------------------------------

    private fun model(block: () -> Unit) { env.model.execute { try { block() } catch (t: Throwable) { env.error("studio model: ${t.javaClass.simpleName}: ${t.message}") } } }

    private inline fun guarded(block: () -> Unit) {
        try { block() }
        catch (e: IllegalArgumentException) { toast(e.message ?: "That did not work.") }   // LayerOps messages are written for the user
        catch (t: Throwable) { env.error("studio op: ${t.javaClass.simpleName}: ${t.message}"); toast(t.message ?: "Something went wrong.") }
    }

    private fun gpuAsync(block: (StudioGpu) -> Unit) = gl.post { g -> try { block(g) } catch (t: Throwable) { env.error("studio gpu: ${t.javaClass.simpleName}: ${t.message}") } }

    /** Runs [block] on the GL thread and waits for its result (model thread only). Null when the surface is gone, the call threw or it took longer than [timeoutMs]. */
    private fun <T> gpuCall(timeoutMs: Long = 10_000, block: (StudioGpu) -> T): T? {
        val box = arrayOfNulls<Any>(1)
        val done = CountDownLatch(1)
        gl.post(onDrop = { done.countDown() }) { g ->
            try { box[0] = block(g) } catch (t: Throwable) { env.error("studio gpu: ${t.javaClass.simpleName}: ${t.message}") } finally { done.countDown() }
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        @Suppress("UNCHECKED_CAST")
        return box[0] as T?
    }

    private fun toast(text: String) { _state.update { it.copy(message = UiMessage(messageId.incrementAndGet(), text)) } }

    private fun newId() = "l" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun publishDoc() { _state.update { it.copy(document = curDoc()) } }

    private fun publishZoom() { _state.update { it.copy(zoomPercent = Math.round(view.zoom * 100f)) } }

    private fun saveStateNow() = when {
        saveFailed -> SaveState.FAILED
        saving -> SaveState.SAVING
        needsSave || dirty.isNotEmpty() -> SaveState.DIRTY
        else -> SaveState.SAVED
    }

    private fun publishSave() { _state.update { it.copy(save = saveStateNow()) }; gauges(false) }

    private fun publish() {
        val d = curDoc()
        val ids = d.layers.map { it.common.id }.toSet()
        _state.update {
            it.copy(
                document = d, activeId = activeId.takeIf { a -> a in ids } ?: d.layers.last().common.id,
                canUndo = history.canUndo, canRedo = history.canRedo,
                thumbs = thumbs.filterKeys { k -> k in ids }.toMap(),
                nonBlank = ids.filter { id -> id !in blank }.toSet(),
                save = saveStateNow(),
            )
        }
    }

    private fun updateFrame() {
        val d = curDoc()
        val out = ArrayList<Float>(d.layers.size * 6)
        for (l in d.layers) {
            if (!l.common.visible) continue
            val slot = slots[l.common.id] ?: continue
            out += slot.toFloat(); out += l.common.x.toFloat(); out += l.common.y.toFloat(); out += l.common.scale; out += l.common.opacity / 100f; out += l.common.blend.id.toFloat()
        }
        gl.setFrame(FrameSpec(out.toFloatArray(), view.x, view.y, view.zoom, d.width, d.height))
        gl.requestRender()
    }

    /** Copy report gauges (spec section 6 of the task): GPU bytes and undo history bytes, and the figures the Studio section prints. */
    private fun gauges(withGpu: Boolean = true) {
        val d = history.document
        val sizes = d.layers.filterIsInstance<Layer.Pixel>().map { it.width to it.height }
        val est = MemoryGuard.estimate(sizes, surfaceW.takeIf { it > 0 } ?: MemoryGuard.DEFAULT_OUT_W, surfaceH.takeIf { it > 0 } ?: MemoryGuard.DEFAULT_OUT_H)
        val hist = history.deltaBytes / (1024 * 1024)
        env.report("studio_history_mb", hist)
        val save = saveStateNow().label
        val name = d.name
        if (withGpu) gl.post { g ->
            val mb = g.textureBytes() / (1024 * 1024)
            env.report("studio_texture_mb", mb)
            StudioStats.update(StudioStats.Snapshot(d.width, d.height, d.layers.size, mb, hist, est / (1024 * 1024), save, name))
        } else StudioStats.update(StudioStats.Snapshot(d.width, d.height, d.layers.size, 0, hist, est / (1024 * 1024), save, name))
    }

    companion object {
        /** Autosave spacing while changes keep coming (decision D8). */
        const val SAVE_EVERY_MS = 5_000L
    }
}
