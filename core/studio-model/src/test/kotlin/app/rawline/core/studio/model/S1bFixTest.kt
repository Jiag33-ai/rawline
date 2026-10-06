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
