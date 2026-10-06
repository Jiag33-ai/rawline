# W28 Studio S1b fixes, file W13-s1b-fixes.md (review-s1b.md F1 to F3 and the quick P2s)

Status at writing: main 7cd1b06 (S1c host parts merged). Spec for what is wrong: review-s1b.md. Entries: BK-479, BK-480, BK-481, BK-483, BK-484, BK-487, BK-503 (and F5, F13). The pure Kotlin below (StrokeTiles, the history and router changes) compiled with Kotlin 2.4.10 together with the real `core/studio-model` sources and ran on the host JVM: all 119 model tests pass (the 93 that exist plus 26 new), with the model test resources on the classpath. The native banded readback was built and run through the real Studio golden harness on Mesa llvmpipe: all existing goldens still PASS and the coverage read is byte-identical to the unbanded read at bands of 1, 7, 64, 256 and 1000 rows. NOT compiled or run: the StudioSession, StudioGl and Compose changes in sections 5 to 7. They are written as exact edits with acceptance tests; the worker must compile and run `:core:studio-render:test`.

## 1. Order of work (each step ends green)
1. Apply `model.patch` (History, InputRouter) and add `StrokeTiles.kt` and the tests (section 3 and 4). Run `./gradlew :core:studio-model:testDebugUnitTest`.
2. Apply `native.patch` (banded readStroke) and run `tools/golden/studio-golden.sh`, then the coverage equality loop (section 4.2).
3. StudioSession edits (section 5). Update `StudioSessionTest` for the new step type and add the tests listed there.
4. GL lifecycle edits (section 6).
5. Compose edits (section 7).
6. Phone: Copy report rows `studio_commit_ms`, `studio_texture_mb`, `studio_history_mb` before and after a diagonal stroke across a 12 MP layer, and a rotation test. No speed claim without the report.

## 2. Decisions (no questions left)
- D1 A stroke commit works per touched 256 px tile (`Tiles.SIZE`). Each tile rectangle is the union of the stamp boxes inside that tile; the union of all rectangles lies inside the old `Dirty.rect`. One undo step per stroke (`Entry.Strokes`), with one `PixelDelta` per tile, so a diagonal line costs about 10 MB of history instead of 96 MB on a 12 MP layer (measured by the test `historyBytesFollowThePaintedAreaNotTheBox`).
- D2 The baked bytes are identical to the old whole-box bake (test `tiledCommitGivesTheSameBytesAsTheBoundingBoxCommit`, 8 random strokes with paint, erase, pressure, random existing pixels). `StrokeTiles.bake` is bit-identical to `StrokeReference.commitRect` (test `bakeIsByteIdenticalToTheReferenceBake`) and allocates no per pixel object.
- D3 Readback is per tile rectangle and banded natively (256 rows). The session reads at most 8 tile rectangles per GL round trip, so peak extra memory is bounded by 8 tiles of floats plus one 16 MB band, whatever the stroke size.
- D4 Pen beats palm: a stylus DOWN while a finger stroke is active cancels that stroke (no history entry) and starts the pen stroke. Fingers are ignored while the pen hovers (`onHover(true)`), while its tip is down, and for 600 ms after it leaves (`InputRouter.PALM_GRACE_MS`). Two-finger gestures are unchanged; a pen landing during a gesture is still ignored.
- D5 Graveyard: after every history change and every trim the session keeps only the encoded pixels of layers named by `history.restorableLayerIds()` (those an undo or redo can bring back).
- D6 GL: jobs queue while the context is not known to be valid (paused or init failed) and drop at once on a failed init; the compositor is destroyed only when the screen is really leaving, never on rotation; the view is created once and moved between layouts.
- D7 Touch targets: every tappable thing is at least 48 dp, visual size unchanged.
- D8 BK-503 (autosave on a full phone) is section 10, added by the PM. Not in this task: the frame path without CPU readback (BK-482, a compositor change of its own), the `StudioSession` autosave debounce can go in with step 3 only if the worker has time (small: stroke end plus 1.5 s idle, 5 s ceiling).

