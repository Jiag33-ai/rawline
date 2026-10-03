package app.rawline.feature.editor

import app.rawline.core.model.Adjust
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Geometry
import app.rawline.core.render.EditorSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EditorTest {
    @Test fun presetStrengthBlends() {
        val base = EditRecipe(adjust = Adjust(contrast = 10f), geometry = Geometry(cropW = 0.5f))
        val preset = EditRecipe(adjust = Adjust(contrast = 30f, saturation = 20f))
        val none = Presets.apply(base, preset, 0f)
        assertEquals(10f, none.adjust.contrast, 1e-4f)
        val half = Presets.apply(base, preset, 0.5f)
        assertEquals(20f, half.adjust.contrast, 1e-4f); assertEquals(10f, half.adjust.saturation, 1e-4f)
        val full = Presets.apply(base, preset, 1f)
        assertEquals(30f, full.adjust.contrast, 1e-4f)
        assertEquals(0.5f, full.geometry.cropW)   // crop is never touched by a look
    }

    @Test fun builtInPresetsHaveUniqueNames() {
        assertEquals(Presets.builtIn.size, Presets.builtIn.map { it.name }.toSet().size)
    }

    @Test fun cropToAspectKeepsCentreAndRatio() {
        val g = fitAspect(Geometry(), 1f, 1.5f)   // square from a 3:2 frame
        val ratio = g.cropW * 1.5f / g.cropH
        assertEquals(1f, ratio, 1e-3f)
        assertEquals(0.5f, g.cropX + g.cropW / 2f, 1e-3f)
        val keep = fitAspect(Geometry(), -1f, 1.5f)
        assertEquals(Geometry(), keep)
    }

    @Test fun kelvinMappingRoundTrips() {
        assertEquals(5500f, tempToKelvin(0f), 1f)
        assertEquals(30f, kelvinToTemp(tempToKelvin(30f)), 1e-3f)
        assertTrue(tempToKelvin(20f) > tempToKelvin(0f))
    }

    @Test fun autoLightBrightensDarkImagesAndTamesBrightOnes() {
        val dark = EditorSession.ImageStats(0.02f, 0.05f, 0.18f, 0.5f, 0.7f, 0.2f, 0.2f, 0.2f)
        assertTrue(AutoTools.autoLight(dark).exposure > 0.3f)
        val bright = EditorSession.ImageStats(0.1f, 0.2f, 0.75f, 0.98f, 1f, 0.8f, 0.8f, 0.8f)
        assertTrue(AutoTools.autoLight(bright).exposure < 0f)
        val clipped = EditorSession.ImageStats(0.05f, 0.1f, 0.5f, 0.98f, 1f, 0.5f, 0.5f, 0.5f)
        assertTrue("blown highlights are pulled back", AutoTools.autoLight(clipped).highlights < 0f)
    }

    @Test fun autoWhiteBalanceCorrectsWarmCast() {
        val warm = EditorSession.ImageStats(0f, 0f, 0.5f, 0.9f, 1f, 0.7f, 0.55f, 0.4f)
        val (temp, _) = AutoTools.autoWb(warm)
        assertTrue("a warm picture needs a cooler (negative) temperature: $temp", temp < 0f)
        val neutral = EditorSession.ImageStats(0f, 0f, 0.5f, 0.9f, 1f, 0.5f, 0.5f, 0.5f)
        val (t2, ti2) = AutoTools.autoWb(neutral)
        assertTrue(abs(t2) < 1f && abs(ti2) < 1f)
    }
}
