package app.rawline.feature.editor

import app.rawline.core.model.Adjust
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Geometry
import app.rawline.core.model.Look
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

    @Test fun applyPresetKeepsTheTargetsLook() {
        val old = EditRecipe(lookVersion = Look.V1, adjust = Adjust(contrast = 10f))
        val preset = EditRecipe(adjust = Adjust(contrast = 30f, saturation = 20f))   // a preset saved from a new edit (look 2)
        for (t in listOf(0f, 0.5f, 1f)) assertEquals(Look.V1, Presets.apply(old, preset, t).lookVersion)
        assertEquals(Look.CURRENT, Presets.apply(EditRecipe(), EditRecipe(lookVersion = Look.V1, adjust = Adjust(contrast = 30f)), 1f).lookVersion)
        for (p in Presets.builtIn) assertEquals(p.name, Look.V1, Presets.apply(old, p.recipe, 1f).lookVersion)
    }

    // EditorSession only queues work until a GL view exists, so a session built on a stub context is enough to drive the history.
    private fun stateOf(r: EditRecipe) = EditorState(r, EditorSession(android.content.ContextWrapper(null)))

    @Test fun updateLookIsOneUndoableStepAndChangesNothingElse() {
        val old = EditRecipe(lookVersion = Look.V1, adjust = Adjust(exposure = 0.3f, contrast = 12f))
        val st = stateOf(old)
        assertTrue(st.canUpdateLook)
        st.updateLook()
        assertEquals(Look.CURRENT, st.recipe.lookVersion)
        assertEquals(old.copy(lookVersion = Look.CURRENT), st.recipe)
        assertEquals(2, st.history.size); assertEquals("Update look", st.history.last().label)
        assertEquals(Look.CURRENT, st.session.currentRecipe.lookVersion)   // the session (and so the engine) follows
        st.undo()
        assertEquals(old, st.recipe); assertEquals(Look.V1, st.session.currentRecipe.lookVersion)
        st.redo()
        assertEquals(Look.CURRENT, st.recipe.lookVersion)
    }

    @Test fun updateLookDoesNothingOnACurrentEdit() {
        val st = stateOf(EditRecipe(adjust = Adjust(exposure = 0.3f)))
        assertEquals(false, st.canUpdateLook)
        st.updateLook()
        assertEquals(1, st.history.size)
    }

    @Test fun resetMovesAnOldEditToTheCurrentLook() {
        val st = stateOf(EditRecipe(lookVersion = Look.V1, adjust = Adjust(exposure = 0.3f)))
        st.reset()
        assertTrue(st.recipe.isDefault); assertEquals(Look.CURRENT, st.recipe.lookVersion)
        st.undo()
        assertEquals(Look.V1, st.recipe.lookVersion)
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

    // ---------- cancel crop ----------

    private fun rec(w: Float) = EditRecipe(geometry = Geometry(cropW = w))

    @Test fun cancelCropWithNothingChangedAddsNoHistoryEntry() {
        val h = listOf(rec(1f), rec(0.8f))
        assertEquals(CropCancelPlan.Nothing, planCropCancel(h, 1, h[1], 1, h[1].geometry))
    }

    @Test fun cancelCropStepsBackToTheEntryAfterEdits() {
        val h = listOf(rec(1f), rec(0.8f), rec(0.6f), rec(0.5f))
        assertEquals(CropCancelPlan.Jump(1), planCropCancel(h, 3, h[3], 1, h[1].geometry))
    }

    @Test fun cancelCropDropsAnUncommittedLiveChange() {
        val h = listOf(rec(1f), rec(0.8f))
        assertEquals(CropCancelPlan.Live(h[1]), planCropCancel(h, 1, rec(0.7f), 1, h[1].geometry))
    }

    @Test fun cancelCropAfterTheEditorWasRebuiltFallsBackToTheSavedGeometry() {
        // after a rotation the history restarts: the saved entry geometry is no longer at its old index
        val h = listOf(rec(0.5f))
        assertEquals(CropCancelPlan.Edit(Geometry(cropW = 0.9f)), planCropCancel(h, 0, h[0], 3, Geometry(cropW = 0.9f)))
        assertEquals(CropCancelPlan.Nothing, planCropCancel(h, 0, h[0], 3, h[0].geometry))
    }

    @Test fun geometrySaverRoundTrips() {
        val g = Geometry(0.1f, 0.2f, 0.5f, 0.4f, 3.5f, 1, true, false, 10f, -5f, "4:5")
        val saved = with(GeometrySaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(g)!! }
        assertEquals(g, GeometrySaver.restore(saved))
    }
}
