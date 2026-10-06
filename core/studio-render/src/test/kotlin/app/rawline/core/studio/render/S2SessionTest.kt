package app.rawline.core.studio.render

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.IRect
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.LayerOps
import app.rawline.core.studio.model.ProjectStore
import app.rawline.core.studio.model.RawPixels
import app.rawline.core.studio.model.SelOp
import app.rawline.core.studio.model.Phase as InPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun doc2(w: Int, h: Int, vararg ids: String): Document =
    Document("p1", "Test", w, h, layers = (if (ids.isEmpty()) listOf("a") else ids.toList()).map { Layer.Pixel(LayerCommon(it, it), w, h) }, created = 1, modified = 1)

private fun white2(w: Int, h: Int) = RawPixels(w, h, ByteArray(w * h * 4) { 255.toByte() })

/** S2 in the session: masks (add, paint, invert, disable, delete, duplicate), selections (three tools, four modes), the clip, undo and redo, saving and reopening. */
class S2SessionTest {
    private val w = 300; private val h = 200
    private fun harness(vararg ids: String): Harness {
        val pix = (if (ids.isEmpty()) listOf("a") else ids.toList()).associateWith { white2(w, h) }
        return Harness(doc = doc2(w, h, *ids), pixels = pix).also { it.s.start() }
    }
    private fun Harness.drag(tool: Tool, vararg pts: Float) {
        s.setTool(tool)
        ev(InPhase.DOWN, 0, pts[0], pts[1])
        var i = 2; while (i + 1 < pts.size) { ev(InPhase.MOVE, 0, pts[i], pts[i + 1]); i += 2 }
        ev(InPhase.UP, 0, pts[pts.size - 2], pts[pts.size - 1])
    }
    /** The compositor slot of a layer: the frame lists the (visible) layers bottom to top with their slot first. All layers are visible in these tests. */
    private fun Harness.slot(id: String) = gl.last!!.layers[st.document.indexOf(id) * 7].toInt()
    private fun Harness.reopened(): Harness = Harness(fs = fs, doc = ProjectStore(fs, Harness.ROOT).open().document, onDisk = true).also { it.s.start() }
    private fun Harness.save() { now += 10_000; s.flush() }
    private fun Harness.gpuMask(id: String) = gl.gpu.masks[slot(id)]

    @Test fun addMaskUploadsItAndIsOneUndoStep() {
        val x = harness()
        x.s.addMask("a", MaskFill.WHITE)
        assertNotNull(x.st.document.layer("a")!!.common.mask)
        assertTrue(x.gpuMask("a")!!.all { it == 255.toByte() }); assertEquals(1f, x.gl.last!!.layers[6], 0f)
        assertNotNull(x.st.maskThumbs["a"])
        x.s.undo(); assertNull(x.st.document.layer("a")!!.common.mask); assertNull(x.gpuMask("a")); assertEquals(0f, x.gl.last!!.layers[6], 0f)
        x.s.redo(); assertNotNull(x.gpuMask("a")); assertEquals(1f, x.gl.last!!.layers[6], 0f)
        x.s.addMask("a", MaskFill.BLACK); assertEquals("a layer has one mask: the second add is refused with a message", true, x.st.message != null)
    }

    @Test fun maskFlagsEnabledAndInvertedChangeTheFrameNotTheTiles() {
        val x = harness(); x.s.addMask("a", MaskFill.WHITE)
        val bytes = x.gpuMask("a")!!.copyOf()
        x.s.invertMask("a"); assertEquals(2f, x.gl.last!!.layers[6], 0f); assertArrayEquals(bytes, x.gpuMask("a"))
        x.s.setMaskEnabled("a", false); assertEquals(0f, x.gl.last!!.layers[6], 0f)
        x.s.setMaskEnabled("a", true); x.s.undo(); x.s.undo(); x.s.undo(); x.s.undo()   // enable, disable, invert, add: four steps
        assertNull(x.st.document.layer("a")!!.common.mask)
        x.s.redo(); x.s.redo(); x.s.redo(); x.s.redo()
        assertEquals(true, x.st.document.layer("a")!!.common.mask!!.inverted)
    }