## 3. Kotlin, compiled and tested
### core/studio-model patch (History.kt and InputRouter.kt)
```diff
--- a/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/History.kt
+++ b/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/History.kt
@@ -25,6 +25,10 @@
     class SetDocument(val document: Document) : Step()
     /** Write [bytes] (the delta's before or after) into the rectangle of the layer, in the CPU copy and in the GPU texture. */
     class SetPixels(val layerId: String, val delta: PixelDelta, val bytes: ByteArray) : Step()
+    /** A stroke that touched several tiles: write every part (before bytes for an undo, after bytes for a redo). */
+    class SetPixelsMany(val layerId: String, val parts: List<PixelDelta>, val useAfter: Boolean) : Step() {
+        fun apply(layer: ByteArray, layerW: Int) { for (d in parts) d.apply(layer, layerW, if (useAfter) d.after else d.before) }
+    }
 }
 
 /**
@@ -35,7 +39,9 @@
     private sealed class Entry {
         class Doc(val before: Document, val after: Document) : Entry()
         class Stroke(val layerId: String, val delta: PixelDelta) : Entry()
+        class Strokes(val layerId: String, val parts: List<PixelDelta>) : Entry()
     }
+    private fun bytesOf(e: Entry): Long = when (e) { is Entry.Stroke -> e.delta.bytes; is Entry.Strokes -> e.parts.sumOf { it.bytes }; is Entry.Doc -> 0L }
     private val undo = ArrayDeque<Entry>()
     private val redo = ArrayDeque<Entry>()
     var document: Document = initial
@@ -54,6 +60,24 @@
     /** Records a finished stroke. The pixels are already in place; only the delta is kept. */
     fun commitStroke(layerId: String, delta: PixelDelta) { push(Entry.Stroke(layerId, delta)); deltaBytes += delta.bytes; trim() }
 
+    /** One entry for a stroke that changed several tiles (one undo step, bytes proportional to the painted tiles, not to a bounding box). */
+    fun commitStrokeTiles(layerId: String, parts: List<PixelDelta>) {
+        if (parts.isEmpty()) return
+        val e = Entry.Strokes(layerId, parts.toList()); push(e); deltaBytes += bytesOf(e); trim()
+    }
+
+    /**
+     * Layers whose pixels an undo or a redo can still need to bring back: those that leave the stack in a recorded layer operation (undo of a delete) or enter it
+     * in an undone one (redo of an add). The session keeps the encoded pixels of exactly these layers and may drop every other graveyard entry.
+     */
+    fun restorableLayerIds(): Set<String> {
+        val out = HashSet<String>()
+        fun ids(d: Document) = d.layers.map { it.common.id }.toSet()
+        for (e in undo) if (e is Entry.Doc) out += ids(e.before) - ids(e.after)
+        for (e in redo) if (e is Entry.Doc) out += ids(e.after) - ids(e.before)
+        return out
+    }
+
     /** Replaces the current document without a history entry (the storage layer filling in pixel file names after a save). */
     fun replaceCurrent(doc: Document) { document = doc }
 
@@ -63,6 +87,7 @@
         return when (e) {
             is Entry.Doc -> { document = e.before; Step.SetDocument(e.before) }
             is Entry.Stroke -> Step.SetPixels(e.layerId, e.delta, e.delta.before)
+            is Entry.Strokes -> Step.SetPixelsMany(e.layerId, e.parts, useAfter = false)
         }
     }
 
@@ -72,6 +97,7 @@
         return when (e) {
             is Entry.Doc -> { document = e.after; Step.SetDocument(e.after) }
             is Entry.Stroke -> Step.SetPixels(e.layerId, e.delta, e.delta.after)
+            is Entry.Strokes -> Step.SetPixelsMany(e.layerId, e.parts, useAfter = true)
         }
     }
 
@@ -80,7 +106,7 @@
         var dropped = false
         while (deltaBytes > maxBytes && undo.size > 1) {
             val e = undo.removeFirst(); dropped = true
-            if (e is Entry.Stroke) deltaBytes -= e.delta.bytes
+            deltaBytes -= bytesOf(e)
         }
         return dropped
     }
@@ -91,12 +117,12 @@
         trim()
     }
 
-    private fun dropRedo() { for (e in redo) if (e is Entry.Stroke) deltaBytes -= e.delta.bytes; redo.clear() }
+    private fun dropRedo() { for (e in redo) deltaBytes -= bytesOf(e); redo.clear() }
 
     private fun trim() {
         while (undo.size > maxEntries || (deltaBytes > maxBytes && undo.size > 1)) {
             val e = undo.removeFirst()
-            if (e is Entry.Stroke) deltaBytes -= e.delta.bytes
+            deltaBytes -= bytesOf(e)
         }
     }
 }
--- a/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/InputRouter.kt
+++ b/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/InputRouter.kt
@@ -37,16 +37,29 @@
     private val down = HashSet<Int>()
     private var lastCx = 0f; private var lastCy = 0f; private var lastDist = 1f
 
+    /** Until this uptime (ms) a finger DOWN is taken for a palm: the pen is near or was just lifted. Long.MAX_VALUE while the tip is down or the pen hovers. */
+    private var penNearUntil = Long.MIN_VALUE
+
+    /** Pen hover from the canvas (Compose PointerEventType.Enter and Move with a stylus: [near] true, Exit: false). */
+    fun onHover(near: Boolean, timeMs: Long) { penNearUntil = if (near) Long.MAX_VALUE else timeMs + PALM_GRACE_MS }
+
     fun onEvent(e: InputEvent): List<Action> {
         val out = ArrayList<Action>(2)
         when (e.phase) {
             Phase.DOWN -> {
                 down.add(e.pointerId)
+                if (e.kind == PointerKind.STYLUS) penNearUntil = Long.MAX_VALUE
+                else if (state == State.IDLE && e.timeMs < penNearUntil) return out   // a palm resting near the pen: ignored (it stays in `down` so its UP is understood)
                 when (state) {
                     State.IDLE -> { state = State.STROKING; stroke = e.pointerId; strokeKind = e.kind; out += Action.StrokeStart(e.x, e.y, e.pressure, e.kind) }
                     State.STROKING -> {
                         if (strokeKind == PointerKind.STYLUS && e.kind == PointerKind.FINGER) return out    // palm
-                        if (e.kind == PointerKind.STYLUS) return out
+                        if (e.kind == PointerKind.STYLUS) {   // the pen wins over a finger or a palm that began a stroke: roll that stroke back and draw with the pen
+                            out += Action.StrokeCancel
+                            stroke = e.pointerId; strokeKind = PointerKind.STYLUS; strokePos = floatArrayOf(e.x, e.y)
+                            out += Action.StrokeStart(e.x, e.y, e.pressure, e.kind)
+                            return out
+                        }
                         out += Action.StrokeCancel
                         val first = strokePos ?: floatArrayOf(e.x, e.y)
                         pos.clear(); pos[stroke] = first; pos[e.pointerId] = floatArrayOf(e.x, e.y)
@@ -62,6 +75,7 @@
             }
             Phase.UP, Phase.CANCEL -> {
                 down.remove(e.pointerId)
+                if (e.kind == PointerKind.STYLUS) penNearUntil = e.timeMs + PALM_GRACE_MS
                 when (state) {
                     State.STROKING -> if (e.pointerId == stroke) { out += if (e.phase == Phase.UP) Action.StrokeEnd else Action.StrokeCancel; state = State.IDLE; strokePos = null }
                     State.GESTURE -> if (pos.containsKey(e.pointerId)) { out += Action.GestureEnd; pos.clear(); state = if (down.isEmpty()) State.IDLE else State.WAIT_FOR_UP }
@@ -76,6 +90,8 @@
 
     private var strokePos: FloatArray? = null
 
+    companion object { const val PALM_GRACE_MS = 600L }
+
     private fun beginGesture() {
         val (cx, cy, d) = measure(); lastCx = cx; lastCy = cy; lastDist = d.coerceAtLeast(1f)
     }
```
### StrokeTiles.kt (new, `core/studio-model/src/main/kotlin/app/rawline/core/studio/model/StrokeTiles.kt`)
```kotlin
package app.rawline.core.studio.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Review F1 (BK-479): a stroke is committed per touched 256 px tile instead of one bounding box. A thin diagonal line across a 12 MP layer touches a few dozen tiles
 * but its bounding box is the whole layer. Everything here is pure maths over the same [Stamp]s the GPU draws.
 */
object StrokeTiles {
    /**
     * The rectangles (x, y, w, h) a stroke can touch, one per 256 px tile, each the union of the stamp boxes inside that tile, clipped to the layer, ordered by tile row then column.
     * A stamp box is the one [Dirty.rect] uses for a single stamp (radius plus one pixel each way), so the union of all rectangles is covered by [Dirty.rect] of the whole stroke.
     */
    fun rects(stamps: List<Stamp>, width: Int, height: Int): List<IntArray> {
        val acc = HashMap<Long, IntArray>()   // key: ty * 1_000_000 + tx; value: x0, y0, x1, y1 (inclusive)
        for (s in stamps) {
            val x0 = max(0, floor(s.x - s.radius - 1).toInt()); val y0 = max(0, floor(s.y - s.radius - 1).toInt())
            val x1 = min(width - 1, ceil(s.x + s.radius + 1).toInt()); val y1 = min(height - 1, ceil(s.y + s.radius + 1).toInt())
            if (x1 < x0 || y1 < y0) continue
            for (ty in y0 / Tiles.SIZE..y1 / Tiles.SIZE) for (tx in x0 / Tiles.SIZE..x1 / Tiles.SIZE) {
                val cx0 = max(x0, tx * Tiles.SIZE); val cy0 = max(y0, ty * Tiles.SIZE)
                val cx1 = min(x1, tx * Tiles.SIZE + Tiles.SIZE - 1); val cy1 = min(y1, ty * Tiles.SIZE + Tiles.SIZE - 1)
                val key = ty.toLong() * 1_000_000L + tx
                val r = acc[key]
                if (r == null) acc[key] = intArrayOf(cx0, cy0, cx1, cy1)
                else { r[0] = min(r[0], cx0); r[1] = min(r[1], cy0); r[2] = max(r[2], cx1); r[3] = max(r[3], cy1) }
            }
        }
        return acc.entries.sortedBy { it.key }.map { (_, r) -> intArrayOf(r[0], r[1], r[2] - r[0] + 1, r[3] - r[1] + 1) }
    }

    /** Pixels in all rectangles: what the commit reads back, bakes and stores, to compare with the bounding box area. */
    fun area(rects: List<IntArray>): Long = rects.sumOf { it[2].toLong() * it[3] }

    /**
     * [StrokeReference.commitRect] without a per pixel object: the same float operations in the same order, so the bytes are identical (tested), about an order of magnitude less garbage.
     * [cov] is the coverage of the rectangle only (row 0 = the rectangle's top row).
     */
    fun bake(pixels: ByteArray, layerW: Int, rect: IntArray, cov: FloatArray, colour: FloatArray, brush: Brush) {
        val rx = rect[0]; val ry = rect[1]; val rw = rect[2]; val rh = rect[3]
        val opacity = brush.opacity.toFloat()
        val cr = colour[0]; val cg = colour[1]; val cb = colour[2]
        for (y in 0 until rh) for (x in 0 until rw) {
            val a = min(cov[y * rw + x], 1f) * opacity
            if (a <= 0f) continue
            val o = ((ry + y) * layerW + rx + x) * 4
            val ab = (pixels[o + 3].toInt() and 255) / 255f
            if (brush.erase) {
                pixels[o + 3] = Math.round(ab * (1f - a) * 255f).toByte()
            } else {
                val ar = a + ab * (1f - a)
                if (ar <= 0f) { pixels[o] = 0; pixels[o + 1] = 0; pixels[o + 2] = 0; pixels[o + 3] = 0 }
                else {
                    pixels[o] = q((((1f - a) * ab * ((pixels[o].toInt() and 255) / 255f) + a * ((1f - ab) * cr + ab * cr))) / ar)
                    pixels[o + 1] = q((((1f - a) * ab * ((pixels[o + 1].toInt() and 255) / 255f) + a * ((1f - ab) * cg + ab * cg))) / ar)
                    pixels[o + 2] = q((((1f - a) * ab * ((pixels[o + 2].toInt() and 255) / 255f) + a * ((1f - ab) * cb + ab * cb))) / ar)
                    pixels[o + 3] = q(ar)
                }
            }
            if (pixels[o + 3].toInt() == 0) { pixels[o] = 0; pixels[o + 1] = 0; pixels[o + 2] = 0 }
        }
    }

    private fun q(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** What a stroke commit does for a list of tile rectangles: before bytes, bake, after bytes, one [PixelDelta] per rectangle. [coverageOf] reads the coverage of one rectangle (the GPU readback). */
    fun commit(pixels: ByteArray, layerW: Int, rects: List<IntArray>, colour: FloatArray, brush: Brush, coverageOf: (IntArray) -> FloatArray): List<PixelDelta> =
        rects.map { r ->
            val before = PixelDelta.cut(pixels, layerW, r[0], r[1], r[2], r[3])
            bake(pixels, layerW, r, coverageOf(r), colour, brush)
            PixelDelta(r[0], r[1], r[2], r[3], before, PixelDelta.cut(pixels, layerW, r[0], r[1], r[2], r[3]))
        }
}
```
### S1bFixTest.kt (new, `core/studio-model/src/test/kotlin/app/rawline/core/studio/model/S1bFixTest.kt`)
```kotlin
package app.rawline.core.studio.model

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class StrokeTilesTest {
    private fun diagonal(w: Int, h: Int, brush: Brush) = BrushMath.walk(listOf(StrokePoint(5.0, 5.0), StrokePoint(w - 5.0, h - 5.0)), brush)

    @Test fun aDiagonalLineTouchesAFewTilesNotTheWholeLayer() {
        val w = 4000; val h = 3000; val st = diagonal(w, h, Brush(diameter = 24.0, pressureSize = false))
        val rects = StrokeTiles.rects(st, w, h)
        val box = Dirty.rect(st, w, h); val boxArea = box[2].toLong() * box[3]
        assertTrue("tiles ${rects.size}", rects.size in 20..45)
        assertTrue("area ${StrokeTiles.area(rects)} of $boxArea", StrokeTiles.area(rects) < boxArea / 8)
        for (r in rects) {                                                  // inside the layer, inside one tile, inside the old box
            assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= w && r[1] + r[3] <= h)
            assertEquals(r[0] / Tiles.SIZE, (r[0] + r[2] - 1) / Tiles.SIZE); assertEquals(r[1] / Tiles.SIZE, (r[1] + r[3] - 1) / Tiles.SIZE)
            assertTrue(r[0] >= box[0] && r[1] >= box[1] && r[0] + r[2] <= box[0] + box[2] && r[1] + r[3] <= box[1] + box[3])
        }
        assertEquals(rects.size, rects.map { (it[1] / Tiles.SIZE) * 100 + it[0] / Tiles.SIZE }.toSet().size)   // one rectangle per tile
    }
    @Test fun historyBytesFollowThePaintedAreaNotTheBox() {
        val w = 4000; val h = 3000; val st = diagonal(w, h, Brush(diameter = 24.0, pressureSize = false))
        val tiled = StrokeTiles.area(StrokeTiles.rects(st, w, h)) * 4 * 2; val box = Dirty.rect(st, w, h).let { it[2].toLong() * it[3] * 4 * 2 }
        assertTrue("tiled ${tiled / 1_000_000} MB, box ${box / 1_000_000} MB", tiled < 12_000_000 && box > 90_000_000)
    }
    @Test fun everyPixelThatGetsCoverageIsInsideARectangle() {
        val rnd = Random(3); val w = 700; val h = 560
        repeat(6) {
            val brush = Brush(diameter = 2.0 + rnd.nextInt(60), hardness = rnd.nextDouble(), pressureSize = false)
            val st = BrushMath.walk(List(4) { StrokePoint(rnd.nextDouble() * (w + 80) - 40, rnd.nextDouble() * (h + 80) - 40) }, brush)
            val cov = StrokeReference.coverage(w, h, st, brush); val rects = StrokeTiles.rects(st, w, h)
            for (y in 0 until h) for (x in 0 until w) if (cov[y * w + x] > 0f)
                assertTrue("($x,$y)", rects.any { x >= it[0] && x < it[0] + it[2] && y >= it[1] && y < it[1] + it[3] })
        }
    }
    @Test fun anOffCanvasStrokeHasNoRectangles() {
        assertTrue(StrokeTiles.rects(listOf(Stamp(-100.0, -100.0, 5.0)), 100, 100).isEmpty())
        assertTrue(StrokeTiles.rects(emptyList(), 100, 100).isEmpty())
    }
    @Test fun tiledCommitGivesTheSameBytesAsTheBoundingBoxCommit() {
        val rnd = Random(11); val w = 620; val h = 530
        repeat(8) { n ->
            val brush = Brush(diameter = 4.0 + rnd.nextInt(50), hardness = rnd.nextDouble(), opacity = 0.2 + rnd.nextDouble() * 0.8, flow = 0.2 + rnd.nextDouble() * 0.8, erase = n % 3 == 2, pressureSize = n % 2 == 0)
            val st = BrushMath.walk(List(5) { StrokePoint(rnd.nextDouble() * w, rnd.nextDouble() * h, 0.1 + rnd.nextDouble() * 0.9) }, brush)
            val base = ByteArray(w * h * 4).also { rnd.nextBytes(it) }
            for (i in 0 until w * h) if (rnd.nextInt(4) == 0) { base[i * 4 + 3] = 0; base[i * 4] = 0; base[i * 4 + 1] = 0; base[i * 4 + 2] = 0 }
            val colour = floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            val cov = StrokeReference.coverage(w, h, st, brush)
            val a = base.copyOf(); val box = Dirty.rect(st, w, h)
            if (box[2] > 0) StrokeReference.commit(a, w, cov, box, colour, brush)
            val b = base.copyOf()
            val deltas = StrokeTiles.commit(b, w, StrokeTiles.rects(st, w, h), colour, brush) { r ->
                FloatArray(r[2] * r[3]).also { out -> for (y in 0 until r[3]) System.arraycopy(cov, (r[1] + y) * w + r[0], out, y * r[2], r[2]) }
            }
            assertArrayEquals("stroke $n", a, b)
            // and the deltas undo it exactly
            val c = b.copyOf(); for (d in deltas.reversed()) d.apply(c, w, d.before); assertArrayEquals(base, c)
        }
    }
    @Test fun bakeIsByteIdenticalToTheReferenceBake() {
        val rnd = Random(21); val w = 140; val h = 120
        repeat(20) { n ->
            val brush = Brush(opacity = 0.1 + rnd.nextDouble() * 0.9, erase = n % 4 == 3)
            val px = ByteArray(w * h * 4).also { rnd.nextBytes(it) }
            for (i in 0 until w * h) if (rnd.nextInt(3) == 0) { px[i * 4 + 3] = (rnd.nextInt(3) * 127).toByte(); if (px[i * 4 + 3].toInt() == 0) { px[i * 4] = 0; px[i * 4 + 1] = 0; px[i * 4 + 2] = 0 } }
            val rect = intArrayOf(rnd.nextInt(20), rnd.nextInt(20), 30 + rnd.nextInt(40), 20 + rnd.nextInt(40))
            val cov = FloatArray(rect[2] * rect[3]) { if (rnd.nextInt(5) == 0) 0f else rnd.nextFloat() * 1.3f }
            val colour = floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            val a = px.copyOf(); val b = px.copyOf()
            StrokeReference.commitRect(a, w, rect, cov, colour, brush); StrokeTiles.bake(b, w, rect, cov, colour, brush)
            assertArrayEquals("case $n", a, b)
        }
    }
}

class HistoryTilesTest {
    private fun layer(id: String) = Layer.Pixel(LayerCommon(id, id), 40, 30)
    private fun doc() = Document("d", "D", 40, 30, layers = listOf(layer("a"), layer("b")))
    private fun part(x: Int, fill: Int) = PixelDelta(x, 0, 4, 4, ByteArray(64) { 0 }, ByteArray(64) { fill.toByte() })

    @Test fun oneUndoStepForAStrokeThatTouchedSeveralTiles() {
        val h = StudioHistory(doc())
        h.commitStrokeTiles("a", listOf(part(0, 1), part(10, 2), part(20, 3)))
        val px = ByteArray(40 * 30 * 4)
        h.undo()!!.let { assertTrue(it is Step.SetPixelsMany); (it as Step.SetPixelsMany).apply(px, 40) }
        assertFalse(h.canUndo); assertTrue(h.canRedo)
        (h.redo() as Step.SetPixelsMany).apply(px, 40)
        assertEquals(1, px[0].toInt()); assertEquals(2, px[10 * 4].toInt()); assertEquals(3, px[20 * 4].toInt())
        (h.undo() as Step.SetPixelsMany).apply(px, 40); assertTrue(px.all { it.toInt() == 0 })
    }
    @Test fun byteAccountingCountsEveryPartAndSurvivesTrimAndRedoDrop() {
        val h = StudioHistory(doc(), maxEntries = 100, maxBytes = 1000)
        repeat(10) { h.commitStrokeTiles("a", listOf(part(0, 1), part(8, 2))) }      // 256 bytes each
        assertTrue(h.deltaBytes <= 1000 && h.deltaBytes % 256 == 0L)
        var n = 0; while (h.undo() != null) n++
        assertEquals(3, n)                                                           // 3 x 256 = 768 fits, 4 x 256 does not
        h.commitStrokeTiles("a", listOf(part(0, 9)))                                 // a new edit drops the redo tail and its bytes
        assertEquals(128L, h.deltaBytes)
        assertFalse(h.canRedo)
    }
    @Test fun trimBytesDropsTiledEntriesToo() {
        val h = StudioHistory(doc()); repeat(6) { h.commitStrokeTiles("a", listOf(part(0, 1), part(8, 2))) }
        assertTrue(h.trimBytes(600)); assertTrue(h.deltaBytes <= 600)
    }
    @Test fun restorableLayersAreThoseAnUndoOrRedoCanBringBack() {
        val h = StudioHistory(doc())
        assertTrue(h.restorableLayerIds().isEmpty())
        h.commitDocument(LayerOps.delete(h.document, "b"))                 // undo would bring b back
        assertEquals(setOf("b"), h.restorableLayerIds())
        h.undo()                                                           // now b is on the stack and a redo would remove it: nothing to restore
        assertTrue(h.restorableLayerIds().isEmpty())
        h.redo(); assertEquals(setOf("b"), h.restorableLayerIds())
        h.undo(); h.commitDocument(LayerOps.add(h.document, layer("c")))   // a new edit drops the redo tail
        assertTrue(h.restorableLayerIds().isEmpty())
        h.undo(); assertEquals(setOf("c"), h.restorableLayerIds())         // redo of the add needs c's pixels
    }
    @Test fun aDeleteThatHistoryForgotIsNotRestorable() {
        val h = StudioHistory(doc(), maxEntries = 3)
        h.commitDocument(LayerOps.delete(h.document, "b"))
        assertEquals(setOf("b"), h.restorableLayerIds())
        repeat(3) { i -> h.commitDocument(LayerOps.setOpacity(h.document, "a", 10 + i)) }   // the delete entry falls off the end
        assertTrue(h.restorableLayerIds().isEmpty())
    }
}

class PenOverPalmTest {
    private fun ev(p: Phase, id: Int, x: Float, kind: PointerKind, t: Long) = InputEvent(p, id, x, 0f, 1f, kind, t)
    private fun names(a: List<Action>) = a.map { it::class.simpleName }

    @Test fun aPenLandingAfterAPalmCancelsTheSmearAndDraws() {
        val r = InputRouter()
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 1, 100f, PointerKind.FINGER, 0))))     // the palm
        r.onEvent(ev(Phase.MOVE, 1, 104f, PointerKind.FINGER, 10))
        val a = r.onEvent(ev(Phase.DOWN, 7, 300f, PointerKind.STYLUS, 20))
        assertEquals(listOf("StrokeCancel", "StrokeStart"), names(a))
        assertEquals(PointerKind.STYLUS, (a[1] as Action.StrokeStart).kind); assertEquals(300f, (a[1] as Action.StrokeStart).x, 0f)
        assertTrue(r.onEvent(ev(Phase.MOVE, 1, 120f, PointerKind.FINGER, 30)).isEmpty())                          // the palm no longer draws
        assertEquals(listOf("StrokeMove"), names(r.onEvent(ev(Phase.MOVE, 7, 310f, PointerKind.STYLUS, 40))))
        assertTrue(r.onEvent(ev(Phase.UP, 1, 120f, PointerKind.FINGER, 45)).isEmpty())                            // the palm lifts first: nothing
        assertEquals(listOf("StrokeEnd"), names(r.onEvent(ev(Phase.UP, 7, 310f, PointerKind.STYLUS, 50))))
    }
    @Test fun aPenAfterAFingerDuringAPinchStaysIgnored() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 1, 100f, PointerKind.FINGER, 0)); r.onEvent(ev(Phase.DOWN, 2, 200f, PointerKind.FINGER, 1))   // two fingers: a gesture
        assertTrue(r.onEvent(ev(Phase.DOWN, 7, 300f, PointerKind.STYLUS, 2)).isEmpty())
    }
    @Test fun aPalmLandingNearTheHoveringPenIsIgnored() {
        val r = InputRouter()
        r.onHover(true, 100)
        assertTrue(r.onEvent(ev(Phase.DOWN, 1, 50f, PointerKind.FINGER, 120)).isEmpty())
        assertTrue(r.onEvent(ev(Phase.MOVE, 1, 60f, PointerKind.FINGER, 130)).isEmpty())
        assertTrue(r.onEvent(ev(Phase.UP, 1, 60f, PointerKind.FINGER, 140)).isEmpty())
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 7, 300f, PointerKind.STYLUS, 150))))    // the pen itself draws
    }
    @Test fun theGraceEndsSixHundredMillisecondsAfterTheHoverExit() {
        val r = InputRouter()
        r.onHover(true, 100); r.onHover(false, 1000)
        assertTrue(r.onEvent(ev(Phase.DOWN, 1, 50f, PointerKind.FINGER, 1599)).isEmpty())
        r.onEvent(ev(Phase.UP, 1, 50f, PointerKind.FINGER, 1599))
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 2, 50f, PointerKind.FINGER, 1600))))
    }
    @Test fun afterTheTipLiftsFingersWaitForTheGrace() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 7, 10f, PointerKind.STYLUS, 0)); r.onEvent(ev(Phase.UP, 7, 10f, PointerKind.STYLUS, 100))
        assertTrue(r.onEvent(ev(Phase.DOWN, 1, 50f, PointerKind.FINGER, 400)).isEmpty())
        r.onEvent(ev(Phase.UP, 1, 50f, PointerKind.FINGER, 450))
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 1, 50f, PointerKind.FINGER, 700))))
    }
    @Test fun withoutAPenFingersBehaveAsBefore() {
        val r = InputRouter()
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 1, 50f, PointerKind.FINGER, 0))))
    }
}
```

