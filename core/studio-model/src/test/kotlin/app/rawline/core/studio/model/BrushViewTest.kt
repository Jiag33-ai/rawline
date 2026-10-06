package app.rawline.core.studio.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushMathTest {
    @Test fun falloffIsOneInsideTheHardCoreZeroOutsideAndSmoothBetween() {
        assertEquals(1.0, BrushMath.falloff(0.0, 10.0, 0.5), 0.0)
        assertEquals(1.0, BrushMath.falloff(5.0, 10.0, 0.5), 0.0)
        assertEquals(0.0, BrushMath.falloff(10.0, 10.0, 0.5), 0.0)
        assertEquals(0.5, BrushMath.falloff(7.5, 10.0, 0.5), 1e-12)   // halfway through the ramp: smoothstep(0.5) = 0.5
        assertEquals(0.0, BrushMath.falloff(10.5, 10.0, 1.0), 0.0)
        assertEquals(1.0, BrushMath.falloff(9.99, 10.0, 1.0), 0.0)    // hardness 1 is a hard edge
    }

    @Test fun pressureScalesTheDiameterFromAFifthToFull() {
        assertEquals(2.0, BrushMath.diameterAt(10.0, 0.0, true), 1e-12)
        assertEquals(10.0, BrushMath.diameterAt(10.0, 1.0, true), 1e-12)
        assertEquals(10.0, BrushMath.diameterAt(10.0, 0.3, false), 1e-12)
    }

    @Test fun stampsSitEveryStepAlongAStraightLine() {
        val b = Brush(diameter = 20.0, spacing = 0.1, pressureSize = false)
        val s = BrushMath.walk(listOf(StrokePoint(10.0, 10.0), StrokePoint(60.0, 10.0)), b)
        assertEquals(26, s.size)                       // 0, 2, ..., 50
        assertEquals(10.0, s[0].x, 1e-12); assertEquals(12.0, s[1].x, 1e-9); assertEquals(60.0, s.last().x, 1e-9)
        assertEquals(10.0, s[0].radius, 1e-12)
    }

    @Test fun theSpacingNeverGoesBelowHalfAPixel() {
        val s = BrushMath.walk(listOf(StrokePoint(0.0, 0.0), StrokePoint(10.0, 0.0)), Brush(diameter = 3.0, spacing = 0.1, pressureSize = false))
        assertEquals(21, s.size)                       // 0.5 px steps, not 0.3
    }

    @Test fun slicingTheInputDoesNotMoveTheStamps() {
        val b = Brush(diameter = 17.0, spacing = 0.13, pressureSize = true)
        val whole = BrushMath.walk(listOf(StrokePoint(3.0, 4.0, 0.2), StrokePoint(40.0, 30.0, 0.9), StrokePoint(90.0, 10.0, 0.5)), b)
        // the same path with an extra point on the first segment (an input event in the middle): same stamps
        val sliced = BrushMath.walk(listOf(StrokePoint(3.0, 4.0, 0.2), StrokePoint(21.5, 17.0, 0.55), StrokePoint(40.0, 30.0, 0.9), StrokePoint(90.0, 10.0, 0.5)), b)
        assertEquals(whole.size, sliced.size)
        for (i in whole.indices) { assertEquals(whole[i].x, sliced[i].x, 1e-6); assertEquals(whole[i].y, sliced[i].y, 1e-6) }
    }

    @Test fun walkerMatchesTheIndependentPythonReference() {
        val text = javaClass.getResourceAsStream("/walker_vectors.tsv")!!.bufferedReader().readText()
        var n = 0
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (head, pts, out) = line.split(" | ")
            val h = head.trim().split(" ")
            val v = pts.trim().split(" ").map { it.toDouble() }
            val points = v.chunked(3).map { StrokePoint(it[0], it[1], it[2]) }
            val exp = out.removePrefix("-> ").trim().split(" ").filter { it.isNotEmpty() }.map { it.toDouble() }.chunked(3)
            val got = BrushMath.walk(points, Brush(diameter = h[0].toDouble(), spacing = h[1].toDouble(), pressureSize = h[2] == "1"))
            assertEquals("count in: $line", exp.size, got.size)
            for (i in exp.indices) { assertEquals(exp[i][0], got[i].x, 1e-5); assertEquals(exp[i][1], got[i].y, 1e-5); assertEquals(exp[i][2], got[i].radius, 1e-5) }
            n++
        }
        assertTrue(n >= 40)
    }

    @Test fun coverageAccumulatesWithOverAndFlow() {
        // two stamps on the same pixel at flow 0.5: 0.5 then 0.5 + 0.5 * 0.5 = 0.75
        val cov = StrokeReference.coverage(4, 4, listOf(Stamp(2.0, 2.0, 1.4), Stamp(2.0, 2.0, 1.4)), Brush(hardness = 1.0, flow = 0.5))
        assertEquals(0.75f, cov[1 * 4 + 1], 1e-6f)
        assertEquals(0f, cov[0], 0f)   // the corner pixel centre is 2.12 away
    }

    @Test fun commitPaintsOverAndEraseRemovesAlphaOnly() {
        val w = 2
        val cov = floatArrayOf(1f, 0.5f, 0f, 0f)
        val paint = ByteArray(w * 2 * 4) { if (it % 4 == 3) 255.toByte() else 0 }          // opaque black
        StrokeReference.commit(paint, w, cov, intArrayOf(0, 0, 2, 1), floatArrayOf(1f, 0f, 0f), Brush(opacity = 1.0))
        assertEquals(listOf(255, 0, 0, 255), (0..3).map { paint[it].toInt() and 255 })        // full coverage: pure red
        assertEquals(listOf(128, 0, 0, 255), (4..7).map { paint[it].toInt() and 255 })        // half coverage over black: half red
        val er = ByteArray(w * 2 * 4) { if (it % 4 == 3) 200.toByte() else 77 }
        StrokeReference.commit(er, w, cov, intArrayOf(0, 0, 2, 1), floatArrayOf(0f, 0f, 0f), Brush(opacity = 0.5, erase = true))
        assertEquals(listOf(77, 77, 77, 100), (0..3).map { er[it].toInt() and 255 })           // alpha 200 * (1 - 0.5)
        assertEquals(listOf(77, 77, 77, 150), (4..7).map { er[it].toInt() and 255 })           // alpha 200 * (1 - 0.25)
    }

    @Test fun commitRectEqualsCommitWithAFullCoverageArray() {
        val w = 30; val h = 22
        val rnd = java.util.Random(4)
        val layer = ByteArray(w * h * 4).also { rnd.nextBytes(it) }
        val brush = Brush(diameter = 9.0, hardness = 0.4, opacity = 0.8, flow = 0.7, pressureSize = false)
        val st = BrushMath.walk(listOf(StrokePoint(5.0, 5.0), StrokePoint(20.0, 14.0)), brush)
        val full = StrokeReference.coverage(w, h, st, brush)
        val r = Dirty.rect(st, w, h)
        val rectCov = FloatArray(r[2] * r[3]) { i -> full[(r[1] + i / r[2]) * w + r[0] + i % r[2]] }
        val a = layer.copyOf(); val b = layer.copyOf()
        StrokeReference.commit(a, w, full, r, floatArrayOf(0.2f, 0.7f, 0.1f), brush)
        StrokeReference.commitRect(b, w, r, rectCov, floatArrayOf(0.2f, 0.7f, 0.1f), brush)
        assertArrayEquals(a, b)
    }

    @Test fun dirtyRectangleCoversTheStampsAndIsClipped() {
        val r = Dirty.rect(listOf(Stamp(5.0, 5.0, 3.0), Stamp(95.0, 40.0, 10.0)), 100, 50)
        assertEquals(1, r[0]); assertEquals(1, r[1]); assertEquals(100, r[0] + r[2]); assertEquals(50, r[1] + r[3])   // x0 + w is the exclusive end: clipped to the layer
        assertEquals(0, Dirty.rect(listOf(Stamp(500.0, 500.0, 3.0)), 100, 50)[2])
    }
}