    @Test fun paintingTheMaskLeavesThePixelsAloneAndUndoRestoresTheMaskExactly() {
        val x = harness(); x.s.addMask("a", MaskFill.WHITE)
        val px0 = x.gl.gpu.tex[0]!!.rgba.copyOf(); val m0 = x.gpuMask("a")!!.copyOf()
        x.s.setPaintMask(true); x.s.setBrush(Brush(diameter = 40.0, hardness = 1.0, opacity = 1.0, flow = 1.0, pressureSize = false)); x.s.setColour(Rgb(0f, 0f, 0f))
        x.stroke(listOf(20f to 30f, 280f to 170f))
        val m1 = x.gpuMask("a")!!.copyOf()
        assertFalse(m0.contentEquals(m1)); assertArrayEquals(px0, x.gl.gpu.tex[0]!!.rgba)
        assertEquals(0, m1[(30 + (170 - 30) * (100 - 20) / (280 - 20)) * w + 100].toInt() and 255)   // on the path: fully hidden
        assertEquals(255, m1[190 * w + 10].toInt() and 255)                                             // far from it: untouched
        x.s.undo(); assertArrayEquals(m0, x.gpuMask("a")); x.s.redo(); assertArrayEquals(m1, x.gpuMask("a"))
    }

    @Test fun maskAndPixelStrokesAreSeparateStepsAndTargets() {
        val x = harness(); x.s.addMask("a", MaskFill.WHITE)
        x.s.setBrush(Brush(diameter = 30.0, hardness = 1.0, pressureSize = false)); x.s.setColour(Rgb(1f, 0f, 0f))
        x.s.setPaintMask(false); x.stroke(listOf(50f to 50f, 100f to 50f))
        assertFalse(x.gl.gpu.tex[0]!!.rgba.all { it == 255.toByte() }); assertTrue(x.gpuMask("a")!!.all { it == 255.toByte() })
        val px = x.gl.gpu.tex[0]!!.rgba.copyOf()
        x.s.setPaintMask(true); x.s.setColour(Rgb(0f, 0f, 0f)); x.stroke(listOf(50f to 120f, 100f to 120f))
        assertArrayEquals(px, x.gl.gpu.tex[0]!!.rgba); assertFalse(x.gpuMask("a")!!.all { it == 255.toByte() })
        x.s.undo(); assertTrue(x.gpuMask("a")!!.all { it == 255.toByte() }); assertArrayEquals(px, x.gl.gpu.tex[0]!!.rgba)
        x.s.undo(); assertTrue(x.gl.gpu.tex[0]!!.rgba.all { it == 255.toByte() })
    }

    @Test fun anInvertedMaskStillHidesWhereYouPaintBlack() {
        val x = harness(); x.s.addMask("a", MaskFill.WHITE); x.s.invertMask("a")
        x.s.setPaintMask(true); x.s.setBrush(Brush(diameter = 40.0, hardness = 1.0, pressureSize = false)); x.s.setColour(Rgb(0f, 0f, 0f))
        x.stroke(listOf(100f to 100f, 101f to 100f))
        // black on an inverted mask moves the stored value toward white, so what you see (inverted) is hidden there
        assertEquals(255, x.gpuMask("a")!![100 * w + 100].toInt() and 255)
        // and a pixel nobody painted stays at the stored 255, which an inverted mask shows as hidden: the whole layer is hidden around the stroke
        assertEquals(2f, x.gl.last!!.layers[6], 0f)
    }

    @Test fun tryingToPaintAMaskThatIsOffExplainsAndDoesNothing() {
        val x = harness(); x.s.addMask("a", MaskFill.WHITE); x.s.setMaskEnabled("a", false)
        x.s.setPaintMask(true); x.s.setBrush(Brush(diameter = 40.0, pressureSize = false)); x.stroke(listOf(10f to 10f, 90f to 90f))
        assertEquals("Turn the mask on to paint it.", x.st.message!!.text); assertTrue(x.gpuMask("a")!!.all { it == 255.toByte() }); assertFalse(x.st.canUndo && x.st.document.layer("a")!!.common.mask!!.enabled)
    }

