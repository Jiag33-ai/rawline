package app.rawline.feature.editor

import app.rawline.core.model.Adjust
import app.rawline.core.model.CurvePoint
import app.rawline.core.model.Curves
import app.rawline.core.model.Detail
import app.rawline.core.model.Effects
import app.rawline.core.model.Geometry
import app.rawline.core.model.Grading
import app.rawline.core.model.Hsl
import app.rawline.core.model.Optics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveEditTest {
    private val ends = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

    @Test fun emptyChannelShowsTheTwoEndPoints() {
        assertEquals(ends, CurveEdit.shown(emptyList()))
        assertEquals(emptyList<CurvePoint>(), CurveEdit.normalise(ends))
        assertEquals(3, CurveEdit.normalise(ends + CurvePoint(0.5f, 0.6f)).size)
    }

    @Test fun legacyListsWithoutEndsGetThem() {
        val s = CurveEdit.shown(listOf(CurvePoint(0.5f, 0.6f)))
        assertEquals(3, s.size); assertEquals(0f, s.first().x, 0f); assertEquals(1f, s.last().x, 0f)
    }

    @Test fun insertKeepsOrderAndReturnsTheIndex() {
        val (a, ia) = CurveEdit.insert(emptyList(), 0.5f, 0.7f)!!
        assertEquals(1, ia); assertEquals(3, a.size); assertEquals(0.7f, a[1].y, 0f)
        val (b, ib) = CurveEdit.insert(a, 0.25f, 0.2f)!!
        assertEquals(1, ib); assertEquals(listOf(0f, 0.25f, 0.5f, 1f), b.map { it.x })
        val (c, ic) = CurveEdit.insert(b, 0.9f, 0.95f)!!
        assertEquals(3, ic); assertEquals(listOf(0f, 0.25f, 0.5f, 0.9f, 1f), c.map { it.x })
    }

    @Test fun insertAtTheEdgesGoesBetweenTheEndPoints() {
        val (a, i) = CurveEdit.insert(emptyList(), 0f, 0.5f)!!
        assertEquals(1, i); assertEquals(CurveEdit.MIN_GAP, a[1].x, 1e-6f)
        val (b, j) = CurveEdit.insert(emptyList(), 1f, 0.5f)!!
        assertEquals(1, j); assertEquals(1f - CurveEdit.MIN_GAP, b[1].x, 1e-6f)
    }

    @Test fun insertRefusesWhenThereIsNoRoom() {
        // between x = 0 and x = 0.015 there is less than two gaps of space
        val tight = listOf(CurvePoint(0f, 0f), CurvePoint(0.015f, 0.1f), CurvePoint(1f, 1f))
        assertNull(CurveEdit.insert(tight, 0.01f, 0.2f))
        assertNotNull(CurveEdit.insert(tight, 0.5f, 0.2f))
    }

    @Test fun movingKeepsPointsBetweenTheirNeighboursSoIndicesStayPut() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.3f), CurvePoint(0.6f, 0.6f), CurvePoint(1f, 1f))
        val m = CurveEdit.move(pts, 1, 0.9f, 1.5f)
        assertEquals(0.6f - CurveEdit.MIN_GAP, m[1].x, 1e-6f); assertEquals(1f, m[1].y, 0f)
        val m2 = CurveEdit.move(pts, 2, -3f, -3f)
        assertEquals(0.3f + CurveEdit.MIN_GAP, m2[2].x, 1e-6f); assertEquals(0f, m2[2].y, 0f)
        assertEquals(listOf(0f, 0.3f, 0.6f, 1f), pts.map { it.x })   // input untouched
    }

    @Test fun endPointsMoveOnlyVertically() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        val m = CurveEdit.move(pts, 0, 0.4f, 0.25f)
        assertEquals(0f, m[0].x, 0f); assertEquals(0.25f, m[0].y, 0f)
        val m2 = CurveEdit.move(pts, 1, 0.1f, 0.8f)
        assertEquals(1f, m2[1].x, 0f); assertEquals(0.8f, m2[1].y, 0f)
    }

    @Test fun movingFromAnEmptyChannelCreatesItsEndPoints() {
        val m = CurveEdit.move(emptyList(), 0, 0f, 0.1f)
        assertEquals(2, m.size); assertEquals(0.1f, m[0].y, 0f)
    }

    @Test fun removeDeletesInteriorPointsAndResetsEndPoints() {
        val pts = listOf(CurvePoint(0f, 0.2f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 0.9f))
        val r = CurveEdit.remove(pts, 1)
        assertEquals(2, r.size)
        val e0 = CurveEdit.remove(pts, 0)
        assertEquals(3, e0.size); assertEquals(0f, e0[0].y, 0f)
        val e1 = CurveEdit.remove(pts, 2)
        assertEquals(1f, e1[2].y, 0f)
        // deleting the last interior point of an otherwise default curve gives back the identity, stored as empty
        val only = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))
        assertEquals(emptyList<CurvePoint>(), CurveEdit.normalise(CurveEdit.remove(only, 1)))
    }

    @Test fun offGraphDeletesOnlyBeyondTheMargin() {
        assertFalse(CurveEdit.offGraph(-10f, 50f, 200f, 100f, 28f))
        assertTrue(CurveEdit.offGraph(-30f, 50f, 200f, 100f, 28f))
        assertTrue(CurveEdit.offGraph(100f, 140f, 200f, 100f, 28f))
        assertFalse(CurveEdit.offGraph(100f, 50f, 200f, 100f, 28f))
    }

    @Test fun hitPicksTheNearestWithinTheTouchRadius() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(0.55f, 0.5f), CurvePoint(1f, 1f))
        // graph 200 x 100 px: points at (100,50) and (110,50)
        assertEquals(1, CurveEdit.hit(pts, 102f, 52f, 200f, 100f, 24f))
        assertEquals(2, CurveEdit.hit(pts, 108f, 50f, 200f, 100f, 24f))
        assertEquals(-1, CurveEdit.hit(pts, 100f, 5f, 200f, 100f, 24f))
        assertEquals(0, CurveEdit.hit(pts, 10f, 90f, 200f, 100f, 24f))   // the end point at the bottom left corner
    }

    @Test fun readoutIsOnTheZeroTo255Scale() {
        assertEquals(128 to 255, CurveEdit.readout(CurvePoint(0.5f, 1f)))
        assertEquals(0 to 0, CurveEdit.readout(CurvePoint(0f, 0f)))
    }

    @Test fun channelAccessorsRoundTrip() {
        var c = Curves()
        val p = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f))
        for (ch in 0..3) { c = CurveEdit.withPoints(c, ch, p); assertEquals(p, CurveEdit.points(c, ch)) }
        assertEquals(1, CurveEdit.interiorCount(p))
    }

    // ---------- reset targets ----------

    @Test fun lightResetOnlyTouchesLight() {
        val a = Adjust(exposure = 1f, contrast = 20f, highlights = -30f, shadows = 40f, whites = 5f, blacks = -5f, saturation = 12f, texture = 9f,
            curves = Curves(master = listOf(CurvePoint(0.5f, 0.6f))))
        val r = Resets.light(a)
        assertEquals(Adjust(saturation = 12f, texture = 9f, curves = a.curves), r)
        assertTrue(Resets.lightModified(a)); assertFalse(Resets.lightModified(r))
    }

    @Test fun colourResetsAreSeparate() {
        val a = Adjust(temp = 10f, tint = -3f, vibrance = 20f, saturation = -10f, mixHue = List(8) { 5f }, grading = Grading(blending = 80f))
        val basics = Resets.colourBasics(a)
        assertEquals(0f, basics.temp, 0f); assertEquals(0f, basics.saturation, 0f)
        assertEquals(List(8) { 5f }, basics.mixHue); assertEquals(80f, basics.grading.blending, 0f)
        assertEquals(List(8) { 0f }, Resets.mixer(a).mixHue)
        assertEquals(Grading(), Resets.grading(a).grading)
        assertEquals(10f, Resets.grading(a).temp, 0f)
    }

    @Test fun mixerBandResetsOneColourInAllThreeModes() {
        val a = Adjust(mixHue = List(8) { 10f }, mixSat = List(8) { 20f }, mixLum = List(8) { 30f })
        val r = Resets.mixerBand(a, 3)
        assertEquals(0f, r.mixHue[3], 0f); assertEquals(0f, r.mixSat[3], 0f); assertEquals(0f, r.mixLum[3], 0f)
        assertEquals(10f, r.mixHue[2], 0f); assertEquals(20f, r.mixSat[4], 0f)
        assertEquals(8, r.mixLum.size)
    }

    @Test fun gradingWheelResetKeepsLuminanceAndOtherWheels() {
        val g = Grading(shadows = Hsl(200f, 50f, 10f), mid = Hsl(30f, 40f, -5f), highlights = Hsl(60f, 20f, 3f), global = Hsl(90f, 10f, 1f))
        val r = Resets.gradingWheel(g, "shadows")
        assertEquals(Hsl(0f, 0f, 10f), r.shadows); assertEquals(g.mid, r.mid); assertEquals(g.highlights, r.highlights)
        assertEquals(Hsl(0f, 0f, -5f), Resets.gradingWheel(g, "mid").mid)
        assertEquals(Hsl(0f, 0f, 3f), Resets.gradingWheel(g, "highlights").highlights)
        assertEquals(Hsl(0f, 0f, 1f), Resets.gradingWheel(g, "global").global)
        assertEquals(g.mid, Resets.gradingWheelOf(g, "mid"))
    }

    @Test fun curveChannelResetKeepsTheOtherChannelsAndParametric() {
        val p = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f))
        val c = Curves(master = p, red = p, green = p, blue = p, parametric = listOf(10f, 0f, 0f, 0f))
        assertEquals(c.copy(red = emptyList()), Resets.curveChannel(c, 1))
        assertEquals(c.copy(master = emptyList()), Resets.curveChannel(c, 0))
        assertEquals(c.copy(blue = emptyList()), Resets.curveChannel(c, 3))
        assertEquals(Curves(), Resets.curves(Adjust(curves = c)).curves)
        assertTrue(Resets.curvesModified(Adjust(curves = c))); assertFalse(Resets.curvesModified(Adjust()))
        assertTrue(Resets.curveChannelModified(c, 2)); assertFalse(Resets.curveChannelModified(Curves(), 2))
    }

    @Test fun effectsDetailOpticsResetsUseTheModelDefaults() {
        val e = Effects(vignetteAmount = -30f, vignetteMidpoint = 20f, vignetteRoundness = 5f, vignetteFeather = 70f, grainAmount = 40f, grainSize = 60f, grainRoughness = 10f)
        assertEquals(Effects(grainAmount = 40f, grainSize = 60f, grainRoughness = 10f), Resets.vignette(e))
        assertEquals(Effects(vignetteAmount = -30f, vignetteMidpoint = 20f, vignetteRoundness = 5f, vignetteFeather = 70f), Resets.grain(e))
        val d = Detail(sharpen = 60f, radius = 2f, detail = 50f, masking = 30f, nrLuminance = 20f, nrColor = 15f, aiDenoise = true, aiDenoiseAmount = 80f)
        assertEquals(Detail(nrLuminance = 20f, nrColor = 15f, aiDenoise = true, aiDenoiseAmount = 80f), Resets.sharpen(d))
        assertEquals(Detail(sharpen = 60f, radius = 2f, detail = 50f, masking = 30f, nrColor = 15f), Resets.noise(d))
        assertFalse(Resets.noise(d).aiDenoise)
        assertEquals(0f, Resets.colourNoise(d).nrColor, 0f)
        assertEquals(Optics(), Resets.optics(Optics(lensCorrection = false, removeCa = false, distortion = 20f, vignetting = -10f)))
        assertTrue(Resets.opticsModified(Optics(distortion = 1f))); assertFalse(Resets.opticsModified(Optics()))
        assertTrue(Resets.vignetteModified(e)); assertFalse(Resets.vignetteModified(Effects(grainAmount = 3f)))
    }

    @Test fun perspectiveAndStraightenResets() {
        val g = Geometry(angle = 3f, keystoneV = 10f, keystoneH = -20f, cropW = 0.5f)
        assertEquals(Geometry(angle = 3f, cropW = 0.5f), Resets.perspective(g))
        assertEquals(Geometry(keystoneV = 10f, keystoneH = -20f, cropW = 0.5f), Resets.straighten(g))
        assertTrue(Resets.perspectiveModified(g)); assertFalse(Resets.perspectiveModified(Geometry(angle = 3f)))
    }
}