class ViewMathTest {
    @Test fun screenAndDocumentCoordinatesRoundTrip() {
        val v = CanvasView(10f, 20f, 2f)
        assertEquals(15f, v.toDocX(10f), 1e-6f); assertEquals(10f, v.toScreenX(15f), 1e-6f)
        assertEquals(v.toDocY(33f), v.toDocY(v.toScreenY(v.toDocY(33f))) , 1e-4f)
    }

    @Test fun zoomAboutAPointKeepsThatPointStill() {
        val v = CanvasView(10f, 20f, 1f)
        val z = v.zoomAbout(3f, 200f, 100f)
        assertEquals(v.toDocX(200f), z.toDocX(200f), 1e-4f); assertEquals(v.toDocY(100f), z.toDocY(100f), 1e-4f)
        assertEquals(3f, z.zoom, 0f)
    }

    @Test fun zoomIsClamped() {
        assertEquals(CanvasView.MAX_ZOOM, CanvasView().zoomAbout(1000f, 0f, 0f).zoom, 0f)
        assertEquals(CanvasView.MIN_ZOOM, CanvasView().zoomAbout(0.0001f, 0f, 0f).zoom, 0f)
    }

    @Test fun panMovesTheCanvasWithTheFinger() {
        val v = CanvasView(0f, 0f, 2f).panBy(40f, -20f)   // finger moves right and up by 40, 20 screen pixels: the picture follows
        assertEquals(-20f, v.x, 1e-6f); assertEquals(10f, v.y, 1e-6f)
    }

