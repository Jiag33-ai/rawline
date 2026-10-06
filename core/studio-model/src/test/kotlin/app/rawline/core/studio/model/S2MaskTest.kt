package app.rawline.core.studio.model

import org.junit.Assert.*
import org.junit.Test

/** scene_mask_small.txt is written by the independent Python reference (tools/studio/studio_mask.py small). */
class S2MaskTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val lines get() = javaClass.getResourceAsStream("/scene_mask_small.txt")!!.bufferedReader().readLines()

    @Test fun maskedSceneEqualsThePythonReferenceWithinOneLevel() {
        val v = lines[0].split(" ").drop(1)
        val layers = lines.filter { it.startsWith("layer ") }.map { l ->
            val t = l.split(" ")   // layer w h x y scale opacity mode maskMode pixelsHex maskHex
            val mask = if (t[10] == "-") null else hex(t[10])
            RefLayer(RefImage(t[1].toInt(), t[2].toInt(), hex(t[9])), t[3].toFloat(), t[4].toFloat(), t[5].toFloat(), t[6].toFloat(), BlendMode.entries.first { it.id == t[7].toInt() }, mask, t[8].toInt())
        }
        val expected = hex(lines.first { it.startsWith("expected ") }.substringAfter(" "))
        val got = ReferenceCompositor.render(layers, v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toInt(), v[4].toInt())
        var worst = 0
        for (i in got.indices) worst = maxOf(worst, Math.abs((got[i].toInt() and 255) - (expected[i].toInt() and 255)))
        assertTrue("worst difference $worst levels", worst <= 1)
        assertTrue("the scene has an inverted, an on and an off mask", layers.mapNotNull { if (it.mask != null) it.maskMode else null }.toSet() == setOf(0, 1, 2))
    }

    @Test fun anAllWhiteMaskIsByteIdenticalToNoMaskAndDisabledAndOffMasksDoNothing() {
        val w = 20; val h = 14; val rnd = java.util.Random(3)
        val img = RefImage(w, h, ByteArray(w * h * 4).also { rnd.nextBytes(it) })
        val base = RefLayer(RefImage(30, 20, ByteArray(30 * 20 * 4) { if (it % 4 == 3) 255.toByte() else (it * 7).toByte() }), 0f, 0f, 1f, 1f, BlendMode.NORMAL)
        fun render(l: RefLayer) = ReferenceCompositor.render(listOf(base, l), 0f, 0f, 1f, 30, 20)
        val plain = render(RefLayer(img, 3f, 2f, 1.5f, 0.8f, BlendMode.MULTIPLY))
        val white = ByteArray(w * h) { 255.toByte() }
        assertArrayEquals(plain, render(RefLayer(img, 3f, 2f, 1.5f, 0.8f, BlendMode.MULTIPLY, white, 1)))
        assertArrayEquals(plain, render(RefLayer(img, 3f, 2f, 1.5f, 0.8f, BlendMode.MULTIPLY, ByteArray(w * h), 0)))   // a black mask that is off
        assertArrayEquals(plain, render(RefLayer(img, 3f, 2f, 1.5f, 0.8f, BlendMode.MULTIPLY, ByteArray(w * h), 2)))   // inverted black = white
    }

    @Test fun layersOfHonoursEnabledAndInvertedAndHiddenLayers() {
        val d = Document("d", "D", 8, 8, layers = listOf(
            Layer.Pixel(LayerCommon("a", "A", mask = MaskRef("mask/a")), 8, 8),
            Layer.Pixel(LayerCommon("b", "B", mask = MaskRef("mask/b", enabled = false)), 8, 8),
            Layer.Pixel(LayerCommon("c", "C", mask = MaskRef("mask/c", inverted = true)), 8, 8),
            Layer.Pixel(LayerCommon("e", "E", visible = false, mask = MaskRef("mask/e")), 8, 8)))
        val r = ReferenceCompositor.layersWithMasks(d, { RefImage(8, 8, ByteArray(256)) }, { ByteArray(64) })
        assertEquals(listOf(1, 0, 2), r.map { it.maskMode })
        assertNull(r[1].mask)
    }

    @Test fun selectionShapesEqualThePythonRasteriserExactly() {
        val e = lines.first { it.startsWith("ellipse ") }.split(" ")
        val s = Selection(e[1].toInt(), e[2].toInt()); s.ellipse(SelOp.REPLACE, e[3].toDouble(), e[4].toDouble(), e[5].toDouble(), e[6].toDouble())
        assertArrayEquals("ellipse", hex(e[7]), s.plane.toBytes())
        val l = lines.first { it.startsWith("lasso ") }
        val head = l.substringBefore(" | ").split(" "); val tail = l.substringAfter(" | ").split(" ")
        val n = 5; val xs = DoubleArray(n) { head[3 + it].toDouble() }; val ys = DoubleArray(n) { tail[it].toDouble() }
        val s2 = Selection(head[1].toInt(), head[2].toInt()); s2.lasso(SelOp.REPLACE, xs, ys)
        assertArrayEquals("lasso", hex(tail[n]), s2.plane.toBytes())
    }
}

class RasterTest {
    private val rnd = java.util.Random(77)

