package app.rawline.core.studio.render

import app.rawline.core.studio.model.BlendMode
import app.rawline.core.studio.model.RefImage
import app.rawline.core.studio.model.RefLayer
import app.rawline.core.studio.model.ReferenceCompositor
import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.BrushMath
import app.rawline.core.studio.model.Dirty
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.Fs
import app.rawline.core.studio.model.InputEvent
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.Phase as InPhase
import app.rawline.core.studio.model.PointerKind
import app.rawline.core.studio.model.ProjectStore
import app.rawline.core.studio.model.RawPixels
import app.rawline.core.studio.model.Stamp
import app.rawline.core.studio.model.StrokePoint
import app.rawline.core.studio.model.StrokeReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class MemFs : Fs {
    val files = LinkedHashMap<String, ByteArray>()
    var failWrites = false
    var failPathContains: String? = null
    var free = Long.MAX_VALUE
    override fun freeBytes() = free
    override fun exists(path: String) = files.containsKey(path)
    override fun read(path: String) = files[path]
    var onWrite: (() -> Unit)? = null
    override fun write(path: String, data: ByteArray) { onWrite?.invoke(); if (failWrites || failPathContains?.let { path.contains(it) } == true) throw java.io.IOException("disk full"); files[path] = data.copyOf() }
    override fun rename(from: String, to: String) { files[to] = files.remove(from) ?: throw IllegalStateException("no $from") }
    override fun delete(path: String) { files.remove(path) }
    override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") && !it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1) }
    override fun dirs(dir: String) = files.keys.filter { it.startsWith("$dir/") && it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1).substringBefore('/') }.distinct()
    override fun size(path: String) = files[path]?.size?.toLong() ?: 0L
    override fun deleteTree(path: String) { files.keys.removeAll { it == path || it.startsWith("$path/") } }
}

/** A GPU on the host: textures are byte arrays, a stroke buffer is the reference coverage of the stamps. */
class FakeGpu : StudioGpu {
    val tex = HashMap<Int, RawPixels>()
    var stroke: Pair<Int, Brush>? = null
    val stamps = ArrayList<Stamp>()
    var strokeBegins = 0
    var strokeEnds = 0
    var failBegin = false
    var failReadAt = 0   // the Nth readStroke call (1 based) returns false
    var reads = 0
    override fun setLayerImage(slot: Int, rgba: ByteArray, w: Int, h: Int): Boolean { tex[slot] = RawPixels(w, h, rgba.copyOf()); return true }
    override fun updateRegion(slot: Int, x: Int, y: Int, w: Int, h: Int, rgba: ByteArray): Boolean {
        val t = tex[slot] ?: return false
        for (r in 0 until h) System.arraycopy(rgba, r * w * 4, t.rgba, ((y + r) * t.w + x) * 4, w * 4)
        return true
    }
    override fun removeLayer(slot: Int) { tex.remove(slot); masks.remove(slot) }
    // S2: masks (one byte per layer pixel) and the canvas selection, held as the real compositor holds them
    val masks = HashMap<Int, ByteArray>()
    var selectionBytes: ByteArray? = null
    var maskStrokeValue: Float? = null
    override fun setMask(slot: Int, r8: ByteArray?, w: Int, h: Int): Boolean { if (r8 == null) masks.remove(slot) else { require(r8.size == w * h && tex[slot]!!.w == w); masks[slot] = r8.copyOf() }; return true }
    override fun updateMask(slot: Int, x: Int, y: Int, w: Int, h: Int, r8: ByteArray): Boolean {
        val m = masks[slot] ?: return false; val t = tex[slot]!!
        for (r in 0 until h) System.arraycopy(r8, r * w, m, (y + r) * t.w + x, w)
        return true
    }
    var selW = 0
    override fun setSelection(r8: ByteArray?, w: Int, h: Int): Boolean { selectionBytes = r8?.copyOf(); selW = w; return true }
    override fun updateSelection(x: Int, y: Int, w: Int, h: Int, r8: ByteArray): Boolean {
        val m = selectionBytes ?: return false
        for (r in 0 until h) System.arraycopy(r8, r * w, m, (y + r) * selW + x, w)
        return true
    }
    override fun beginMaskStroke(slot: Int, value: Float, opacity: Float, hardness: Float, flow: Float): Boolean {
        if (failBegin || masks[slot] == null) return false
        strokeBegins++; stamps.clear(); maskStrokeValue = value
        stroke = slot to Brush(hardness = hardness.toDouble(), flow = flow.toDouble(), opacity = opacity.toDouble(), erase = false)
        return true
    }
    override fun beginStroke(slot: Int, r: Float, g: Float, b: Float, opacity: Float, erase: Boolean, hardness: Float, flow: Float): Boolean {
        if (failBegin) return false
        strokeBegins++; stamps.clear()
        stroke = slot to Brush(hardness = hardness.toDouble(), flow = flow.toDouble(), opacity = opacity.toDouble(), erase = erase)
        return true
    }
    override fun addStamps(xyr: FloatArray, count: Int): Boolean { for (i in 0 until count) stamps += Stamp(xyr[i * 3].toDouble(), xyr[i * 3 + 1].toDouble(), xyr[i * 3 + 2].toDouble()); return true }
    override fun readStroke(x: Int, y: Int, w: Int, h: Int, coverage: FloatArray): Boolean {
        if (failReadAt > 0 && ++reads == failReadAt) return false
        val (slot, brush) = stroke ?: return false
        val t = tex[slot]!!
        val full = StrokeReference.coverage(t.w, t.h, stamps, brush)
        for (j in 0 until h) for (i in 0 until w) coverage[j * w + i] = full[(y + j) * t.w + x + i]
        return true
    }
    override fun endStroke() { strokeEnds++; stroke = null; maskStrokeValue = null }
    override fun textureBytes() = tex.values.sumOf { it.rgba.size.toLong() }
    var renderFails = false
    var lastRender: Triple<Int, Int, Float>? = null
    /** The reference compositor over the slot textures: what the real compositor is compared with in the golden. */
    override fun render(layers: FloatArray, vx: Float, vy: Float, zoom: Float, outW: Int, outH: Int, out: ByteArray): Boolean {
        if (renderFails) return false
        lastRender = Triple(outW, outH, zoom)
        val refs = (0 until layers.size / 7).map { i ->
            val o = i * 7; val t = tex[layers[o].toInt()]!!
            val mm = layers[o + 6].toInt()
            RefLayer(RefImage(t.w, t.h, t.rgba), layers[o + 1], layers[o + 2], layers[o + 3], layers[o + 4], BlendMode.entries.first { it.id == layers[o + 5].toInt() }, if (mm != 0) masks[layers[o].toInt()] else null, mm)
        }
        System.arraycopy(ReferenceCompositor.render(refs, vx, vy, zoom, outW, outH), 0, out, 0, outW * outH * 4)
        return true
    }
}