    @Test fun clampKeepsSomeCanvasOnScreen() {
        val v = CanvasView(5000f, -5000f, 1f).clamped(1000, 800, 400, 600)
        assertTrue(v.x <= 1000f - 64f); assertTrue(v.y >= (64f - 600f))
    }

    @Test fun fitCentresTheCanvas() {
        val v = CanvasView.fit(1000, 500, 400, 800)
        assertEquals(0.368f, v.zoom, 1e-3f)                                       // (400 - 32) / 1000
        assertEquals(16f, v.toScreenX(0f), 1e-3f); assertEquals(384f, v.toScreenX(1000f), 1e-2f)
        assertEquals(800f / 2f, (v.toScreenY(0f) + v.toScreenY(500f)) / 2f, 1e-2f)
    }
}

class PlacementAndColourTest {
    @Test fun scaleAboutAPointKeepsThePointFixed() {
        val (x, y, s) = Placement.scaleAbout(100, 50, 1f, 2f, 300f, 150f)
        assertEquals(2f, s, 0f)
        // the document point under (300, 150) was layer pixel (200, 100); after scaling by 2 it must still be under (300, 150)
        assertEquals(300f, x + 200f * 2f, 1f); assertEquals(150f, y + 100f * 2f, 1f)
    }

    @Test fun scaleIsClampedToAQuarterAndFour() {
        assertEquals(4f, Placement.scaleAbout(0, 0, 1f, 40f, 0f, 0f).third, 0f)
        assertEquals(0.25f, Placement.scaleAbout(0, 0, 1f, 0.001f, 0f, 0f).third, 0f)
    }

    @Test fun hsvRoundTripsAndKnownColours() {
        val red = Hsv.toRgb(0f, 1f, 1f); assertEquals(1f, red[0], 0f); assertEquals(0f, red[1], 0f)
        val g = Hsv.toRgb(1f / 3f, 1f, 1f); assertEquals(1f, g[1], 1e-6f); assertEquals(0f, g[0], 1e-6f)
        val rnd = java.util.Random(1)
        repeat(200) {
            val r = rnd.nextFloat(); val gg = rnd.nextFloat(); val b = rnd.nextFloat()
            val back = Hsv.fromRgb(r, gg, b).let { Hsv.toRgb(it[0], it[1], it[2]) }
            assertEquals(r, back[0], 1e-5f); assertEquals(gg, back[1], 1e-5f); assertEquals(b, back[2], 1e-5f)
        }
    }
}

