package app.rawline.feature.masking

import app.rawline.core.model.BrushStroke
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Geometry
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskOp
import app.rawline.core.render.P
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskLogicTest {
    private fun mask(kind: MaskKind, name: String) = MaskFactory.mask(kind, name)
    private fun recipe(vararg m: Mask) = EditRecipe(masks = m.toList())
    private val stroke = BrushStroke(listOf(0.1f, 0.1f, 0.2f, 0.2f), 0.05f, 0.5f, 1f, false)

    // ---- names, selection, limits

    @Test fun nextNameIsLowestUnused() {
        val a = mask(MaskKind.BRUSH, "Brush 1"); val b = mask(MaskKind.BRUSH, "Brush 2")
        assertEquals("Brush 3", MaskRules.nextName("Brush", listOf(a, b)))
        assertEquals("Brush 1", MaskRules.nextName("Brush", listOf(b)))
        assertEquals("Linear 1", MaskRules.nextName("Linear", listOf(a, b)))
    }

    @Test fun cleanNameTrimsAndRejectsBlank() {
        assertEquals("Sky glow", MaskRules.cleanName("  Sky   glow "))
        assertNull(MaskRules.cleanName("   "))
        assertEquals(MaskRules.MAX_NAME, MaskRules.cleanName("x".repeat(100))!!.length)
        val m = mask(MaskKind.LINEAR, "Linear 1")
        assertEquals("Linear 1", MaskRules.rename(recipe(m), m.id, " ").masks[0].name)
        assertEquals("Face", MaskRules.rename(recipe(m), m.id, "Face").masks[0].name)
    }

    @Test fun reconcileDropsMissingSelectionAndClampsPart() {
        val m = mask(MaskKind.LINEAR, "L")
        assertEquals(null to 0, MaskRules.reconcile("gone", 3, listOf(m)))
        assertEquals(null to 0, MaskRules.reconcile(null, 0, emptyList()))
        assertEquals(m.id to 0, MaskRules.reconcile(m.id, 5, listOf(m)))
    }

    @Test fun maskAndLayerLimits() {
        val eight = (1..P.MAX_MASKS).map { mask(MaskKind.LINEAR, "L$it") }
        assertFalse(MaskRules.canAddMask(EditRecipe(masks = eight)))
        assertTrue(MaskRules.canAddMask(EditRecipe(masks = eight.drop(1))))
        val fullLayers = EditRecipe(masks = listOf(
            Mask("a", "a", (1..6).map { MaskFactory.component(MaskKind.BRUSH) }),
            Mask("b", "b", (1..6).map { MaskFactory.component(MaskKind.BRUSH) }),
            Mask("c", "c", (1..4).map { MaskFactory.component(MaskKind.BRUSH) }),
        ))
        assertEquals(P.MAX_LAYERS, MaskRules.layerKeys(fullLayers).size)
        assertFalse(MaskRules.canAddMask(fullLayers, 1))
        assertTrue(MaskRules.canAddMask(fullLayers, 0))
        assertFalse(MaskRules.canAddPart(fullLayers, "a", 0))   // six parts already
        assertTrue(MaskRules.canAddPart(fullLayers, "c", 0))
        assertFalse(MaskRules.canAddPart(fullLayers, "c", 1))   // no layer slot left
    }

    // ---- component ops

    @Test fun partOpsAddressByIdAndKeepOneComponent() {
        var r = recipe(mask(MaskKind.LINEAR, "L"))
        val id = r.masks[0].id
        r = MaskRules.addPart(r, id, MaskFactory.component(MaskKind.RADIAL, MaskOp.SUBTRACT))
        assertEquals(2, r.masks[0].components.size)
        assertEquals(MaskOp.SUBTRACT, r.masks[0].components[1].op)
        r = MaskRules.setPartOp(r, id, 1, MaskOp.INTERSECT)
        assertEquals(MaskOp.INTERSECT, r.masks[0].components[1].op)
        r = MaskRules.togglePartInvert(r, id, 0)
        assertTrue(r.masks[0].components[0].invert)
        r = MaskRules.removePart(r, id, 1)
        assertEquals(1, r.masks[0].components.size)
        assertEquals(1, MaskRules.removePart(r, id, 0).masks[0].components.size)   // never empty
        assertEquals(r, MaskRules.setPartOp(r, "missing", 0, MaskOp.ADD))
    }

    @Test fun addPartStopsAtSix() {
        var r = recipe(mask(MaskKind.LINEAR, "L"))
        val id = r.masks[0].id
        repeat(10) { r = MaskRules.addPart(r, id, MaskFactory.component(MaskKind.LUMINANCE)) }
        assertEquals(MaskRules.MAX_PARTS, r.masks[0].components.size)
    }

    @Test fun undoStrokeRemovesOnlyTheLast() {
        val m = mask(MaskKind.BRUSH, "B")
        val s2 = stroke.copy(size = 0.1f)
        val r = recipe(m.copy(components = listOf(m.components[0].copy(strokes = listOf(stroke, s2)))))
        val one = MaskRules.removeLastStroke(r, m.id, 0)
        assertEquals(listOf(stroke), one.masks[0].components[0].strokes)
        val none = MaskRules.removeLastStroke(one, m.id, 0)
        assertTrue(none.masks[0].components[0].strokes.isEmpty())
        assertEquals(none, MaskRules.removeLastStroke(none, m.id, 0))
    }

    @Test fun withParamPadsShortLists() {
        val c = MaskFactory.component(MaskKind.COLOR).copy(params = listOf(0.1f))
        assertEquals(listOf(0.1f, 0f, 0.7f), MaskRules.withParam(c, 2, 0.7f).params)
    }

    @Test fun defaultGradientsSitInsideTheCrop() {
        val crop = Geometry(cropX = 0.2f, cropY = 0.3f, cropW = 0.5f, cropH = 0.4f)
        val lin = MaskFactory.component(MaskKind.LINEAR, crop = crop).params
        assertTrue(lin[0] in 0.2f..0.7f && lin[1] in 0.3f..0.7f && lin[3] in 0.3f..0.7f)
        val rad = MaskFactory.component(MaskKind.RADIAL, crop = crop).params
        assertEquals(0.45f, rad[0], 1e-5f); assertEquals(0.5f, rad[1], 1e-5f)
    }

    // ---- layer key lifecycle

    @Test fun duplicateGivesBrushItsOwnLayerAndSharesAiLayers() {
        val ai = MaskFactory.aiPart("Sky", "ai_1")
        val m = Mask("x", "Brush 1", listOf(MaskFactory.component(MaskKind.BRUSH).copy(strokes = listOf(stroke)), ai))
        val d = MaskFactory.duplicate(m, "Brush 2")
        assertNotEquals(m.id, d.id)
        assertNotEquals(m.components[0].layerKey, d.components[0].layerKey)
        assertTrue(d.components[0].layerKey!!.startsWith("brush_"))
        assertEquals(listOf(stroke), d.components[0].strokes)
        assertEquals("ai_1", d.components[1].layerKey)
    }

    @Test fun layerKeysFreedOnDeleteAndWantedAgainOnUndo() {
        val a = mask(MaskKind.BRUSH, "A"); val b = mask(MaskKind.BRUSH, "B")
        val full = recipe(a, b)
        val live = MaskRules.layerKeys(full)
        val afterDelete = MaskRules.deleteMask(full, a.id)
        assertEquals(listOf(a.components[0].layerKey), LayerPlan.unused(live, afterDelete))
        assertTrue(LayerPlan.unused(live, full).isEmpty())
        assertEquals(live - a.components[0].layerKey!!, MaskRules.layerKeys(afterDelete))
    }

    @Test fun brushSignatureChangesWithSizeOrStrokes() {
        val s = LayerPlan.brushSignature(listOf(stroke).hashCode(), 2048, 1365)
        assertNotEquals(s, LayerPlan.brushSignature(listOf(stroke).hashCode(), 1365, 2048))
        assertNotEquals(s, LayerPlan.brushSignature(emptyList<BrushStroke>().hashCode(), 2048, 1365))
    }

    @Test fun savedRecipesStillRoundTrip() {
        var r = recipe(mask(MaskKind.LINEAR, "Linear 1"), mask(MaskKind.BRUSH, "Brush 1"))
        r = MaskRules.addPart(r, r.masks[0].id, MaskFactory.component(MaskKind.RADIAL, MaskOp.SUBTRACT))
        assertEquals(r, EditRecipe.fromJson(r.toJson()))
    }

    // ---- linear handles

    @Test fun linearHitPrefersNearestEndThenMiddleThenLine() {
        val a = V2(100f, 100f); val b = V2(100f, 500f)
        assertEquals(LinearPart.START, LinearGeom.hit(a, b, V2(110f, 130f), 40f, 20f))
        assertEquals(LinearPart.END, LinearGeom.hit(a, b, V2(95f, 480f), 40f, 20f))
        assertEquals(LinearPart.MOVE, LinearGeom.hit(a, b, V2(120f, 300f), 40f, 20f))      // middle handle
        assertEquals(LinearPart.MOVE, LinearGeom.hit(a, b, V2(115f, 220f), 40f, 20f))      // on the line
        assertNull(LinearGeom.hit(a, b, V2(300f, 300f), 40f, 20f))
        assertEquals(LinearPart.END, LinearGeom.hit(V2(100f, 100f), V2(130f, 100f), V2(125f, 100f), 40f, 20f))
        assertEquals(LinearPart.START, LinearGeom.hit(V2(100f, 100f), V2(130f, 100f), V2(105f, 100f), 40f, 20f))
    }

    @Test fun linearTranslateKeepsShapeInsideFrame() {
        val p = listOf(0.4f, 0.3f, 0.6f, 0.5f)
        val t = LinearGeom.translate(p, 0.1f, -0.1f)
        assertEquals(0.5f, t[0], 1e-5f); assertEquals(0.2f, t[1], 1e-5f)
        val edge = LinearGeom.translate(p, 5f, 5f)
        assertEquals(1f, edge[2], 1e-5f); assertEquals(1f, edge[3], 1e-5f)
        assertEquals(0.8f, edge[0], 1e-5f)
    }

    @Test fun linearAngleLengthRoundTrip() {
        val asp = 1.5f
        val p = listOf(0.5f, 0.3f, 0.5f, 0.6f)
        assertEquals(90f, LinearGeom.angleDeg(p, asp), 1e-3f)
        assertEquals(0.3f, LinearGeom.length(p, asp), 1e-5f)
        val q = LinearGeom.withAngleLength(p, 45f, 0.4f, asp)
        assertEquals(45f, LinearGeom.angleDeg(q, asp), 0.05f)
        assertEquals(0.4f, LinearGeom.length(q, asp), 1e-3f)
        assertEquals(listOf(0.5f, 0.6f, 0.5f, 0.3f), LinearGeom.flip(p))
        assertEquals(-90f, LinearGeom.angleDeg(LinearGeom.flip(p), asp), 1e-3f)
        val moved = LinearGeom.moveEnd(p, LinearPart.END, 2f, -1f)
        assertEquals(1f, moved[2], 0f); assertEquals(0f, moved[3], 0f)
    }

    // ---- radial handles

    private val rad = listOf(0.5f, 0.5f, 0.2f, 0.1f, 0f, 0.5f)
    private val asp = 1.5f
    /** View is a 1000 px tall frame, so frame (x, y) maps to (x * asp * 1000, y * 1000). */
    private val toView: (V2) -> V2 = { V2(it.x * asp * 1000f, it.y * 1000f) }

    @Test fun radialHandlesAreWhereTheShaderPutsThem() {
        val v = RadialGeom.viewHandles(rad, asp, 100f, toView)
        assertEquals(750f, v.center.x, 0.01f); assertEquals(500f, v.center.y, 0.01f)
        assertEquals(750f + 0.2f * 1000f, v.xPos.x, 0.01f); assertEquals(500f, v.xPos.y, 0.01f)
        assertEquals(750f - 200f, v.xNeg.x, 0.01f)
        assertEquals(600f, v.yPos.y, 0.01f); assertEquals(400f, v.yNeg.y, 0.01f)
        assertEquals(300f, v.rotate.y, 0.01f)
        val r90 = RadialGeom.viewHandles(listOf(0.5f, 0.5f, 0.2f, 0.1f, (PI / 2).toFloat(), 0.5f), asp, 100f, toView)
        assertEquals(750f, r90.xPos.x, 0.5f); assertEquals(700f, r90.xPos.y, 0.5f)
    }

    @Test fun radialHitPriority() {
        val v = RadialGeom.viewHandles(rad, asp, 100f, toView)
        assertEquals(RadialPart.ROTATE, RadialGeom.hit(v, v.rotate + V2(5f, 5f), 40f, false))
        assertEquals(RadialPart.X_POS, RadialGeom.hit(v, v.xPos + V2(-10f, 8f), 40f, true))
        assertEquals(RadialPart.CENTER, RadialGeom.hit(v, v.center + V2(5f, 0f), 40f, true))
        assertEquals(RadialPart.BODY, RadialGeom.hit(v, v.center + V2(80f, 20f), 20f, true))
        assertNull(RadialGeom.hit(v, V2(0f, 0f), 40f, false))
        val tiny = RadialGeom.viewHandles(listOf(0.5f, 0.5f, 0.01f, 0.01f, 0f, 0.5f), asp, 100f, toView)
        assertEquals(RadialPart.CENTER, RadialGeom.hit(tiny, tiny.center, 60f, true))
        assertEquals(RadialPart.X_POS, RadialGeom.hit(tiny, tiny.xPos + V2(2f, 0f), 60f, true))
    }

    @Test fun radialContains() {
        assertTrue(RadialGeom.contains(rad, asp, V2(0.5f, 0.5f)))
        assertTrue(RadialGeom.contains(rad, asp, V2(0.5f + 0.19f / asp, 0.5f)))
        assertFalse(RadialGeom.contains(rad, asp, V2(0.5f + 0.21f / asp, 0.5f)))
        assertFalse(RadialGeom.contains(rad, asp, V2(0.5f, 0.5f + 0.11f)))
    }

    @Test fun radialScaleDragDoesNotHop() {
        val grabAt = V2(0.5f + 0.22f / asp, 0.5f)           // finger 0.02 beyond the true edge
        val g = RadialGeom.grab(RadialPart.X_POS, rad, grabAt, asp)
        assertEquals(0.2f, RadialGeom.drag(RadialPart.X_POS, g, grabAt, asp)[2], 1e-5f)
        assertEquals(0.3f, RadialGeom.drag(RadialPart.X_POS, g, V2(0.5f + 0.32f / asp, 0.5f), asp)[2], 1e-5f)
        assertEquals(0.3f, RadialGeom.drag(RadialPart.X_NEG, g, V2(0.5f - 0.32f / asp, 0.5f), asp)[2], 1e-5f)
        assertEquals(RadialGeom.MIN_R, RadialGeom.drag(RadialPart.X_POS, g, V2(0.5f, 0.5f), asp)[2], 1e-6f)
        val gy = RadialGeom.grab(RadialPart.Y_POS, rad, V2(0.5f, 0.6f), asp)
        val dy = RadialGeom.drag(RadialPart.Y_POS, gy, V2(0.5f, 0.75f), asp)
        assertEquals(0.25f, dy[3], 1e-5f)
        assertEquals(rad[2], dy[2], 0f)
    }

    @Test fun radialMoveKeepsOffsetAndStaysInFrame() {
        val g = RadialGeom.grab(RadialPart.BODY, rad, V2(0.55f, 0.52f), asp)
        val m = RadialGeom.drag(RadialPart.BODY, g, V2(0.65f, 0.62f), asp)
        assertEquals(0.6f, m[0], 1e-5f); assertEquals(0.6f, m[1], 1e-5f)
        val far = RadialGeom.drag(RadialPart.CENTER, g, V2(9f, -9f), asp)
        assertEquals(1f, far[0], 0f); assertEquals(0f, far[1], 0f)
    }

    @Test fun radialRotateFollowsFingerAndSnaps() {
        val g = RadialGeom.grab(RadialPart.ROTATE, rad, V2(0.5f, 0.2f), asp)
        assertEquals(0f, RadialGeom.drag(RadialPart.ROTATE, g, V2(0.5f, 0.2f), asp)[4], 1e-5f)
        assertEquals((PI / 2).toFloat(), RadialGeom.drag(RadialPart.ROTATE, g, V2(0.5f + 0.3f / asp, 0.5f), asp)[4], 1e-4f)
        fun at(deg: Double): Float {
            val d = Math.toRadians(deg)
            return RadialGeom.drag(RadialPart.ROTATE, g, V2(0.5f + (0.3 * Math.sin(d) / asp).toFloat(), 0.5f - (0.3 * Math.cos(d)).toFloat()), asp)[4]
        }
        assertEquals(Math.toRadians(45.0).toFloat(), at(44.0), 1e-4f)      // snaps
        assertEquals(Math.toRadians(20.0).toFloat(), at(20.0), 1e-3f)      // does not
    }

    @Test fun angleNormalisedIntoHalfTurn() {
        for (deg in listOf(-170, -100, -90, -45, 0, 30, 90, 91, 135, 180, 270)) {
            val n = Math.toDegrees(RadialGeom.normaliseAngle(Math.toRadians(deg.toDouble()).toFloat()).toDouble())
            assertTrue("$deg -> $n", n > -90.01 && n <= 90.01)
            val k = (deg - n) / 180.0
            assertEquals(0.0, k - Math.round(k), 1e-3)
        }
    }

    // ---- brush ring

    @Test fun ringRadiusScalesWithCropAndSize() {
        assertEquals(60f, BrushMath.ringRadiusPx(0.06f, 1000f, 0.5f), 1e-3f)
        assertEquals(30f, BrushMath.ringRadiusPx(0.06f, 1000f, 1f), 1e-3f)
        assertTrue(BrushMath.farEnough(0f, 0f, 0.01f, 0f, 1.5f))
        assertFalse(BrushMath.farEnough(0f, 0f, 0.0005f, 0f, 1.5f))
    }

    // ---- latest wins serialiser (the persist ordering)

    @Test fun serialRunsInlineAndKeepsOrder() {
        val seen = ArrayList<Int>()
        val s = LatestWinsSerial<Int>({ it.run() }) { seen.add(it) }
        s.submit(1); s.submit(2); s.submit(3)
        assertEquals(listOf(1, 2, 3), seen)
        assertTrue(s.isIdle)
    }

    @Test fun serialFoldsValuesArrivingWhileBusyAndNewestWins() {
        val seen = ArrayList<String>()
        lateinit var s: LatestWinsSerial<Pair<String, Boolean>>
        s = LatestWinsSerial({ it.run() }, merge = { old, n -> n.first to (n.second || old?.second == true) }) { v ->
            seen.add("${v.first}/${v.second}")
            if (v.first == "a") { s.submit("b" to true); s.submit("c" to false) }
        }
        s.submit("a" to false)
        assertEquals(listOf("a/false", "c/true"), seen)    // b folded into c, the force flag kept
    }

    @Test fun serialNeverOverlapsAndLastSubmitIsLastRun() {
        val pool = Executors.newFixedThreadPool(4)
        val inFlight = AtomicInteger(); val maxInFlight = AtomicInteger()
        val last = AtomicInteger(-1)
        val done = CountDownLatch(1)
        val s = LatestWinsSerial<Int>({ pool.execute(it) }) { v ->
            val n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            Thread.sleep(1)
            last.set(v)
            inFlight.decrementAndGet()
            if (v == 999) done.countDown()
        }
        val producers = (0 until 4).map { t -> Thread { for (i in 0 until 250) s.submit(t * 250 + i) } }
        producers.forEach { it.start() }; producers.forEach { it.join() }
        s.submit(999)
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(1, maxInFlight.get())
        assertEquals(999, last.get())
        assertTrue(s.isIdle)
    }

    @Test fun serialSurvivesAFailingJob() {
        val seen = ArrayList<Int>(); val errors = ArrayList<String>()
        val s = LatestWinsSerial<Int>({ it.run() }, onError = { errors.add(it.message ?: "") }) { v -> if (v == 1) error("boom"); seen.add(v) }
        s.submit(1); s.submit(2)
        assertEquals(listOf("boom"), errors); assertEquals(listOf(2), seen); assertTrue(s.isIdle)
    }

    @Test fun serialRecoversWhenLaunchThrows() {
        var fail = true; val seen = ArrayList<Int>()
        val s = LatestWinsSerial<Int>({ if (fail) throw IllegalStateException("scope gone") else it.run() }) { seen.add(it) }
        try { s.submit(1) } catch (_: IllegalStateException) {}
        fail = false
        s.submit(2)
        assertTrue(2 in seen)
    }
}