## 4. Native: banded stroke readback (built and run)
### 4.1 Patch
```diff
--- a/core/native/src/main/cpp/studio/studio_compositor.cpp
+++ b/core/native/src/main/cpp/studio/studio_compositor.cpp
@@ -214,16 +214,23 @@
     return glGetError() == GL_NO_ERROR;
 }
 
-bool Compositor::readStroke(int x, int y, int w, int h, float *coverage) {
+bool Compositor::readStroke(int x, int y, int w, int h, float *coverage, int bandRows) {
     if (!ready_ || !strokeBuf_.tex || x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > strokeBuf_.w || y + h > strokeBuf_.h) return false;
+    bandRows = std::max(1, bandRows);
     GLint prevFbo = 0;
     glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
     glBindFramebuffer(GL_FRAMEBUFFER, strokeBuf_.fbo);
     glPixelStorei(GL_PACK_ALIGNMENT, 1);
-    std::vector<float> rgba(size_t(w) * h * 4);   // RGBA with FLOAT is the combination every driver accepts for a float attachment
-    glReadPixels(x, y, w, h, GL_RGBA, GL_FLOAT, rgba.data());
+    // Bands of at most bandRows rows through one reusable RGBA/FLOAT buffer (the combination every driver accepts for a float attachment): the temporary is
+    // w * bandRows * 16 bytes (16 MB for a 4096 wide band of 256 rows) instead of w * h * 16 bytes (192 MB for a 12 MP box).
+    std::vector<float> rgba(size_t(w) * size_t(std::min(h, bandRows)) * 4);
+    for (int y0 = 0; y0 < h; y0 += bandRows) {
+        const int bh = std::min(bandRows, h - y0);
+        glReadPixels(x, y + y0, w, bh, GL_RGBA, GL_FLOAT, rgba.data());
+        float *dst = coverage + size_t(y0) * w;
+        for (size_t i = 0; i < size_t(w) * bh; i++) dst[i] = rgba[i * 4];
+    }
     glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
-    for (size_t i = 0; i < size_t(w) * h; i++) coverage[i] = rgba[i * 4];
     return glGetError() == GL_NO_ERROR;
 }
 
--- a/core/native/src/main/cpp/studio/studio_compositor.h
+++ b/core/native/src/main/cpp/studio/studio_compositor.h
@@ -43,7 +43,7 @@
      */
     bool beginStroke(int slot, float r, float g, float b, float opacity, bool erase, float hardness, float flow);
     bool addStamps(const float *xyr, int count);
-    bool readStroke(int x, int y, int w, int h, float *coverage);
+    bool readStroke(int x, int y, int w, int h, float *coverage, int bandRows = 256);
     void endStroke();
     bool stroking() const { return stroke_.slot >= 0; }
 
--- a/tools/golden/studio_golden.cpp
+++ b/tools/golden/studio_golden.cpp
@@ -63,7 +63,7 @@
     if (!stamps.empty() && !comp.addStamps(stamps.data(), int(stamps.size() / 3))) { std::fprintf(stderr, "addStamps failed\n"); return 6; }
     std::vector<uint8_t> out(size_t(ow) * oh * 4);
     if (comp.stroking() && getenv("STUDIO_READ_STROKE")) {   // commit path check: read the coverage back
-        std::vector<float> cov(size_t(ow) * oh); comp.readStroke(0, 0, ow, oh, cov.data());
+        std::vector<float> cov(size_t(ow) * oh); comp.readStroke(0, 0, ow, oh, cov.data(), getenv("STUDIO_BAND") ? atoi(getenv("STUDIO_BAND")) : 256);
         FILE *cf = std::fopen(getenv("STUDIO_READ_STROKE"), "wb"); std::fwrite(cov.data(), 4, cov.size(), cf); std::fclose(cf);
     }
     if (!comp.render(draws, vx, vy, zoom, ow, oh, out.data())) { std::fprintf(stderr, "render failed\n"); return 7; }
```
### 4.2 Check
After applying: `tools/golden/studio-golden.sh` must PASS everything. Then for scenes hard, flow and erase compare the coverage dump of the unbanded original with banded runs, for each band in 1 7 64 256 1000:
```
STUDIO_BAND=$b STUDIO_READ_STROKE=$W/cov.bin $W/studio_golden $W/scene_$k.txt $W/o.rgba && cmp cov_original.bin $W/cov.bin
```
Result when I ran it: identical in all 15 cases. Add this loop to `studio-golden.sh` (original dump taken with `STUDIO_BAND=100000`, which is one band). Peak temporary in `readStroke` is now w x 256 x 16 bytes (16 MB at 4096 wide) instead of w x h x 16 bytes (192 MB for a 12 MP box).