/** stroke_small.txt is written by the independent Python reference (tools/studio/studio_brush.py small): one layer, then a paint and an erase case with the baked result. */
class StrokeCommitMatchesPythonTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun walkerCoverageAndCommitEqualThePythonReferenceWithinOneLevel() {
        val lines = javaClass.getResourceAsStream("/stroke_small.txt")!!.bufferedReader().readLines()
        val head = lines[0].split(" "); val w = head[1].toInt(); val h = head[2].toInt(); val layer = hex(head[3])
        var cases = 0
        for (line in lines.drop(1)) {
            val (a, pts, expHex) = line.split(" | ")
            val t = a.split(" ")
            val brush = Brush(diameter = t[2].toDouble(), hardness = t[3].toDouble(), flow = t[4].toDouble(), opacity = t[5].toDouble(), spacing = t[6].toDouble(), pressureSize = t[7] == "1", erase = t[1] == "erase")
            val colour = floatArrayOf(t[8].toFloat(), t[9].toFloat(), t[10].toFloat())
            val points = pts.trim().split(" ").map { it.toDouble() }.chunked(3).map { StrokePoint(it[0], it[1], it[2]) }
            val stamps = BrushMath.walk(points, brush)
            val cov = StrokeReference.coverage(w, h, stamps, brush)
            val px = layer.copyOf()
            StrokeReference.commit(px, w, cov, intArrayOf(0, 0, w, h), colour, brush)
            val exp = hex(expHex.trim())
            var worst = 0
            for (i in px.indices) worst = maxOf(worst, Math.abs((px[i].toInt() and 255) - (exp[i].toInt() and 255)))
            assertTrue("${t[1]}: worst difference $worst levels", worst <= 1)
            cases++
        }
        assertEquals(2, cases)
    }
}

class StrokeWalkerTest {
    @Test fun pointByPointEqualsAllAtOnce() {
        val rnd = java.util.Random(9)
        repeat(20) {
            val b = Brush(diameter = 4.0 + rnd.nextInt(40), spacing = 0.05 + rnd.nextDouble() * 0.3, pressureSize = rnd.nextBoolean())
            val pts = List(2 + rnd.nextInt(8)) { StrokePoint(rnd.nextDouble() * 200, rnd.nextDouble() * 200, rnd.nextDouble()) }
            val whole = BrushMath.walk(pts, b)
            val w = StrokeWalker(b); val live = pts.flatMap { w.add(it) }
            assertEquals(whole.size, live.size)
            for (i in whole.indices) { assertEquals(whole[i].x, live[i].x, 1e-9); assertEquals(whole[i].y, live[i].y, 1e-9); assertEquals(whole[i].radius, live[i].radius, 1e-9) }
        }
    }
}

class HistoryTest {
    private fun layer(id: String) = Layer.Pixel(LayerCommon(id, id), 40, 30)
    private fun doc() = Document("d", "D", 40, 30, layers = listOf(layer("a")))