    @Test fun maskTilesAreSavedAndComeBackOnReopen() {
        val x = harness(); x.s.addMask("a", MaskFill.WHITE)
        x.s.setPaintMask(true); x.s.setBrush(Brush(diameter = 50.0, hardness = 0.5, pressureSize = false)); x.s.setColour(Rgb(0.2f, 0.2f, 0.2f))
        x.stroke(listOf(30f to 40f, 270f to 160f))
        x.save()
        val onGpu = x.gpuMask("a")!!.copyOf()
        val (doc, store) = x.reopen()
        assertEquals("mask/a", doc.layer("a")!!.common.mask!!.dir)
        assertArrayEquals(onGpu, store.loadPlane("mask/a", w, h).toBytes())
        val y = x.reopened()
        assertArrayEquals(onGpu, y.gl.gpu.masks.values.single())
        assertEquals(1f, y.gl.last!!.layers[6], 0f)
        x.s.deleteMask("a"); x.save()
        assertTrue("the generation before still names the mask, so its tiles stay", x.fs.files.keys.any { it.contains("/mask/") })
        x.s.setVisible("a", false); x.save(); x.s.setVisible("a", true); x.save()
        assertTrue("a deleted mask's tiles are collected once no generation names them", x.fs.files.keys.none { it.contains("/mask/") })
    }

    @Test fun deletingALayerKeepsItsMaskForUndoAndDuplicateGetsItsOwn() {
        val x = harness("a", "b"); x.s.selectLayer("a"); x.s.addMask("a", MaskFill.BLACK)
        x.s.setPaintMask(true); x.s.setBrush(Brush(diameter = 30.0, pressureSize = false)); x.s.setColour(Rgb(1f, 1f, 1f)); x.stroke(listOf(60f to 60f, 120f to 60f))
        val m = x.gpuMask("a")!!.copyOf(); assertTrue(m.any { it != 0.toByte() })
        x.s.duplicateLayer("a")
        val copyId = x.st.document.layers.first { it.common.name == "a copy" }.common.id
        assertEquals("mask/$copyId", x.st.document.layer(copyId)!!.common.mask!!.dir); assertArrayEquals(m, x.gpuMask(copyId))
        x.s.setPaintMask(true); x.s.setColour(Rgb(0f, 0f, 0f)); x.stroke(listOf(60f to 60f, 120f to 60f))   // painting the copy leaves the original alone
        assertArrayEquals(m, x.gpuMask("a")); assertFalse(m.contentEquals(x.gpuMask(copyId)!!))
        x.s.deleteLayer("a"); assertNull(x.st.document.layer("a")); x.s.undo()
        assertArrayEquals(m, x.gpuMask("a"))
        x.save(); val (_, store) = x.reopen(); assertArrayEquals(m, store.loadPlane("mask/a", w, h).toBytes())
    }

    @Test fun rectangleSelectionAndItsUndo() {
        val x = harness()
        x.drag(Tool.RECT_SELECT, 50f, 40f, 180f, 120f)
        val sv = x.st.selection!!; assertEquals(IRect(50, 40, 180, 120), sv.bounds); assertEquals(16, sv.contour.size)   // an exact outline: 4 lines of 4 numbers
        assertEquals(255, x.gl.gpu.selectionBytes!![50 * w + 60].toInt() and 255); assertEquals(0, x.gl.gpu.selectionBytes!![10 * w + 10].toInt() and 255)
        x.s.deselect(); assertNull(x.st.selection); assertNull(x.gl.gpu.selectionBytes)
        x.s.undo(); assertEquals(IRect(50, 40, 180, 120), x.st.selection!!.bounds); assertNotNull(x.gl.gpu.selectionBytes)
        x.s.undo(); assertNull(x.st.selection); x.s.redo(); assertNotNull(x.st.selection)
    }

