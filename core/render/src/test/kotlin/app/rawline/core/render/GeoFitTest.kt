package app.rawline.core.render

import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Geometry
import app.rawline.core.model.Optics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Constrain to image: crop, straighten, keystone and lens correction never expose pixels from outside the source. */
class GeoFitTest {
    private val synth = floatArrayOf(1f - 0.02161f + 0.03781f + 0.08584f, -0.08584f, -0.03781f, 0.02161f, 0f)   // golden "lens"
    private val fill = floatArrayOf(0.94f, 0f, 0.06f, 0f, 0f)                                                    // golden "lensfill"
    private val W = 3004; private val H = 2004

    private fun fit(g: Geometry, o: Optics = Optics(), lens: FloatArray? = null, orientation: Int = 1, w: Int = W, h: Int = H) =
        Geo.fitCrop(g, o, orientation, w, h, lens)

    /** Dense interior check (not just the border the solver tests), strictly against the source edges with no margin. */
    private fun assertNoOutside(c: FloatArray, g: Geometry, o: Optics, lens: FloatArray?, orientation: Int = 1, w: Int = W, h: Int = H) {
        val n = 80
        for (i in 0..n) for (j in 0..n) {
            val r = Geo.frameToSource(c[0] + c[2] * i / n, c[1] + c[3] * j / n, g, o, orientation, w, h, lens)
            assertEquals("outside at ($i,$j) -> ${r[0]},${r[1]}", 1f, r[2])
        }
    }

    @Test fun untouchedGeometryKeepsTheCropExactly() {
        val g = Geometry(cropX = 0.1f, cropY = 0.2f, cropW = 0.5f, cropH = 0.4f)
        assertEquals(listOf(0.1f, 0.2f, 0.5f, 0.4f), fit(g).toList())
        assertEquals(listOf(0f, 0f, 1f, 1f), fit(Geometry()).toList())
    }

    @Test fun straightenFullFrameMatchesTheInscribedRectangleFormula() {
        for (deg in listOf(0.5f, 2f, 5.7f, 12f, -8f)) {
            val g = Geometry(angle = deg)
            val c = fit(g)
            val a = Math.toRadians(abs(deg).toDouble()); val co = cos(a); val si = sin(a)
            val expect = min(W / (W * co + H * si), H / (W * si + H * co)).toFloat()
            // the safety margin takes 0.3 percent, never more
            assertEquals("scale at $deg", expect, c[2], 0.006f)
            assertTrue(c[2] <= expect + 1e-4f)
            assertEquals("aspect kept", 1f, c[2] / c[3], 1e-3f)
            assertEquals("centred x", 0.5f, c[0] + c[2] / 2, 1e-3f); assertEquals("centred y", 0.5f, c[1] + c[3] / 2, 1e-3f)
            assertNoOutside(c, g, Optics(), null)
        }
    }

    @Test fun fittedCropIsTight() {
        val g = Geometry(angle = 6f)
        val c = fit(g)
        val bigger = floatArrayOf(c[0] - c[2] * 0.02f, c[1] - c[3] * 0.02f, c[2] * 1.04f, c[3] * 1.04f)
        val bad = (0..40).any { i -> listOf(0f, 1f).any { e -> Geo.frameToSource(bigger[0] + bigger[2] * i / 40, bigger[1] + bigger[3] * e, g, Optics(), 1, W, H)[2] == 0f } }
        assertTrue("4 percent larger must touch outside", bad)
    }

    @Test fun cropNearACornerIsPushedInsideKeepingSizeWhenItCan() {
        val g = Geometry(angle = 3f, cropX = 0f, cropY = 0f, cropW = 0.5f, cropH = 0.5f)
        val c = fit(g)
        assertNoOutside(c, g, Optics(), null)
        assertTrue("moved towards the centre", c[0] > 0f && c[1] > 0f)
        assertEquals("aspect kept", 1f, c[2] / c[3], 1e-3f)
        assertTrue("a half frame crop fits a 3 degree tilt without shrinking much", c[2] > 0.49f)
    }

    @Test fun cropAlreadyInsideIsLeftAlone() {
        val g = Geometry(angle = 4f, cropX = 0.3f, cropY = 0.3f, cropW = 0.4f, cropH = 0.4f)
        assertEquals(listOf(0.3f, 0.3f, 0.4f, 0.4f), fit(g).toList())
    }

    @Test fun keystoneAndManualDistortionAreCovered() {
        val g = Geometry(keystoneV = 35f, keystoneH = -20f, angle = -2f)
        val o = Optics(distortion = 30f)
        val c = fit(g, o)
        assertTrue("scale ${c.toList()}", c[2] < 0.95f)
        assertNoOutside(c, g, o, null)
        for (o2 in listOf(Optics(distortion = 40f), Optics(distortion = -60f), Optics(distortion = 100f))) {
            val c2 = fit(Geometry(), o2)
            assertNoOutside(c2, Geometry(), o2, null)
        }
    }