    /** The exit check: random strokes and layer operations, then every undo returns byte-identical pixels and equal documents; redo returns the final state. */
    @Test fun fuzzUndoAllThenRedoAllIsByteIdentical() {
        val rnd = java.util.Random(5)
        val w = 40; val h = 30
        val pixels = ByteArray(w * h * 4).also { rnd.nextBytes(it) }
        for (i in 0 until w * h) if (rnd.nextInt(3) == 0) { pixels[i * 4 + 3] = 0; pixels[i * 4] = 0; pixels[i * 4 + 1] = 0; pixels[i * 4 + 2] = 0 }
        val original = pixels.copyOf()
        val hist = StudioHistory(doc())
        val docs = ArrayList<Document>(); docs.add(hist.document)
        repeat(60) { n ->
            if (n % 7 == 3) {
                hist.commitDocument(LayerOps.setOpacity(hist.document, "a", rnd.nextInt(101)))
            } else {
                val brush = Brush(diameter = 3.0 + rnd.nextInt(14), hardness = rnd.nextDouble(), opacity = 0.3 + rnd.nextDouble() * 0.7, flow = 0.3 + rnd.nextDouble() * 0.7, erase = rnd.nextInt(4) == 0, pressureSize = false)
                val st = BrushMath.walk(List(3) { StrokePoint(rnd.nextDouble() * w, rnd.nextDouble() * h) }, brush)
                val r = Dirty.rect(st, w, h)
                if (r[2] > 0) {
                    val before = PixelDelta.cut(pixels, w, r[0], r[1], r[2], r[3])
                    StrokeReference.commit(pixels, w, StrokeReference.coverage(w, h, st, brush), r, floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()), brush)
                    hist.commitStroke("a", PixelDelta(r[0], r[1], r[2], r[3], before, PixelDelta.cut(pixels, w, r[0], r[1], r[2], r[3])))
                }
            }
            docs.add(hist.document)
        }
        val finalPixels = pixels.copyOf(); val finalDoc = hist.document
        var guard = 0
        while (hist.canUndo) { val s = hist.undo()!!; if (s is Step.SetPixels) s.delta.apply(pixels, w, s.bytes); guard++ }
        assertArrayEquals(original, pixels)
        assertEquals(doc(), hist.document)
        while (hist.canRedo) { val s = hist.redo()!!; if (s is Step.SetPixels) s.delta.apply(pixels, w, s.bytes) }
        assertArrayEquals(finalPixels, pixels); assertEquals(finalDoc, hist.document)
        assertTrue(guard > 40)
    }

    @Test fun aNewEditAfterUndoDropsTheRedoTail() {
        val h = StudioHistory(doc())
        h.commitDocument(LayerOps.setOpacity(h.document, "a", 50))
        h.undo(); assertTrue(h.canRedo)
        h.commitDocument(LayerOps.setOpacity(h.document, "a", 70)); assertFalse(h.canRedo)
    }

    @Test fun byteBudgetDropsTheOldestStrokes() {
        val h = StudioHistory(doc(), maxEntries = 100, maxBytes = 1000)
        repeat(10) { h.commitStroke("a", PixelDelta(0, 0, 5, 5, ByteArray(100), ByteArray(100))) }   // 200 bytes each
        assertTrue(h.deltaBytes <= 1000)
        var n = 0; while (h.undo() != null) n++
        assertEquals(5, n)
    }

    @Test fun identicalDocumentIsNotRecorded() { val h = StudioHistory(doc()); h.commitDocument(h.document); assertFalse(h.canUndo) }
}

class InputRouterTest {
    private fun ev(p: Phase, id: Int, x: Float, y: Float, kind: PointerKind = PointerKind.FINGER, t: Long = 0) = InputEvent(p, id, x, y, 1f, kind, t)
    private fun names(a: List<Action>) = a.map { it::class.simpleName }

