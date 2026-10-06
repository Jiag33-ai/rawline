package app.rawline.core.render

import app.rawline.core.model.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealOverlayTest {
    private class Sink : OverlaySink {
        var full: ShortArray? = null; var fw = 0; var fh = 0
        override fun setOverlay(rgbaHalf: ShortArray?, w: Int, h: Int) { full = rgbaHalf?.copyOf(); fw = w; fh = h }
        override fun updateOverlay(x: Int, y: Int, w: Int, h: Int, rgbaHalf: ShortArray) {
            val f = full!!
            for (j in 0 until h) for (i in 0 until w) for (k in 0 until 4) f[((y + j) * fw + x + i) * 4 + k] = rgbaHalf[(j * w + i) * 4 + k]
        }
    }

    @Test fun anOpaquePatchIsStoredPremultipliedAndOutsideItNothingChanges() {
        val sink = Sink()
        val o = HealOverlay(sink, 1000, 500, Look.V2)   // 1000 x 500 stays at that size (under 3072)
        assertEquals(1000, sink.fw); assertEquals(500, sink.fh)
        val pw = 16; val ph = 16
        val patch = IntArray(pw * ph) { (255 shl 24) or (128 shl 16) or (128 shl 8) or 128 }   // opaque mid grey
        o.apply(listOf(0.25f, 0.25f, 0.2f, 0.2f), patch, pw, ph)
        val f = sink.full!!
        fun px(x: Int, y: Int) = (0 until 4).map { Halfs.toFloat(f[(y * sink.fw + x) * 4 + it]) }
        val inside = px(350, 175)
        assertEquals(1f, inside[3], 1e-3f)
        assertTrue("grey stays neutral (${inside}) and is between black and white", inside[0] in 0.05f..0.6f && Math.abs(inside[0] - inside[1]) < 0.02f && Math.abs(inside[1] - inside[2]) < 0.02f)
        assertEquals(0f, px(10, 10)[3], 0f)    // untouched
        assertEquals(0f, px(900, 450)[3], 0f)
    }

    @Test fun aTransparentPatchLeavesTheOverlayAlone() {
        val sink = Sink()
        val o = HealOverlay(sink, 400, 300, Look.V2)
        o.apply(listOf(0f, 0f, 0.5f, 0.5f), IntArray(64) { 0 }, 8, 8)
        assertTrue(sink.full!!.all { it == 0.toShort() })
    }

    private fun neutralAfterPatch(useBase: Boolean, look: Int = Look.V2): Float {
        val sink = Sink()
        val o = HealOverlay(sink, 200, 200, look, useBase)
        o.apply(listOf(0.1f, 0.1f, 0.5f, 0.5f), IntArray(16 * 16) { (255 shl 24) or (128 shl 16) or (128 shl 8) or 128 }, 16, 16)
        return Halfs.toFloat(sink.full!![(60 * sink.fw + 60) * 4])
    }

    @Test fun aPatchOnAFinishedPictureIsNotDarkenedByTheCameraCurve() {
        // mid grey 128 is linear 0.216 in plain sRGB, which is what a JPEG shows (identity base curve)
        val plain = neutralAfterPatch(useBase = false)
        assertEquals(0.2158f, plain, 0.01f)
        // for a raw the camera curve is inverted first, so the same display grey lands well below that (the old behaviour for every file)
        val raw = neutralAfterPatch(useBase = true)
        assertTrue("raw $raw plain $plain", raw < plain * 0.8f)
    }

    @Test fun displayAndWorkingRoundTripThroughBothCurves() {
        val t = FloatArray(3); val back = FloatArray(3)
        for (look in listOf(Look.V1, Look.V2)) for (useBase in listOf(true, false)) for (v in listOf(0.1f, 0.3f, 0.5f, 0.8f)) {
            ColorSpaces.displayToWorking(v, v, v, t, look, useBase = useBase)
            ColorSpaces.workingToDisplay(t[0], t[1], t[2], back, look, useBase = useBase)
            assertEquals("display $v (look $look, base=$useBase)", v, back[0], 0.01f)
        }
    }

    @Test fun aPatchIsConvertedWithTheCurveOfTheEditsLook() {
        // the same display grey needs a different working value under look 1 and look 2 (look 2 adds the white balance gain in front of its curve)
        val one = neutralAfterPatch(useBase = true, look = Look.V1)
        val two = neutralAfterPatch(useBase = true, look = Look.V2)
        assertNotEquals(one, two)
        // the finished picture path has no base curve, so the look makes no difference there
        assertEquals(neutralAfterPatch(useBase = false, look = Look.V1), neutralAfterPatch(useBase = false, look = Look.V2), 1e-6f)
        // what the engine draws is table(look)(oetf(working x gain)): a look 1 patch (gain 1) and a look 2 patch (gain K) land on the same display grey
        fun oetf(l: Double) = if (l <= 0.0031308) 12.92 * l else 1.055 * Math.pow(l, 1 / 2.4) - 0.055
        fun lookup(tab: FloatArray, s: Double): Double { val p = s * 255; val i = p.toInt().coerceAtMost(254); return tab[i] + (tab[i + 1] - tab[i]) * (p - i) }
        val shownOne = lookup(BaseCurve.table(Look.V1), oetf(one.toDouble()))
        val shownTwo = lookup(BaseCurve.table(Look.V2), oetf(two.toDouble()))
        assertEquals(128 / 255.0, shownOne, 0.01); assertEquals(128 / 255.0, shownTwo, 0.01)
    }
}