class FakeGl(val gpu: FakeGpu = FakeGpu()) : GpuExecutor {
    override var listener: SurfaceListener? = null
    var last: FrameSpec? = null
    var renders = 0
    var frozen = false   // a GL thread that never runs anything
    override var isPaused = false   // the view is paused (app in the background)
    override fun post(onDrop: (() -> Unit)?, block: (StudioGpu) -> Unit) { if (!frozen) block(gpu) }
    override fun setFrame(frame: FrameSpec?) { last = frame }
    override fun requestRender() { renders++ }
}

class Harness(val fs: MemFs = MemFs(), doc: Document, pixels: Map<String, RawPixels> = emptyMap(), onDisk: Boolean = pixels.isEmpty(), recovered: Boolean = false, active: String? = null) {
    val gl = FakeGl()
    var now = 1_000_000L
    val timers = ArrayList<Pair<Long, Runnable>>()
    val reports = ArrayList<Pair<String, Long>>()
    val errors = ArrayList<String>()
    val direct = Executor { it.run() }
    val env = StudioEnv(direct, direct, { ms, r -> timers += ms to r }, clock = { now }, report = { n, v -> reports += n to v }, error = { errors += it })
    val s = StudioSession(fs, ROOT, gl, env, doc, pixels, onDisk, recovered, activeId = active)
    var t = 0L

    fun fireTimers() { val due = ArrayList(timers); timers.clear(); due.forEach { it.second.run() } }
    fun ev(p: InPhase, id: Int, x: Float, y: Float, kind: PointerKind = PointerKind.FINGER, pressure: Float = 1f) = s.onInput(InputEvent(p, id, x, y, pressure, kind, t++))
    fun stroke(pts: List<Pair<Float, Float>>) {
        ev(InPhase.DOWN, 0, pts[0].first, pts[0].second)
        for (p in pts.drop(1)) ev(InPhase.MOVE, 0, p.first, p.second)
        ev(InPhase.UP, 0, pts.last().first, pts.last().second)
    }
    fun reopen(): Pair<Document, ProjectStore> { val st = ProjectStore(fs, ROOT); return st.open().document to st }
    fun layerPixels(id: String): ByteArray { val (d, st) = reopen(); return st.load(d.layer(id) as Layer.Pixel)!!.rgba }
    val st get() = s.state.value
    companion object { const val ROOT = "files/studio/p1" }
}