    @Test fun oneFingerDrawsADownMoveUpStroke() {
        val r = InputRouter()
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 0, 10f, 10f))))
        assertEquals(listOf("StrokeMove"), names(r.onEvent(ev(Phase.MOVE, 0, 12f, 10f))))
        assertEquals(listOf("StrokeEnd"), names(r.onEvent(ev(Phase.UP, 0, 12f, 10f))))
    }

    @Test fun aSecondFingerCancelsTheStrokeAndStartsAPanAndZoom() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 0, 100f, 100f)); r.onEvent(ev(Phase.MOVE, 0, 102f, 100f))
        assertEquals(listOf("StrokeCancel", "GestureStart"), names(r.onEvent(ev(Phase.DOWN, 1, 200f, 100f))))
        // the fingers start 98 apart with their centre at x = 151; the second moves out to x = 298: 196 apart, centre at x = 200, so zoom 2 and a pan of 49
        r.onEvent(ev(Phase.MOVE, 0, 102f, 100f))
        val a = r.onEvent(ev(Phase.MOVE, 1, 298f, 100f)).single() as Action.GestureUpdate
        assertEquals(196f / 98f, a.scale, 1e-4f); assertEquals(49f, a.dx, 1e-3f); assertEquals(200f, a.cx, 1e-3f)
        assertEquals(listOf("GestureEnd"), names(r.onEvent(ev(Phase.UP, 1, 298f, 100f))))
    }

    @Test fun theRemainingFingerDoesNotStartAStrokeAfterAPinch() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 0, 100f, 100f)); r.onEvent(ev(Phase.DOWN, 1, 200f, 100f)); r.onEvent(ev(Phase.UP, 1, 200f, 100f))
        assertTrue(r.onEvent(ev(Phase.MOVE, 0, 120f, 120f)).isEmpty())
        assertTrue(r.onEvent(ev(Phase.UP, 0, 120f, 120f)).isEmpty())
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 0, 5f, 5f))))   // everything is up: a new stroke is fine
    }

    @Test fun aPalmDoesNotInterruptAPenStroke() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 7, 100f, 100f, PointerKind.STYLUS))
        assertTrue(r.onEvent(ev(Phase.DOWN, 1, 300f, 300f, PointerKind.FINGER)).isEmpty())
        assertEquals(listOf("StrokeMove"), names(r.onEvent(ev(Phase.MOVE, 7, 110f, 100f, PointerKind.STYLUS))))
        assertTrue(r.onEvent(ev(Phase.MOVE, 1, 310f, 300f, PointerKind.FINGER)).isEmpty())
        assertEquals(listOf("StrokeEnd"), names(r.onEvent(ev(Phase.UP, 7, 110f, 100f, PointerKind.STYLUS))))
    }

    @Test fun aSystemCancelRollsTheStrokeBack() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 0, 1f, 1f))
        assertEquals(listOf("StrokeCancel"), names(r.onEvent(ev(Phase.CANCEL, 0, 1f, 1f))))
    }
}

class GestureAndSamplesTest {
    @Test fun aGestureStepKeepsTheDocumentPointUnderTheFingerCentre() {
        val v = CanvasView(120f, 80f, 1.5f)
        // the centre was at (300, 400), moves to (340, 380) while the fingers spread by 1.4
        val before = floatArrayOf(v.toDocX(300f), v.toDocY(400f))
        val w = v.gestured(40f, -20f, 1.4f, 340f, 380f)
        assertEquals(before[0], w.toDocX(340f), 1e-3f); assertEquals(before[1], w.toDocY(380f), 1e-3f)
        assertEquals(1.5f * 1.4f, w.zoom, 1e-6f)
    }

    @Test fun historicalSamplesGetPressureByTime() {
        val s = InputSamples.expand(0.2f, 100, listOf(Sample(1f, 1f, 9f, 110), Sample(2f, 2f, 9f, 130)), Sample(3f, 3f, 0.8f, 140))
        assertEquals(3, s.size)
        assertEquals(0.2f + 0.6f * 0.25f, s[0].pressure, 1e-6f)
        assertEquals(0.2f + 0.6f * 0.75f, s[1].pressure, 1e-6f)
        assertEquals(0.8f, s[2].pressure, 0f)
        assertEquals(0.8f, InputSamples.expand(0.2f, 140, listOf(Sample(1f, 1f, 0f, 140)), Sample(3f, 3f, 0.8f, 140))[0].pressure, 0f)   // no time passed: the current pressure
    }

    @Test fun trimBytesDropsTheOldestStrokesAndKeepsTheNewest() {
        val d = Document("d", "D", 40, 30, layers = listOf(Layer.Pixel(LayerCommon("a", "a"), 40, 30)))
        val h = StudioHistory(d)
        repeat(6) { h.commitStroke("a", PixelDelta(0, 0, 5, 5, ByteArray(100), ByteArray(100))) }   // 200 bytes each
        assertTrue(h.trimBytes(500)); assertTrue(h.deltaBytes <= 500)
        var n = 0; while (h.undo() != null) n++
        assertEquals(2, n)
        assertFalse(h.trimBytes(0))
    }
}