    @Test fun fourModesCombineAndEveryStepUndoesToTheExactPreviousBytes() {
        val x = harness()
        val states = ArrayList<ByteArray?>()
        fun snap() = states.add(x.gl.gpu.selectionBytes?.copyOf())
        snap()
        x.s.setSelectionOp(SelOp.REPLACE); x.drag(Tool.RECT_SELECT, 20f, 20f, 160f, 120f); snap()
        x.s.setSelectionOp(SelOp.ADD); x.drag(Tool.ELLIPSE_SELECT, 120f, 60f, 280f, 190f); snap()
        x.s.setSelectionOp(SelOp.SUBTRACT); x.drag(Tool.LASSO_SELECT, 60f, 10f, 140f, 20f, 150f, 100f, 70f, 90f); snap()
        x.s.setSelectionOp(SelOp.INTERSECT); x.drag(Tool.RECT_SELECT, 30f, 30f, 250f, 150f); snap()
        x.s.invertSelection(); snap()
        for (i in states.size - 1 downTo 1) { x.s.undo(); assertArrayEquals("undo to state ${i - 1}", states[i - 1], x.gl.gpu.selectionBytes) }
        for (i in 1 until states.size) { x.s.redo(); assertArrayEquals("redo to state $i", states[i], x.gl.gpu.selectionBytes) }
        // the model's plane agrees with the GPU copy after all that
        x.save(); val (_, store) = x.reopen(); assertArrayEquals(states.last(), store.loadPlane("sel", w, h).toBytes())
    }

    @Test fun aTapWithASelectionToolDeselectsAndALassoOfTwoPointsSelectsNothing() {
        val x = harness(); x.drag(Tool.RECT_SELECT, 10f, 10f, 100f, 100f); assertNotNull(x.st.selection)
        x.drag(Tool.RECT_SELECT, 50f, 50f, 50f, 50f); assertNull(x.st.selection)
        x.drag(Tool.RECT_SELECT, 10f, 10f, 100f, 100f); x.drag(Tool.LASSO_SELECT, 20f, 20f, 40f, 40f); assertNull(x.st.selection)
    }

    @Test fun theSelectionClipsPixelStrokesAndMaskStrokesAndTheClipIsExact() {
        val x = harness()
        x.drag(Tool.ELLIPSE_SELECT, 60f, 50f, 240f, 150f)
        val sel = x.gl.gpu.selectionBytes!!.copyOf()
        x.s.setBrush(Brush(diameter = 60.0, hardness = 1.0, opacity = 1.0, flow = 1.0, pressureSize = false)); x.s.setColour(Rgb(1f, 0f, 0f)); x.s.setTool(Tool.BRUSH)
        x.stroke(listOf(10f to 100f, 290f to 100f))
        val px = x.gl.gpu.tex[0]!!.rgba
        fun red(xx: Int, yy: Int) = (px[(yy * w + xx) * 4 + 1].toInt() and 255)   // green channel: 255 white, 0 fully red paint
        assertEquals("outside the ellipse is untouched", 255, red(20, 100)); assertEquals("inside is painted", 0, red(150, 100))
        for (yy in 0 until h) for (xx in 0 until w) if ((sel[yy * w + xx].toInt() and 255) == 0) assertEquals("pixel $xx,$yy outside the selection changed", 255, red(xx, yy))
        // partial selection pixels give partial paint (the ellipse edge)
        var partial = 0; for (yy in 76 until 124) for (xx in 0 until w) { val v = sel[yy * w + xx].toInt() and 255; if (v in 1..254) { partial++; val g = red(xx, yy); assertTrue("edge pixel $xx,$yy: sel $v green $g", g in 1..254) } }
        assertTrue(partial > 20)
        // a mask stroke is clipped the same way
        x.s.addMask("a", MaskFill.WHITE); x.s.setPaintMask(true); x.s.setColour(Rgb(0f, 0f, 0f)); x.stroke(listOf(10f to 60f, 290f to 60f))
        val m = x.gpuMask("a")!!
        assertEquals(255, m[60 * w + 20].toInt() and 255); assertEquals(0, m[60 * w + 150].toInt() and 255)   // (150, 60) is inside the ellipse, (20, 60) is outside it
        for (yy in 0 until h) for (xx in 0 until w) if ((sel[yy * w + xx].toInt() and 255) == 0) assertEquals(255, m[yy * w + xx].toInt() and 255)
    }

