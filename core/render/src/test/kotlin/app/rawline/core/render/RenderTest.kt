package app.rawline.core.render

import app.rawline.core.model.Adjust
import app.rawline.core.model.CurvePoint
import app.rawline.core.model.Curves
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Geometry
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskComponent
import app.rawline.core.model.MaskType
import app.rawline.core.model.Optics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RenderTest {
    private fun header(name: String): String {
        val f = listOf("../core/native/src/main/cpp/engine/params.h", "../native/src/main/cpp/engine/params.h", "core/native/src/main/cpp/engine/params.h").map(::File).first { it.exists() }
        return f.readText()
    }
    private fun const(text: String, name: String) = Regex("""$name\s*=\s*(\d+)""").find(text)!!.groupValues[1].toInt()

    @Test fun kotlinAndNativeParamLayoutsAgree() {
        val h = header("params.h")
        assertEquals(const(h, "kBlockTexels"), P.BLOCK_TEXELS)
        assertEquals(const(h, "kMaxMasks"), P.MAX_MASKS)
        assertEquals(const(h, "kMaskTexels"), P.MASK_TEXELS)
        assertEquals(const(h, "kMaxLayers"), P.MAX_LAYERS)
        assertEquals(const(h, "kCurveSize"), P.CURVE_SIZE)
        for ((n, v) in listOf("G_CROP" to P.G_CROP, "G_GEO" to P.G_GEO, "G_GEO2" to P.G_GEO2, "G_DETAIL" to P.G_DETAIL, "G_NR" to P.G_NR, "G_FX" to P.G_FX,
            "G_FX2" to P.G_FX2, "G_NUM_MASKS" to P.G_NUM_MASKS, "G_OVERLAY" to P.G_OVERLAY, "G_SHOWMASK" to P.G_SHOWMASK, "G_COUNT" to P.G_COUNT)) assertEquals(n, const(h, n), v)
    }

    @Test fun curveLutIsIdentityWithoutPoints_andMonotone() {
        val id = CurveMath.lut(emptyList())
        assertEquals(0f, id[0]); assertEquals(1f, id[255]); assertEquals(0.5f, id[128], 0.01f)
        val s = CurveMath.lut(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.1f), CurvePoint(0.75f, 0.9f), CurvePoint(1f, 1f)))
        for (i in 1 until 256) assertTrue("monotone at $i", s[i] >= s[i - 1] - 1e-6f)
        assertTrue(s[64] < 0.25f); assertTrue(s[192] > 0.75f)
    }

    @Test fun defaultRecipeGivesIdentityBlocks() {
        val p = RenderParams.build(EditRecipe(), 1, useBaseline = false)
        assertEquals(P.TOTAL, p.size)
        assertEquals(1f, p[P.G_CROP + 2]); assertEquals(1f, p[P.G_CROP + 3])
        assertEquals(0f, p[P.OFF_BLOCKS]) // exposure
        assertEquals(0f, p[P.G_NUM_MASKS])
        // all curve rows are identity
        for (r in 0 until P.CURVE_ROWS) { assertEquals(0f, p[P.OFF_CURVES + r * 256]); assertEquals(1f, p[P.OFF_CURVES + r * 256 + 255]) }
    }

    @Test fun orientationSixIsClockwiseQuarterTurn() {
        val p = RenderParams.build(EditRecipe(), 6, useBaseline = false)
        assertEquals(1f, p[P.G_GEO + 3]); assertEquals(0f, p[P.G_GEO + 1])
        val q = RenderParams.build(EditRecipe(geometry = Geometry(rotate90 = 3)), 6, useBaseline = false)
        assertEquals(0f, q[P.G_GEO + 3])    // 1 + 3 = full turn
    }

    @Test fun maskIsPackedForTheShader() {
        val m = Mask("a", "A", listOf(MaskComponent(MaskType.LINEAR, params = listOf(0.1f, 0.2f, 0.3f, 0.4f))), Adjust(exposure = -1f), amount = 0.5f)
        val p = RenderParams.build(EditRecipe(masks = listOf(m)), 1, useBaseline = false)
        assertEquals(1f, p[P.G_NUM_MASKS])
        val o = P.OFF_MASKS
        assertEquals(1f, p[o]); assertEquals(0.5f, p[o + 1])
        assertEquals(MaskType.LINEAR.code.toFloat(), p[o + 4]); assertEquals(0.3f, p[o + 10])
        assertEquals(-1f, p[P.OFF_BLOCKS + P.BLOCK_FLOATS]) // block 1 exposure
    }

    @Test fun bitmapMaskWithoutLayerIsSkipped() {
        val m = Mask("a", "A", listOf(MaskComponent(MaskType.BITMAP, layerKey = "k")))
        assertEquals(0f, RenderParams.build(EditRecipe(masks = listOf(m)), 1)[P.OFF_MASKS])
        assertEquals(1f, RenderParams.build(EditRecipe(masks = listOf(m)), 1, layers = mapOf("k" to 3))[P.OFF_MASKS])
    }

    @Test fun geometryMatchesShaderForSimpleCases() {
        val g = Geometry(); val o = Optics()
        val c = Geo.frameToSource(0.25f, 0.75f, g, o, 1, 6000, 4000)
        assertEquals(0.25f, c[0], 1e-5f); assertEquals(0.75f, c[1], 1e-5f); assertEquals(1f, c[2])
        // orientation 6: top-left of the shown frame is the bottom-left of the stored picture
        val r = Geo.frameToSource(0f, 0f, g, o, 6, 6000, 4000)
        assertEquals(0f, r[0], 1e-5f); assertEquals(1f, r[1], 1e-5f)
        val flip = Geo.frameToSource(0.1f, 0.5f, g.copy(flipH = true), o, 1, 6000, 4000)
        assertEquals(0.9f, flip[0], 1e-5f)
        val out = Geo.frameToSource(0.5f, 0.5f, g.copy(angle = 90f), o, 1, 6000, 4000)
        assertEquals(1f, out[2]) // centre stays inside
    }

    @Test fun tiffWriterProducesValidHeaderAndSize() {
        val out = ByteArrayOutputStream()
        val w = Tiff16Writer(out, 4, 2, "me")
        w.writeRows(ShortArray(4 * 2 * 3) { 1000 }, 2)
        w.finish()
        val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4949, b.getShort(0).toInt() and 0xFFFF); assertEquals(42, b.getShort(2).toInt())
        val ifd = b.getInt(4)
        val n = b.getShort(ifd).toInt()
        var dataOff = -1; var width = -1
        for (i in 0 until n) {
            val tag = b.getShort(ifd + 2 + i * 12).toInt() and 0xFFFF
            val v = b.getInt(ifd + 2 + i * 12 + 8)
            if (tag == 273) dataOff = v
            if (tag == 256) width = v
        }
        assertEquals(4, width)
        assertEquals(out.size(), dataOff + 4 * 2 * 3 * 2)
        assertEquals(1000, b.getShort(dataOff).toInt())
    }

    @Test fun curvesIdentityFlag() {
        assertTrue(Curves().isIdentity)
        assertTrue(!Curves(master = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.9f))).isIdentity)
    }

    private fun lensDb(): LensProfiles {
        val f = listOf("src/main/assets/lensfun/lenses_lmount.xml", "core/render/src/main/assets/lensfun/lenses_lmount.xml").map(::File).first { it.exists() }
        return f.inputStream().use { LensProfiles.parse(it) }
    }

    @Test fun lensProfileIsFoundAndInterpolated() {
        val db = lensDb()
        val a = db.find("LUMIX S 20-60/F3.5-5.6", 20f, 3.5f)!!
        assertEquals("Lumix S 20-60/F3.5-5.6", a.name)
        // ptlens at 20 mm: d = 1 - a - b - c
        assertEquals(1f - 0.02161f + 0.03781f + 0.08584f, a.dist!![0], 1e-4f)
        assertEquals(-0.8703127f, a.vig!![0], 1e-4f)
        val mid = db.find("lumix s 20-60/f3.5-5.6", 21f, 3.5f)!!.dist!!     // between the 20 and 22 mm entries
        assertTrue(mid[1] > -0.08584f && mid[1] < -0.05843f)
        assertEquals(null, db.find("Some Other Lens 85mm", 85f, 1.8f))
        assertEquals(null, db.find(null, 20f, 3.5f))
    }

    @Test fun lensParamsReachTheShader() {
        val lens = lensDb().find("LUMIX S 20-60/F3.5-5.6", 20f, 5f)!!
        val off = RenderParams.build(EditRecipe(), 1, lens = lens)
        assertEquals(0f, off[P.G_LDIST_ON]); assertEquals(0f, off[P.G_LVIG_ON]); assertEquals(0f, off[P.G_LTCA_ON])
        val on = RenderParams.build(EditRecipe(optics = Optics(lensCorrection = true, removeCa = true)), 1, lens = lens)
        assertEquals(1f, on[P.G_LDIST_ON]); assertEquals(1f, on[P.G_LVIG_ON]); assertEquals(1f, on[P.G_LTCA_ON])
        assertEquals(-0.8195091f, on[P.G_LVIG], 1e-4f)   // f/5.0 entry is the nearest to f/5
    }
}
