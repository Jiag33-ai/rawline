package app.rawline.feature.masking

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import app.rawline.core.cache.MaskStore
import app.rawline.core.cache.PerfLog
import app.rawline.core.ml.MaskAi
import app.rawline.core.model.BrushStroke
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskComponent
import app.rawline.core.model.MaskOp
import app.rawline.core.model.MaskType
import app.rawline.core.render.EditorSession
import app.rawline.core.render.P
import app.rawline.feature.editor.EditorState
import app.rawline.feature.editor.EditorTab
import app.rawline.feature.editor.PhotoMapper
import app.rawline.feature.editor.ToolGestures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Masking tab, on-photo handles and brush. One instance per open photo.
 *
 * Threading: the UI and the gesture callbacks run on the main thread. Everything that keeps the saved images and the GPU layers in step
 * with the recipe ([persist], [restore]) goes through one [LatestWinsSerial] worker, so it never runs twice at once and the newest
 * recipe always wins. [BrushLayer] is internally synchronised, so painting and the worker may touch the same layer safely.
 *
 * @param reference software bitmap of the screen preview, used by edge-aware brush
 */
class MaskingFeature(
    internal val state: EditorState,
    private val session: EditorSession,
    private val store: MaskStore,
    private val scope: CoroutineScope,
    private val reference: suspend (Int) -> Bitmap?,
    internal val ai: MaskAi? = null,
) {
    val ui = MaskUi()

    // ---------------------------------------------------------------- brush layers and persistence

    private val brushLayers = HashMap<String, BrushLayer>()
    private class Ref(val w: Int, val h: Int, val px: IntArray)
    @Volatile private var ref: Ref? = null
    /** What each layer currently holds on the GPU / in its saved PNG, as a [LayerPlan.brushSignature]. */
    private val uploaded = ConcurrentHashMap<String, Int>()
    private val saved = ConcurrentHashMap<String, Int>()

    private fun layerDims() = BrushLayer.dims(session.baseAspect())

    /** The layer for [key] at the current frame size. A new or resized layer (for example after a rotate) is redrawn from [strokes]. */
    private fun brushLayer(key: String, strokes: List<BrushStroke>): BrushLayer {
        val (w, h) = layerDims()
        synchronized(brushLayers) {
            brushLayers[key]?.let { if (it.w == w && it.h == h) return it }
            val r = ref
            val l = BrushLayer(w, h, if (r != null && r.w == w && r.h == h) r.px else null)
            if (strokes.isNotEmpty()) l.renderAll(strokes)
            brushLayers[key] = l
            return l
        }
    }

    /** Renders the frame at layer size so the edge-aware brush has a picture to compare colours against. */
    fun ensureReference() {
        scope.launch(Dispatchers.Default) {
            val (w, h) = layerDims()
            val cur = ref
            if (cur != null && cur.w == w && cur.h == h) return@launch
            val b = reference(maxOf(w, h)) ?: return@launch
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
            ref = Ref(w, h, px)
            synchronized(brushLayers) { brushLayers.values.forEach { it.reference = px } }
        }
    }

    private class SyncReq(val recipe: EditRecipe, val force: Boolean)

    private val serial = LatestWinsSerial<SyncReq>(
        // NonCancellable: a save that has been asked for finishes even if the editor is left straight after the last stroke
        launch = { r -> scope.launch(Dispatchers.Default + NonCancellable) { r.run() } },
        merge = { old, n -> SyncReq(n.recipe, n.force || old?.force == true) },
        onError = { PerfLog.error("masks sync: ${it.message}") },
        work = { sync(it.recipe, it.force) },
    )

    /**
     * Keeps the saved alpha images of brush masks and the GPU layers in step with the recipe (after commits, undo, redo). Export reads
     * the saved images, so the exported mask is the very image the screen used. Runs one at a time, newest recipe wins.
     */
    fun persist(recipe: EditRecipe) = serial.submit(SyncReq(recipe, false))

    /** Re-creates GPU layers for every bitmap mask after opening a photo. */
    fun restore(recipe: EditRecipe) = serial.submit(SyncReq(recipe, true))

    private fun sync(recipe: EditRecipe, force: Boolean) {
        prune(recipe)
        val live = session.layerIndex
        recipe.masks.flatMap { it.components }.filter { it.type == MaskType.BITMAP }.forEach { c ->
            val key = c.layerKey ?: return@forEach
            if (MaskRules.isBrush(c)) syncBrush(key, c.strokes, force)
            // An AI layer is never edited: it only needs to come back from its saved image when the slot was freed (undo of a delete) or on open.
            else if (force || key !in live) store.load(key)?.let { (a, w, h) -> upload(key, a, w, h) }
        }
    }

    private fun syncBrush(key: String, strokes: List<BrushStroke>, force: Boolean) {
        val l = brushLayer(key, strokes)
        val hash = strokes.hashCode()
        // Pixels differ from the recipe (undo, redo, resize): redraw. Never while a finger is painting on this layer; that commit syncs again.
        if (l.syncedStrokes != hash && !l.renderAllIfIdle(strokes)) return
        if (l.drawing) return
        val sig = LayerPlan.brushSignature(hash, l.w, l.h)
        if (force || uploaded[key] != sig || key !in session.layerIndex) { upload(key, l.snapshot(), l.w, l.h); uploaded[key] = sig }
        if (saved[key] != sig) {
            // on open the saved image is already right; do not re-encode every brush just to be sure
            if (!(force && saved[key] == null && store.file(key).exists())) store.save(key, l.snapshot(), l.w, l.h)
            saved[key] = sig
        }
    }

    /** Gives back the GPU layer slots (there are only a few) of masks that no longer exist: after delete, undo, redo, reset. */
    private fun prune(recipe: EditRecipe) {
        LayerPlan.unused(session.layerIndex.keys, recipe).forEach { k ->
            session.removeLayer(k)
            synchronized(brushLayers) { brushLayers.remove(k) }
            uploaded.remove(k); saved.remove(k)
        }
    }

    private fun upload(key: String, bytes: ByteArray, w: Int, h: Int) = session.setLayer(key, bytes, w, h)

    // ---------------------------------------------------------------- coordinates

    /**
     * The crop rectangle (cropX, cropY, cropW, cropH of the returned Geometry) that is actually rendered. This is the ONLY place the
     * masking code reads crop fields, so the view <-> frame mapping, brush ring and default gradient placement all follow it.
     * TODO(PM): after merging the edge fix, return the session's effective crop (EditorSession.effectiveCrop / Geo.constrainGeometry)
     * here instead of the recipe's own fields.
     */
    internal fun cropRect(): app.rawline.core.model.Geometry = state.recipe.geometry

    private fun pf(p: Offset): V2 {
        val g = cropRect()
        return V2(g.cropX + p.x * g.cropW, g.cropY + p.y * g.cropH)
    }
    private fun toOut(f: V2): V2 {
        val g = cropRect()
        return V2((f.x - g.cropX) / g.cropW, (f.y - g.cropY) / g.cropH)
    }
    /** View position (px) of a frame position. */
    internal fun viewOf(mapper: PhotoMapper, f: V2): V2 { val o = toOut(f); val v = mapper.toView(o.x, o.y); return V2(v.x, v.y) }
    private fun frameOf(mapper: PhotoMapper, pos: Offset): V2? = mapper.toImage(pos.x, pos.y)?.let { pf(it) }
    internal fun aspect() = session.baseAspect()
    private fun dp(v: Float) = v * ui.density

    // ---------------------------------------------------------------- selection and navigation

    internal val masks get() = state.recipe.masks
    internal val selIndex get() = MaskRules.indexOf(state.recipe, ui.selectedId)
    internal val selMask: Mask? get() = masks.getOrNull(selIndex)
    internal val selComp: MaskComponent? get() = selMask?.components?.getOrNull(ui.selectedComp)

    internal fun effectiveOverlay(): Boolean = ui.overlayPref ?: (ui.sub == "mask")

    private var lastShown = -2

    /** Points the red tint at the selected mask (an index into all masks; RenderParams maps it to the visible ones). */
    internal fun syncOverlay() {
        val idx = selIndex
        val want = if (ui.active && ui.page == MaskPage.EDIT && idx >= 0 && effectiveOverlay()) idx else -1
        if (want != lastShown) { lastShown = want; session.setShowMask(want) }
    }

    /** Called when the recipe changes under us (undo, redo, reset, delete): drops a selection that no longer exists. */
    internal fun reconcile() {
        val (id, part) = MaskRules.reconcile(ui.selectedId, ui.selectedComp, masks)
        if (id != ui.selectedId) { ui.selectedId = id; if (ui.page == MaskPage.EDIT) ui.page = MaskPage.LIST; ui.renaming = false; cancelPicks() }
        if (part != ui.selectedComp) ui.selectedComp = part
    }

    internal fun select(id: String, part: Int = 0) {
        cancelPicks()
        ui.selectedId = id; ui.selectedComp = part; ui.sub = "mask"; ui.page = MaskPage.EDIT; ui.renaming = false; ui.toast = null
    }

    internal fun selectPart(i: Int) { cancelPicks(); ui.selectedComp = i }

    internal fun toList() { cancelWork(); ui.renaming = false; ui.page = MaskPage.LIST }

    internal fun openPicker() {
        cancelPicks(); ui.renaming = false
        ui.pickFrom = if (ui.page == MaskPage.EDIT && selMask != null) MaskPage.EDIT else MaskPage.LIST
        ui.page = MaskPage.PICK
    }

    internal fun closePicker() {
        cancelWork()
        ui.page = if (ui.pickFrom == MaskPage.EDIT && selMask != null) MaskPage.EDIT else MaskPage.LIST
    }

    /** Back inside the tray. Returns false when there is nothing to step out of, so the editor can close the panel. */
    internal fun back(): Boolean {
        if (ui.busy != null || ui.pickingObject || ui.pickingColour) { cancelWork(); return true }
        if (ui.renaming) { ui.renaming = false; return true }
        return when (ui.page) {
            MaskPage.EDIT -> { toList(); true }
            MaskPage.PICK -> if (masks.isEmpty()) false else { closePicker(); true }
            MaskPage.LIST -> false
        }
    }

    // ---------------------------------------------------------------- creating, editing, deleting

    internal fun notify(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) { ui.toast = MaskToast(text, actionLabel, action) }

    private fun limitMessage(r: EditRecipe, newLayers: Int) =
        if (r.masks.size >= P.MAX_MASKS) "A photo can have ${P.MAX_MASKS} masks. Delete one to add another."
        else "Too many brush and AI parts on this photo (${P.MAX_LAYERS} at most). Delete one first."

    internal fun create(tool: MaskTool) {
        val r = state.recipe
        val kind = tool.kind
        if (kind == null) {
            if (ai == null) { notify("AI masks are not available on this device."); return }
            if (!MaskRules.canAddMask(r, 1)) { notify(limitMessage(r, 1)); return }
            if (tool == MaskTool.OBJECT) { startObjectPick(AiTarget.NewMask) } else runAiTool(tool, AiTarget.NewMask)
            return
        }
        val need = if (kind == MaskKind.BRUSH) 1 else 0
        if (!MaskRules.canAddMask(r, need)) { notify(limitMessage(r, need)); return }
        val m = MaskFactory.mask(kind, MaskRules.nextName(tool.nameBase, r.masks), cropRect())
        state.edit("Add ${tool.label} mask") { it.copy(masks = it.masks + m) }
        select(m.id)
        if (kind == MaskKind.COLOR) ui.pickingColour = true
    }

    /** Adds a part to the selected mask, combined with [MaskUi.nextOp]. */
    internal fun addPart(tool: MaskTool) {
        val id = ui.selectedId ?: return
        val r = state.recipe
        val kind = tool.kind
        val need = if (kind == null || kind == MaskKind.BRUSH) 1 else 0
        if (!MaskRules.canAddPart(r, id, need)) {
            notify(if ((selMask?.components?.size ?: 0) >= MaskRules.MAX_PARTS) "A mask can have ${MaskRules.MAX_PARTS} parts." else limitMessage(r, need)); return
        }
        if (kind == null) {
            if (ai == null) { notify("AI masks are not available on this device."); return }
            val target = AiTarget.Part(id, ui.nextOp)
            if (tool == MaskTool.OBJECT) startObjectPick(target) else runAiTool(tool, target)
            return
        }
        val c = MaskFactory.component(kind, ui.nextOp, crop = cropRect())
        state.edit("Add mask part") { MaskRules.addPart(it, id, c) }
        ui.selectedComp = selMask?.components?.lastIndex ?: 0
        if (kind == MaskKind.COLOR) ui.pickingColour = true
    }

    internal fun deleteMask(id: String) {
        if (MaskRules.indexOf(state.recipe, id) < 0) return
        cancelPicks()
        state.edit("Delete mask") { MaskRules.deleteMask(it, id) }
        ui.selectedId = null; ui.selectedComp = 0; ui.renaming = false; ui.page = MaskPage.LIST
        notify("Mask deleted", "Undo") { state.undo(); ui.toast = null }
    }

    internal fun duplicateMask(id: String) {
        val r = state.recipe
        val m = r.masks.firstOrNull { it.id == id } ?: return
        val newLayers = m.components.count { MaskRules.isBrush(it) }
        if (!MaskRules.canAddMask(r, newLayers)) { notify(limitMessage(r, newLayers)); return }
        val d = MaskFactory.duplicate(m, MaskRules.nextName(m.name.replace(Regex(" \\d+$"), ""), r.masks))
        // the copy's brush layers are drawn from its strokes by the persist step that the commit triggers
        state.edit("Duplicate mask") { it.copy(masks = it.masks + d) }
        select(d.id)
    }

    internal fun toggleVisible(id: String) = state.edit("Show or hide mask") { r -> MaskRules.mapMask(r, id) { it.copy(visible = !it.visible) } }
    internal fun toggleInvert(id: String) = state.edit("Invert mask") { r -> MaskRules.mapMask(r, id) { it.copy(invert = !it.invert) } }
    internal fun rename(id: String, raw: String) { state.edit("Rename mask") { MaskRules.rename(it, id, raw) } }
    internal fun setPartOp(id: String, part: Int, op: MaskOp) = state.edit("Mask part ${op.name.lowercase()}") { MaskRules.setPartOp(it, id, part, op) }
    internal fun togglePartInvert(id: String, part: Int) = state.edit("Invert part") { MaskRules.togglePartInvert(it, id, part) }
    internal fun removePart(id: String, part: Int) {
        cancelPicks()
        state.edit("Remove mask part") { MaskRules.removePart(it, id, part) }
        reconcile()
    }

    internal fun toggleOverlay() { ui.overlayPref = !effectiveOverlay() }

    /**
     * Takes back the last brush stroke. When that stroke is the newest history step it is a plain Undo (so Redo brings it back);
     * otherwise it is a new edit that removes it.
     */
    internal fun undoStroke() {
        val id = ui.selectedId ?: return
        val ci = ui.selectedComp
        val cur = state.recipe
        if (MaskRules.part(cur, id, ci)?.strokes.isNullOrEmpty()) return
        val without = MaskRules.removeLastStroke(cur, id, ci)
        if (state.canUndo && state.history[state.historyIndex - 1].recipe == without) state.undo()
        else state.edit("Undo brush stroke") { MaskRules.removeLastStroke(it, id, ci) }
    }

    internal fun clearBrush() {
        val id = ui.selectedId ?: return
        val ci = ui.selectedComp
        state.edit("Clear brush") { MaskRules.mapPart(it, id, ci) { c -> c.copy(strokes = emptyList()) } }
    }

    internal fun brushPreview() { ui.brushPreviewTick++ }

    internal fun startColourPick() { cancelPicks(); ui.pickingColour = true }

    // ---------------------------------------------------------------- AI

    private var aiJob: Job? = null

    private fun startObjectPick(target: AiTarget) { cancelWork(); ui.aiTarget = target; ui.pickingObject = true; ui.toast = null }

    /** Stops a running AI job and any "tap the photo" mode. */
    internal fun cancelWork() {
        aiJob?.cancel(); aiJob = null
        ui.busy = null; ui.pickingObject = false; ui.pickingColour = false
    }

    private fun cancelPicks() { ui.pickingObject = false; ui.pickingColour = false; if (ui.busy != null) { aiJob?.cancel(); aiJob = null; ui.busy = null } }

    private suspend fun <T> guarded(block: suspend () -> T?): T? =
        try { withContext(Dispatchers.Default) { block() } } catch (e: CancellationException) { throw e } catch (t: Throwable) { PerfLog.error("mask ai: ${t.message}"); null }

    private fun runAiTool(tool: MaskTool, target: AiTarget, at: V2? = null) {
        val ai = ai ?: return
        if (ui.busy != null) return
        ui.toast = null
        ui.busy = "Finding ${tool.label.lowercase()}"
        aiJob = scope.launch {
            val (w, h) = layerDims()
            if (tool == MaskTool.PEOPLE) {
                val parts = guarded { ai.people(w, h) }.orEmpty().filter { (_, a) -> a.any { it.toInt() != 0 } }
                ui.busy = null
                if (parts.isEmpty()) { notify("No people found in this photo."); return@launch }
                val r = state.recipe
                val room = minOf(P.MAX_MASKS - r.masks.size, P.MAX_LAYERS - MaskRules.layerKeys(r).size)
                if (room <= 0) { notify(limitMessage(r, 1)); return@launch }
                var last: String? = null
                parts.take(room).forEach { (n, a) -> last = addAiResult(n, a, w, h, AiTarget.NewMask, selectIt = false) }
                last?.let { select(it) }
                if (parts.size > room) notify("Added $room of ${parts.size} people. That is the limit for this photo.")
                return@launch
            }
            val out: ByteArray? = guarded {
                when (tool) {
                    MaskTool.SUBJECT -> ai.subject(w, h)
                    MaskTool.SKY -> ai.sky(w, h)
                    MaskTool.BACKGROUND -> ai.subject(w, h)?.let { a -> if (a.all { it.toInt() == 0 }) a else ByteArray(a.size) { i -> (255 - (a[i].toInt() and 255)).toByte() } }
                    MaskTool.OBJECT -> at?.let { ai.objectAt(it.x, it.y, w, h) }
                    else -> null
                }
            }
            ui.busy = null
            // for Background an empty subject comes back unchanged (all zero), so "empty" means nothing was found either way
            if (out == null || out.all { it.toInt() == 0 }) notify("Couldn't find ${tool.label.lowercase()} in this photo. Try the brush instead.")
            else addAiResult(tool.label, out, w, h, target, selectIt = true)
        }
    }

    /** Saves the computed alpha and adds it as a new mask or as a part. Returns the mask id it ended up in. */
    private suspend fun addAiResult(label: String, alpha: ByteArray, w: Int, h: Int, target: AiTarget, selectIt: Boolean): String? {
        val key = "ai_${MaskFactory.newId()}"
        withContext(Dispatchers.IO) { store.save(key, alpha, w, h) }
        var id: String? = null
        when (target) {
            AiTarget.NewMask -> {
                val r = state.recipe
                if (!MaskRules.canAddMask(r, 1)) { notify(limitMessage(r, 1)); return null }
                val m = Mask(MaskFactory.newId(), MaskRules.nextName(label, r.masks), listOf(MaskFactory.aiPart(label, key)))
                state.edit("Add $label mask") { it.copy(masks = it.masks + m) }
                id = m.id
            }
            is AiTarget.Part -> {
                val r = state.recipe
                if (!MaskRules.canAddPart(r, target.maskId, 1)) { notify("That mask has changed. Add the part again."); return null }
                state.edit("Add $label part") { MaskRules.addPart(it, target.maskId, MaskFactory.aiPart(label, key, target.op)) }
                id = target.maskId
            }
        }
        upload(key, alpha, w, h)
        if (selectIt) {
            val part = if (target is AiTarget.Part) (state.recipe.masks.firstOrNull { it.id == id }?.components?.lastIndex ?: 0) else 0
            select(id!!, part)
        }
        return id
    }

    // ---------------------------------------------------------------- tab

    /** The tray came on screen: tint allowed, edge-aware brush reference and the AI models warming up. */
    internal fun onEnter() {
        ui.active = true
        ensureReference()
        if (ai != null) scope.launch(Dispatchers.Default) { runCatching { ai.prepare() } }
    }

    /** Leaving the masking tool (switch, close, Back) removes the tint, stops AI work and drops every pick mode so nothing leaks into the next tool. */
    fun onExit() {
        cancelWork()
        ui.clearTransient()
        ui.active = false
        ui.selectedId = null; ui.selectedComp = 0; ui.page = MaskPage.LIST; ui.pickFrom = MaskPage.LIST; ui.sub = "mask"; ui.overlayPref = null
        lastShown = -2
        session.setShowMask(-1)
    }

    val tab = EditorTab("masking", "Masking", onExit = ::onExit) { MaskTray(this) }

    @Composable
    fun BoxScope.Overlay(tabId: String, mapper: PhotoMapper) {
        if (tabId != "masking") return
        MaskOverlay(this@MaskingFeature, mapper)
    }

    // ---------------------------------------------------------------- on photo

    private var lastUpload = 0L

    /** One finger input on the photo for the active tab, or null when the tab has none (a tap then closes the panel, as for other tools). */
    fun gestures(tabId: String, mapper: PhotoMapper): ToolGestures? {
        if (tabId != "masking") return null
        if (ui.pickingObject) return objectPicker(mapper)
        if (ui.page != MaskPage.EDIT) return null
        val m = selMask ?: return null
        val c = selComp ?: return null
        val id = m.id; val ci = ui.selectedComp
        return when (c.type) {
            MaskType.BITMAP -> if (MaskRules.isBrush(c)) c.layerKey?.let { brushGestures(mapper, id, ci, it) } else null
            MaskType.LINEAR -> linearGestures(mapper, id, ci)
            MaskType.RADIAL -> radialGestures(mapper, id, ci)
            MaskType.COLOR -> if (ui.pickingColour) colourPicker(mapper, id, ci) else null
            MaskType.LUMINANCE -> null
        }
    }

    private fun setParams(id: String, ci: Int, p: List<Float>) = state.live { r -> MaskRules.mapPart(r, id, ci) { c -> c.copy(params = p) } }

    private fun endDrag(moved: Boolean, cancelled: Boolean, label: String) {
        ui.dragHandle = -1
        if (!moved) return
        if (cancelled) state.jump(state.historyIndex) else state.commit(label)
    }

    private fun linearGestures(mapper: PhotoMapper, id: String, ci: Int): ToolGestures {
        var part: LinearPart? = null
        var start: List<Float> = emptyList()
        var grab = V2(0f, 0f)
        var moved = false
        return ToolGestures(
            onDown = { pos ->
                val c = MaskRules.part(state.recipe, id, ci)
                if (c == null || c.type != MaskType.LINEAR) false else {
                    val q = LinearGeom.padded(c.params)
                    val a = viewOf(mapper, V2(q[0], q[1])); val b = viewOf(mapper, V2(q[2], q[3]))
                    val hit = LinearGeom.hit(a, b, V2(pos.x, pos.y), dp(32f), dp(22f))
                    part = hit; start = q; moved = false
                    // where the finger is in the frame; a handle at the very edge of the photo may read as outside, so fall back to the handle itself
                    grab = frameOf(mapper, pos) ?: when (hit) { LinearPart.END -> V2(q[2], q[3]); else -> V2(q[0], q[1]) }
                    ui.dragHandle = when (hit) { LinearPart.START -> 0; LinearPart.END -> 1; LinearPart.MOVE -> 2; null -> -1 }
                    hit != null
                }
            },
            onMove = { pos ->
                val f = frameOf(mapper, pos)
                val p = part
                if (f != null && p != null) {
                    val dx = f.x - grab.x; val dy = f.y - grab.y
                    val np = when (p) {
                        LinearPart.START -> LinearGeom.moveEnd(start, p, start[0] + dx, start[1] + dy)
                        LinearPart.END -> LinearGeom.moveEnd(start, p, start[2] + dx, start[3] + dy)
                        LinearPart.MOVE -> LinearGeom.translate(start, dx, dy)
                    }
                    moved = true
                    setParams(id, ci, np)
                }
            },
            onUp = { cancelled -> part = null; endDrag(moved, cancelled, "Move gradient") },
        )
    }

    private fun radialGestures(mapper: PhotoMapper, id: String, ci: Int): ToolGestures {
        var part: RadialPart? = null
        var grab: RadialGeom.Grab? = null
        var moved = false
        val asp = aspect()
        return ToolGestures(
            onDown = { pos ->
                val c = MaskRules.part(state.recipe, id, ci)
                if (c == null || c.type != MaskType.RADIAL) false else {
                    val q = RadialGeom.padded(c.params)
                    val v = RadialGeom.viewHandles(q, asp, dp(40f)) { viewOf(mapper, it) }
                    val f = frameOf(mapper, pos)
                    val hit = RadialGeom.hit(v, V2(pos.x, pos.y), dp(30f), f != null && RadialGeom.contains(q, asp, f))
                    part = hit; moved = false
                    grab = if (hit != null) RadialGeom.grab(hit, q, f ?: V2(q[0], q[1]), asp) else null
                    ui.dragHandle = hit?.ordinal ?: -1
                    hit != null
                }
            },
            onMove = { pos ->
                val f = frameOf(mapper, pos)
                val p = part; val g = grab
                if (f != null && p != null && g != null) { moved = true; setParams(id, ci, RadialGeom.drag(p, g, f, asp)) }
            },
            onUp = { cancelled -> part = null; grab = null; endDrag(moved, cancelled, "Move radial") },
        )
    }

    private fun colourPicker(mapper: PhotoMapper, id: String, ci: Int) = ToolGestures(
        onDown = { pos ->
            val p = mapper.toImage(pos.x, pos.y)
            if (p != null) scope.launch {
                val rgb = session.sample(p.x, p.y)
                // the picking mode may have been cancelled while the sample was read back
                if (rgb != null && ui.pickingColour) {
                    // The shader compares gamma encoded colours of the edited image at that point
                    state.edit("Pick colour") { r -> MaskRules.mapPart(r, id, ci) { c -> MaskRules.withParam(MaskRules.withParam(MaskRules.withParam(c, 0, rgb[0]), 1, rgb[1]), 2, rgb[2]) } }
                }
                ui.pickingColour = false
            }
            true
        },
        onMove = {}, onUp = {},
    )

    private fun objectPicker(mapper: PhotoMapper) = ToolGestures(
        onDown = { pos ->
            val f = frameOf(mapper, pos)
            if (f != null && ui.pickingObject) {
                ui.pickingObject = false
                runAiTool(MaskTool.OBJECT, ui.aiTarget, f)
            }
            true
        },
        onMove = {}, onUp = {},
    )

    private fun brushGestures(mapper: PhotoMapper, id: String, ci: Int, key: String): ToolGestures {
        // points are collected in one growing list that the layer reads directly; it is frozen into the recipe only when the finger lifts
        val pts = ArrayList<Float>()
        var stroke: BrushStroke? = null
        var layer: BrushLayer? = null
        var lastX = 0f; var lastY = 0f
        val asp = aspect()
        return ToolGestures(
            onDown = { pos ->
                val f = frameOf(mapper, pos)
                if (f == null) false else {
                    val strokes = MaskRules.part(state.recipe, id, ci)?.strokes ?: emptyList()
                    val l = brushLayer(key, strokes)
                    pts.clear(); pts.add(f.x); pts.add(f.y); lastX = f.x; lastY = f.y
                    val s = BrushStroke(pts, ui.brushSize, ui.brushFeather, ui.brushFlow, ui.brushErase, ui.brushAuto)
                    stroke = s; layer = l
                    l.beginStroke(s); l.update(s)
                    upload(key, l.snapshot(), l.w, l.h)
                    lastUpload = System.currentTimeMillis()
                    ui.brushCursor = pos
                    true
                }
            },
            onMove = { pos ->
                ui.brushCursor = pos
                val f = frameOf(mapper, pos)
                val s = stroke; val l = layer
                if (f != null && s != null && l != null && BrushMath.farEnough(lastX, lastY, f.x, f.y, asp)) {
                    pts.add(f.x); pts.add(f.y); lastX = f.x; lastY = f.y
                    l.update(s)
                    val now = System.currentTimeMillis()
                    if (now - lastUpload > 40) { lastUpload = now; upload(key, l.snapshot(), l.w, l.h) }
                }
            },
            onUp = { cancelled ->
                ui.brushCursor = null
                val s = stroke; val l = layer
                stroke = null; layer = null
                if (s != null && l != null) {
                    l.endStroke()
                    val cur = MaskRules.part(state.recipe, id, ci)?.strokes
                    if (cancelled || cur == null) {
                        // put the layer back to what the recipe says
                        l.renderAll(cur ?: emptyList()); upload(key, l.snapshot(), l.w, l.h)
                    } else {
                        val done = s.copy(points = pts.toList())
                        val next = cur + done
                        // mark first: the commit below wakes the persist step, which must see these pixels as already in step with the recipe
                        l.markSynced(next)
                        upload(key, l.snapshot(), l.w, l.h)
                        uploaded[key] = LayerPlan.brushSignature(next.hashCode(), l.w, l.h)
                        state.edit("Brush stroke") { r -> MaskRules.mapPart(r, id, ci) { c -> c.copy(strokes = c.strokes + done) } }
                    }
                }
            },
        )
    }

    /** Test hook and diagnostics: whether the persist worker has nothing queued or running. */
    internal val syncIdle: Boolean get() = serial.isIdle
}