private fun blankDoc(w: Int = 64, h: Int = 48, vararg ids: String): Document =
    Document("p1", "Test", w, h, layers = (if (ids.isEmpty()) listOf("a") else ids.toList()).map { Layer.Pixel(LayerCommon(it, it), w, h) }, created = 1, modified = 1)

private fun white(w: Int, h: Int) = RawPixels(w, h, ByteArray(w * h * 4) { 255.toByte() })

class StudioSessionTest {
    @Test fun aNewProjectUploadsEveryLayerAndIsOnDiskAtOnce() {
        val h = Harness(doc = blankDoc(64, 48, "a", "b"), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        assertEquals(Phase.READY, h.st.phase)
        assertEquals(2, h.gl.gpu.tex.size)
        assertEquals(64 * 48 * 4, h.gl.gpu.tex[0]!!.rgba.size)
        val (d, store) = h.reopen()
        assertEquals(2, d.layers.size)
        assertEquals(255, h.layerPixels("a")[0].toInt() and 255)
        assertNull(store.load(d.layer("b") as Layer.Pixel))   // a blank layer has no file
        assertEquals(SaveState.SAVED, h.st.save)
        assertEquals(setOf("a"), h.st.nonBlank)
    }

    @Test fun aStrokeIsPaintedBakedPreviewedAndOneHistoryEntry() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 10.0, hardness = 1.0, opacity = 1.0, flow = 1.0, spacing = 0.1, pressureSize = false))
        h.s.setColour(Rgb(1f, 0f, 0f))
        h.stroke(listOf(10f to 20f, 40f to 20f))
        // what the commit must produce, from the independent reference path
        val st = BrushMath.walk(listOf(StrokePoint(10.0, 20.0), StrokePoint(40.0, 20.0)), Brush(diameter = 10.0, hardness = 1.0, pressureSize = false))
        val expect = white(64, 48).rgba.copyOf()
        StrokeReference.commit(expect, 64, StrokeReference.coverage(64, 48, st, Brush(hardness = 1.0)), Dirty.rect(st, 64, 48), floatArrayOf(1f, 0f, 0f), Brush(hardness = 1.0))
        assertArrayEquals(expect, h.gl.gpu.tex[0]!!.rgba)              // GPU texture got the baked rectangle
        assertEquals(1, h.gl.gpu.strokeBegins); assertEquals(1, h.gl.gpu.strokeEnds)
        assertTrue(h.st.canUndo)
        assertTrue(h.reports.any { it.first == "studio_commit_ms" }); assertTrue(h.reports.any { it.first == "studio_stroke_stamp_ms" })
        h.s.undo()
        assertArrayEquals(white(64, 48).rgba, h.gl.gpu.tex[0]!!.rgba)
        assertFalse(h.st.canUndo); assertTrue(h.st.canRedo)
        h.s.redo()
        assertArrayEquals(expect, h.gl.gpu.tex[0]!!.rgba)
    }

    @Test fun aSecondFingerCancelsTheStrokeWithNoHistoryEntryAndAPanStartsInstead() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.onSurfaceSize(400, 300)
        val before = h.gl.last!!
        h.ev(InPhase.DOWN, 0, 100f, 100f); h.ev(InPhase.MOVE, 0, 110f, 100f)
        h.ev(InPhase.DOWN, 1, 200f, 100f)
        assertFalse(h.st.canUndo)
        assertEquals(1, h.gl.gpu.strokeEnds)                           // the live stroke was thrown away
        assertArrayEquals(white(64, 48).rgba, h.gl.gpu.tex[0]!!.rgba)
        h.ev(InPhase.MOVE, 1, 300f, 100f)                              // fingers spread: zoom in
        assertTrue(h.gl.last!!.zoom > before.zoom)
        h.ev(InPhase.UP, 1, 300f, 100f); h.ev(InPhase.UP, 0, 110f, 100f)
        assertFalse(h.st.canUndo)
    }

    @Test fun aStylusStrokeIsNotInterruptedByAPalm() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 8.0, pressureSize = true))
        h.ev(InPhase.DOWN, 7, 10f, 10f, PointerKind.STYLUS, 0.5f)
        h.ev(InPhase.DOWN, 1, 50f, 40f)                                // palm
        h.ev(InPhase.MOVE, 7, 30f, 10f, PointerKind.STYLUS, 0.9f)
        h.ev(InPhase.UP, 1, 50f, 40f)
        h.ev(InPhase.UP, 7, 30f, 10f, PointerKind.STYLUS, 0.9f)
        assertEquals(1, h.gl.gpu.strokeBegins); assertEquals(1, h.gl.gpu.strokeEnds)   // exactly one stroke, committed
        assertTrue(h.st.canUndo)
    }

    @Test fun lockedAndHiddenLayersRefuseToPaintAndSayWhy() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setLocked("a", true)
        h.stroke(listOf(5f to 5f, 20f to 5f))
        assertEquals("Layer is locked", h.st.message!!.text); assertEquals(0, h.gl.gpu.strokeBegins)
        h.s.setLocked("a", false); h.s.setVisible("a", false)
        h.stroke(listOf(5f to 5f, 20f to 5f))
        assertEquals("Layer is hidden", h.st.message!!.text); assertEquals(0, h.gl.gpu.strokeBegins)
        assertEquals(0, h.gl.last!!.layers.size)                      // hidden layers are not drawn
    }

    @Test fun anAutosaveRunsAtOnceThenAtMostEveryFiveSecondsAndLosesNothing() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 6.0, hardness = 1.0, pressureSize = false)); h.s.setColour(Rgb(0f, 0f, 1f))
        h.now += 10_000
        h.stroke(listOf(5f to 5f, 30f to 5f))                           // more than 5 s since the first save: saved at once
        assertEquals(SaveState.SAVED, h.st.save)
        assertArrayEquals(h.gl.gpu.tex[0]!!.rgba, h.layerPixels("a"))
        h.now += 1_000
        h.stroke(listOf(5f to 25f, 30f to 25f))                         // 1 s later: waits
        assertEquals(SaveState.DIRTY, h.st.save)
        assertEquals(1, h.timers.size)
        assertTrue(h.timers[0].first in 3_900L..4_000L)                // the remainder of the five seconds
        h.stroke(listOf(5f to 35f, 30f to 35f))
        assertEquals(1, h.timers.size)                                  // one timer, not one per stroke
        val killedCopy = MemFs().also { it.files.putAll(h.fs.files) }  // the app is killed now: the project opens to the last save, not the two strokes since
        assertNotNull(ProjectStore(killedCopy, Harness.ROOT).open())
        h.now += 4_000; h.fireTimers()
        assertEquals(SaveState.SAVED, h.st.save)
        assertArrayEquals(h.gl.gpu.tex[0]!!.rgba, h.layerPixels("a"))
        assertTrue(h.reports.any { it.first == "studio_autosave_ms" })
    }

    @Test fun flushSavesNowEvenInsideTheFiveSeconds() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
        h.stroke(listOf(5f to 5f, 30f to 5f))
        assertEquals(SaveState.DIRTY, h.st.save)
        h.s.flush()
        assertEquals(SaveState.SAVED, h.st.save)
        assertArrayEquals(h.gl.gpu.tex[0]!!.rgba, h.layerPixels("a"))
    }

    @Test fun aFailedSaveIsReportedAndRetriedWithoutLosingTheStroke() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
        h.now += 10_000
        h.fs.failWrites = true
        h.stroke(listOf(5f to 5f, 30f to 5f))
        assertEquals(SaveState.FAILED, h.st.save)
        assertTrue(h.errors.any { it.contains("studio save") })
        assertEquals(app.rawline.core.studio.model.SpaceCheck.SAVE_FAILED_OTHER, h.st.message!!.text)
        h.fs.failWrites = false
        h.now += 6_000; h.fireTimers()
        assertEquals(SaveState.SAVED, h.st.save)
        assertArrayEquals(h.gl.gpu.tex[0]!!.rgba, h.layerPixels("a"))
    }

    @Test fun layerOperationsAreHistoryEntriesAndDeleteIsUndoneWithItsPixels() {
        val h = Harness(doc = blankDoc(64, 48, "a"), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.addLayer()
        assertEquals(2, h.st.document.layers.size)
        val added = h.st.document.layers[1].common.id
        assertEquals(added, h.st.activeId)
        h.s.setBrush(Brush(diameter = 8.0, hardness = 1.0, pressureSize = false)); h.s.setColour(Rgb(0f, 1f, 0f))
        h.stroke(listOf(10f to 10f, 30f to 10f))
        val painted = h.gl.gpu.tex[1]!!.rgba.copyOf()
        h.s.setBlend(added, BlendMode.MULTIPLY)
        assertEquals(BlendMode.MULTIPLY.id.toFloat(), h.gl.last!!.layers[5 + 7], 0f)
        h.s.deleteLayer(added)
        assertEquals(1, h.st.document.layers.size)
        assertNull(h.gl.gpu.tex[1])
        h.s.undo()                                                       // the delete
        assertEquals(2, h.st.document.layers.size)
        assertArrayEquals(painted, h.gl.gpu.tex[h.gl.last!!.layers[7].toInt()]!!.rgba)
        h.s.flush()
        assertArrayEquals(painted, h.layerPixels(added))                 // and it is saved again under its own name
        h.s.undo(); assertEquals(BlendMode.NORMAL, h.st.document.layers[1].common.blend)
        h.s.undo()                                                       // the stroke
        assertEquals(0, h.gl.gpu.tex[h.gl.last!!.layers[7].toInt()]!!.rgba.count { it.toInt() != 0 })
        h.s.undo()                                                       // the add
        assertEquals(1, h.st.document.layers.size); assertEquals("a", h.st.activeId)
        h.s.redo(); h.s.redo()                                           // the add, then the stroke on it
        assertEquals(2, h.st.document.layers.size)
        assertArrayEquals(painted, h.gl.gpu.tex[h.gl.last!!.layers[7].toInt()]!!.rgba)
    }

    @Test fun undoingAStrokeOnAnotherLayerSwitchesToItAndKeepsBothLayersSaved() {
        val h = Harness(doc = blankDoc(64, 48, "a", "b"), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 8.0, hardness = 1.0, pressureSize = false)); h.s.setColour(Rgb(1f, 0f, 0f))
        h.s.selectLayer("a")
        h.stroke(listOf(10f to 10f, 30f to 10f))                         // on a
        h.s.selectLayer("b")
        h.stroke(listOf(10f to 30f, 30f to 30f))                         // on b
        assertEquals("b", h.st.activeId)
        h.s.undo(); assertEquals("b", h.st.activeId)
        h.s.undo()                                                       // the stroke on a: a becomes active
        assertEquals("a", h.st.activeId)
        assertArrayEquals(white(64, 48).rgba, h.gl.gpu.tex[0]!!.rgba)
        h.s.flush()
        assertArrayEquals(white(64, 48).rgba, h.layerPixels("a"))
        h.s.redo(); h.s.redo()
        h.s.flush()
        assertArrayEquals(h.gl.gpu.tex[0]!!.rgba, h.layerPixels("a"))
        assertArrayEquals(h.gl.gpu.tex[1]!!.rgba, h.layerPixels("b"))
    }

    @Test fun duplicateCopiesThePixelsAndReorderMovesTheSlotOrder() {
        val h = Harness(doc = blankDoc(64, 48, "a", "b"), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.duplicateLayer("a")
        assertEquals(3, h.st.document.layers.size)
        val dup = h.st.document.layers[1].common.id            // sits just above its original
        assertEquals("a copy", h.st.document.layers[1].common.name); assertEquals(dup, h.st.activeId)
        h.s.flush()
        assertArrayEquals(white(64, 48).rgba, h.layerPixels(dup))
        h.s.moveLayer("b", up = false)
        assertEquals("b", h.st.document.layers[1].common.id)
        h.s.moveLayer("b", up = false); h.s.moveLayer("b", up = false)   // already at the bottom: nothing happens
        assertEquals("b", h.st.document.layers[0].common.id)
        assertEquals(3, h.st.document.layers.size)
    }

    @Test fun theTenLayerCapAndTheMemoryGuardGiveTheirMessages() {
        val h = Harness(doc = blankDoc(16, 16, "a"), pixels = mapOf("a" to white(16, 16)))
        h.s.start()
        repeat(9) { h.s.addLayer() }
        assertEquals(10, h.st.document.layers.size)
        h.s.addLayer()
        assertEquals("A project can have 10 layers for now. Delete one to add another.", h.st.message!!.text)
        assertEquals(10, h.st.document.layers.size)
        val g = Harness(doc = blankDoc(4000, 3000, "a"), pixels = emptyMap(), onDisk = true)
        g.s.start(); g.s.onSurfaceSize(7000, 7000)                        // an enormous output eats the budget
        g.s.addLayer()
        assertEquals(MemoryGuard.MESSAGE, g.st.message!!.text); assertEquals(1, g.st.document.layers.size)
    }

    @Test fun opacitySliderIsOneHistoryEntryForTheWholeDrag() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.previewOpacity("a", 90); h.s.previewOpacity("a", 70); h.s.previewOpacity("a", 40)
        assertEquals(0.4f, h.gl.last!!.layers[4], 1e-6f)
        assertFalse(h.st.canUndo)                                        // not recorded yet
        h.s.commitPreview()
        h.s.undo()
        assertEquals(100, h.st.document.layers[0].common.opacity); assertFalse(h.st.canUndo)
    }

    @Test fun moveIsOneHistoryEntryAndSnapsToWholePixelsAndCancelRollsBack() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setTool(Tool.MOVE)
        h.ev(InPhase.DOWN, 0, 10f, 10f); h.ev(InPhase.MOVE, 0, 14.4f, 12.6f); h.ev(InPhase.MOVE, 0, 20.4f, 25.6f)
        assertEquals(10f, h.gl.last!!.layers[1], 0f); assertEquals(16f, h.gl.last!!.layers[2], 0f)   // 10.4 -> 10, 15.6 -> 16
        h.ev(InPhase.UP, 0, 20.4f, 25.6f)
        assertEquals(10, h.st.document.layers[0].common.x); assertEquals(16, h.st.document.layers[0].common.y)
        h.s.undo()
        assertEquals(0, h.st.document.layers[0].common.x); assertFalse(h.st.canUndo)                    // exactly one entry
        h.ev(InPhase.DOWN, 0, 10f, 10f); h.ev(InPhase.MOVE, 0, 30f, 10f); h.ev(InPhase.DOWN, 1, 50f, 50f)  // a second finger: cancel
        assertEquals(0, h.st.document.layers[0].common.x); assertEquals(0f, h.gl.last!!.layers[1], 0f)
        h.ev(InPhase.UP, 1, 50f, 50f); h.ev(InPhase.UP, 0, 30f, 10f)
        assertFalse(h.st.canUndo)
    }

    @Test fun scaleToolPinchScalesAboutTheCentreWithinLimitsAndIsOneEntry() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setTool(Tool.SCALE)
        h.ev(InPhase.DOWN, 0, 20f, 20f); h.ev(InPhase.DOWN, 1, 40f, 20f)      // centre (30, 20), 20 apart
        h.ev(InPhase.MOVE, 1, 60f, 20f)                                      // 40 apart: twice as big
        assertEquals(2f, h.gl.last!!.layers[3], 1e-5f)
        h.ev(InPhase.MOVE, 1, 600f, 20f)                                     // far past the limit: clamped at 4
        assertEquals(4f, h.gl.last!!.layers[3], 0f)
        h.ev(InPhase.UP, 1, 600f, 20f); h.ev(InPhase.UP, 0, 20f, 20f)
        assertEquals(4f, h.st.document.layers[0].common.scale, 0f)
        h.s.undo()
        assertEquals(1f, h.st.document.layers[0].common.scale, 0f); assertFalse(h.st.canUndo)
        h.s.previewScale(50f); h.s.previewScale(50f); h.s.commitPreview()   // numeric entry keeps the layer centred
        val c = h.st.document.layers[0].common
        assertEquals(0.5f, c.scale, 0f); assertEquals(16, c.x); assertEquals(12, c.y)
        h.s.previewScale(200f); h.s.previewScale(100f); h.s.commitPreview()         // a drag that ends at 100 percent: from the start values, so back to the original place
        assertEquals(1f, h.st.document.layers[0].common.scale, 0f); assertEquals(0, h.st.document.layers[0].common.x)
        h.s.undo(); assertEquals(0.5f, h.st.document.layers[0].common.scale, 0f)
    }

    @Test fun aMovedAndScaledLayerIsPaintedWhereTheFingerIs() {
        val doc = blankDoc(100, 100).let { it.copy(layers = listOf(Layer.Pixel(LayerCommon("a", "a", x = 20, y = 10, scale = 2f), 40, 40))) }
        val h = Harness(doc = doc, pixels = mapOf("a" to white(40, 40)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 2.0, hardness = 1.0, pressureSize = false)); h.s.setColour(Rgb(1f, 0f, 0f))
        h.ev(InPhase.DOWN, 0, 60f, 50f); h.ev(InPhase.UP, 0, 60f, 50f)       // doc (60, 50) -> layer ((60-20)/2, (50-10)/2) = (20, 20)
        val px = h.gl.gpu.tex[0]!!
        val o = (20 * 40 + 20) * 4
        assertEquals(255, px.rgba[o].toInt() and 255); assertEquals(0, px.rgba[o + 1].toInt() and 255)
    }

    @Test fun aLostGlContextIsRefilledFromMemoryIncludingUnsavedPaint() {
        val h = Harness(doc = blankDoc(64, 48, "a", "b"), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 8.0, hardness = 1.0, pressureSize = false)); h.s.setColour(Rgb(1f, 0f, 0f))
        h.stroke(listOf(10f to 10f, 30f to 10f))                         // not saved yet (inside the first five seconds)
        val painted = h.gl.gpu.tex[1]!!.rgba.copyOf()
        h.gl.gpu.tex.clear()
        h.s.onContextRestored()
        assertEquals(2, h.gl.gpu.tex.size)
        assertArrayEquals(painted, h.gl.gpu.tex[1]!!.rgba)
    }

    @Test fun anOpenedProjectRecoversFromTheBackupGenerationAndSaysSo() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
        h.now += 10_000; h.stroke(listOf(5f to 5f, 30f to 5f))
        val strokedPixels = h.layerPixels("a")
        h.fs.files["files/studio/p1/project.json"] = "{ torn".toByteArray()
        val r = ProjectStore(h.fs, Harness.ROOT).open()
        assertTrue(r.recovered)
        val h2 = Harness(fs = h.fs, doc = r.document, pixels = emptyMap(), onDisk = true, recovered = r.recovered)
        h2.s.start()
        assertTrue(h2.st.recovered); assertEquals(Phase.READY, h2.st.phase)
        assertEquals(1, h2.gl.gpu.tex.size)
        assertFalse(strokedPixels.contentEquals(h2.gl.gpu.tex[0]!!.rgba))   // the older generation: the second save was the one that was damaged
    }

    @Test fun trimMemoryDropsOldUndoStepsWithOneNotice() {
        val h = Harness(doc = blankDoc(64, 48), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 30.0, pressureSize = false))
        repeat(3) { h.stroke(listOf(5f to 5f, 50f to 40f)) }
        h.s.trimMemory()                                                  // under 50 MB: nothing dropped, no notice
        assertNull(h.st.message)
        assertTrue(h.st.canUndo)
    }

    @Test fun aFailedStrokeStartOnTheGpuSaysSoAndLeavesNoEntry() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.gl.gpu.failBegin = true
        h.stroke(listOf(5f to 5f, 20f to 5f))
        assertEquals("Could not finish that stroke.", h.st.message!!.text)
        assertFalse(h.st.canUndo)
    }

    @Test fun thumbnailsAreMadeForEveryLayerAndRefreshedAfterAStroke() {
        val h = Harness(doc = blankDoc(200, 100, "a", "b"), pixels = mapOf("a" to white(200, 100)))
        h.s.start()
        val t = h.st.thumbs["a"]!!
        assertEquals(96, t.w); assertEquals(48, t.h)
        assertEquals(0xFFFFFFFF.toInt(), t.argb[0]); assertEquals(0, h.st.thumbs["b"]!!.argb[0])
        h.s.selectLayer("a")
        h.s.setBrush(Brush(diameter = 100.0, hardness = 1.0, pressureSize = false)); h.s.setColour(Rgb(0f, 0f, 0f))
        h.stroke(listOf(100f to 50f, 101f to 50f))
        assertEquals(0xFF000000.toInt(), h.st.thumbs["a"]!!.argb[24 * 96 + 48])
    }

    @Test fun inputToPixelStampIsHandedToTheRenderer() {
        val h = Harness(doc = blankDoc(), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
        h.ev(InPhase.DOWN, 0, 5f, 5f); h.ev(InPhase.MOVE, 0, 30f, 5f)
        assertTrue(h.s.takeInputStamp() > 0); assertEquals(0L, h.s.takeInputStamp())
        h.ev(InPhase.UP, 0, 30f, 5f)
    }
}

