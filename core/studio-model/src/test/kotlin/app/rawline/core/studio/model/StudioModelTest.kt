package app.rawline.core.studio.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BlendTest {
    private fun px(r: Float, g: Float, b: Float, a: Float) = Rgba(r, g, b, a)
    private fun near(exp: Float, got: Float, what: String) = assertEquals(what, exp, got, 1e-6f)

    @Test fun opaqueNormalReplacesTheBackdrop() {
        val r = Blend.over(px(0.2f, 0.4f, 0.6f, 1f), px(0.8f, 0.5f, 0.1f, 1f), BlendMode.NORMAL)
        near(0.8f, r.r, "r"); near(0.5f, r.g, "g"); near(0.1f, r.b, "b"); near(1f, r.a, "a")
    }

    @Test fun multiplyWithWhiteIsTheBackdrop() {
        val r = Blend.over(px(0.2f, 0.4f, 0.6f, 1f), px(1f, 1f, 1f, 1f), BlendMode.MULTIPLY)
        near(0.2f, r.r, "r"); near(0.4f, r.g, "g"); near(0.6f, r.b, "b")
    }

    @Test fun screenWithBlackIsTheBackdrop() {
        val r = Blend.over(px(0.2f, 0.4f, 0.6f, 1f), px(0f, 0f, 0f, 1f), BlendMode.SCREEN)
        near(0.2f, r.r, "r"); near(0.4f, r.g, "g"); near(0.6f, r.b, "b")
    }

    @Test fun handComputedPartialAlphaOverOpaque() {
        // backdrop (0.2, 0.4, 0.6) opaque, source (0.8, 0.5, 0.1) at alpha 0.5
        val n = Blend.over(px(0.2f, 0.4f, 0.6f, 1f), px(0.8f, 0.5f, 0.1f, 0.5f), BlendMode.NORMAL)
        near(0.5f, n.r, "normal r"); near(0.45f, n.g, "normal g"); near(0.35f, n.b, "normal b")
        val m = Blend.over(px(0.2f, 0.4f, 0.6f, 1f), px(0.8f, 0.5f, 0.1f, 0.5f), BlendMode.MULTIPLY)   // 0.5 * Cb + 0.5 * Cb * Cs
        near(0.18f, m.r, "multiply r"); near(0.3f, m.g, "multiply g"); near(0.33f, m.b, "multiply b")
        val s = Blend.over(px(0.2f, 0.4f, 0.6f, 1f), px(0.8f, 0.5f, 0.1f, 0.5f), BlendMode.SCREEN)     // 0.5 * Cb + 0.5 * (Cb + Cs - Cb Cs)
        near(0.52f, s.r, "screen r"); near(0.55f, s.g, "screen g"); near(0.62f, s.b, "screen b")
    }

    @Test fun overATransparentBackdropTakesTheSourceColourWhateverTheMode() {
        for (mode in BlendMode.entries) {
            val r = Blend.over(Rgba.CLEAR, px(0.8f, 0.5f, 0.1f, 0.5f), mode)
            near(0.8f, r.r, "$mode r"); near(0.5f, r.g, "$mode g"); near(0.1f, r.b, "$mode b"); near(0.5f, r.a, "$mode a")
        }
    }

    @Test fun nothingOverNothingStaysNothing() {
        val r = Blend.over(Rgba.CLEAR, px(0.8f, 0.5f, 0.1f, 0f), BlendMode.NORMAL)
        assertEquals(0f, r.a, 0f); assertEquals(0f, r.r, 0f)
    }

    @Test fun opacityMultipliesSourceAlpha() {
        val a = Blend.over(px(0.2f, 0.4f, 0.6f, 1f), px(0.8f, 0.5f, 0.1f, 1f), BlendMode.NORMAL, opacity = 0.5f)
        val b = Blend.over(px(0.2f, 0.4f, 0.6f, 1f), px(0.8f, 0.5f, 0.1f, 0.5f), BlendMode.NORMAL)
        near(b.r, a.r, "r"); near(b.g, a.g, "g"); near(b.b, a.b, "b")
    }

    @Test fun stackedNormalLayersAreAssociative() {
        val l1 = px(0.9f, 0.1f, 0.1f, 0.5f); val l2 = px(0.1f, 0.9f, 0.1f, 0.4f); val l3 = px(0.1f, 0.1f, 0.9f, 0.7f)
        val base = px(0.3f, 0.3f, 0.3f, 1f)
        val left = Blend.over(Blend.over(Blend.over(base, l1, BlendMode.NORMAL), l2, BlendMode.NORMAL), l3, BlendMode.NORMAL)
        // composite the top two layers first, then put the result over the lower ones
        val top = Blend.over(Blend.over(Rgba.CLEAR, l2, BlendMode.NORMAL), l3, BlendMode.NORMAL)
        val right = Blend.over(Blend.over(base, l1, BlendMode.NORMAL), top, BlendMode.NORMAL)
        near(left.r, right.r, "r"); near(left.g, right.g, "g"); near(left.b, right.b, "b"); near(left.a, right.a, "a")
    }

    /** blend_vectors.tsv is written by the independent Python reference (tools/studio/studio_ref.py vectors). */
    @Test fun matchesTheIndependentReferenceOnEveryVector() {
        val text = javaClass.getResourceAsStream("/blend_vectors.tsv")!!.bufferedReader().readText()
        var n = 0
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (lhs, rhs) = line.split(" -> ")
            val v = lhs.trim().split(" ").map { it.toFloat() }
            val e = rhs.trim().split(" ").map { it.toFloat() }
            val r = Blend.over(Rgba(v[0], v[1], v[2], v[3]), Rgba(v[4], v[5], v[6], v[7]), BlendMode.entries.first { it.id == v[8].toInt() }, opacity = v[9])
            assertEquals("r in: $line", e[0], r.r, 2e-6f); assertEquals("g in: $line", e[1], r.g, 2e-6f)
            assertEquals("b in: $line", e[2], r.b, 2e-6f); assertEquals("a in: $line", e[3], r.a, 2e-6f)
            n++
        }
        assertTrue("vectors read: $n", n >= 300)
    }
}

