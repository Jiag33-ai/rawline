package app.rawline.feature.editor

import app.rawline.core.model.Geometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropMathTest {
    private val fa = 1.5f   // 3:2 frame

    private fun pixAspect(r: CropRect) = r.w * fa / r.h
    private fun area(angle: Float, kv: Float = 0f, kh: Float = 0f) = CropMath.validArea(Geometry(angle = angle, keystoneV = kv, keystoneH = kh), 0f, fa)
    private fun inFrame(r: CropRect) = r.x0 >= -1e-5f && r.y0 >= -1e-5f && r.x1 <= 1f + 1e-5f && r.y1 <= 1f + 1e-5f

    // ---------- picture area ----------

    @Test fun levelPictureFillsTheWholeFrame() {
        val a = area(0f)
        assertTrue(a.contains(CropRect.Full.let { CropRect(0.001f, 0.001f, 0.999f, 0.999f) }))
    }

    @Test fun straightenedPictureLeavesEmptyCorners() {
        val a = area(10f)
        assertFalse("a full-frame crop would show empty wedges", a.contains(CropRect(0.001f, 0.001f, 0.999f, 0.999f)))
        assertFalse(a.contains(0.002f, 0.002f))
        assertTrue(a.contains(0.5f, 0.5f))
    }

    @Test fun largestCropStaysOnPictureAndKeepsShape() {
        val a = area(10f)
        val r = CropMath.largest(fa, fa, a)
        assertTrue(a.contains(r))
        assertEquals(fa, pixAspect(r), 1e-3f)
        assertEquals(0.5f, r.cx, 1e-4f); assertEquals(0.5f, r.cy, 1e-4f)
        assertTrue(r.w < 0.95f && r.w > 0.5f)
        // and it is close to the largest: growing by 3 percent no longer fits
        assertFalse(a.contains(CropMath.scaleAbout(r, 1.03f)))
        // a level picture allows the whole frame
        val full = CropMath.largest(fa, fa, area(0f))
        assertEquals(1f, full.w, 1e-3f)
    }

    @Test fun largestSquareInA32Frame() {
        val r = CropMath.largest(1f, fa, ValidArea.Everything)
        assertEquals(1f, pixAspect(r), 1e-3f)
        assertEquals(1f, r.h, 1e-3f)          // as tall as the frame
    }

    @Test fun clampToValidStopsAtThePictureEdge() {
        val a = area(8f)
        val safe = CropMath.largest(fa, fa, a)
        val wild = CropRect(-0.2f, -0.2f, 1.2f, 1.2f)
        val c = CropMath.clampToValid(wild, safe, a)
        assertTrue(a.contains(c))
        // it went as far as it could: nudging 2 percent further out is not valid
        assertFalse(a.contains(CropRect.lerp(c, wild, 0.02f)))
    }

    @Test fun shrinkIntoKeepsCentreWhenThatIsOnPicture() {
        val a = area(12f)
        val start = CropRect(0.1f, 0.1f, 0.9f, 0.9f)
        val r = CropMath.shrinkInto(start, a)
        assertTrue(a.contains(r))
        assertEquals(start.cx, r.cx, 1e-4f)
        assertEquals(start.w / start.h, r.w / r.h, 1e-3f)
        assertTrue(r.w < start.w)
        // an already valid crop is untouched
        val ok = CropRect(0.4f, 0.4f, 0.6f, 0.6f)
        assertEquals(ok, CropMath.shrinkInto(ok, a))
    }

    @Test fun shrinkIntoRecoversACropWholeCentreIsOffPicture() {
        val a = area(30f)
        val corner = CropRect(0f, 0f, 0.2f, 0.2f)
        val r = CropMath.shrinkInto(corner, a)
        assertTrue(a.contains(r))
    }

    @Test fun straightenRefitsFromTheCropWhenTheDialStarted() {
        val base = CropRect.Full
        val g = Geometry()
        val tilted = CropMath.withAngle(g, 10f, base, 0f, fa)
        val tr = CropRect.of(tilted)
        assertTrue(tr.w < 1f)
        assertTrue(area(10f).contains(tr))
        assertEquals(10f, tilted.angle, 0f)
        // dragging back to level from the same base grows the crop back to the frame
        val back = CropMath.withAngle(tilted, 0f, base, 0f, fa)
        assertEquals(1f, CropRect.of(back).w, 1e-4f)
        // a bigger angle is tighter than a smaller one
        val more = CropRect.of(CropMath.withAngle(g, 20f, base, 0f, fa))
        assertTrue(more.w < tr.w)
    }

    @Test fun straightenWithEveryCropAspectNeverExposesTheEdge() {
        for (angle in listOf(-45f, -17.3f, -3f, 0.4f, 6f, 14.5f, 33f, 45f)) for (t in listOf(null, 1f, 1.5f, 16f / 9f, 0.8f)) {
            val g0 = Geometry(aspect = if (t == null) "free" else CropMath.customAspect(t))
            val g = CropMath.withAngle(g0, angle, CropRect(0.05f, 0.1f, 0.95f, 0.9f), 0f, fa)
            val r = CropRect.of(g)
            assertTrue("angle $angle aspect $t", CropMath.validArea(g, 0f, fa).contains(r))
            assertTrue(inFrame(r))
        }
    }

    @Test fun keystoneIsKeptOnPictureToo() {
        val g = Geometry(keystoneV = 60f, keystoneH = -40f)
        val a = CropMath.validArea(g, 0f, fa)
        val r = CropMath.shrinkInto(CropRect(0.01f, 0.01f, 0.99f, 0.99f), a)
        assertTrue(a.contains(r))
    }

    // ---------- dragging ----------

    @Test fun cornerDragWithLockedAspectKeepsAspectAndAnchor() {
        val start = CropRect(0.1f, 0.1f, 0.7f, 0.1f + 0.6f * fa / 1.5f)   // 3:2 pixels
        for ((dx, dy) in listOf(0.1f to 0.0f, -0.2f to 0.15f, 0.0f to -0.3f, 0.05f to 0.05f)) {
            val r = CropMath.drag(start, CropHandle.BR, dx, dy, 1.5f, fa)
            assertEquals("aspect", 1.5f, pixAspect(r), 1e-3f)
            assertEquals(start.x0, r.x0, 1e-5f); assertEquals(start.y0, r.y0, 1e-5f)
            assertTrue(inFrame(r))
        }
        // top left keeps the bottom right corner
        val r = CropMath.drag(start, CropHandle.TL, 0.05f, 0.04f, 1.5f, fa)
        assertEquals(start.x1, r.x1, 1e-5f); assertEquals(start.y1, r.y1, 1e-5f)
    }

    @Test fun cornerDragNeverLeavesTheFrameOrGoesBelowTheMinimum() {
        val start = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val big = CropMath.drag(start, CropHandle.BR, 5f, 5f, null, fa)
        assertEquals(1f, big.x1, 0f); assertEquals(1f, big.y1, 0f)
        val tiny = CropMath.drag(start, CropHandle.BR, -5f, -5f, null, fa)
        assertEquals(CropMath.MIN_SIDE, tiny.w, 1e-5f); assertEquals(CropMath.MIN_SIDE, tiny.h, 1e-5f)
        val lockedBig = CropMath.drag(start, CropHandle.BR, 5f, 5f, 1.5f, fa)
        assertTrue(inFrame(lockedBig)); assertEquals(1.5f, pixAspect(lockedBig), 1e-3f)
        val lockedTiny = CropMath.drag(start, CropHandle.BR, -5f, -5f, 1.5f, fa)
        assertTrue(lockedTiny.w >= CropMath.MIN_SIDE - 1e-6f && lockedTiny.h >= CropMath.MIN_SIDE - 1e-6f)
    }

    @Test fun edgeDragFreeMovesOnlyThatEdge() {
        val start = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val r = CropMath.drag(start, CropHandle.L, -0.1f, 0.3f, null, fa)
        assertEquals(0.1f, r.x0, 1e-5f); assertEquals(start.x1, r.x1, 0f); assertEquals(start.y0, r.y0, 0f); assertEquals(start.y1, r.y1, 0f)
        val t = CropMath.drag(start, CropHandle.T, 0.3f, 0.1f, null, fa)
        assertEquals(0.3f, t.y0, 1e-5f); assertEquals(start.y1, t.y1, 0f); assertEquals(start.x0, t.x0, 0f)
    }

    @Test fun edgeDragLockedRecentresTheOtherDimension() {
        val start = CropRect(0.2f, 0.3f, 0.8f, 0.3f + 0.6f * fa / 1.5f)
        val r = CropMath.drag(start, CropHandle.R, 0.1f, 0f, 1.5f, fa)
        assertEquals(1.5f, pixAspect(r), 1e-3f)
        assertEquals(start.x0, r.x0, 1e-5f)
        assertEquals(start.cy, r.cy, 1e-3f)
        assertTrue(inFrame(r))
        val tall = CropMath.drag(start, CropHandle.B, 0f, 0.4f, 1.5f, fa)
        assertTrue(inFrame(tall)); assertEquals(1.5f, pixAspect(tall), 1e-3f); assertEquals(start.y0, tall.y0, 1e-5f)
    }

    @Test fun moveStaysInTheFrameAndKeepsSize() {
        val start = CropRect(0.2f, 0.2f, 0.5f, 0.6f)
        val r = CropMath.drag(start, CropHandle.MOVE, 3f, -3f, null, fa)
        assertEquals(start.w, r.w, 1e-6f); assertEquals(start.h, r.h, 1e-6f)
        assertEquals(1f, r.x1, 1e-6f); assertEquals(0f, r.y0, 1e-6f)
    }

    @Test fun movingAgainstASlantedEdgeSlidesAlongItInsteadOfSticking() {
        val a = area(15f)
        val start = CropMath.largest(1.5f, fa, a).let { CropMath.scaleAbout(it, 0.6f) }
        var last = start
        // drag diagonally far to the top right; each step is settled against the last valid position
        for (step in 1..40) {
            val proposed = CropMath.drag(start, CropHandle.MOVE, 0.012f * step, -0.012f * step, null, fa)
            last = CropMath.settle(proposed, last, a, true)
            assertTrue(a.contains(last))
        }
        assertTrue("it moved a good way", last.cx > start.cx + 0.05f)
        assertEquals(start.w, last.w, 1e-3f)
    }

    @Test fun pinchScalesAboutCentreAndRespectsLimits() {
        val start = CropRect(0.3f, 0.3f, 0.7f, 0.7f)
        val bigger = CropMath.pinch(start, 1.5f)
        assertEquals(0.6f, bigger.w, 1e-4f); assertEquals(0.5f, bigger.cx, 1e-4f)
        assertEquals(1f, CropMath.pinch(start, 50f).w, 1e-4f)
        assertEquals(CropMath.MIN_SIDE, CropMath.pinch(start, 0.001f).w, 1e-4f)
        val edge = CropRect(0.6f, 0.6f, 1f, 1f)
        assertTrue(inFrame(CropMath.pinch(edge, 2f)))
    }

    // ---------- hit testing ----------

    @Test fun hitTestPrefersCornersThenEdgesThenInside() {
        val l = 100f; val t = 100f; val r = 500f; val b = 400f
        fun hit(x: Float, y: Float) = CropMath.hitTest(l, t, r, b, x, y, 34f, 26f)
        assertEquals(CropHandle.TL, hit(110f, 95f))
        assertEquals(CropHandle.BR, hit(520f, 420f))
        assertEquals(CropHandle.T, hit(300f, 90f))          // just outside the top edge still grabs it
        assertEquals(CropHandle.L, hit(115f, 250f))
        assertEquals(CropHandle.MOVE, hit(300f, 250f))
        assertEquals(CropHandle.NONE, hit(50f, 50f))
        assertEquals(CropHandle.NONE, hit(300f, 600f))
    }

    @Test fun hitTestLeavesRoomToMoveASmallCrop() {
        val h = CropMath.hitTest(100f, 100f, 180f, 180f, 140f, 140f, 34f, 26f)
        assertEquals(CropHandle.MOVE, h)
    }

    // ---------- turn, flip, swap ----------

    @Test fun rotatingFourTimesComesBack() {
        val r = CropRect(0.1f, 0.2f, 0.5f, 0.9f)
        var x = r
        repeat(4) { x = CropMath.rotateCw(x) }
        assertRectEq(r, x)
        assertRectEq(r, CropMath.rotateCcw(CropMath.rotateCw(r)))
        assertRectEq(r, CropMath.mirrorX(CropMath.mirrorX(r)))
        assertRectEq(r, CropMath.mirrorY(CropMath.mirrorY(r)))
    }

    @Test fun clockwiseTurnMovesTheTopLeftCornerToTheTopRight() {
        val r = CropMath.rotateCw(CropRect(0f, 0f, 0.2f, 0.1f))
        assertRectEq(CropRect(0.9f, 0f, 1f, 0.2f), r)
    }

    @Test fun rotateGeometryTurnsTheCropAndSwapsTheAspect() {
        val g = Geometry(cropX = 0.1f, cropY = 0.1f, cropW = 0.6f, cropH = 0.4f, aspect = "16:9")
        val r = CropMath.rotate(g, true, 0f, fa)
        assertEquals(1, r.rotate90)
        assertEquals("9:16", r.aspect)
        assertTrue(CropRect.of(r).h > CropRect.of(r).w * 0.5f)
        assertEquals(0, CropMath.rotate(r, false, 0f, 1f / fa).rotate90)
        var x = g
        var f = fa
        repeat(4) { x = CropMath.rotate(x, true, 0f, f); f = 1f / f }
        assertEquals(0, x.rotate90)
        assertEquals("16:9", x.aspect)
        assertRectEq(CropRect.of(g), CropRect.of(x))
        // turning a 3:2 crop in a 3:2 frame gives 2:3, which is the turned frame's own shape: it becomes Original
        assertEquals("original", CropMath.rotate(Geometry(aspect = "3:2"), true, 0f, fa).aspect)
    }

    @Test fun originalStaysOriginalWhenTurned() {
        val g = Geometry(aspect = "original")
        assertEquals("original", CropMath.rotate(g, true, 0f, fa).aspect)
        assertEquals("free", CropMath.rotate(Geometry(aspect = "free"), true, 0f, fa).aspect)
    }

    @Test fun flipMirrorsCropAndKeepsTheHorizonLevel() {
        val g = Geometry(cropX = 0.1f, cropY = 0.2f, cropW = 0.5f, cropH = 0.3f, angle = 4f, keystoneH = 10f, keystoneV = 20f)
        val h = CropMath.flip(g, true)
        assertTrue(h.flipH)
        assertEquals(-4f, h.angle, 0f); assertEquals(-10f, h.keystoneH, 0f); assertEquals(20f, h.keystoneV, 0f)
        assertEquals(0.4f, h.cropX, 1e-6f)
        val v = CropMath.flip(g, false)
        assertTrue(v.flipV); assertEquals(-4f, v.angle, 0f); assertEquals(-20f, v.keystoneV, 0f); assertEquals(10f, v.keystoneH, 0f)
        assertEquals(0.5f, v.cropY, 1e-6f)
        val twice = CropMath.flip(CropMath.flip(g, true), true)
        assertRectEq(CropRect.of(g), CropRect.of(twice)); assertEquals(g.angle, twice.angle, 0f); assertFalse(twice.flipH)
    }

    @Test fun flippedCropStaysOnPicture() {
        val g = CropMath.withAngle(Geometry(), 9f, CropRect.Full, 0f, fa)
        val f = CropMath.flip(g, true)
        assertTrue(CropMath.validArea(f, 0f, fa).contains(CropRect.of(f)))
        val f2 = CropMath.flip(g, false)
        assertTrue(CropMath.validArea(f2, 0f, fa).contains(CropRect.of(f2)))
    }

    @Test fun swapLandscapePortraitTradesWidthAndHeight() {
        val g = CropMath.pickAspectLargest(Geometry(), "3:2", 0f, fa)
        val s = CropMath.swapLandscapePortrait(g, 0f, fa)
        assertEquals("2:3", s.aspect)
        assertEquals(2f / 3f, pixAspect(CropRect.of(s)), 1e-2f)
        assertTrue(CropMath.validArea(s, 0f, fa).contains(CropRect.of(s)))
        // original becomes a custom ratio that reads back as the inverse
        val o = CropMath.swapLandscapePortrait(Geometry(), 0f, fa)
        assertEquals(1f / fa, CropMath.aspectValue(o.aspect, fa)!!, 1e-3f)
        assertEquals("Custom", CropMath.label(o.aspect))
        // free just swaps the shape
        val fr = CropMath.swapLandscapePortrait(Geometry(cropX = 0.2f, cropY = 0.2f, cropW = 0.6f, cropH = 0.3f, aspect = "free"), 0f, fa)
        assertEquals("free", fr.aspect)
        assertEquals(0.3f / fa, fr.cropW, 1e-4f)
    }

    // ---------- aspect names, angle ----------

    @Test fun aspectNames() {
        assertEquals(null, CropMath.aspectValue("free", fa))
        assertEquals(fa, CropMath.aspectValue("Original", fa)!!, 0f)
        assertEquals(16f / 9f, CropMath.aspectValue("16:9", fa)!!, 1e-5f)
        assertEquals(8.5f / 11f, CropMath.aspectValue("8.5:11", fa)!!, 1e-5f)
        assertEquals(null, CropMath.aspectValue("nonsense", fa))
        assertEquals("9:16", CropMath.swapName("16:9", fa))
        assertEquals("free", CropMath.swapName("free", fa))
        assertEquals("original", CropMath.swapName("2:3", fa))      // swapping 2:3 gives 3:2, this frame's own shape
        assertEquals("8.5:11", CropMath.label("8.5:11"))
        assertEquals("Original", CropMath.label("original"))
    }

    @Test fun pickAspectTrimsAboutTheCentreAndStaysOnPicture() {
        val g = CropMath.pickAspect(Geometry(), "1:1", 0f, fa)
        val r = CropRect.of(g)
        assertEquals(1f, pixAspect(r), 1e-3f)
        assertEquals(0.5f, r.cx, 1e-4f)
        assertEquals("1:1", g.aspect)
        val tilted = CropMath.pickAspectLargest(Geometry(angle = 10f), "16:9", 0f, fa)
        assertEquals(16f / 9f, pixAspect(CropRect.of(tilted)), 1e-3f)
        assertTrue(CropMath.validArea(tilted, 0f, fa).contains(CropRect.of(tilted)))
        // free keeps the rectangle
        assertEquals(Geometry(aspect = "free"), CropMath.pickAspect(Geometry(), "free", 0f, fa))
    }

    @Test fun resetCropGivesTheBiggestCropOfTheCurrentAspect() {
        val g = Geometry(cropX = 0.3f, cropY = 0.3f, cropW = 0.2f, cropH = 0.2f, angle = 6f, aspect = "1:1")
        val r = CropMath.resetCrop(g, 0f, fa)
        assertEquals(1f, pixAspect(CropRect.of(r)), 1e-3f)
        assertTrue(CropRect.of(r).w > 0.4f)
        assertTrue(CropMath.validArea(r, 0f, fa).contains(CropRect.of(r)))
        assertEquals(6f, r.angle, 0f)
    }

    @Test fun snapAngleSnapsToZeroAndClamps() {
        assertEquals(0f, CropMath.snapAngle(0.2f), 0f)
        assertEquals(0f, CropMath.snapAngle(-0.24f), 0f)
        assertEquals(0.3f, CropMath.snapAngle(0.3f), 1e-6f)
        assertEquals(45f, CropMath.snapAngle(99f), 0f)
        assertEquals(-45f, CropMath.snapAngle(-99f), 0f)
        assertEquals(12.35f, CropMath.snapAngle(12.3456f), 1e-6f)
    }

    @Test fun dialCanLeaveZeroUsingARunningValue() {
        // the old dial recomputed from the snapped value and stuck at zero; with a running raw value it walks away
        var raw = 0f
        var shown = 0f
        repeat(40) { raw -= -1.5f / 9f; shown = CropMath.snapAngle(raw) }   // 40 small drags of 1.5 px
        assertTrue("angle $shown", shown > 5f)
    }

    // ---------- the crop tool and the renderer agree ----------

    private val synthLens = floatArrayOf(1f - 0.02161f + 0.03781f + 0.08584f, -0.08584f, -0.03781f, 0.02161f, 0f)
    private val W = 3000; private val H = 2000

    private fun rendered(g: Geometry, lens: FloatArray?, o: app.rawline.core.model.Optics = app.rawline.core.model.Optics()) =
        app.rawline.core.render.Geo.fitCrop(g, o, 1, W, H, lens)

    @Test fun largestBoxIsWhatTheRendererShows() {
        for (lens in listOf<FloatArray?>(null, synthLens)) for (angle in listOf(3f, -7.5f, 20f)) {
            val g0 = Geometry(angle = angle)
            val r = CropMath.largest(null, fa, CropMath.validArea(g0, 0f, fa, lens))
            val g = r.applyTo(g0)
            val shown = rendered(g, lens)
            assertEquals("x angle=$angle lens=${lens != null}", r.x0, shown[0], 3e-3f)
            assertEquals("y", r.y0, shown[1], 3e-3f)
            assertEquals("w", r.w, shown[2], 3e-3f)
            assertEquals("h", r.h, shown[3], 3e-3f)
        }
    }

    @Test fun lensProfileShrinksTheValidAreaOnlyWhenPassed() {
        val g = Geometry(angle = 2f)
        val without = CropMath.largest(null, fa, CropMath.validArea(g, 0f, fa, null))
        val with = CropMath.largest(null, fa, CropMath.validArea(g, 0f, fa, synthLens))
        assertNotEquals("the lens polynomial must change the box", without.w, with.w, 1e-4f)
    }

    @Test fun validAreaKeepsTheRendererEdgeMargin() {
        // a frame point mapping 0.1% inside the source edge is on picture for the old test but not for the renderer
        val g = Geometry(angle = 0.5f)
        val a = CropMath.validArea(g, 0f, fa, null)
        val r = CropMath.largest(null, fa, a)
        val c = app.rawline.core.render.Geo.frameToSource(r.x0, r.y0, g, app.rawline.core.model.Optics(), 1, W, H)
        assertTrue("corner at ${c[0]},${c[1]} must keep the margin", c[0] >= app.rawline.core.render.Geo.EDGE_MARGIN - 1e-4f || c[1] >= app.rawline.core.render.Geo.EDGE_MARGIN - 1e-4f)
    }

    @Test fun fitStoredCropWritesTheRenderedCropBackOnlyWhenItDiffers() {
        val o = app.rawline.core.model.Optics()
        val consistent = CropMath.largest(null, fa, CropMath.validArea(Geometry(angle = 4f), 0f, fa, null)).applyTo(Geometry(angle = 4f))
        val same = fitStoredCrop(consistent, o, 1, W, H, null, tolerance = 3e-3f)
        assertTrue("already what is rendered: unchanged instance", same === consistent)
        val tooBig = Geometry(angle = 8f)   // full frame crop at 8 degrees would show empty wedges
        val fixed = fitStoredCrop(tooBig, o, 1, W, H, null)
        assertTrue(fixed !== tooBig)
        assertTrue(fixed.cropW < 1f && fixed.cropH < 1f)
        val shown = rendered(tooBig, null)
        assertEquals(shown[2], fixed.cropW, 1e-6f)
        assertEquals(8f, fixed.angle, 0f)
    }

    private fun assertRectEq(a: CropRect, b: CropRect) {
        assertTrue("$a vs $b", abs(a.x0 - b.x0) < 1e-5f && abs(a.y0 - b.y0) < 1e-5f && abs(a.x1 - b.x1) < 1e-5f && abs(a.y1 - b.y1) < 1e-5f)
        assertNotEquals(null, a)
    }
}
