package app.rawline.core.studio.render

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.Dirty
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.Phase as InPhase
import app.rawline.core.studio.model.RawPixels
import app.rawline.core.studio.model.SpaceCheck
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun doc(w: Int, h: Int, vararg ids: String): Document =
    Document("p1", "Test", w, h, layers = (if (ids.isEmpty()) listOf("a") else ids.toList()).map { Layer.Pixel(LayerCommon(it, it), w, h) }, created = 1, modified = 1)

private fun white(w: Int, h: Int) = RawPixels(w, h, ByteArray(w * h * 4) { 255.toByte() })

/** Review s1b fixes: per tile stroke commit, graveyard pruning, a failed readback, a GL thread that never answers. */
class S1bSessionTest {
    private fun brush() = Brush(diameter = 24.0, hardness = 1.0, opacity = 1.0, flow = 1.0, pressureSize = false)

    @Test fun aDiagonalStrokeCostsHistoryByThePaintedTilesNotTheBoundingBox() {
        val w = 2000; val h = 1500
        val s = Harness(doc = doc(w, h), pixels = mapOf("a" to white(w, h)))
        s.s.start(); s.s.setBrush(brush())
        s.stroke(listOf(5f to 5f, 1995f to 1495f))
        val box = Dirty.rect(StrokeWalkerStamps.of(5.0, 5.0, w - 5.0, h - 5.0, brush()), w, h)
        val boxBytes = box[2].toLong() * box[3] * 4 * 2
        assertTrue("history ${s.s.historyBytes()} of box $boxBytes", s.s.historyBytes() in 1 until boxBytes * 15 / 100)
    }

    @Test fun undoAndRedoOfAMultiTileStrokeAreByteIdenticalOnTheCpuCopyAndTheGpu() {
        val w = 700; val h = 400
        val s = Harness(doc = doc(w, h), pixels = mapOf("a" to white(w, h)))
        s.s.start(); s.s.setBrush(brush()); s.s.setColour(Rgb(1f, 0f, 0f))
        val before = s.gl.gpu.tex[0]!!.rgba.copyOf()
        s.stroke(listOf(10f to 10f, 690f to 390f))
        val after = s.gl.gpu.tex[0]!!.rgba.copyOf()
        assertFalse(before.contentEquals(after))
        s.s.undo(); assertArrayEquals(before, s.gl.gpu.tex[0]!!.rgba)
        s.now += 10_000; s.s.flush(); assertArrayEquals(before, s.layerPixels("a"))
        s.s.redo(); assertArrayEquals(after, s.gl.gpu.tex[0]!!.rgba)
        s.now += 10_000; s.s.flush(); assertArrayEquals(after, s.layerPixels("a"))
        s.s.undo(); assertFalse(s.st.canUndo)
    }

    @Test fun pixelsOfADeletedLayerThatHistoryForgotAreDropped() {
        val s = Harness(doc = doc(40, 30, "a", "b"), pixels = mapOf("a" to white(40, 30), "b" to white(40, 30)), active = "a")
        s.s.start()
        s.s.deleteLayer("b")
        assertEquals(1, s.s.graveyardSize())
        repeat(105) { i -> s.s.setVisible("a", i % 2 == 1) }   // the delete falls out of the 100 entry history
        assertEquals(0, s.s.graveyardSize())
    }

    @Test fun aFailedReadbackOnTheThirdTileRollsTheStrokeBack() {
        val w = 700; val h = 300
        val s = Harness(doc = doc(w, h), pixels = mapOf("a" to white(w, h)))
        s.s.start(); s.s.setBrush(brush())
        val before = s.gl.gpu.tex[0]!!.rgba.copyOf()
        s.gl.gpu.failReadAt = 3
        s.stroke(listOf(10f to 10f, 690f to 290f))
        assertArrayEquals(before, s.gl.gpu.tex[0]!!.rgba)
        assertFalse(s.st.canUndo)
        assertTrue(s.st.message?.text?.contains("Could not finish") == true)
        s.now += 10_000; s.s.flush(); assertArrayEquals(before, s.layerPixels("a"))
    }

    @Test fun startingAStrokeDoesNotWaitForTheGlThread() {
        val s = Harness(doc = doc(64, 48), pixels = mapOf("a" to white(64, 48)))
        s.s.start(); s.s.setBrush(brush())
        s.gl.frozen = true
        val t0 = System.nanoTime()
        s.ev(InPhase.DOWN, 0, 5f, 5f); s.ev(InPhase.MOVE, 0, 30f, 30f); s.ev(InPhase.CANCEL, 0, 30f, 30f)
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 2_000_000_000L)
    }
}

