package app.rawline.core.render

import org.junit.Assert.assertEquals
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
        val o = HealOverlay(sink, 1000, 500)   // 1000 x 500 stays at that size (under 3072)
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
        val o = HealOverlay(sink, 400, 300)
        o.apply(listOf(0f, 0f, 0.5f, 0.5f), IntArray(64) { 0 }, 8, 8)
        assertTrue(sink.full!!.all { it == 0.toShort() })
    }
}