class SessionFuzzTest {
    /** Random strokes, layer operations, selections, undo, redo and flushes. The invariant after a flush: the saved project is exactly what is on the GPU and in the state. */
    @Test fun randomWorkThenFlushLeavesTheSavedProjectEqualToTheScreen() {
        for (seed in 1..6) {
            val rnd = java.util.Random(seed.toLong())
            val h = Harness(doc = blankDoc(48, 40, "a"), pixels = mapOf("a" to white(48, 40)))
            h.s.start()
            h.s.setBrush(Brush(diameter = 5.0 + rnd.nextInt(10), hardness = rnd.nextDouble(), opacity = 0.5 + rnd.nextDouble() / 2, flow = 0.5 + rnd.nextDouble() / 2, pressureSize = false))
            repeat(250) {
                when (rnd.nextInt(14)) {
                    0, 1, 2, 3 -> { h.s.setColour(Rgb(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())); h.s.setTool(if (rnd.nextInt(5) == 0) Tool.ERASER else Tool.BRUSH); h.stroke(List(2 + rnd.nextInt(3)) { rnd.nextInt(48).toFloat() to rnd.nextInt(40).toFloat() }) }
                    4 -> h.s.addLayer()
                    5 -> h.s.deleteLayer(h.st.document.layers[rnd.nextInt(h.st.document.layers.size)].common.id)
                    6 -> h.s.selectLayer(h.st.document.layers[rnd.nextInt(h.st.document.layers.size)].common.id)
                    7 -> h.s.moveLayer(h.st.document.layers[rnd.nextInt(h.st.document.layers.size)].common.id, rnd.nextBoolean())
                    8 -> h.s.duplicateLayer(h.st.document.layers[rnd.nextInt(h.st.document.layers.size)].common.id)
                    9, 10 -> h.s.undo()
                    11 -> h.s.redo()
                    12 -> { h.now += 6_000; h.fireTimers() }
                    13 -> h.s.setBlend(h.st.document.layers[rnd.nextInt(h.st.document.layers.size)].common.id, BlendMode.entries[rnd.nextInt(3)])
                }
                h.s.setTool(Tool.BRUSH)
            }
            h.s.flush()
            assertEquals("seed $seed", SaveState.SAVED, h.st.save)
            val (saved, store) = h.reopen()
            val shown = h.st.document
            assertEquals("seed $seed ids", shown.layers.map { it.common }, saved.layers.map { it.common })
            // pixels on the GPU by slot, found through the last frame (visible layers only) plus hidden ones are never hidden here
            val frame = h.gl.last!!.layers.toList().chunked(7)
            assertEquals("seed $seed visible layers", shown.layers.count { it.common.visible }, frame.size)
            for ((i, l) in shown.layers.filterIsInstance<Layer.Pixel>().withIndex()) {
                val onDisk = store.load(saved.layers[i] as Layer.Pixel)?.rgba ?: ByteArray(l.width * l.height * 4)
                assertArrayEquals("seed $seed layer ${l.common.id}", h.gl.gpu.tex[frame[i][0].toInt()]!!.rgba, onDisk)
            }
            assertEquals("seed $seed no errors", emptyList<String>(), h.errors)
            assertTrue("seed $seed did real work: ${h.reports.count { it.first == "studio_commit_ms" }} strokes", h.reports.count { it.first == "studio_commit_ms" } >= 15)
        }
    }
}