/** The stamps of one straight stroke, for the bounding box the old commit used. */
private object StrokeWalkerStamps {
    fun of(x0: Double, y0: Double, x1: Double, y1: Double, b: Brush) =
        app.rawline.core.studio.model.BrushMath.walk(listOf(app.rawline.core.studio.model.StrokePoint(x0, y0), app.rawline.core.studio.model.StrokePoint(x1, y1)), b)
}

/** BK-503: a nearly full phone. */
class FullDiskTest {
    private fun blank(w: Int, h: Int) = doc(w, h)

    @Test fun storageThatStaysFullRetriesAFewTimesWithBackoffAndOneNotice() {
        val mem = MemFs()
        val h = Harness(fs = mem, doc = blank(64, 48), pixels = mapOf("a" to white(64, 48)))
        h.s.start(); h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
        h.now += 10_000
        mem.failWrites = true
        h.stroke(listOf(5f to 5f, 30f to 5f))
        var toasts = 0; var lastId = -1L
        for (i in 1..200) {   // 17 minutes of an open Studio on a full phone
            h.now += 5_100; h.fireTimers()
            val m = h.st.message; if (m != null && m.id != lastId) { toasts++; lastId = m.id }
        }
        val saveErrors = h.errors.count { it.contains("studio save") }
        assertTrue("save attempts $saveErrors", saveErrors in 1..12)
        assertEquals("notices", 1, toasts)
        assertEquals(SaveState.FAILED, h.st.save)
    }

    @Test fun anEditAfterTheRetriesStoppedAllowsAnotherSlowTryAndSpaceBeingFreedSaves() {
        val mem = MemFs()
        val h = Harness(fs = mem, doc = blank(64, 48), pixels = mapOf("a" to white(64, 48)))
        h.s.start(); h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
        h.now += 10_000; mem.failWrites = true
        h.stroke(listOf(5f to 5f, 30f to 5f))
        repeat(60) { h.now += 61_000; h.fireTimers() }
        val stopped = h.errors.count { it.contains("studio save") }
        assertEquals(SpaceCheck.MAX_TRIES, stopped)
        mem.failWrites = false
        h.stroke(listOf(5f to 20f, 30f to 20f))   // the user paints again: a try, and it works
        h.now += 61_000; h.fireTimers()
        assertEquals(SaveState.SAVED, h.st.save)
        assertEquals(stopped, h.errors.count { it.contains("studio save") })
    }

    @Test fun aNearlyFullPhoneIsToldBeforeAnythingIsWrittenAndTheWorkStaysOpen() {
        val mem = MemFs()
        val h = Harness(fs = mem, doc = blank(64, 48), pixels = mapOf("a" to white(64, 48)))
        h.s.start(); h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
        h.now += 10_000
        val filesBefore = HashMap(mem.files)
        mem.free = 1_000_000
        h.stroke(listOf(5f to 5f, 30f to 5f))
        assertEquals(SaveState.NO_SPACE, h.st.save)
        assertEquals(SpaceCheck.SAVE_FAILED_FULL, h.st.message?.text)
        assertEquals(filesBefore.keys, mem.files.keys)           // nothing written
        assertTrue(h.st.canUndo)                                  // the project is intact in memory
        mem.free = Long.MAX_VALUE
        h.now += 61_000; h.fireTimers()
        assertEquals(SaveState.SAVED, h.st.save)
    }

    @Test fun aFirstSaveThatFailsLeavesNoHalfWrittenFolder() {
        val mem = MemFs()
        mem.failPathContains = "project.json"
        val h = Harness(fs = mem, doc = blank(64, 48), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        assertEquals(SaveState.FAILED, h.st.save)
        assertTrue(mem.files.keys.toString(), mem.files.keys.none { it.startsWith(Harness.ROOT) })
        mem.failPathContains = null
        h.now += 10_000; h.fireTimers()
        assertEquals(SaveState.SAVED, h.st.save)
        assertNotNull(h.reopen())
    }

    @Test fun theSpaceRuleAndTheRetrySchedule() {
        assertNull(SpaceCheck.problem(500L * 1024 * 1024, 100L * 1024 * 1024))
        assertEquals("Not enough space. Free about 100 MB and try again.", SpaceCheck.problem(200L * 1024 * 1024, 100L * 1024 * 1024))
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (1..6).map { SpaceCheck.retryDelayMs(it) })
    }
}