class ReferenceCompositorTest {
    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int) = RefImage(w, h, ByteArray(w * h * 4) { when (it % 4) { 0 -> r; 1 -> g; 2 -> b; else -> a }.toByte() })
    private fun px(out: ByteArray, i: Int) = (0..3).map { out[i * 4 + it].toInt() and 255 }

    @Test fun multiplyAtHalfOpacityOverOpaqueRed() {
        val layers = listOf(
            RefLayer(solid(2, 1, 255, 0, 0, 255), 0f, 0f, 1f, 1f, BlendMode.NORMAL),
            RefLayer(solid(2, 1, 0, 255, 0, 255), 0f, 0f, 1f, 0.5f, BlendMode.MULTIPLY),
        )
        val out = ReferenceCompositor.render(layers, 0f, 0f, 1f, 2, 1)
        assertEquals(listOf(128, 0, 0, 255), px(out, 0))   // 0.5 * red + 0.5 * (red x green = 0)
    }

    @Test fun offsetLayerOnlyCoversItsOwnPixel() {
        val layers = listOf(
            RefLayer(solid(2, 1, 10, 20, 30, 255), 0f, 0f, 1f, 1f, BlendMode.NORMAL),
            RefLayer(solid(1, 1, 200, 100, 50, 255), 1f, 0f, 1f, 1f, BlendMode.NORMAL),
        )
        val out = ReferenceCompositor.render(layers, 0f, 0f, 1f, 2, 1)
        assertEquals(listOf(10, 20, 30, 255), px(out, 0)); assertEquals(listOf(200, 100, 50, 255), px(out, 1))
    }

    @Test fun scaleTwoCoversTwoByTwoAndKeepsColourAtTheEdge() {
        val out = ReferenceCompositor.render(listOf(RefLayer(solid(1, 1, 200, 100, 50, 255), 0f, 0f, 2f, 1f, BlendMode.NORMAL)), 0f, 0f, 1f, 3, 2)
        for (i in 0..1) assertEquals("pixel $i", listOf(200, 100, 50, 255), px(out, i))
        assertEquals(listOf(0, 0, 0, 0), px(out, 2))   // outside the layer: transparent
    }

    @Test fun transparentPixelsDoNotBleedColourIntoTheirNeighboursWhenScaled() {
        // left texel opaque red, right texel fully transparent with garbage (green) colour: the straight colour of every covered pixel stays red
        val img = RefImage(2, 1, byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 0))
        val out = ReferenceCompositor.render(listOf(RefLayer(img, 0f, 0f, 2f, 1f, BlendMode.NORMAL)), 0f, 0f, 1f, 4, 1)
        for (i in 0..2) { val p = px(out, i); assertEquals("red at $i", 255, p[0]); assertEquals("no green at $i", 0, p[1]) }
        assertTrue(px(out, 1)[3] > px(out, 2)[3] && px(out, 2)[3] > 0)   // alpha falls off to the right: 191 then 64
        assertEquals(0, px(out, 3)[3])                                     // beyond the last opaque texel's reach
    }

    @Test fun viewOffsetAndZoomMapOutputPixelsToDocumentPixels() {
        val img = RefImage(2, 1, byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 0, 255.toByte(), 255.toByte()))   // red | blue
        val l = listOf(RefLayer(img, 0f, 0f, 1f, 1f, BlendMode.NORMAL))
        assertEquals(listOf(0, 0, 255, 255), px(ReferenceCompositor.render(l, 1f, 0f, 1f, 1, 1), 0))        // the view starts at document x = 1: the blue texel
        val zoomed = ReferenceCompositor.render(l, 0f, 0f, 2f, 2, 1)                                          // 2 screen pixels per document pixel
        assertEquals(listOf(255, 0, 0, 255), px(zoomed, 0))      // document x 0.25: left of the first texel centre, clamped red
        assertEquals(listOf(191, 0, 64, 255), px(zoomed, 1))     // document x 0.75: three quarters red, one quarter blue (bilinear)
    }

    @Test fun hiddenLayersAndLayersWithoutPixelsAreSkipped() {
        val a = LayerCommon("a", "A"); val b = LayerCommon("b", "B", visible = false); val c = LayerCommon("c", "C")
        val doc = Document("d", "D", 2, 1, layers = listOf(Layer.Pixel(a, 2, 1, "x"), Layer.Pixel(b, 2, 1, "y"), Layer.Pixel(c, 2, 1, null)))
        val got = ReferenceCompositor.layersOf(doc) { p -> if (p.pixelsFile == null) null else solid(2, 1, 1, 2, 3, 255) }
        assertEquals(1, got.size)
    }
}