    @Test fun aSelectionOfNothingClipsNothing() {
        val x = harness(); x.s.selectAll(); x.s.deselect()
        x.s.setBrush(Brush(diameter = 30.0, hardness = 1.0, pressureSize = false)); x.s.setColour(Rgb(0f, 0f, 1f)); x.s.setTool(Tool.BRUSH)
        x.stroke(listOf(30f to 30f, 200f to 30f)); assertEquals(0, x.gl.gpu.tex[0]!!.rgba[(30 * w + 100) * 4 + 1].toInt() and 255)
    }

    @Test fun theSelectionIsSavedAndRestoredAndDeselectingRemovesItsFiles() {
        val x = harness(); x.drag(Tool.ELLIPSE_SELECT, 30f, 30f, 200f, 170f)
        val bytes = x.gl.gpu.selectionBytes!!.copyOf(); val bounds = x.st.selection!!.bounds
        x.save()
        assertNotNull(x.reopen().first.selection)
        val y = x.reopened()
        assertEquals(bounds, y.st.selection!!.bounds); assertArrayEquals(bytes, y.gl.gpu.selectionBytes)
        x.s.deselect(); x.save(); x.s.setVisible("a", false); x.save(); x.s.setVisible("a", true); x.save()
        assertNull(x.reopen().first.selection); assertTrue(x.fs.files.keys.none { it.contains("/sel/") })
    }

    @Test fun maskFromSelectionAndFromNoSelection() {
        val x = harness(); x.s.addMask("a", MaskFill.FROM_SELECTION)    // no selection: the whole layer shows
        assertTrue(x.gpuMask("a")!!.all { it == 255.toByte() })
        x.s.deleteMask("a"); x.drag(Tool.RECT_SELECT, 100f, 50f, 200f, 150f)
        x.s.addMask("a", MaskFill.FROM_SELECTION)
        val m = x.gpuMask("a")!!
        assertEquals(255, m[100 * w + 150].toInt() and 255); assertEquals(0, m[10 * w + 10].toInt() and 255); assertEquals(0, m[100 * w + 200].toInt() and 255)
        assertEquals(100 * 100, m.count { it == 255.toByte() })
    }

    @Test fun framesAndExportsCarryTheMask() {
        val x = harness(); x.s.addMask("a", MaskFill.BLACK)
        val snap = x.s.exportSnapshot()!!; assertEquals(1f, snap.layers[6], 0f)
        val strip = x.s.renderStrip(snap, 0, 4)!!
        assertEquals("a black mask hides the white layer completely", 0, strip[3].toInt() and 255)
    }

    @Test fun contextRestoreUploadsMasksAndTheSelectionAgain() {
        val x = harness(); x.s.addMask("a", MaskFill.WHITE); x.drag(Tool.RECT_SELECT, 10f, 10f, 90f, 90f)
        val sel = x.gl.gpu.selectionBytes!!.copyOf(); val mask = x.gpuMask("a")!!.copyOf()
        x.gl.gpu.masks.clear(); x.gl.gpu.selectionBytes = null; x.gl.gpu.tex.clear()
        x.s.onContextRestored()
        assertArrayEquals(sel, x.gl.gpu.selectionBytes); assertArrayEquals(mask, x.gl.gpu.masks.values.single())
    }

