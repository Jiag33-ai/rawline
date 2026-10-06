package app.rawline.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

class LensVignettingTest {
    private fun vig(focal: Float, ap: Float, distance: Float, k1: Float, k2: Float = 0f, k3: Float = 0f) =
        LensProfiles.Vig(focal, ap, distance, floatArrayOf(k1, k2, k3))

    private fun interp(v: List<LensProfiles.Vig>, focal: Float, ap: Float) = LensProfiles.interpolateVignetting(v, focal, ap)!!

    @Test fun anExactCalibrationIsReturnedUnchanged() {
        val v = listOf(vig(20f, 3.5f, 1000f, -0.87f, 0.17f, -0.15f), vig(30f, 4.2f, 1000f, -0.73f))
        val k = interp(v, 20f, 3.5f)
        assertEquals(-0.87f, k[0], 1e-6f); assertEquals(0.17f, k[1], 1e-6f); assertEquals(-0.15f, k[2], 1e-6f)
    }

    @Test fun focalLengthIsInterpolatedLinearly() {
        val v = listOf(vig(20f, 3.5f, 1000f, -0.9f, 0.2f, 0f), vig(30f, 3.5f, 1000f, -0.5f, 0.0f, 0.1f))
        val mid = interp(v, 25f, 3.5f)
        assertEquals(-0.7f, mid[0], 1e-5f); assertEquals(0.1f, mid[1], 1e-5f); assertEquals(0.05f, mid[2], 1e-5f)
        val quarter = interp(v, 22.5f, 3.5f)
        assertEquals(-0.8f, quarter[0], 1e-5f)
    }

    @Test fun apertureIsInterpolatedInLogSteps() {
        val v = listOf(vig(20f, 2.0f, 1000f, -1.0f), vig(20f, 8.0f, 1000f, -0.2f))
        // f/4 is the geometric middle of f/2 and f/8
        assertEquals(-0.6f, interp(v, 20f, 4.0f)[0], 1e-5f)
        // the arithmetic middle (f/5) is past the log middle, so closer to the f/8 value
        assertTrue(interp(v, 20f, 5.0f)[0] > -0.6f)
    }

    @Test fun bothAxesTogetherAreBilinear() {
        val v = listOf(vig(20f, 2.0f, 1000f, 0f), vig(20f, 8.0f, 1000f, 1f), vig(40f, 2.0f, 1000f, 2f), vig(40f, 8.0f, 1000f, 3f))
        // centre of the cell: mean of the four corners
        assertEquals(1.5f, interp(v, 30f, 4.0f)[0], 1e-5f)
        // along the aperture axis at 20 mm, along focal at f/2
        assertEquals(0.5f, interp(v, 20f, 4.0f)[0], 1e-5f)
        assertEquals(1.0f, interp(v, 30f, 2.0f)[0], 1e-5f)
    }

    @Test fun outsideTheCalibratedRangeTheNearestCalibrationIsUsed() {
        val v = listOf(vig(20f, 3.5f, 1000f, -0.9f), vig(20f, 8f, 1000f, -0.4f), vig(30f, 4.2f, 1000f, -0.7f), vig(30f, 11f, 1000f, -0.3f))
        assertEquals(-0.9f, interp(v, 15f, 2.0f)[0], 1e-5f)    // wider and shorter than anything calibrated
        assertEquals(-0.3f, interp(v, 70f, 22f)[0], 1e-5f)     // longer and smaller aperture
        assertEquals(-0.9f, interp(v, 20f, 0f)[0], 1e-5f)      // unknown aperture counts as wide open
    }

    @Test fun nearFocusCalibrationsAreIgnoredWhenFarOnesExist() {
        val v = listOf(vig(56f, 2.0f, 0.5f, -0.45f), vig(56f, 2.0f, 1000f, -0.085f), vig(56f, 2.0f, 3f, -0.1f))
        assertEquals(-0.085f, interp(v, 56f, 2.0f)[0], 1e-6f)
        // with only near calibrations they are all that exists
        assertEquals(-0.45f, interp(listOf(vig(56f, 2.0f, 0.5f, -0.45f)), 56f, 2.0f)[0], 1e-6f)
    }

