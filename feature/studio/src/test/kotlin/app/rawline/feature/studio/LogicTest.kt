package app.rawline.feature.studio

import app.rawline.core.studio.model.BlendMode
import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.render.Rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun hexRoundTripsAndAcceptsShortAndBareForms() {
        assertEquals("#437EE4", ColourHex.format(Rgb(0x43 / 255f, 0x7E / 255f, 0xE4 / 255f)))
        val c = ColourHex.parse("#437ee4")!!
        assertEquals(0x43 / 255f, c.r, 1e-6f); assertEquals(0xE4 / 255f, c.b, 1e-6f)
        assertEquals(ColourHex.parse("ffffff"), Rgb(1f, 1f, 1f))
        assertEquals(ColourHex.parse("#f80"), Rgb(1f, 0x88 / 255f, 0f))
        assertNull(ColourHex.parse("")); assertNull(ColourHex.parse("#12")); assertNull(ColourHex.parse("#12345g")); assertNull(ColourHex.parse("#1234567"))
        // every byte survives format then parse
        for (v in 0..255 step 17) { val s = ColourHex.format(Rgb(v / 255f, v / 255f, v / 255f)); assertEquals(s, ColourHex.format(ColourHex.parse(s)!!)) }
    }

    @Test fun layersShowTopFirstWithTheMoveButtonsAtTheEnds() {
        val layers = listOf("a", "b", "c").map { Layer.Pixel(LayerCommon(it, "Layer $it", opacity = 50, blend = BlendMode.SCREEN), 4, 4) }
        val rows = LayerRows.of(layers, "b", setOf("c"))
        assertEquals(listOf("c", "b", "a"), rows.map { it.id })
        assertFalse(rows[0].canMoveUp); assertTrue(rows[0].canMoveDown)
        assertTrue(rows[2].canMoveUp); assertFalse(rows[2].canMoveDown)
        assertTrue(rows[1].active); assertTrue(rows[0].hasPixels); assertFalse(rows[1].hasPixels)
        assertEquals("Screen", LayerRows.blendName(rows[0].blend))
        assertEquals(listOf("Normal", "Multiply", "Screen"), BlendMode.entries.map { LayerRows.blendName(it) })
    }

    @Test fun brushSlidersConvertPercentsAndNeverBreakTheBrushInvariants() {
        val b = Brush()
        assertEquals(0.35, BrushSliders.withHardness(b, 35f).hardness, 1e-9)
        assertEquals(1.0, BrushSliders.withOpacity(b, 250f).opacity, 0.0)        // typed values are coerced: Brush would throw otherwise
        assertEquals(0.0, BrushSliders.withOpacity(b, -4f).opacity, 0.0)
        assertEquals(0.01, BrushSliders.withFlow(b, 0f).flow, 1e-9)
        assertEquals(1.0, BrushSliders.withSize(b, 0f).diameter, 0.0)
        assertEquals(2000.0, BrushSliders.withSize(b, 99999f).diameter, 0.0)
    }
}
