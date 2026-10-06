package app.rawline.feature.studio

import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.MaskRef
import app.rawline.core.studio.model.SelOp
import app.rawline.core.studio.render.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S2LogicTest {
    private fun row(mask: MaskRef?, active: Boolean = true) =
        LayerRows.of(listOf(Layer.Pixel(LayerCommon("a", "A", mask = mask), 4, 4)), if (active) "a" else "zz", emptySet()).single()

    @Test fun rowsCarryTheMaskFlags() {
        val r = row(MaskRef("mask/a", enabled = false, inverted = true))
        assertTrue(r.hasMask); assertFalse(r.maskEnabled); assertTrue(r.maskInverted)
        assertFalse(row(null).hasMask)
    }

    @Test fun theMaskMenuOffersWhatAMaskNeeds() {
        val none = MaskMenu.entries(row(null), paintingMask = false, hasSelection = false)
        assertEquals(listOf(MaskAction.ADD_WHITE, MaskAction.ADD_BLACK, MaskAction.ADD_FROM_SELECTION), none.map { it.action })
        assertFalse("from selection needs a selection", none.last().enabled); assertTrue(MaskMenu.entries(row(null), false, true).last().enabled)
        val on = MaskMenu.entries(row(MaskRef("m")), paintingMask = false, hasSelection = false)
        assertEquals(listOf(MaskAction.PAINT_MASK, MaskAction.TURN_OFF, MaskAction.INVERT, MaskAction.DELETE), on.map { it.action })
        assertEquals(MaskAction.PAINT_PIXELS, MaskMenu.entries(row(MaskRef("m")), paintingMask = true, hasSelection = false).first().action)
        assertEquals(MaskAction.PAINT_MASK, MaskMenu.entries(row(MaskRef("m"), active = false), paintingMask = true, hasSelection = false).first().action)   // painting a mask means the active layer's
        assertEquals(MaskAction.TURN_ON, MaskMenu.entries(row(MaskRef("m", enabled = false)), false, false)[1].action)
        assertEquals("Show mask the normal way", MaskMenu.entries(row(MaskRef("m", inverted = true)), false, false)[2].label)
    }

    @Test fun selectionWordsAreStraightAustralianEnglish() {
        assertEquals(listOf("Replace", "Add", "Subtract", "Intersect"), SelectionText.ops.map { SelectionText.label(it) })
        assertEquals(listOf("Rectangle", "Ellipse", "Lasso"), SelectionText.tools.map { SelectionText.toolName(it) })
        assertTrue(SelectionText.isSelect(Tool.LASSO_SELECT)); assertFalse(SelectionText.isSelect(Tool.BRUSH))
        assertEquals(SelOp.entries.toSet(), SelectionText.ops.toSet())
        for (t in SelectionText.tools) assertFalse(SelectionText.hint(t).contains("\u2014"))
    }
}