    @Test fun nothingCalibratedMeansNoCorrection() {
        assertNull(LensProfiles.interpolateVignetting(emptyList(), 20f, 3.5f))
    }

    private fun shippedDb(): LensProfiles {
        val f = listOf("src/main/assets/lensfun/lenses_lmount.xml", "core/render/src/main/assets/lensfun/lenses_lmount.xml").map(::File).first { it.exists() }
        return f.inputStream().use { LensProfiles.parse(it) }
    }

    /** Gain the shader divides by at the frame corner (r = 1 of half the diagonal): 1 + k1 + k2 + k3. */
    private fun cornerGain(k: FloatArray) = 1f + k[0] + k[1] + k[2]

    @Test fun theCorrectionDoesNotStepAcrossAZoom() {
        // The shipped 20 to 60 zoom at f/5.6: sweep the focal length in 0.25 mm steps. With nearest calibration the corner gain jumped by
        // about 0.4 half way between two calibrated focal lengths; interpolated it changes smoothly (the largest change per step is small).
        val db = shippedDb()
        var prev = Float.NaN; var worst = 0f
        var f = 20f
        while (f <= 60f) {
            val k = db.find("Lumix S 20-60/F3.5-5.6", f, 5.6f)!!.vig!!
            val g = cornerGain(k)
            if (!prev.isNaN()) worst = maxOf(worst, abs(g - prev))
            prev = g
            f += 0.25f
        }
        assertTrue("largest corner gain change per 0.25 mm step was $worst", worst < 0.06f)
    }

    @Test fun theCorrectionDoesNotStepAcrossApertures() {
        val db = shippedDb()
        var prev = Float.NaN; var worst = 0f
        var stops = 0.0
        while (stops <= 4.0) {   // f/3.5 to f/56 in 1/12 stop steps
            val ap = (3.5 * Math.pow(2.0, stops / 2.0)).toFloat()
            val g = cornerGain(db.find("Lumix S 20-60/F3.5-5.6", 20f, ap)!!.vig!!)
            if (!prev.isNaN()) worst = maxOf(worst, abs(g - prev))
            prev = g
            stops += 1.0 / 12
        }
        assertTrue("largest corner gain change per 1/12 stop was $worst", worst < 0.06f)
    }

    @Test fun midwayBetweenTwoZoomPositionsIsBetweenTheirValues() {
        val db = shippedDb()
        val a = db.find("Lumix S 20-60/F3.5-5.6", 20f, 5.0f)!!.vig!!
        val b = db.find("Lumix S 20-60/F3.5-5.6", 30f, 5.0f)!!.vig!!
        val m = db.find("Lumix S 20-60/F3.5-5.6", 25f, 5.0f)!!.vig!!
        for (i in 0 until 3) assertTrue("k${i + 1}", m[i] >= minOf(a[i], b[i]) - 1e-5f && m[i] <= maxOf(a[i], b[i]) + 1e-5f)
        assertEquals((cornerGain(a) + cornerGain(b)) / 2f, cornerGain(m), 0.02f)
    }

    @Test fun parsedXmlUsesTheInterpolationToo() {
        val xml = """<lensdatabase><lens><maker>Test</maker><model>Test 20-40/F4</model><cropfactor>1</cropfactor><calibration>
            <vignetting model="pa" focal="20" aperture="4" distance="1000" k1="-1" k2="0" k3="0"/>
            <vignetting model="pa" focal="40" aperture="4" distance="1000" k1="-0.5" k2="0" k3="0"/>
            </calibration></lens></lensdatabase>"""
        val db = LensProfiles.parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(-0.75f, db.find("Test 20-40/F4", 30f, 4f)!!.vig!![0], 1e-5f)
    }
}