class LayerOpsTest {
    private fun layer(id: String) = Layer.Pixel(LayerCommon(id, id), 100, 100)
    private fun doc(vararg ids: String) = Document("d", "D", 100, 100, layers = ids.map(::layer))

    @Test fun addAtAndDelete() {
        var d = doc("a", "b")
        d = LayerOps.add(d, layer("c"), at = 1)
        assertEquals(listOf("a", "c", "b"), d.layers.map { it.common.id })
        d = LayerOps.delete(d, "c")
        assertEquals(listOf("a", "b"), d.layers.map { it.common.id })
    }

    @Test fun theLastLayerCannotBeDeleted() {
        try { LayerOps.delete(doc("a"), "a"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("at least one")) }
    }

    @Test fun tenLayersIsTheS1Cap() {
        var d = doc("l0")
        for (i in 1 until Document.MAX_LAYERS_S1) d = LayerOps.add(d, layer("l$i"))
        try { LayerOps.add(d, layer("extra")); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("10 layers")) }
    }

    @Test fun duplicateSitsAboveTheOriginalAndKeepsItsSettings() {
        var d = doc("a", "b")
        d = LayerOps.setOpacity(d, "a", 40); d = LayerOps.setBlend(d, "a", BlendMode.SCREEN)
        d = LayerOps.duplicate(d, "a", "a2")
        assertEquals(listOf("a", "a2", "b"), d.layers.map { it.common.id })
        val c = d.layer("a2")!!.common
        assertEquals("a copy", c.name); assertEquals(40, c.opacity); assertEquals(BlendMode.SCREEN, c.blend)
    }

    @Test fun moveReordersAndClamps() {
        var d = doc("a", "b", "c")
        d = LayerOps.move(d, "a", 2); assertEquals(listOf("b", "c", "a"), d.layers.map { it.common.id })
        d = LayerOps.move(d, "a", -5); assertEquals(listOf("a", "b", "c"), d.layers.map { it.common.id })
    }

    @Test fun settingsAreClamped() {
        var d = doc("a")
        d = LayerOps.setOpacity(d, "a", 140); assertEquals(100, d.layer("a")!!.common.opacity)
        d = LayerOps.setPlacement(d, "a", 5, 6, 9f); assertEquals(4f, d.layer("a")!!.common.scale, 0f)
        d = LayerOps.rename(d, "a", "   "); assertEquals("a", d.layer("a")!!.common.name)
    }

    @Test fun canvasOverTwelveMegapixelsIsRefused() {
        try { Document("d", "D", 4000, 3001); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("cap")) }
        Document("d", "D", 4000, 3000)   // exactly 12 MP is fine
    }

    @Test fun undoAndRedoWalkTheStates() {
        val h = LayerHistory(doc("a"))
        h.commit(LayerOps.add(h.current, layer("b")))
        h.commit(LayerOps.setOpacity(h.current, "b", 30))
        assertEquals(30, h.current.layer("b")!!.common.opacity)
        assertEquals(100, h.undo()!!.layer("b")!!.common.opacity)
        assertNull(h.undo()!!.layer("b"))
        assertFalse(h.canUndo); assertNull(h.undo())
        assertNotNull(h.redo()); assertNotNull(h.redo()); assertNull(h.redo())
        assertEquals(30, h.current.layer("b")!!.common.opacity)
    }

    @Test fun anIdenticalStateIsNotRecordedAndANewActionClearsRedo() {
        val h = LayerHistory(doc("a"))
        h.commit(h.current); assertFalse(h.canUndo)
        h.commit(LayerOps.add(h.current, layer("b"))); h.undo(); assertTrue(h.canRedo)
        h.commit(LayerOps.add(h.current, layer("c"))); assertFalse(h.canRedo)
    }

    @Test fun historyIsBounded() {
        val h = LayerHistory(doc("a"), limit = 3)
        for (i in 1..10) h.commit(LayerOps.setOpacity(h.current, "a", i))
        var n = 0; while (h.undo() != null) n++
        assertEquals(3, n)
    }
}