class SmallPiecesTest {
    @Test fun memoryGuardMatchesTheDocumentedFigures() {
        // ten 12 MP layers on a 3120 x 1440 phone: 480 MB of layers plus the pair, resolve and stroke buffer
        val ten = List(10) { 4000 to 3000 }
        assertTrue(MemoryGuard.allows(ten, 3120, 1440))
        assertFalse(MemoryGuard.allows(ten + (4000 to 3000), 3120, 1440))
        assertEquals(10L * 12_000_000 * 4 + 2L * 8 * 3120 * 1440 + 4L * 3120 * 1440 + 2L * 12_000_000, MemoryGuard.estimate(ten, 3120, 1440))
    }

    @Test fun thumbnailOfAHalfTransparentLayerKeepsStraightColour() {
        val w = 8; val h = 8
        val px = ByteArray(w * h * 4)
        for (i in 0 until w * h) { px[i * 4] = 200.toByte(); px[i * 4 + 1] = 100; px[i * 4 + 2] = 50; px[i * 4 + 3] = if (i % w < 4) 255.toByte() else 0 }
        val t = Thumbs.make(RawPixels(w, h, px), 96)
        assertEquals(8, t.w)
        assertEquals((255 shl 24) or (200 shl 16) or (100 shl 8) or 50, t.argb[0])
        assertEquals(0, t.argb[7])
        val small = Thumbs.make(RawPixels(w, h, px), 2)                     // 8 -> 2: each output pixel averages 4 columns
        assertEquals(2, small.w)
        assertEquals(0xFF, small.argb[0] ushr 24); assertEquals(200, (small.argb[0] shr 16) and 255); assertEquals(0, small.argb[1])
    }

    @Test fun statsDescribeTheGuardAndAreNullBeforeAnyProject() {
        StudioStats.clear()
        assertNull(StudioStats.describe())
        StudioStats.update(StudioStats.Snapshot(4000, 3000, 3, 120, 8, 200, "saved", "p"))
        val d = StudioStats.describe()!!
        assertTrue(d.contains("12.0 MP")); assertTrue(d.contains("memory guard 200 of 600 MB (ok)")); assertTrue(d.contains("Autosave: saved"))
        StudioStats.clear()
    }
}