    @Test fun lensProfileThatPullsEdgesInIsCovered() {
        val c = fit(Geometry(), lens = fill)
        assertTrue("strong pincushion fix must crop in", c[2] < 0.95f)
        assertNoOutside(c, Geometry(), Optics(), fill)
        // the Lumix style profile stays inside the frame: only the safety margin is taken
        val s = fit(Geometry(), lens = synth)
        assertTrue(s[2] > 0.99f)
        assertNoOutside(s, Geometry(), Optics(), synth)
    }

    @Test fun orientationsAndFlipsAreHandled() {
        for (ori in listOf(1, 3, 6, 8, 2, 5)) for (flip in listOf(false, true)) {
            val g = Geometry(angle = 4f, flipH = flip, flipV = !flip, rotate90 = if (flip) 1 else 0)
            val c = fit(g, Optics(), fill, ori)
            assertNoOutside(c, g, Optics(), fill, ori)
        }
    }

    @Test fun framingDoesNotDependOnResolution() {
        val g = Geometry(angle = 5f)
        val half = fit(g, w = 3004, h = 2004); val full = fit(g, w = 6008, h = 4008)
        for (i in 0 until 4) assertEquals(half[i], full[i], 2e-3f)
    }

    @Test fun renderParamsCarryTheFittedCropOnlyWhenAskedTo() {
        val r = EditRecipe(geometry = Geometry(angle = 5f))
        val plain = RenderParams.build(r, 1)
        assertEquals(1f, plain[P.G_CROP + 2])                       // crop editing and whole-frame renders keep the recipe crop
        val fitted = RenderParams.build(r, 1, srcW = W, srcH = H)
        val c = fit(r.geometry)
        for (i in 0 until 4) assertEquals(c[i], fitted[P.G_CROP + i], 0f)
        assertTrue(fitted[P.G_CROP + 2] < 1f)
        // lens correction off: its profile must not shrink the crop
        val lens = LensCorrection("x", fill, null, null)
        val off = RenderParams.build(EditRecipe(optics = Optics(lensCorrection = false)), 1, lens = lens, srcW = W, srcH = H)
        assertEquals(1f, off[P.G_CROP + 2])
        val on = RenderParams.build(EditRecipe(), 1, lens = lens, srcW = W, srcH = H)
        assertTrue(on[P.G_CROP + 2] < 0.95f)
    }

    @Test fun fitCropIsPureAndRepeatable() {
        val g = Geometry(angle = 7f)
        val a = fit(g); a[0] = 99f
        val b = fit(g)
        assertFalse(b[0] == 99f)
        assertEquals(fit(g.copy(angle = 7f)).toList(), b.toList())
    }

    /** The golden harness solves the same crops in C++ (tools/golden/run-golden.sh checks them against this file). */
    @Test fun matchesTheGoldenHarnessFixture() {
        val f = listOf("../../tools/golden/fitcrop.expected", "tools/golden/fitcrop.expected", "../tools/golden/fitcrop.expected").map(::File).first { it.exists() }
        var rows = 0
        for (line in f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }) {
            val t = line.split(Regex("\\s+"))
            val arrow = t.indexOf("->")
            val w = t[1].toInt(); val h = t[2].toInt(); val ori = t[3].toInt()
            val g = Geometry(
                cropW = t[arrow - 1].toFloat(), angle = Math.toDegrees(t[4].toDouble()).toFloat(),
                keystoneV = t[5].toFloat() * 200f, keystoneH = t[6].toFloat() * 200f,
            )
            val o = Optics(distortion = t[7].toFloat() * 200f)
            val lens = when (t[8]) { "synth" -> synth; "fill" -> fill; else -> null }
            val got = fit(g, o, lens, ori, w, h)
            for (i in 0 until 4) assertEquals("${t[0]}[$i]", t[arrow + 1 + i].toFloat(), got[i], 2e-3f)
            rows++
        }
        assertTrue(rows >= 5)
    }

    @Test fun fitCacheNeverMixesResultsAcrossThreads() {
        Geo.clearFitCache()
        val ga = Geometry(angle = 3f); val gb = Geometry(angle = -9f, keystoneV = 20f)
        val expectA = Geo.fitCrop(ga, Optics(), 1, W, H, null).toList()
        val expectB = Geo.fitCrop(gb, Optics(), 1, W, H, null).toList()
        assertTrue(expectA != expectB)
        val bad = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until 6).map { n ->
            Thread { repeat(400) { i ->
                val useA = (i + n) % 2 == 0
                val got = Geo.fitCrop(if (useA) ga else gb, Optics(), 1, W, H, null).toList()
                if (got != (if (useA) expectA else expectB)) bad.incrementAndGet()
            } }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(0, bad.get())
    }

    @Test fun fitCacheReturnsACopyAndKeepsItsKey() {
        Geo.clearFitCache()
        val g = Geometry(angle = 5f)
        val first = fit(g)
        first[2] = 0f   // the caller scribbles on its copy
        val again = fit(g)
        assertTrue(again[2] > 0.5f)
        assertEquals(again.toList(), fit(g).toList())
    }
}