class TilesTest {
    @Test fun counts() { assertEquals(4, Tiles.countX(1000)); assertEquals(4, Tiles.countX(1024)); assertEquals(5, Tiles.countX(1025)); assertEquals(1, Tiles.countY(1)) }
    @Test fun edgeTileIsClipped() { assertArrayEquals(intArrayOf(768, 0, 232, 256), Tiles.rect(3, 0, 1000, 600)) }
    @Test fun coveringRectangleAcrossATileBoundary() {
        // a 20 px square straddling x = 256 and y = 256 touches four tiles of a 1000 x 600 layer (4 tiles wide)
        assertEquals(listOf(0, 1, 4, 5), Tiles.covering(246, 246, 20, 20, 1000, 600))
        assertEquals(emptyList<Int>(), Tiles.covering(2000, 0, 10, 10, 1000, 600))
        assertEquals(listOf(0), Tiles.covering(-10, -10, 20, 20, 1000, 600))
    }
}

class ProjectJsonTest {
    private val sample get() = javaClass.getResourceAsStream("/sample_v1.json")!!.bufferedReader().readText()

    @Test fun readsTheStoredVersionOneSampleAndIgnoresUnknownKeys() {
        val d = ProjectJson.read(sample)
        assertEquals("Sample \"quoted\" name", d.name)
        assertEquals(ColourSpace.DISPLAY_P3, d.colourSpace); assertEquals(2, d.layers.size)
        val paint = d.layers[1] as Layer.Pixel
        assertEquals(65, paint.common.opacity); assertEquals(BlendMode.MULTIPLY, paint.common.blend); assertFalse(paint.common.visible)
        assertEquals(-20, paint.common.x); assertEquals(1.5f, paint.common.scale, 0f); assertNull(paint.pixelsFile)
        assertEquals("layers/bg.webp", (d.layers[0] as Layer.Pixel).pixelsFile)
    }

