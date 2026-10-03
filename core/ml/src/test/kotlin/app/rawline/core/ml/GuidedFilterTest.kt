package app.rawline.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedFilterTest {
    @Test fun boxFilterAveragesAndKeepsConstants() {
        val w = 8; val h = 6
        val c = FloatArray(w * h) { 0.4f }
        GuidedFilter.box(c, w, h, 2).forEach { assertEquals(0.4f, it, 1e-5f) }
        val ramp = FloatArray(w * h) { (it % w).toFloat() }
        val b = GuidedFilter.box(ramp, w, h, 1)
        assertEquals(3f, b[3], 1e-5f)   // mean of 2,3,4
    }

    @Test fun guidedFilterSnapsMaskToGuideEdge() {
        val w = 64; val h = 16
        // guide: sharp step at x = 32; mask: blurry step at x = 28..40
        val guide = FloatArray(w * h) { if (it % w < 32) 0.1f else 0.9f }
        val mask = FloatArray(w * h) { val x = it % w; ((x - 28) / 12f).coerceIn(0f, 1f) }
        val out = GuidedFilter.apply(guide, mask, w, h, 4, 1e-3f)
        val row = 5 * w
        // the blurry ramp is pulled towards the guide's edge from both sides
        assertTrue("left of the edge goes down: ${out[row + 29]} vs ${mask[row + 29]}", out[row + 29] < mask[row + 29])
        assertTrue("right of the edge goes up: ${out[row + 35]} vs ${mask[row + 35]}", out[row + 35] > mask[row + 35])
    }
}
