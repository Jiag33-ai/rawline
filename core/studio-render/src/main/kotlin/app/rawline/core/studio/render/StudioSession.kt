package app.rawline.core.studio.render

import app.rawline.core.studio.model.Action
import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.BlendMode
import app.rawline.core.studio.model.CanvasView
import app.rawline.core.studio.model.ColourSpace
import app.rawline.core.studio.model.Dirty
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.Fs
import app.rawline.core.studio.model.InputEvent
import app.rawline.core.studio.model.InputRouter
import app.rawline.core.studio.model.IRect
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.LayerOps
import app.rawline.core.studio.model.MaskOps
import app.rawline.core.studio.model.PlaneDelta
import app.rawline.core.studio.model.PlaneStep
import app.rawline.core.studio.model.PlaneTarget
import app.rawline.core.studio.model.SelOp
import app.rawline.core.studio.model.Selection
import app.rawline.core.studio.model.SelectionClip
import app.rawline.core.studio.model.SelectionContour
import app.rawline.core.studio.model.SelectionRef
import app.rawline.core.studio.model.TileKey
import app.rawline.core.studio.model.TilePlane
import app.rawline.core.studio.model.PixelContainer
import app.rawline.core.studio.model.PixelDelta
import app.rawline.core.studio.model.Placement
import app.rawline.core.studio.model.ProjectStore
import app.rawline.core.studio.model.RawPixels
import app.rawline.core.studio.model.SpaceCheck
import app.rawline.core.studio.model.Step
import app.rawline.core.studio.model.StrokePoint
import app.rawline.core.studio.model.StrokeReference
import app.rawline.core.studio.model.StrokeTiles
import app.rawline.core.studio.model.StrokeWalker
import app.rawline.core.studio.model.StudioHistory
import app.rawline.core.studio.model.ThumbScheduler
import app.rawline.core.studio.model.Stamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What a flatten export or a thumbnail renders: the visible layers as the compositor takes them (see `StudioSession.exportSnapshot`). Immutable. */
class ExportSnapshot(val name: String, val width: Int, val height: Int, val colourSpace: ColourSpace, val layers: FloatArray)

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
    private val history = StudioHistory(initial.copy(selection = null))   // the saved selection is the session's `selection`, not a history state
    private val initialSelection: SelectionRef? = initial.selection
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
    private val thumbSched = ThumbScheduler()                   // BK-507: when the home's thumbnail is written (model thread)
    private var thumbTimerArmed = false
    private var thumbBusy = false
    /** BK-507: renders and writes the project thumbnail for [snap] (worker thread, returns false on failure). Set by the canvas screen; with none set no thumbnail is made and no timer runs. */
    @Volatile var thumbnailer: Thumbnailer? = null
    /** Open in Studio: runs once on the saver thread after the first save of this session worked (the project folder exists then). Set before [start]; a failure is logged and the project stays as it is. */
    @Volatile var afterFirstSave: (() -> Unit)? = null
    fun interface Thumbnailer { fun write(snap: ExportSnapshot): Boolean }
    private var needsSave = false
    private var saving = false
    private var saveAgain = false
    private var saveAgainNow = false
    private var timerArmed = false
    private var firstEditAt = -1L                                // BK-484: clock of the first edit not yet saved; the 5 s ceiling counts from here
    private var lastEditAt = -1L                                 // clock of the newest edit; a save waits for IDLE_MS after it
    private var lastSaveStart = Long.MIN_VALUE / 2
    private var lastFailureAt = Long.MIN_VALUE / 2               // when the last save failed: the retry gap counts from here, not from when that save began
    private var lastModified = initial.modified
    private var saveFailed = false
    private var noSpace = false                                  // the last failure was "not enough free space"
    private var failStreak = 0                                   // failed saves in a row; the retry delay grows with it (BK-503)
    private var retryHalted = false                              // MAX_TRIES failures in a row: no more timer retries until something changes
    private var failureNotified = false                          // one notice per failure episode, not per retry
    private var everSaved = onDisk                               // false until the first save of a new project worked
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

    // S2: layer masks and the selection. Planes are in memory (a 12 MP mask is at most 12 MB); tiles are written by the saver before the JSON that names them.
    private val masks = HashMap<String, TilePlane>()             // layer id -> mask plane in the layer's own pixels; kept for layers an undo can bring back
    private val gpuMasks = HashSet<String>()                     // layers whose mask is on the GPU
    private val maskThumbs = HashMap<String, Thumb>()
    private var selection = Selection(initial.width, initial.height)
    private var gpuSelection = false
    private var pureRect: IRect? = null                          // the selection is exactly this rectangle (the outline is then exact)
    private var selectionVersion = 0
    private var selectionView: SelectionView? = null
    private val pendingTiles = HashMap<String, LinkedHashMap<TileKey, ByteArray?>>()    // tile directory -> tiles not on disk yet (null = delete the tile)
    private var inFlightTiles: Map<String, LinkedHashMap<TileKey, ByteArray?>> = emptyMap()

    private class NoSpaceException(message: String) : java.io.IOException(message)

    private class PaintStroke(val layerId: String, val slot: Int, val brush: Brush, val colour: FloatArray, val walker: StrokeWalker, val maskValue: Float? = null) { val stamps = ArrayList<Stamp>() }
    private class SelectDrag(val tool: Tool, val sx: Float, val sy: Float) { val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); var ex = sx; var ey = sy }
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
            initialSelection?.let { loadSelection(it) }
            uploadAll()
            _state.update { it.copy(phase = Phase.READY) }
            publish()
            updateFrame()
            if (!onDisk || needsSaveAtStart()) markDirty(now = true)
            if (!onDisk) noteEdit()    // a new project gets its first thumbnail after the first quiet moment
        } catch (t: Throwable) {
            env.error("studio open: ${t.javaClass.simpleName}: ${t.message}")
            _state.update { it.copy(phase = Phase.ERROR, error = t.message ?: "Could not open the project") }
        }
    }

    private fun needsSaveAtStart() = dirty.isNotEmpty()

    /** On pause and Back: write what changed now, not in five seconds. Returns at once. */
    fun flush() = model { if (needsSave || dirty.isNotEmpty()) markDirty(now = true) }

    /**
     * Leaving: saves now and waits (at most [timeoutMs], real time) until the save has finished or failed, then returns the save state. Call from a worker thread, never the main thread.
     * SAVED, or a state with unsaved work (FAILED, NO_SPACE, or DIRTY/SAVING when the time ran out) so the screen can ask before it throws the work away.
     */
    fun flushAndWait(timeoutMs: Long): SaveState {
        flush()
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            val s = _state.value.save
            if (s != SaveState.SAVING && s != SaveState.DIRTY) return s
            Thread.sleep(20)
        }
        return _state.value.save
    }

    /** The app is in the background or the screen is off: the GPU waits until it is back. The exporter waits for the foreground instead of failing (review R2). */
    fun isBackgrounded(): Boolean = gl.isPaused

    /** The screen is gone. A last save is started if anything is unsaved; the pools wind themselves down when idle. */
    fun release() {
        model { if (needsSave || dirty.isNotEmpty()) markDirty(now = true); released = true }
        gl.listener = null
    }

    // ---- surface callbacks (GL thread) -------------------------------------------------------------------------------------------

    override fun onSurfaceSize(w: Int, h: Int) = model {
        if (w <= 0 || h <= 0 || (w == surfaceW && h == surfaceH)) return@model
        val d = curDoc()
        view = if (surfaceW > 0 && !viewFitted) {
            // The user zoomed or panned and the surface changed size (a rotation): keep the zoom and the document point at the centre of the screen.
            val cx = view.toDocX(surfaceW / 2f); val cy = view.toDocY(surfaceH / 2f)
            CanvasView(cx - w / 2f / view.zoom, cy - h / 2f / view.zoom, view.zoom).clamped(d.width, d.height, w, h)
        } else { viewFitted = true; CanvasView.fit(d.width, d.height, w, h) }
        surfaceW = w; surfaceH = h
        publishZoom(); updateFrame()
    }

    override fun onContextRestored() = model {
        if (_state.value.phase != Phase.READY) return@model
        drag = null   // the stroke buffer died with the context; the router resets itself on the next pointer up
        _state.update { it.copy(selectionDrag = null) }
        try { uploadAll(); updateFrame() } catch (t: Throwable) { env.error("studio context restore: ${t.message}"); toast("Could not restore the picture after the screen was reset.") }
    }

    // ---- tool settings (any thread, applied at once) -----------------------------------------------------------------------------

    fun setTool(t: Tool) { if (drag == null) _state.update { it.copy(tool = t) } }
    /** How the next selection combines with the one there is: Replace, Add, Subtract or Intersect. Stays until changed. */
    fun setSelectionOp(op: SelOp) = _state.update { it.copy(selectionOp = op) }
    /** The brush and eraser paint the active layer's mask (true) or its pixels (false). Has an effect only on a layer that has a mask. */
    fun setPaintMask(on: Boolean) = _state.update { it.copy(paintMask = on) }
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

    /** Pen hover from the canvas (main thread): fingers are taken for a palm while the pen is near and for a short grace after. */
    fun onHover(near: Boolean, timeMs: Long) { if (!released) router.onHover(near, timeMs) }

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
            masks[id]?.let { masks[newId] = it.copy() }   // the copy has its own mask directory (LayerOps.duplicate); syncMasks uploads it and queues its tiles
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

    // ---- layer masks (S2, spec 2.1) -----------------------------------------------------------------------------------------------

    /** Adds a mask to the layer: white (everything shows), black (everything hidden) or the selection. One undo step. */
    fun addMask(id: String, fill: MaskFill) = model {
        guarded {
            val d = history.document
            val l = d.layer(id) as? Layer.Pixel ?: return@guarded
            if (l.common.mask != null) throw IllegalArgumentException("That layer already has a mask.")
            masks[id] = when (fill) {
                MaskFill.WHITE -> MaskOps.white(l.width, l.height)
                MaskFill.BLACK -> MaskOps.black(l.width, l.height)
                MaskFill.FROM_SELECTION -> MaskOps.fromSelection(selection, l.width, l.height, l.common.x, l.common.y, l.common.scale)
            }
            applyDocument(LayerOps.addMask(d, id), emptyMap(), null)
            _state.update { it.copy(paintMask = false) }
        }
    }
    fun setMaskEnabled(id: String, enabled: Boolean) = model { guarded { applyDocument(LayerOps.setMaskEnabled(history.document, id, enabled), emptyMap(), null) } }
    /** Shows the mask the other way round (a flag, the tiles are not rewritten). Painting then still follows the colour: black hides what you see. */
    fun invertMask(id: String) = model { guarded { val m = history.document.layer(id)?.common?.mask ?: return@guarded; applyDocument(LayerOps.setMaskInverted(history.document, id, !m.inverted), emptyMap(), null) } }
    fun deleteMask(id: String) = model { guarded { applyDocument(LayerOps.deleteMask(history.document, id), emptyMap(), null); _state.update { it.copy(paintMask = false) } } }

    // ---- selection (S2, spec 2.2) --------------------------------------------------------------------------------------------------

    fun selectAll() = model { guarded { editSelection(IRect(0, 0, curDoc().width, curDoc().height)) { selection.selectAll() } } }
    fun deselect() = model { guarded { editSelection(null) { selection.clear() } } }
    fun invertSelection() = model { guarded { editSelection(null) { selection.invert() } } }

    private fun editSelection(pure: IRect?, f: () -> Unit) {
        if (drag != null) { toast("Finish the stroke first."); return }
        cancelWorking()
        val before = selection.bounds
        selection.plane.beginRecord()
        try { f() } catch (t: Throwable) { selection.plane.endRecord(); throw t }
        val parts = selection.plane.endRecord()
        val after = selection.bounds
        pureRect = if (selection.isEmpty()) null else pure
        if (parts.isEmpty() && before == after) { publishSelection(); return }
        history.commitPlane(PlaneTarget.Selection, parts, before, after)
        syncSelectionGpu(parts.map { it to it.after }, before.empty)
        publishSelection()
        markDirty(); pruneGraveyard(); publish(); gl.requestRender()
    }

    /** Puts changed selection tiles on the GPU: the whole plane when there was no selection there, otherwise only the tiles. No selection removes the GPU copy. */
    private fun syncSelectionGpu(tiles: List<Pair<PlaneDelta, ByteArray>>, wasEmpty: Boolean) {
        val d = curDoc()
        if (selection.isEmpty()) { if (gpuSelection) gpuAsync { it.setSelection(null, 0, 0) }; gpuSelection = false; return }
        if (!gpuSelection || wasEmpty) { val bytes = selection.plane.toBytes(); gpuAsync { it.setSelection(bytes, d.width, d.height) }; gpuSelection = true; return }
        gpuAsync { g -> for ((t, bytes) in tiles) g.updateSelection(t.x, t.y, t.w, t.h, bytes) }
    }

    private fun publishSelection() {
        selectionVersion++
        selectionView = if (selection.isEmpty()) null else SelectionView(selection.bounds, SelectionContour.segments(selection, pureRect), selectionVersion)
        _state.update { it.copy(selection = selectionView, selectionDrag = null) }
    }

    private fun loadSelection(ref: SelectionRef) {
        val d = history.document
        val bad = IntArray(1)
        selection = Selection(d.width, d.height, store.loadPlane(ref.dir, d.width, d.height, bad)).also { it.recomputeBounds() }
        if (bad[0] > 0) toast("Part of the saved selection could not be read.")
        selectionView = if (selection.isEmpty()) null else SelectionView(selection.bounds, SelectionContour.segments(selection, null), ++selectionVersion)
        _state.update { it.copy(selection = selectionView) }
    }

    fun undo() = model { guarded { if (drag != null) return@guarded; cancelWorking(); applyStep(history.undo()) } }
    fun redo() = model { guarded { if (drag != null) return@guarded; cancelWorking(); applyStep(history.redo()) } }

    /** Memory pressure: drop what can be rebuilt. Undo steps older than the newest 50 MB go, with one notice. */
    fun trimMemory() = model {
        graveyard.clear()
        if (history.trimBytes(50L * 1024 * 1024) && !trimmedToast) { trimmedToast = true; toast("Older undo steps were cleared to free memory") }
        pruneGraveyard()
        publish()
    }

    // ---- actions (model thread) ----------------------------------------------------------------------------------------------------

    private fun handle(a: Action, timeMs: Long) {
        try {
            when (a) {
                is Action.StrokeStart -> strokeStart(a, timeMs)
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

    private fun strokeStart(a: Action.StrokeStart, timeMs: Long) {
        if (_state.value.phase != Phase.READY) return
        val l = layer(activeId) as? Layer.Pixel ?: return
        val s = _state.value
        when (s.tool) {
            Tool.RECT_SELECT, Tool.ELLIPSE_SELECT, Tool.LASSO_SELECT -> {
                val d = SelectDrag(s.tool, view.toDocX(a.x), view.toDocY(a.y))
                if (s.tool == Tool.LASSO_SELECT) { d.xs += d.sx; d.ys += d.sy }
                drag = d
                publishDrag(d)
            }
            Tool.BRUSH, Tool.ERASER -> {
                if (!editable(l)) return
                val brush = (if (s.tool == Tool.ERASER) s.eraser else s.brush).copy(erase = s.tool == Tool.ERASER)
                val slot = slots[activeId] ?: return
                val mask = l.common.mask
                if (s.paintMask && mask != null) {
                    if (!mask.enabled || activeId !in gpuMasks) { toast("Turn the mask on to paint it."); return }
                    // the brush paints toward the foreground grey, the eraser toward the background grey (black hides, white reveals)
                    val value = MaskOps.target((if (brush.erase) s.background else s.colour).array(), mask.inverted)
                    gpuAsync { it.beginMaskStroke(slot, value, brush.opacity.toFloat(), brush.hardness.toFloat(), brush.flow.toFloat()) }
                    val st = PaintStroke(activeId, slot, brush, FloatArray(3), StrokeWalker(brush), maskValue = value)
                    drag = st
                    addPoint(st, a.x, a.y, a.pressure, timeMs)
                    return
                }
                ensureActivePixels()
                val c = s.colour.array()
                // FIFO with the stamps that follow; if it fails the commit's readStroke fails cleanly and the stroke is rolled back
                gpuAsync { it.beginStroke(slot, c[0], c[1], c[2], brush.opacity.toFloat(), brush.erase, brush.hardness.toFloat(), brush.flow.toFloat()) }
                val st = PaintStroke(activeId, slot, brush, c, StrokeWalker(brush))
                drag = st
                addPoint(st, a.x, a.y, a.pressure, timeMs)
            }
            Tool.MOVE -> {
                if (!editable(l)) return
                drag = MoveDrag(activeId, a.x, a.y, l.common.x, l.common.y, l.common.scale)
            }
            Tool.SCALE -> {}
        }
    }

    private fun publishDrag(d: SelectDrag) {
        val v = SelectionDrag(d.tool, d.sx, d.sy, d.ex, d.ey, d.xs.toFloatArray(), d.ys.toFloatArray())
        _state.update { it.copy(selectionDrag = v) }
    }

    /** The finished shape goes into the selection with the mode the user chose (Replace, Add, Subtract, Intersect). One undo step. */
    private fun finishSelect(d: SelectDrag) {
        val op = _state.value.selectionOp
        val w = curDoc().width; val h = curDoc().height
        val x0 = minOf(d.sx, d.ex); val x1 = maxOf(d.sx, d.ex); val y0 = minOf(d.sy, d.ey); val y1 = maxOf(d.sy, d.ey)
        when (d.tool) {
            Tool.RECT_SELECT -> {
                val ix0 = Math.round(x0); val iy0 = Math.round(y0); val ix1 = Math.round(x1); val iy1 = Math.round(y1)
                val clip = IRect(ix0, iy0, ix1, iy1).intersect(IRect(0, 0, w, h))
                val pure = if (!clip.empty && (op == SelOp.REPLACE || selection.isEmpty() && op == SelOp.ADD)) clip else null
                editSelection(pure) { selection.rect(op, ix0, iy0, ix1, iy1) }
            }
            Tool.ELLIPSE_SELECT -> editSelection(null) { selection.ellipse(op, x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble()) }
            else -> {
                val xs = DoubleArray(d.xs.size) { d.xs[it].toDouble() }; val ys = DoubleArray(d.ys.size) { d.ys[it].toDouble() }
                editSelection(null) { selection.lasso(op, xs, ys) }
            }
        }
    }

    private fun strokeMove(a: Action.StrokeMove, timeMs: Long) {
        when (val d = drag) {
            is SelectDrag -> {
                d.ex = view.toDocX(a.x); d.ey = view.toDocY(a.y)
                if (d.tool == Tool.LASSO_SELECT) {
                    // a point only when the finger moved at least 2 screen pixels (a 200 point lasso stays 200 points, not 2000)
                    val lx = d.xs.last(); val ly = d.ys.last()
                    if (Math.hypot(((d.ex - lx) * view.zoom).toDouble(), ((d.ey - ly) * view.zoom).toDouble()) >= 2.0) { d.xs += d.ex; d.ys += d.ey }
                }
                publishDrag(d)
            }
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
            is SelectDrag -> { drag = null; finishSelect(d) }
            is PaintStroke -> { drag = null; commitStroke(d) }
            is MoveDrag -> { drag = null; commitWorking(); publish() }
            else -> {}
        }
    }

    private fun strokeCancel() {
        when (val d = drag) {
            is SelectDrag -> { drag = null; _state.update { it.copy(selectionDrag = null) } }
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
            viewFitted = false
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
        if (st.maskValue != null) { commitMaskStroke(st); return }
        val l = layer(st.layerId) as? Layer.Pixel
        val px = activePixels
        if (l == null || px == null || activeId != st.layerId) { gpuAsync { it.endStroke() }; return }
        val rects = StrokeTiles.rects(st.stamps, l.width, l.height)
        if (rects.isEmpty()) { gpuAsync { it.endStroke() }; gl.requestRender(); return }
        val t0 = env.nanos()
        // Read the coverage of every touched tile first (nothing is baked until all reads worked, so a failed read rolls the whole stroke back).
        val covs = ArrayList<FloatArray>(rects.size)
        for (batch in rects.chunked(8)) {   // at most 8 tiles of floats in flight, one GL round trip per batch
            val got = gpuCall { g -> batch.map { r -> FloatArray(r[2] * r[3]).also { c -> if (!g.readStroke(r[0], r[1], r[2], r[3], c)) throw IllegalStateException("readStroke") } } }
            if (got == null) { gpuAsync { it.endStroke() }; toast("Could not finish that stroke."); gl.requestRender(); return }
            covs += got
        }
        // the selection clips the stroke: coverage times the selection under each pixel (the live stroke did the same in the shader)
        for ((n, r) in rects.withIndex()) SelectionClip.apply(selection, covs[n], r, l.common.x, l.common.y, l.common.scale)
        var i = 0
        val deltas = StrokeTiles.commit(px.rgba, l.width, rects, st.colour, st.brush) { covs[i++] }
        // one GPU job: the baked tiles go in and the live stroke goes away before the next frame, so the stroke is never drawn twice
        gpuAsync { g -> for (d in deltas) g.updateRegion(st.slot, d.x, d.y, d.w, d.h, d.after); g.endStroke() }
        history.commitStrokeTiles(st.layerId, deltas)
        blank -= st.layerId
        dirty += st.layerId
        thumbs[st.layerId] = Thumbs.make(px)
        env.report("studio_commit_ms", (env.nanos() - t0) / 1_000_000)
        markDirty()
        pruneGraveyard()
        publish()
        gl.requestRender()
        gauges()
    }

    /** A stroke on a layer mask: the coverage (after the selection clip) moves the mask toward the brush grey; the changed tiles are one undo step. */
    private fun commitMaskStroke(st: PaintStroke) {
        val l = layer(st.layerId) as? Layer.Pixel
        val plane = masks[st.layerId]
        if (l == null || plane == null || st.layerId !in gpuMasks) { gpuAsync { it.endStroke() }; return }
        val rects = StrokeTiles.rects(st.stamps, l.width, l.height)
        if (rects.isEmpty()) { gpuAsync { it.endStroke() }; gl.requestRender(); return }
        val t0 = env.nanos()
        val covs = ArrayList<FloatArray>(rects.size)
        for (batch in rects.chunked(8)) {
            val got = gpuCall { g -> batch.map { r -> FloatArray(r[2] * r[3]).also { c -> if (!g.readStroke(r[0], r[1], r[2], r[3], c)) throw IllegalStateException("readStroke") } } }
            if (got == null) { gpuAsync { it.endStroke() }; toast("Could not finish that stroke."); gl.requestRender(); return }
            covs += got
        }
        for ((n, r) in rects.withIndex()) SelectionClip.apply(selection, covs[n], r, l.common.x, l.common.y, l.common.scale)
        plane.beginRecord()
        for ((n, r) in rects.withIndex()) MaskOps.bake(plane, r, covs[n], st.maskValue!!, st.brush.opacity.toFloat())
        val parts = plane.endRecord()
        val slot = st.slot
        gpuAsync { g -> for (d in parts) g.updateMask(slot, d.x, d.y, d.w, d.h, d.after); g.endStroke() }
        history.commitPlane(PlaneTarget.Mask(st.layerId), parts, null, null)
        maskThumbs[st.layerId] = Thumbs.makeMask(plane)
        env.report("studio_commit_ms", (env.nanos() - t0) / 1_000_000)
        markDirty(); pruneGraveyard(); publish(); gl.requestRender(); gauges()
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
            is Step.SetPixelsMany -> {
                if (activeId != step.layerId) activate(step.layerId)
                val l = layer(step.layerId) as Layer.Pixel
                val px = activePixels ?: return
                step.apply(px.rgba, l.width)
                val slot = slots[step.layerId] ?: return
                gpuAsync { g -> for (d in step.parts) g.updateRegion(slot, d.x, d.y, d.w, d.h, if (step.useAfter) d.after else d.before) }
                blank -= step.layerId; dirty += step.layerId; thumbs[step.layerId] = Thumbs.make(px); markDirty()
            }
            is Step.SetDocument -> {
                syncSlots(step.document)
                syncMasks(step.document)
                ensureActiveValid(step.document)
                markDirty()
            }
            is Step.SetPlane -> applyPlaneStep(step.edit)
        }
        pruneGraveyard()
        publish(); updateFrame(); gauges()
    }

    private fun applyPlaneStep(e: PlaneStep) {
        val tiles = e.parts.map { it to (if (e.useAfter) it.after else it.before) }
        when (val t = e.target) {
            PlaneTarget.Selection -> {
                val wasEmpty = selection.isEmpty()
                for ((d, bytes) in tiles) selection.plane.writeTile(d.key, bytes)
                selection.restoreBounds(e.bounds ?: IRect(0, 0, 0, 0))
                pureRect = null
                syncSelectionGpu(tiles, wasEmpty)
                publishSelection()
            }
            is PlaneTarget.Mask -> {
                val plane = masks[t.layerId] ?: return
                for ((d, bytes) in tiles) plane.writeTile(d.key, bytes)
                val slot = slots[t.layerId]
                if (slot != null && t.layerId in gpuMasks) gpuAsync { g -> for ((d, bytes) in tiles) g.updateMask(slot, d.x, d.y, d.w, d.h, bytes) }
                maskThumbs[t.layerId] = Thumbs.makeMask(plane)
            }
        }
        markDirty()
    }

    /** Keeps the encoded pixels only of layers an undo or a redo can still bring back (history trims and new edits make others unreachable). */
    private fun pruneGraveyard() {
        val keep = history.restorableLayerIds()
        graveyard.keys.retainAll(keep); lost.retainAll(keep)
        val inStack = history.document.layers.map { it.common.id }.toSet()
        masks.keys.retainAll(inStack + keep); maskThumbs.keys.retainAll(masks.keys)
    }

    /** Test hook: bytes the undo history holds. */
    internal fun historyBytes(): Long = history.deltaBytes

    /** Test hook: encoded bytes the graveyard holds. */
    internal fun graveyardBytes(): Long = graveyard.values.sumOf { it.size.toLong() }

    /** Test hook: how many removed layers still have their pixels kept. */
    internal fun graveyardSize(): Int = graveyard.size

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
        pruneGraveyard()
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
        syncMasks(next)
        history.commitDocument(next)
        working = null; scaleBase = null
        if (newActive != null) activate(newActive, take = newPixels[newActive])
        ensureActiveValid(next)
        markDirty()
        pruneGraveyard()
        publish(); updateFrame(); gauges()
    }

    /** Makes the GPU layers match [doc]: slots of layers that left the stack are freed (their pixels kept for undo), layers that came back are uploaded. */
    private fun syncSlots(doc: Document) {
        val ids = doc.layers.map { it.common.id }.toSet()
        for ((id, slot) in slots.toList()) if (id !in ids) {
            stash(id)
            gpuAsync { it.removeLayer(slot) }
            slots.remove(id); gpuMasks.remove(id)
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

    /** Makes the GPU masks match [doc]: a layer with a mask that is not on the GPU gets it (its tiles are queued for the next save), a layer without one loses it. */
    private fun syncMasks(doc: Document, fresh: Boolean = true) {
        for (l in doc.layers) {
            if (l !is Layer.Pixel) continue
            val id = l.common.id; val slot = slots[id] ?: continue
            val ref = l.common.mask
            if (ref != null && id !in gpuMasks) {
                val plane = masks.getOrPut(id) { loadMaskPlane(l) }
                val bytes = plane.toBytes()
                if (gpuCall(120_000) { it.setMask(slot, bytes, l.width, l.height) } != true) throw IllegalStateException("Could not put the mask on the GPU. The picture may be too large for the memory that is free.")
                gpuMasks += id
                if (fresh) plane.markAllDirty()
                maskThumbs[id] = Thumbs.makeMask(plane)
            } else if (ref == null && id in gpuMasks) {
                gpuAsync { it.setMask(slot, null, 0, 0) }
                gpuMasks -= id
            }
        }
    }

    private fun loadMaskPlane(l: Layer.Pixel): TilePlane {
        val ref = l.common.mask!!
        val bad = IntArray(1)
        val p = store.loadPlane(ref.dir, l.width, l.height, bad)
        if (bad[0] > 0) toast("Part of a layer mask could not be read.")
        return p
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
        slots.clear(); gpuMasks.clear(); gpuSelection = false
        for (l in d.layers) if (l is Layer.Pixel) {
            val slot = freeSlot() ?: throw IllegalStateException("no free layer slot")
            val px = if (l.common.id == activeId) (activePixels ?: loadForEditing(l.common.id).also { activePixels = it }) else pixelsOf(l.common.id)
            val full = px ?: RawPixels(l.width, l.height, ByteArray(l.width * l.height * 4))
            if (px == null) blank += l.common.id
            upload(slot, full)
            slots[l.common.id] = slot
            thumbs[l.common.id] = Thumbs.make(full)
        }
        syncMasks(d, fresh = false)
        if (!selection.isEmpty()) { val bytes = selection.plane.toBytes(); gpuAsync { it.setSelection(bytes, d.width, d.height) }; gpuSelection = true }
        gauges()
    }

    // ---- autosave (decision D8) ---------------------------------------------------------------------------------------------------

    /** A change to save (an edit, a pause, leaving). After a run of failures an edit allows a few more slow tries and a pause or leaving allows one. */
    private fun markDirty(now: Boolean = false) {
        needsSave = true
        if (!now) { noteEdit(); val t = env.clock(); if (firstEditAt < 0) firstEditAt = t; lastEditAt = t }
        if (retryHalted) { retryHalted = false; failStreak = if (now) SpaceCheck.MAX_TRIES - 1 else SpaceCheck.MAX_TRIES - 3 }
        scheduleSave(now)
    }

    // ---- thumbnail on the autosave path (BK-507) ----------------------------------------------------------------------------------
    // Written after 2 s without an edit, at most once a minute, and only if something changed; never on the Close path (Close does not wait for it).

    private fun noteEdit() { thumbSched.onEdit(env.clock()); armThumbTimer() }

    private fun armThumbTimer() {
        if (thumbTimerArmed || released || thumbnailer == null) return
        val wait = thumbSched.waitMs(env.clock()) ?: return
        thumbTimerArmed = true
        env.later(maxOf(wait, 250L)) { env.model.execute { thumbTimerArmed = false; checkThumb() } }
    }

    private fun checkThumb() {
        val t = thumbnailer
        if (released || t == null || thumbBusy || !thumbSched.needsWrite()) return
        // a project whose first save has not worked has no folder to put a thumbnail in: wait for the save (onSaved asks again)
        if (!everSaved || _state.value.phase != Phase.READY) return
        val now = env.clock()
        if (!thumbSched.due(now)) { armThumbTimer(); return }
        thumbBusy = true
        env.thumb.execute {
            var ok = false
            try {
                val snap = exportSnapshot(2_000)
                ok = snap != null && t.write(snap)
            } catch (e: Throwable) { env.error("studio thumbnail: ${e.javaClass.simpleName}: ${e.message}") }
            env.model.execute {
                thumbBusy = false
                // a failure counts as a try: no hot loop, the next edit asks again. An edit made during the write keeps the thumbnail due.
                thumbSched.onWritten(now)
                if (!ok) env.error("studio thumbnail: not written")
                if (thumbSched.needsWrite()) armThumbTimer()
            }
        }
    }

    private fun scheduleSave(now: Boolean) {
        if (saving) { saveAgain = true; if (now) saveAgainNow = true; publishSave(); return }
        val wait = when {
            now -> 0L
            saveFailed -> lastFailureAt + SpaceCheck.retryDelayMs(failStreak) - env.clock()
            firstEditAt < 0 -> lastSaveStart + SAVE_EVERY_MS - env.clock()
            else -> minOf(lastEditAt + IDLE_MS, firstEditAt + SAVE_EVERY_MS) - env.clock()   // stroke end plus 1.5 s idle, 5 s ceiling
        }
        if (wait <= 0L) startSave()
        else {
            publishSave()
            if (!timerArmed) {
                timerArmed = true
                env.later(wait) { env.model.execute { timerArmed = false; if (needsSave && !saving && !released && !retryHalted) scheduleSave(false) } }
            }
        }
    }

    private fun startSave() {
        needsSave = false; saving = true; saveAgain = false; firstEditAt = -1L; lastEditAt = -1L
        lastSaveStart = env.clock()
        lastModified = maxOf(env.clock(), lastModified + 1)
        val base = history.document
        collectTiles()
        val tiles = HashMap<String, LinkedHashMap<TileKey, ByteArray?>>(); for ((d, t) in pendingTiles) tiles[d] = LinkedHashMap(t)
        pendingTiles.clear(); inFlightTiles = tiles
        val doc = base.copy(modified = lastModified, selection = if (selection.isEmpty()) null else SelectionRef(SELECTION_DIR), layers = base.layers.map { if (it is Layer.Pixel) it.copy(pixelsFile = files[it.common.id]) else it })
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
                // free space first: a phone that is nearly full gets one clear message instead of a half written project
                val raw = snaps.values.sumOf { it.rgba.size.toLong() } + tiles.values.sumOf { m -> m.values.sumOf { (it?.size ?: 0).toLong() } }
                SpaceCheck.problem(fs.freeBytes(), SpaceCheck.saveEstimate(raw), SpaceCheck.SAVE_MARGIN_BYTES)?.let { throw NoSpaceException(it) }
                for ((dir, t) in tiles) store.saveTiles(dir, t)   // tiles first, then the JSON that names them (kill safe, see ProjectStore.saveTiles)
                val saved = store.save(doc, { p -> snaps[p.common.id] }, changed)
                env.report("studio_autosave_ms", (env.nanos() - t0) / 1_000_000)
                env.model.execute { onSaved(saved, snaps) }
            } catch (t: Throwable) {
                env.error("studio save: ${t.javaClass.simpleName}: ${t.message}")
                env.model.execute { onSaveFailed(changed, snaps, t is NoSpaceException || (t is java.io.IOException && fs.freeBytes() < SpaceCheck.SAVE_MARGIN_BYTES)) }
            }
        }
    }

    /** Moves the tiles every mask and the selection changed into the queue for the next save. */
    private fun collectTiles() {
        for ((id, plane) in masks) { val t = plane.takeDirty(); if (t.isNotEmpty()) pendingTiles.getOrPut(LayerOps.maskDir(id)) { LinkedHashMap() }.putAll(t) }
        val t = selection.plane.takeDirty(); if (t.isNotEmpty()) pendingTiles.getOrPut(SELECTION_DIR) { LinkedHashMap() }.putAll(t)
    }

    private fun onSaved(saved: Document, snaps: Map<String, RawPixels>) {
        inFlightTiles = emptyMap()
        afterFirstSave?.let { hook ->
            afterFirstSave = null
            env.saver.execute { try { hook() } catch (t: Throwable) { env.error("studio after first save: ${t.javaClass.simpleName}: ${t.message}") } }
        }
        for (l in saved.layers) if (l is Layer.Pixel) files[l.common.id] = l.pixelsFile
        for ((id, px) in snaps) { inFlight.remove(id); if (unsaved[id] === px) unsaved.remove(id) }
        saving = false; saveFailed = false; noSpace = false; failStreak = 0; failureNotified = false; retryHalted = false; everSaved = true
        if (saveAgain || needsSave || dirty.isNotEmpty()) { val urgent = saveAgainNow; saveAgain = false; saveAgainNow = false; markDirty(now = urgent) } else publishSave()
        gauges()
        if (thumbSched.needsWrite()) armThumbTimer()
    }

    /**
     * BK-503: the work stays in memory (dirty and unsaved are kept). One notice per failure episode, the status strip says it for as long as it lasts, the retry delay doubles
     * (5, 10, 20, 40, 60 s) and after [SpaceCheck.MAX_TRIES] failures the timer stops until the user edits, pauses or leaves.
     */
    private fun onSaveFailed(changed: Set<String>, snaps: Map<String, RawPixels>, full: Boolean) {
        for ((id, px) in snaps) { inFlight.remove(id); if (id != activeId) unsaved.putIfAbsent(id, px) }
        // tiles that did not reach the disk go back in the queue, unless a newer version of the same tile is already queued
        for ((dir, t) in inFlightTiles) { val q = pendingTiles.getOrPut(dir) { LinkedHashMap() }; for ((k, v) in t) if (!q.containsKey(k)) q[k] = v }
        inFlightTiles = emptyMap()
        dirty += changed
        saving = false; saveFailed = true; noSpace = full; failStreak++; lastFailureAt = env.clock()
        if (!everSaved) fs.deleteTree(root)   // a first save that failed leaves no half written project folder behind
        if (!failureNotified) { failureNotified = true; toast(if (full) SpaceCheck.SAVE_FAILED_FULL else SpaceCheck.SAVE_FAILED_OTHER) }
        if (failStreak >= SpaceCheck.MAX_TRIES) { retryHalted = true; needsSave = true; publishSave(); return }
        needsSave = true
        scheduleSave(false)
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

    private fun publishZoom() { _state.update { it.copy(zoomPercent = Math.round(view.zoom * 100f), view = view) } }

    private fun saveStateNow() = when {
        saveFailed && noSpace -> SaveState.NO_SPACE
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
                maskThumbs = maskThumbs.filterKeys { k -> k in ids }.toMap(),
                nonBlank = ids.filter { id -> id !in blank }.toSet(),
                save = saveStateNow(),
            )
        }
    }

    /** The visible layers bottom to top as 7 floats each (slot, x, y, scale, opacity 0..1, blend id, mask mode 0 none or off / 1 mask / 2 inverted): what the compositor is given for a frame, an export strip or a thumbnail. */
    private fun layerSpec(d: Document): FloatArray {
        val out = ArrayList<Float>(d.layers.size * 7)
        for (l in d.layers) {
            if (!l.common.visible) continue
            val slot = slots[l.common.id] ?: continue
            val m = l.common.mask
            val maskMode = if (m != null && m.enabled && l.common.id in gpuMasks) (if (m.inverted) 2 else 1) else 0
            out += slot.toFloat(); out += l.common.x.toFloat(); out += l.common.y.toFloat(); out += l.common.scale; out += l.common.opacity / 100f; out += l.common.blend.id.toFloat(); out += maskMode.toFloat()
        }
        return out.toFloatArray()
    }

    private fun updateFrame() {
        val d = curDoc()
        gl.setFrame(FrameSpec(layerSpec(d), view.x, view.y, view.zoom, d.width, d.height))
        gl.requestRender()
    }

    // ---- flatten export and thumbnail input (S1c) -----------------------------------------------------------------------------------------

    /**
     * The document as it is now, in the form the compositor takes. Waits for the model thread, so call it from a worker (never the main thread, never the model or GL thread).
     * Null when the project is not open yet, was released, or the model thread did not answer in [timeoutMs].
     */
    fun exportSnapshot(timeoutMs: Long = 5_000): ExportSnapshot? {
        if (released) return null
        val box = arrayOfNulls<ExportSnapshot>(1)
        val done = CountDownLatch(1)
        env.model.execute {
            try {
                if (_state.value.phase == Phase.READY) { val d = curDoc(); box[0] = ExportSnapshot(d.name, d.width, d.height, d.colourSpace, layerSpec(d)) }
            } catch (t: Throwable) { env.error("studio snapshot: ${t.javaClass.simpleName}: ${t.message}") } finally { done.countDown() }
        }
        return if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) box[0] else null
    }

    /**
     * Renders rows [y, y + rows) of the whole canvas at zoom 1 (one strip of a flatten export): straight RGBA8, `snap.width * rows * 4` bytes. Worker thread only, like [exportSnapshot].
     * Null when the GPU is gone or failed. Keep [rows] the same for every strip but the last: a new output size reallocates the compositor's ping-pong pair.
     */
    fun renderStrip(snap: ExportSnapshot, y: Int, rows: Int): ByteArray? = renderView(snap, 0f, y.toFloat(), 1f, snap.width, rows)

    /** The whole canvas scaled to fit [maxEdge] on its long side (project thumbnail). Returns (width, height, RGBA8) or null. */
    fun renderThumbnail(snap: ExportSnapshot, maxEdge: Int, timeoutMs: Long = 60_000): Triple<Int, Int, ByteArray>? {
        val zoom = minOf(1f, maxEdge.toFloat() / maxOf(snap.width, snap.height))
        val w = maxOf(1, Math.round(snap.width * zoom)); val h = maxOf(1, Math.round(snap.height * zoom))
        val px = renderView(snap, 0f, 0f, zoom, w, h, timeoutMs) ?: return null
        return Triple(w, h, px)
    }

    private fun renderView(snap: ExportSnapshot, vx: Float, vy: Float, zoom: Float, w: Int, h: Int, timeoutMs: Long = 60_000): ByteArray? {
        if (released) return null
        val out = ByteArray(w * h * 4)
        val ok = gpuCall(timeoutMs) { it.render(snap.layers, vx, vy, zoom, w, h, out) } == true
        return if (ok) out else null
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
        const val SAVE_EVERY_MS = 5_000L   // ceiling from the first unsaved edit
        const val IDLE_MS = 1_500L
        /** Tile directory of the saved selection (schema v2). */
        const val SELECTION_DIR = "sel"
    }
}
