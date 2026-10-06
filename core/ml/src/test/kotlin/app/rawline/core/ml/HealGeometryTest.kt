package app.rawline.core.ml

import app.rawline.core.model.EditRecipe
import app.rawline.core.render.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class HealGeometryTest {
    // Lumix S 20-60 at 20 mm (ptlens a, b, c turned into the polynomial the engine uses): about 0.95 at the frame corner
    private val lens = floatArrayOf(1f - 0.02161f + 0.03781f + 0.08584f, -0.08584f, -0.03781f, 0.02161f, 0f)

    @Test fun strokesAreMappedThroughTheLensPolynomial() {
        val r = EditRecipe()
        val corner = listOf(0.95f, 0.93f)
        val withLens = HealGeometry.strokeToSource(corner, r, 1, 6000, 4000, lens)[0]
        val without = HealGeometry.strokeToSource(corner, r, 1, 6000, 4000, null)[0]
        // identical to what the shader does (Geo is the CPU copy of geometry.glsl)
        val expected = Geo.frameToSource(corner[0], corner[1], r.geometry, r.optics, 1, 6000, 4000, lens)
        assertEquals(expected[0], withLens[0], 1e-6f); assertEquals(expected[1], withLens[1], 1e-6f)
        // and it moves the point, by roughly the 4 to 5 percent of the radius the audit measured, hundreds of pixels at 24 MP
        val dxPx = (withLens[0] - without[0]) * 6000; val dyPx = (withLens[1] - without[1]) * 4000
        val shift = hypot(dxPx, dyPx)
        assertTrue("corner stroke moves by $shift px", shift in 80f..250f)
    }

    @Test fun nearTheCentreTheMappingBarelyMoves() {
        val r = EditRecipe()
        val c = listOf(0.51f, 0.5f)
        val a = HealGeometry.strokeToSource(c, r, 1, 6000, 4000, lens)[0]
        val b = HealGeometry.strokeToSource(c, r, 1, 6000, 4000, null)[0]
        assertTrue(hypot((a[0] - b[0]) * 6000, (a[1] - b[1]) * 4000) < 10f)
    }

    @Test fun noProfileMeansTheOldMapping() {
        val r = EditRecipe()
        val p = listOf(0.2f, 0.3f, 0.4f, 0.5f)
        val m = HealGeometry.strokeToSource(p, r, 6, 4000, 6000, null)
        assertEquals(2, m.size)
        for (k in 0 until 2) {
            val e = Geo.frameToSource(p[k * 2], p[k * 2 + 1], r.geometry, r.optics, 6, 4000, 6000)
            assertEquals(e[0], m[k][0], 1e-6f); assertEquals(e[1], m[k][1], 1e-6f); assertEquals(e[2], m[k][2], 0f)
        }
    }
}