    @Test fun fastEllipseEqualsTheDefinitionOnManyShapes() {
        repeat(200) {
            val w = 20 + rnd.nextInt(60); val h = 20 + rnd.nextInt(60)
            val x0 = rnd.nextDouble() * w - 5; val y0 = rnd.nextDouble() * h - 5; val x1 = x0 + rnd.nextDouble() * w; val y1 = y0 + rnd.nextDouble() * h
            val r = IRect(0, 0, w, h)
            assertArrayEquals("ellipse $x0 $y0 $x1 $y1", Raster.ellipseReference(r, x0, y0, x1, y1), Raster.ellipse(r, x0, y0, x1, y1))
        }
        // shapes whose edges fall exactly on sample boundaries
        for (v in listOf(doubleArrayOf(2.0, 2.0, 10.0, 10.0), doubleArrayOf(0.0, 0.0, 16.0, 8.0), doubleArrayOf(1.25, 1.5, 9.25, 7.5), doubleArrayOf(3.0, 3.0, 3.5, 3.5)))
            assertArrayEquals(Raster.ellipseReference(IRect(0, 0, 20, 12), v[0], v[1], v[2], v[3]), Raster.ellipse(IRect(0, 0, 20, 12), v[0], v[1], v[2], v[3]))
    }

    @Test fun fastLassoEqualsTheDefinitionOnRandomPolygonsIncludingSelfIntersecting() {
        repeat(200) {
            val n = 3 + rnd.nextInt(9); val w = 30 + rnd.nextInt(40); val h = 30 + rnd.nextInt(40)
            val xs = DoubleArray(n) { rnd.nextDouble() * (w + 10) - 5 }; val ys = DoubleArray(n) { rnd.nextDouble() * (h + 10) - 5 }
            val r = IRect(0, 0, w, h)
            assertArrayEquals("lasso #$it", Raster.lassoReference(r, xs, ys), Raster.lasso(r, xs, ys))
        }
        // vertices on integer and quarter coordinates (crossings land exactly on sample positions)
        val xs = doubleArrayOf(2.0, 12.0, 12.0, 6.25, 2.0); val ys = doubleArrayOf(2.0, 2.0, 9.5, 12.0, 8.0)
        assertArrayEquals(Raster.lassoReference(IRect(0, 0, 16, 16), xs, ys), Raster.lasso(IRect(0, 0, 16, 16), xs, ys))
    }

    @Test fun selectionOpsKeepTheirMeaningWithTheTileWisePlane() {
        // a selection that crosses tile edges: add, subtract, intersect against a brute force model
        val w = 700; val h = 300; val s = Selection(w, h); val model = IntArray(w * h)
        fun m(op: SelOp, x0: Int, y0: Int, x1: Int, y1: Int) { for (y in 0 until h) for (x in 0 until w) { val inside = x in x0 until x1 && y in y0 until y1
            if (op == SelOp.REPLACE) model[y * w + x] = if (inside) 255 else 0 else if (inside || op == SelOp.INTERSECT) model[y * w + x] = ByteAlgebra.combine(op, model[y * w + x], if (inside) 255 else 0) } }
        for ((op, r) in listOf(SelOp.REPLACE to intArrayOf(100, 50, 400, 250), SelOp.ADD to intArrayOf(300, 100, 650, 290), SelOp.SUBTRACT to intArrayOf(200, 0, 350, 150), SelOp.INTERSECT to intArrayOf(120, 60, 600, 280))) {
            s.rect(op, r[0], r[1], r[2], r[3]); m(op, r[0], r[1], r[2], r[3])
            for (y in 0 until h) for (x in 0 until w) if (s.plane[x, y] != model[y * w + x]) fail("$op at $x,$y: ${s.plane[x, y]} vs ${model[y * w + x]}")
        }
        var minx = w; var miny = h; var maxx = 0; var maxy = 0
        for (y in 0 until h) for (x in 0 until w) if (model[y * w + x] != 0) { minx = minOf(minx, x); miny = minOf(miny, y); maxx = maxOf(maxx, x + 1); maxy = maxOf(maxy, y + 1) }
        assertEquals(IRect(minx, miny, maxx, maxy), s.bounds)
        s.invert(); assertEquals(255 - model[5 * w + 5], s.plane[5, 5]); assertEquals(IRect(0, 0, w, h), s.bounds)
    }

    @Test fun recordingGivesOneDeltaPerChangedTileAndUndoRestoresIt() {
        val p = TilePlane(600, 300); p[10, 10] = 9
        p.beginRecord(); p[10, 10] = 50; p[300, 20] = 7; p[11, 11] = 0; val d = p.endRecord()
        assertEquals(2, d.size)   // tile (0,0) changed, tile (1,0) changed; writing 0 over 0 left nothing else
        for (x in d) p.writeTile(x.key, x.before)
        assertEquals(9, p[10, 10]); assertEquals(0, p[300, 20])
        for (x in d) p.writeTile(x.key, x.after)
        assertEquals(50, p[10, 10]); assertEquals(7, p[300, 20])
        p.beginRecord(); p[10, 10] = 50; assertTrue(p.endRecord().isEmpty())   // the same value again is no change
        val dirty = p.takeDirty(); assertTrue(dirty.isNotEmpty())
    }

    @Test fun toBytesAndNonZeroBoundsOnATiledPlane() {
        val p = TilePlane(300, 280); p[299, 279] = 3; p[256, 5] = 4
        val b = p.toBytes(); assertEquals(3, b[279 * 300 + 299].toInt()); assertEquals(4, b[5 * 300 + 256].toInt()); assertEquals(0, b[0].toInt())
        assertEquals(IRect(256, 5, 300, 280), p.nonZeroBounds()); assertEquals(IRect(0, 0, 0, 0), TilePlane(10, 10).nonZeroBounds())
    }
}