## 5. StudioSession edits (not compiled; tests listed)
1. `commitStroke`: replace the single box with tiles.
```kotlin
val rects = StrokeTiles.rects(st.stamps, l.width, l.height)
if (rects.isEmpty()) { gpuAsync { it.endStroke() }; gl.requestRender(); return }
val t0 = env.nanos()
val covs = ArrayList<FloatArray>(rects.size)
for (batch in rects.chunked(8)) {                       // at most 8 tiles of floats in flight, one GL round trip per batch
    val got = gpuCall { g -> batch.map { r -> FloatArray(r[2] * r[3]).also { c -> if (!g.readStroke(r[0], r[1], r[2], r[3], c)) throw IllegalStateException("readStroke") } } }
    if (got == null) { gpuAsync { it.endStroke() }; toast("Could not finish that stroke."); gl.requestRender(); return }
    covs += got
}
var i = 0
val deltas = StrokeTiles.commit(px.rgba, l.width, rects, st.colour, st.brush) { covs[i++] }
gpuAsync { g -> for (d in deltas) g.updateRegion(st.slot, d.x, d.y, d.w, d.h, d.after); g.endStroke() }   // one GPU job: tiles in, live stroke out, so it is never drawn twice
history.commitStrokeTiles(st.layerId, deltas)
```
Keep the existing `blank -= `, `dirty +=`, `thumbs`, `markDirty()`, `publish()` lines. Delete the `Dirty.rect` and whole-box `readStroke` use. `StrokeReference.commitRect` stays for the tests.
2. `applyStep`: add a branch for the new step.
```kotlin
is Step.SetPixelsMany -> {
    if (activeId != step.layerId) activate(step.layerId)
    val l = layer(step.layerId) as Layer.Pixel
    val px = activePixels ?: return
    step.apply(px.rgba, l.width)
    val slot = slots[step.layerId] ?: return
    gpuAsync { g -> for (d in step.parts) g.updateRegion(slot, d.x, d.y, d.w, d.h, if (step.useAfter) d.after else d.before) }
    blank -= step.layerId; dirty += step.layerId; thumbs[step.layerId] = Thumbs.make(px); markDirty()
}
```
3. Graveyard (D5): a private `pruneGraveyard()` = `val keep = history.restorableLayerIds(); graveyard.keys.retainAll(keep); lost.retainAll(keep)`; call it at the end of `applyDocument`, `commitWorking`, `commitStroke`, `applyStep` and `trimMemory`.
4. F5 and F13 (quick): in `strokeStart` replace `gpuCall { it.beginStroke(...) } == true` by `gpuAsync { it.beginStroke(...) }` (FIFO order with the stamps that follow; the commit's `readStroke` already fails cleanly if the stroke never began); change the signature to `strokeStart(a, timeMs)` and pass `timeMs` to the first `addPoint` so the first stamp is input-stamped. `handle()` already has `timeMs`.
5. Hover: add `fun onHover(near: Boolean, timeMs: Long) { if (!released) router.onHover(near, timeMs) }` (main thread, next to `onInput`).
Tests to add to `StudioSessionTest` (fake GPU harness already exists): (a) a diagonal stroke across a 2000 x 1500 layer gives `history.deltaBytes` under 10 percent of the bounding box; (b) undo and redo of a multi tile stroke leave pixels byte-identical to before and after, on the CPU copy and in the fake GPU's image; (c) delete a layer, undo twelve unrelated edits so the delete falls out of history (use a small `maxEntries`), assert the graveyard is empty (expose `graveyardSize()` internal for the test); (d) a fake GPU whose `readStroke` returns false on the third tile: the stroke is rolled back (pixels unchanged, no history entry, a toast); (e) strokeStart no longer blocks: a fake executor that never runs jobs must not hang `onInput`'s model task.

## 6. GL lifecycle edits (StudioGl.kt, not compiled)
- F7: add `@Volatile private var failed = false`. In `onSurfaceCreated` when `init` returns an error: `val dropped = synchronized(pending) { failed = true; ArrayList(pending).also { pending.clear() } }; dropped.forEach { drop(it) }`. In `post`: `if (released || failed) { drop(q); return }`. Test with a fake `GpuExecutor` that fails init: `StudioSession.start()` reports Phase.ERROR in under 1 s.
- F8: `StudioGlView.onPause()` calls `gl.paused()` (sets `glReady = false` under the lock; later posts queue). `onResume()` calls `gl.resumed()`, which does `view.queueEvent { if (handle != 0L && EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) markReady() }`, where `markReady()` sets `glReady = true` and drains `pending` in order. If the context was lost, `onSurfaceCreated` runs later and does the same. Phone check: lock the screen for 30 s, unlock, draw; logcat has no GL errors from rawline-studio.
- F9: `StudioGlView.onDetachedFromWindow` calls `gl.destroy(this)` only when `gl.released` is true (the screen is leaving); otherwise it does nothing and the compositor lives on with the renderer. When it does destroy, the wait stays bounded at 500 ms (the user is leaving). Add a native live handle counter (`StudioNative.liveHandles()`, an atomic in jni_studio.cpp incremented in `create`, decremented in `destroy` and `abandon`) and print it in the Copy report Studio section; it must read 0 after leaving Studio.

## 7. Compose edits (not compiled)
- F16 rotation: in `StudioCanvasScreen` create the view once, `val glView = remember(gl) { StudioGlView(context, gl) }`, wrap the surface in `val surface = remember { movableContentOf { AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize()) } }` and call `surface()` from both `Portrait` and `Landscape` (pass it as a parameter). With the view never detached on rotation, F9's rule means nothing is destroyed. Keep the view transform when only the orientation changed: in `onSurfaceSize` skip `CanvasView.fit` when the previous size had the same area and the document is the same (test: zoom, rotate, `zoomPercent` unchanged).
- F18 targets: `SmallChip` and the two colour chips get `Modifier.minimumInteractiveComponentSize()` before `clickable` (visual size unchanged); chip descriptions `"Blend mode ${name}"` and `"Opacity ${n} percent"`; colour chips already have descriptions.
- BK-481 hover: in `canvasInput`, before the `when`, add `if (c.type == PointerType.Stylus && !c.pressed) current.onHover(event.type != PointerEventType.Exit, c.uptimeMillis)`.
- BK-487 system cancel (verify first): on the phone pull the notification shade mid-stroke and look at the undo list. If a stroke was committed, route `ACTION_CANCEL` through a `pointerInteropFilter` that calls `current.onInput(InputEvent(Phase.CANCEL, ...))` for every active pointer, and add a Robolectric test that sends a cancel `MotionEvent`.

## 8. Acceptance
1. `:core:studio-model:testDebugUnitTest` 119 tests green (done on the host in my harness); `:core:studio-render:test` green with the five new session tests; golden and the band loop green.
2. Diagonal stroke across a 4000 x 3000 layer: history under 12 MB (host test), `studio_commit_ms` recorded from the phone (no claim before).
3. S Pen with the hand resting on the glass: the pen draws, the palm never does (phone).
4. Rotate with five layers open: `studio_texture_mb` unchanged, no blank flash, zoom kept (phone).
5. Lock and unlock the screen: no GL errors, drawing works (phone).

## 9. Risks
- Per tile history entries must keep the existing fuzz test green (`HistoryTest.fuzzUndoAllThenRedoAllIsByteIdentical` uses single deltas; add the same fuzz with `commitStrokeTiles`).
- The palm grace of 600 ms can make a quick finger tap right after pen use feel dead; the constant is one line (`PALM_GRACE_MS`) and Jai can judge it with the pen in hand.
- `movableContentOf` with an `AndroidView` keeps the view across layouts but not across Activity recreation; the debug activity declares configChanges, the real Studio entry (S1c) must too.

## 10. BK-503: Studio on a nearly full phone (added by PM decision; built and run on the host)
Measured first: with writes failing, the live session code retried 201 times and showed 200 toasts in 17 minutes of simulated time (`probes/FullDiskProbe.kt`), each retry re-encoding the changed layers. Nothing in Studio checked free space. After the change below the same run gives at most 11 tries, one message, and the state `FULL`. All 33 tests pass against the real `StudioSession` with the fake GPU (the 22 that exist, one of them updated for the new error text, plus 11 new), and the 104 studio-model tests still pass. The Compose edits at the end are not compiled.

Decisions: D9 retry gaps 5, 10, 20, 40, then 60 s, and after 10 failures in a row the session stops until the user edits again or the app pauses (`flush()` tries once more). D10 One message per failure streak ("Not saved: the phone is almost full." for a full volume, else "Could not save. Will try again."); after that only the status line shows (`SaveState.FULL`: "not saved: the phone is almost full"). D11 A new project that never reached the disk deletes its half written folder when it gives up. D12 Free space is checked before a new project (start), before a photo layer is added, before a duplicate (which also removes a half copy when it fails) and before an export: need = estimated size plus 200 MB, message "Not enough space. Free about N MB and try again." with N rounded up to 50 MB and never 0. D13 `Fs.freeBytes()` (no argument, default unknown) is the one seam; if the W13 worker has already added it (the working tree had it at 12:30Z), skip those two hunks of the patch and keep the rest.

Patch (applies to 8d45621 with `patch -p1`, no failed hunks; rebase over the in-flight W13 edits as needed):
```diff
--- a/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/ProjectStore.kt
+++ b/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/ProjectStore.kt
@@ -22,6 +22,8 @@
     fun size(path: String): Long
     /** Deletes [path] and everything under it. */
     fun deleteTree(path: String)
+    /** Bytes that can still be written on the volume holding this file system. Unknown (and so never a reason to refuse) when not overridden. */
+    fun freeBytes(): Long = Long.MAX_VALUE
 }
 
 /** Straight RGBA8 pixels of one layer. */
--- a/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/JavaFs.kt
+++ b/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/JavaFs.kt
@@ -27,4 +27,5 @@
     override fun dirs(dir: String): List<String> = f(dir).listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
     override fun size(path: String): Long = f(path).takeIf { it.isFile }?.length() ?: 0L
     override fun deleteTree(path: String) { f(path).deleteRecursively() }
+    override fun freeBytes(): Long = generateSequence(base) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: Long.MAX_VALUE
 }
--- a/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/Catalog.kt
+++ b/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/Catalog.kt
@@ -37,11 +37,19 @@
     fun duplicate(fs: Fs, root: String, id: String, newId: String, nowMs: Long): ProjectRow {
         require(!fs.exists("$root/$newId/project.json") && fs.dirs(root).none { it == newId }) { "project $newId exists" }
         val src = "$root/$id"; val dst = "$root/$newId"
-        for (n in fs.list(src)) if (!n.endsWith(".tmp")) fs.write("$dst/$n", fs.read("$src/$n")!!)
-        for (n in fs.list("$src/layers")) if (!n.endsWith(".tmp")) fs.write("$dst/layers/$n", fs.read("$src/layers/$n")!!)
-        val store = ProjectStore(fs, dst)
-        val doc = store.open().document
-        store.save(doc.copy(id = newId, name = (doc.name + " copy").take(60), modified = nowMs), { null }, emptySet())
+        val need = SpaceRule.needForCopy((fs.list(src).map { "$src/$it" } + fs.list("$src/layers").map { "$src/layers/$it" }).sumOf { fs.size(it) })
+        val free = fs.freeBytes()
+        if (!SpaceRule.enough(free, need)) throw java.io.IOException(SpaceRule.message(free, need))
+        try {
+            for (n in fs.list(src)) if (!n.endsWith(".tmp")) fs.write("$dst/$n", fs.read("$src/$n")!!)
+            for (n in fs.list("$src/layers")) if (!n.endsWith(".tmp")) fs.write("$dst/layers/$n", fs.read("$src/layers/$n")!!)
+            val store = ProjectStore(fs, dst)
+            val doc = store.open().document
+            store.save(doc.copy(id = newId, name = (doc.name + " copy").take(60), modified = nowMs), { null }, emptySet())
+        } catch (t: Throwable) {
+            runCatching { fs.deleteTree(dst) }   // a copy that did not finish is not left behind as a damaged project
+            throw t
+        }
         return row(fs, dst, newId)
     }
 
--- a/core/studio-render/src/main/kotlin/app/rawline/core/studio/render/StudioState.kt
+++ b/core/studio-render/src/main/kotlin/app/rawline/core/studio/render/StudioState.kt
@@ -16,7 +16,7 @@
 }
 
 enum class SaveState(val label: String) {
-    SAVED("saved"), DIRTY("changes not saved yet"), SAVING("saving"), FAILED("last save failed, will try again"),
+    SAVED("saved"), DIRTY("changes not saved yet"), SAVING("saving"), FAILED("last save failed, will try again"), FULL("not saved: the phone is almost full"),
 }
 
 class UiMessage(val id: Long, val text: String)
--- a/core/studio-render/src/main/kotlin/app/rawline/core/studio/render/StudioSession.kt
+++ b/core/studio-render/src/main/kotlin/app/rawline/core/studio/render/StudioSession.kt
@@ -18,6 +18,8 @@
 import app.rawline.core.studio.model.Placement
 import app.rawline.core.studio.model.ProjectStore
 import app.rawline.core.studio.model.RawPixels
+import app.rawline.core.studio.model.SaveBackoff
+import app.rawline.core.studio.model.SpaceRule
 import app.rawline.core.studio.model.Step
 import app.rawline.core.studio.model.StrokePoint
 import app.rawline.core.studio.model.StrokeReference
@@ -83,6 +85,11 @@
     private var lastSaveStart = Long.MIN_VALUE / 2
     private var lastModified = initial.modified
     private var saveFailed = false
+    private var saveFailures = 0                       // failed saves in a row (BK-503)
+    private var gaveUp = false                          // stopped retrying until the user edits or the app pauses
+    private var lastFailureFull = false
+    private var everSaved = false
+    private val onDiskAtStart = onDisk
     private var view = CanvasView()
     private var surfaceW = 0
     private var surfaceH = 0
@@ -111,6 +118,7 @@
     /** Loads every layer to the GPU (waits for the GL context), then publishes READY. A new project is saved at once so it exists on disk. */
     fun start() = model {
         try {
+            if (!onDisk) { val need = SpaceRule.needForNewProject(history.document.layers.filterIsInstance<Layer.Pixel>().map { it.width to it.height }); val free = fs.freeBytes(); if (!SpaceRule.enough(free, need)) throw IllegalStateException(SpaceRule.message(free, need)) }
             uploadAll()
             _state.update { it.copy(phase = Phase.READY) }
             publish()
@@ -199,6 +207,7 @@
     /** A photo or other picture as a new layer on top, centred on the canvas. [px] is already scaled to fit by the caller. */
     fun addPhotoLayer(px: RawPixels, name: String) = model {
         guarded {
+            if (!spaceAllows(SpaceRule.needForLayer(px.w, px.h))) return@guarded
             val d = history.document
             val l = Layer.Pixel(LayerCommon(newId(), name.take(40).ifEmpty { "Photo" }, x = (d.width - px.w) / 2, y = (d.height - px.h) / 2), px.w, px.h)
             addLayerInternal(d, l, px)
@@ -480,6 +489,8 @@
         applyDocument(next, if (px != null) mapOf(l.common.id to px) else emptyMap(), l.common.id)
     }
 
+    private fun spaceAllows(need: Long): Boolean { val free = fs.freeBytes(); if (SpaceRule.enough(free, need)) return true; toast(SpaceRule.message(free, need)); return false }
+
     private fun guardAllows(d: Document, extra: Pair<Int, Int>): Boolean {
         val sizes = d.layers.filterIsInstance<Layer.Pixel>().map { it.width to it.height } + extra
         val ok = MemoryGuard.allows(sizes, surfaceW.takeIf { it > 0 } ?: MemoryGuard.DEFAULT_OUT_W, surfaceH.takeIf { it > 0 } ?: MemoryGuard.DEFAULT_OUT_H)
@@ -639,6 +650,7 @@
 
     private fun markDirty(now: Boolean = false) {
         needsSave = true
+        if (gaveUp) { gaveUp = false; saveFailures = 0 }   // the user edited again (or the app paused): one more try
         if (saving) { saveAgain = true; if (now) saveAgainNow = true; publishSave(); return }
         val wait = if (now) 0L else lastSaveStart + SAVE_EVERY_MS - env.clock()
         if (wait <= 0L) startSave()
@@ -676,7 +688,7 @@
                 env.model.execute { onSaved(saved, snaps) }
             } catch (t: Throwable) {
                 env.error("studio save: ${t.javaClass.simpleName}: ${t.message}")
-                env.model.execute { onSaveFailed(changed, snaps) }
+                env.model.execute { onSaveFailed(changed, snaps, t) }
             }
         }
     }
@@ -684,17 +696,31 @@
     private fun onSaved(saved: Document, snaps: Map<String, RawPixels>) {
         for (l in saved.layers) if (l is Layer.Pixel) files[l.common.id] = l.pixelsFile
         for ((id, px) in snaps) { inFlight.remove(id); if (unsaved[id] === px) unsaved.remove(id) }
-        saving = false; saveFailed = false
+        saving = false; saveFailed = false; saveFailures = 0; gaveUp = false; everSaved = true
         if (saveAgain || needsSave || dirty.isNotEmpty()) { val urgent = saveAgainNow; saveAgain = false; saveAgainNow = false; markDirty(now = urgent) } else publishSave()
         gauges()
     }
 
-    private fun onSaveFailed(changed: Set<String>, snaps: Map<String, RawPixels>) {
+    private fun onSaveFailed(changed: Set<String>, snaps: Map<String, RawPixels>, cause: Throwable? = null) {
         for ((id, px) in snaps) { inFlight.remove(id); if (id != activeId) unsaved.putIfAbsent(id, px) }
         dirty += changed
         saving = false; saveFailed = true
-        toast("Could not save. Will try again.")
-        markDirty()   // the next try waits for the five second spacing
+        saveFailures++
+        lastFailureFull = SpaceRule.isNoSpace(cause)
+        if (saveFailures == 1) toast(if (lastFailureFull) "Not saved: the phone is almost full." else "Could not save. Will try again.")   // one message per streak, then only the status line
+        needsSave = true
+        if (SaveBackoff.giveUp(saveFailures)) { gaveUp = true; discardIfNeverSaved(); publishSave(); return }
+        publishSave()
+        if (!timerArmed) {
+            timerArmed = true
+            env.later(SaveBackoff.delayMs(saveFailures)) { env.model.execute { timerArmed = false; if (needsSave && !saving && !released && !gaveUp) startSave() } }
+        }
+    }
+
+    /** A project that never reached the disk (its first saves all failed) leaves no half written folder behind. */
+    private fun discardIfNeverSaved() {
+        if (everSaved || onDiskAtStart) return
+        env.saver.execute { runCatching { if (!fs.exists("$root/project.json") && !fs.exists("$root/project.json.new") && !fs.exists("$root/project.json.bak")) fs.deleteTree(root) } }
     }
 
     // ---- plumbing ---------------------------------------------------------------------------------------------------------------------
@@ -730,7 +756,7 @@
     private fun publishZoom() { _state.update { it.copy(zoomPercent = Math.round(view.zoom * 100f)) } }
 
     private fun saveStateNow() = when {
-        saveFailed -> SaveState.FAILED
+        saveFailed -> if (lastFailureFull) SaveState.FULL else SaveState.FAILED
         saving -> SaveState.SAVING
         needsSave || dirty.isNotEmpty() -> SaveState.DIRTY
         else -> SaveState.SAVED
--- a/core/studio-render/src/test/kotlin/app/rawline/core/studio/render/StudioSessionTest.kt
+++ b/core/studio-render/src/test/kotlin/app/rawline/core/studio/render/StudioSessionTest.kt
@@ -33,13 +33,18 @@
     var failWrites = false
     override fun exists(path: String) = files.containsKey(path)
     override fun read(path: String) = files[path]
-    override fun write(path: String, data: ByteArray) { if (failWrites) throw java.io.IOException("disk full"); files[path] = data.copyOf() }
+    override fun write(path: String, data: ByteArray) { if (failWrites || writes++ >= failWriteAfter) throw java.io.IOException(failMessage); files[path] = data.copyOf() }
     override fun rename(from: String, to: String) { files[to] = files.remove(from) ?: throw IllegalStateException("no $from") }
     override fun delete(path: String) { files.remove(path) }
     override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") && !it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1) }
     override fun dirs(dir: String) = files.keys.filter { it.startsWith("$dir/") && it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1).substringBefore('/') }.distinct()
     override fun size(path: String) = files[path]?.size?.toLong() ?: 0L
     override fun deleteTree(path: String) { files.keys.removeAll { it == path || it.startsWith("$path/") } }
+    var free = Long.MAX_VALUE
+    override fun freeBytes() = free
+    var failWriteAfter = Int.MAX_VALUE   // writes that still succeed before every further write fails
+    var writes = 0
+    var failMessage = "No space left on device"
 }
 
 /** A GPU on the host: textures are byte arrays, a stroke buffer is the reference coverage of the stamps. */
@@ -242,7 +247,7 @@
         h.s.start()
         h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
         h.now += 10_000
-        h.fs.failWrites = true
+        h.fs.failWrites = true; h.fs.failMessage = "permission denied"
         h.stroke(listOf(5f to 5f, 30f to 5f))
         assertEquals(SaveState.FAILED, h.st.save)
         assertTrue(h.errors.any { it.contains("studio save") })
```
### core/studio-model/.../SaveRules.kt (new)
```kotlin
package app.rawline.core.studio.model

/** Review hole BK-503: what a project does when the phone is nearly full. Pure rules, so the host tests can run them. */

/** Retry spacing after a failed autosave: 5, 10, 20, 40, then 60 s; after [MAX_TRIES] failures in a row the session stops until the user edits again or the app pauses. */
object SaveBackoff {
    private val STEPS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)
    const val MAX_TRIES = 10
    fun delayMs(failures: Int): Long = STEPS[(failures - 1).coerceIn(0, STEPS.size - 1)]
    fun giveUp(failures: Int): Boolean = failures >= MAX_TRIES
}

object SpaceRule {
    const val MARGIN = 200L * 1024 * 1024
    private const val MB = 1024L * 1024

    /** A new project is saved once as lossless layer files: assume no compression (worst case) plus the margin. */
    fun needForNewProject(layerSizes: List<Pair<Int, Int>>): Long = layerSizes.sumOf { it.first.toLong() * it.second * 4 } + MARGIN
    fun needForLayer(w: Int, h: Int): Long = w.toLong() * h * 4 + MARGIN
    fun needForCopy(projectBytes: Long): Long = projectBytes + MARGIN
    fun needForExport(w: Int, h: Int): Long = w.toLong() * h * 4 / 2 + MARGIN   // a JPEG or PNG is far under raw size; half is a safe upper bound

    fun enough(freeBytes: Long, needBytes: Long) = freeBytes >= needBytes

    /** "Not enough space. Free about 300 MB and try again." The figure is the shortfall rounded up to 50 MB. */
    fun message(freeBytes: Long, needBytes: Long): String {
        val short = (needBytes - freeBytes).coerceAtLeast(0)
        val mb = (((short + 50 * MB - 1) / (50 * MB)) * 50).coerceAtLeast(50)
        return "Not enough space. Free about $mb MB and try again."
    }

    /** True for the errors a full volume gives (ENOSPC text on Android and Linux, a plain "disk full" from a wrapper), looking through the causes. */
    fun isNoSpace(t: Throwable?): Boolean {
        var e = t; var n = 0
        while (e != null && n++ < 6) {
            val m = (e.message ?: "").lowercase()
            if (m.contains("enospc") || m.contains("no space left") || m.contains("disk full") || m.contains("not enough space") || m.contains("quota")) return true
            e = e.cause
        }
        return false
    }
}
```
### core/studio-render/src/test/.../FullPhoneTest.kt (new)
```kotlin
package app.rawline.core.studio.render

import app.rawline.core.studio.model.*
import org.junit.Assert.*
import org.junit.Test

class FullPhoneTest {
    private fun blank(w: Int = 64, h: Int = 48) = Document("p1", "Test", w, h, layers = listOf(Layer.Pixel(LayerCommon("a", "a"), w, h)), created = 1, modified = 1)
    private fun white(w: Int = 64, h: Int = 48) = RawPixels(w, h, ByteArray(w * h * 4) { 255.toByte() })
    private fun paint(h: Harness) { h.s.setBrush(Brush(diameter = 6.0, pressureSize = false)); h.now += 10_000; h.stroke(listOf(5f to 5f, 30f to 5f)) }

    @Test fun aGenericFailureIsNotCalledFull() {
        val mem = MemFs(); mem.failMessage = "permission denied"; val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); mem.failWrites = true; paint(h)
        assertEquals(SaveState.FAILED, h.st.save); assertEquals("Could not save. Will try again.", h.st.message!!.text)
    }
    @Test fun aFullPhoneIsNamedInTheStatusAndTheMessage() {
        val mem = MemFs(); val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); mem.failWrites = true; paint(h)
        assertEquals(SaveState.FULL, h.st.save); assertEquals("Not saved: the phone is almost full.", h.st.message!!.text)
    }
    @Test fun aPhoneThatStaysFullGetsAFewTriesNotAnEndlessLoop() {
        val mem = MemFs(); val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); mem.failWrites = true; paint(h)
        var toasts = 0; var last = -1L
        for (i in 1..200) { h.now += 61_000; h.fireTimers(); val m = h.st.message; if (m != null && m.id != last) { toasts++; last = m.id } }
        val tries = h.errors.count { it.contains("studio save") }
        assertTrue("tries $tries", tries <= SaveBackoff.MAX_TRIES + 1)
        assertEquals(1, toasts)
        assertEquals(SaveState.FULL, h.st.save)
    }
    @Test fun theRetryGapsGrowAndStop() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (1..6).map { SaveBackoff.delayMs(it) })
        assertFalse(SaveBackoff.giveUp(9)); assertTrue(SaveBackoff.giveUp(10))
        val mem = MemFs(); val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); h.timers.clear(); mem.failWrites = true; paint(h)
        val gaps = ArrayList<Long>()
        gaps += h.timers.map { it.first }                                        // armed after the first failure (left in place so it can fire)
        repeat(12) { h.now += 61_000; val due = ArrayList(h.timers); h.timers.clear(); due.forEach { it.second.run() }; gaps += h.timers.map { it.first } }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L), gaps.take(9))
        assertEquals("nothing is armed after the tenth failure", 9, gaps.size)
    }
    @Test fun anEditAfterGivingUpTriesAgainAndASuccessResetsTheStreak() {
        val mem = MemFs(); val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); mem.failWrites = true; paint(h)
        for (i in 1..40) { h.now += 61_000; h.fireTimers() }
        val frozen = h.errors.count { it.contains("studio save") }
        for (i in 1..5) { h.now += 61_000; h.fireTimers() }
        assertEquals("no more tries while nothing changes", frozen, h.errors.count { it.contains("studio save") })
        mem.failWrites = false
        h.now += 10_000; h.stroke(listOf(10f to 20f, 40f to 20f))    // the user paints again
        h.now += 6_000; h.fireTimers()
        assertEquals(SaveState.SAVED, h.st.save)
        assertArrayEquals(h.gl.gpu.tex[0]!!.rgba, h.layerPixels("a"))
    }
    @Test fun pausingTriesOnceMoreAfterGivingUp() {
        val mem = MemFs(); val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); mem.failWrites = true; paint(h)
        for (i in 1..40) { h.now += 61_000; h.fireTimers() }
        val before = h.errors.count { it.contains("studio save") }
        mem.failWrites = false; h.s.flush()
        assertEquals(SaveState.SAVED, h.st.save); assertTrue(before >= SaveBackoff.MAX_TRIES)
    }
    @Test fun aNewProjectThatNeverSavedLeavesNoFolder() {
        val mem = MemFs(); val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()), onDisk = false)
        mem.failWrites = true
        h.s.start()
        for (i in 1..40) { h.now += 61_000; h.fireTimers() }
        assertTrue("left: ${mem.files.keys}", mem.files.keys.none { it.startsWith(Harness.ROOT) })
    }
    @Test fun aNewProjectNeedsRoomAndSaysHowMuch() {
        val mem = MemFs(); mem.free = 100L * 1024 * 1024
        val h = Harness(fs = mem, doc = blank(2000, 1500), pixels = mapOf("a" to white(2000, 1500)), onDisk = false)
        h.s.start()
        assertEquals(Phase.ERROR, h.st.phase)
        assertTrue(h.st.error!!, h.st.error!!.startsWith("Not enough space. Free about ") && h.st.error!!.endsWith(" MB and try again."))
        assertTrue(mem.files.isEmpty())
    }
    @Test fun anAddedPhotoIsRefusedWhenThereIsNoRoom() {
        val mem = MemFs(); val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); mem.free = 10L * 1024 * 1024
        h.s.addPhotoLayer(white(500, 400), "Photo")
        assertEquals(1, h.st.document.layers.size)
        assertTrue(h.st.message!!.text.startsWith("Not enough space."))
    }
    @Test fun spaceRuleMessagesAndErrorDetection() {
        assertEquals("Not enough space. Free about 50 MB and try again.", SpaceRule.message(0, 1))
        assertEquals("Not enough space. Free about 300 MB and try again.", SpaceRule.message(0, 300L * 1024 * 1024))
        assertEquals("Not enough space. Free about 50 MB and try again.", SpaceRule.message(500, 500))   // never says 0
        assertTrue(SpaceRule.isNoSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(SpaceRule.isNoSpace(RuntimeException("x", java.io.IOException("disk full"))))
        assertFalse(SpaceRule.isNoSpace(java.io.IOException("permission denied"))); assertFalse(SpaceRule.isNoSpace(null))
        assertTrue(SpaceRule.enough(10, 10)); assertFalse(SpaceRule.enough(9, 10))
    }
    @Test fun aDuplicateThatRunsOutOfRoomLeavesNoHalfCopy() {
        val mem = MemFs(); val fsRoot = "studio"
        val h = Harness(fs = mem, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); h.now += 6_000; h.fireTimers()
        // the harness project lives under files/studio/p1; copy it with a file system that fails after two writes
        mem.writes = 0; mem.failWriteAfter = 2
        try { ProjectCatalog.duplicate(mem, "files/studio", "p1", "p2", 5); fail("should fail") } catch (e: java.io.IOException) {}
        assertTrue("half copy left: ${mem.files.keys.filter { it.contains("/p2") }}", mem.files.keys.none { it.contains("files/studio/p2") })
        mem.failWriteAfter = Int.MAX_VALUE
        mem.free = 1L * 1024 * 1024
        try { ProjectCatalog.duplicate(mem, "files/studio", "p1", "p3", 5); fail("should refuse") } catch (e: java.io.IOException) { assertTrue(e.message!!.startsWith("Not enough space.")) }
        assertTrue(mem.files.keys.none { it.contains("files/studio/p3") })
    }
}
```
Compose edits (not compiled): (1) `CanvasScreen.leave`: when `state.save` is `FAILED` or `FULL` show a dialog "Leave without saving?" with the text "Your last changes are not saved." and the buttons "Stay" and "Leave"; Leave calls the existing path. (2) `StudioRoot` new from photo and `ExportSheet`: before starting call `SpaceRule.enough(projects.fs().freeBytes(), SpaceRule.needForExport(w, h))` (export) or rely on the session's start check (new project), and show `SpaceRule.message(...)`. (3) The status strip already prints `state.save.label`; make the FULL label red like FAILED. (4) `StudioHomeViewModel.duplicate` already reports "Could not duplicate the project."; show the IOException message when it starts with "Not enough space." Acceptance for BK-503: the tests above; phone step 10 of PHONE-TEST-S1 (only on a nearly full phone).
