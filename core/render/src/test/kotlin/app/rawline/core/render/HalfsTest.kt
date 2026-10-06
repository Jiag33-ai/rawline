package app.rawline.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfsTest {
    private fun bits(f: Float) = Halfs.toHalf(f).toInt() and 0xFFFF

    @Test fun valuesAbove65519NoLongerBecomeInfinity() {
        // the audit's probe values (AE-028): 65520 to 65535 used to round up to 0x7C00
        assertEquals(0x7BFF, bits(65504f))
        assertEquals(0x7BFF, bits(65519f))
        assertEquals(0x7BFF, bits(65520f))
        assertEquals(0x7BFF, bits(65535f))
        assertEquals(0x7BFF, bits(65536f))
        assertEquals(0xFBFF, bits(-65520f))
        assertEquals(0xFBFF, bits(-65535f))
        assertEquals(0x7BFF, bits(Float.POSITIVE_INFINITY))
        assertEquals(0xFBFF, bits(Float.NEGATIVE_INFINITY))
        assertEquals(0, bits(Float.NaN))
        var f = 32768f
        while (f <= 70000f) { assertTrue("$f", ((bits(f) shr 10) and 0x1F) != 31); f += 7f }
    }

    @Test fun ordinaryValuesMatchTheNativeConverter() {
        assertEquals(0x3C00, bits(1f)); assertEquals(0xC000, bits(-2f)); assertEquals(0x3800, bits(0.5f))
        assertEquals(0x0400, bits(6.103515625e-05f)); assertEquals(0x0001, bits(5.9604645e-08f)); assertEquals(0, bits(1e-9f))
    }

    @Test fun everyFiniteHalfSurvivesARoundTrip() {
        for (h in 0 until 0x10000) {
            if (((h shr 10) and 0x1F) == 31 || h == 0x8000) continue
            val back = bits(Halfs.toFloat(h.toShort()))
            assertEquals("half 0x${Integer.toHexString(h)}", h, back)
        }
    }
}
