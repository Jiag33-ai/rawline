package app.rawline.core.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerPackTest {
    @Test fun roundTripKeepsBytesAndSize() {
        val w = 300; val h = 200
        val a = ByteArray(w * h) { i -> if ((i % w - 100) * (i % w - 100) + (i / w - 80) * (i / w - 80) < 3600) 255.toByte() else 0 }
        val p = LayerPack.pack(a, w, h)
        assertEquals(w, p.w); assertEquals(h, p.h)
        assertArrayEquals(a, LayerPack.unbundle(p))
        assertTrue("mask should compress well", p.data.size < a.size / 10)
    }

    @Test fun noiseStillRoundTrips() {
        val a = ByteArray(64 * 64) { (it * 31 + it / 7).toByte() }
        assertArrayEquals(a, LayerPack.unbundle(LayerPack.pack(a, 64, 64)))
    }
}