    @Test fun randomMaskAndSelectionWorkThenFlushSavesWhatIsOnScreen() {
        for (seed in 1..6) {
            val rnd = java.util.Random(seed.toLong())
            val x = Harness(doc = doc2(120, 80, "a"), pixels = mapOf("a" to white2(120, 80))); x.s.start()
            x.s.setBrush(Brush(diameter = 6.0 + rnd.nextInt(14), hardness = rnd.nextDouble(), opacity = 0.5 + rnd.nextDouble() / 2, flow = 0.5 + rnd.nextDouble() / 2, pressureSize = false))
            fun any() = x.st.document.layers[rnd.nextInt(x.st.document.layers.size)].common.id
            repeat(300) {
                when (rnd.nextInt(20)) {
                    0, 1, 2 -> { x.s.setColour(Rgb(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())); x.s.setPaintMask(rnd.nextBoolean()); x.s.setTool(if (rnd.nextInt(4) == 0) Tool.ERASER else Tool.BRUSH); x.stroke(List(2 + rnd.nextInt(3)) { rnd.nextInt(120).toFloat() to rnd.nextInt(80).toFloat() }) }
                    3 -> x.s.addMask(any(), MaskFill.values()[rnd.nextInt(3)])
                    4 -> x.s.deleteMask(any())
                    5 -> x.s.invertMask(any())
                    6 -> x.s.setMaskEnabled(any(), rnd.nextBoolean())
                    7, 8, 9 -> { x.s.setSelectionOp(SelOp.values()[rnd.nextInt(4)]); val t = listOf(Tool.RECT_SELECT, Tool.ELLIPSE_SELECT, Tool.LASSO_SELECT)[rnd.nextInt(3)]
                        x.drag(t, *FloatArray(4 + 2 * rnd.nextInt(3)) { i -> if (i % 2 == 0) rnd.nextInt(130).toFloat() else rnd.nextInt(90).toFloat() }) }
                    10 -> x.s.invertSelection()
                    11 -> x.s.deselect()
                    12 -> x.s.selectAll()
                    13 -> x.s.addLayer()
                    14 -> x.s.duplicateLayer(any())
                    15 -> x.s.deleteLayer(any())
                    16 -> x.s.selectLayer(any())
                    17, 18 -> x.s.undo()
                    19 -> x.s.redo()
                }
                x.s.setTool(Tool.BRUSH)
                if (rnd.nextInt(25) == 0) { x.now += 6_000; x.fireTimers() }
            }
            x.s.flush()
            assertEquals("seed $seed", SaveState.SAVED, x.st.save)
            val (saved, store) = x.reopen()
            assertEquals("seed $seed doc", x.st.document.layers.map { it.common }, saved.layers.map { it.common })
            for ((i, l) in saved.layers.withIndex()) {
                val ref = l.common.mask
                val slot = x.gl.last!!.layers[i * 7].toInt()
                if (ref != null) assertArrayEquals("seed $seed mask of ${l.common.id}", x.gl.gpu.masks[slot], store.loadPlane(ref.dir, 120, 80).toBytes())
                else assertNull("seed $seed GPU mask of ${l.common.id} without a mask in the document", x.gl.gpu.masks[slot])
            }
            val selSaved = saved.selection?.let { store.loadPlane(it.dir, 120, 80).toBytes() }
            assertEquals("seed $seed selection present", x.st.selection != null, saved.selection != null)
            if (selSaved != null) assertArrayEquals("seed $seed selection", x.gl.gpu.selectionBytes, selSaved) else assertNull(x.gl.gpu.selectionBytes)
            assertEquals("seed $seed no errors", emptyList<String>(), x.errors)
        }
    }

    @Test fun theHandOffHookRunsOnceAfterTheFirstSaveAndNotBefore() {
        val doc = app.rawline.core.studio.model.HandOffPlan.make("pho", "shot.jpg", w, h, previewOnly = false, nowMs = 5).document
        val x = Harness(doc = doc, pixels = mapOf("l1" to white2(w, h)), onDisk = false)
        var calls = 0; var sawProject = false
        x.s.afterFirstSave = { calls++; sawProject = x.fs.exists("${Harness.ROOT}/project.json") }
        assertEquals(0, calls)
        x.s.start()
        assertEquals("runs once the project exists on disk", 1, calls); assertTrue(sawProject)
        x.s.addLayer(); x.save(); x.save()
        assertEquals("and never again", 1, calls)
        assertEquals("develop", x.reopen().first.layers.first { it.common.id == "l1" }.common.origin)
    }
}