    @Test fun roundTripIsExactAndStable() {
        val d = ProjectJson.read(sample)
        val text = ProjectJson.write(d, "0.1.101")
        assertEquals(d, ProjectJson.read(text))
        assertEquals(text, ProjectJson.write(ProjectJson.read(text), "0.1.101"))   // writing what was read changes nothing
    }

    @Test fun anEmptyDocumentRoundTrips() {
        val d = Document("e", "Empty é中\n", 640, 480)
        assertEquals(d, ProjectJson.read(ProjectJson.write(d, "x")))
    }

    @Test fun aNewerSchemaIsRefusedWithItsVersion() {
        val newer = sample.replace("\"schemaVersion\": 1", "\"schemaVersion\": 7")
        try { ProjectJson.read(newer); fail() } catch (e: NewerSchemaException) { assertEquals(7, e.version) }
    }

    @Test fun damagedFilesAreReportedNotCrashed() {
        for (bad in listOf("", "{", "[]", sample.take(sample.length / 2), sample.replace("\"schemaVersion\": 1,", ""),
            sample.replace("\"multiply\"", "\"dissolve\""), sample.replace("\"pixel\"", "\"text\""), sample.replace("\"width\": 1200", "\"width\": 0"))) {
            try { ProjectJson.read(bad); fail("accepted: ${bad.take(40)}") } catch (e: ProjectFormatException) { assertTrue(e.message!!.isNotEmpty()) }
        }
    }

    @Test fun duplicateLayerIdsAreRejected() {
        val dup = sample.replace("\"id\": \"paint\"", "\"id\": \"bg\"")
        try { ProjectJson.read(dup); fail() } catch (e: ProjectFormatException) { assertTrue(e.message!!.contains("duplicate")) }
    }
}

/** scene_small.txt is written by the independent Python reference (tools/studio/studio_scene.py small): layers as hex, then the expected straight RGBA8. */
class ReferenceMatchesPythonSceneTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun kotlinReferenceEqualsPythonReferenceWithinOneLevel() {
        val lines = javaClass.getResourceAsStream("/scene_small.txt")!!.bufferedReader().readLines()
        val v = lines[0].split(" ").drop(1)
        val vx = v[0].toFloat(); val vy = v[1].toFloat(); val zoom = v[2].toFloat(); val ow = v[3].toInt(); val oh = v[4].toInt()
        val layers = lines.filter { it.startsWith("layer ") }.map { l ->
            val t = l.split(" ")
            RefLayer(RefImage(t[1].toInt(), t[2].toInt(), hex(t[8])), t[3].toFloat(), t[4].toFloat(), t[5].toFloat(), t[6].toFloat(), BlendMode.entries.first { it.id == t[7].toInt() })
        }
        val expected = hex(lines.first { it.startsWith("expected ") }.substringAfter(" "))
        val got = ReferenceCompositor.render(layers, vx, vy, zoom, ow, oh)
        assertEquals(expected.size, got.size)
        var worst = 0
        for (i in got.indices) worst = maxOf(worst, Math.abs((got[i].toInt() and 255) - (expected[i].toInt() and 255)))
        assertTrue("worst difference $worst levels", worst <= 1)
    }
}
