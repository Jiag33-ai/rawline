package app.rawline.core.studio.render

import app.rawline.core.studio.model.*
import org.junit.Assert.*
import org.junit.Test

class W32SessionTest {
    private fun blank(w: Int = 64, h: Int = 48) = Document("p1", "T", w, h, layers = listOf(Layer.Pixel(LayerCommon("a", "a"), w, h)), created = 1, modified = 1)
    private fun white(w: Int = 64, h: Int = 48) = RawPixels(w, h, ByteArray(w * h * 4) { 255.toByte() })

    @Test fun theRetryGapCountsFromTheFailureNotFromTheStartOfTheSave() {
        val m = MemFs(); val h = Harness(fs = m, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); h.s.setBrush(Brush(diameter = 6.0, pressureSize = false)); h.now += 10_000
        m.failWrites = true; m.onWrite = { h.now += 7_000 }               // every save takes 7 s of fake time before it fails
        val before = h.errors.count { it.contains("studio save") }
        h.stroke(listOf(5f to 5f, 30f to 5f))
        h.now += 1_500; h.fireTimers()
        val tries = h.errors.count { it.contains("studio save") } - before
        assertEquals("one try, then a timer: no immediate second try", 1, tries)
        assertEquals(1, h.timers.size)
        assertEquals(SpaceCheck.retryDelayMs(1), h.timers.single().first)
    }
    @Test fun leavingReportsAFailedSaveInsteadOfSilentlyDroppingTheWork() {
        val m = MemFs(); val h = Harness(fs = m, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); h.s.setBrush(Brush(diameter = 6.0, pressureSize = false)); h.now += 10_000
        h.stroke(listOf(5f to 5f, 30f to 5f)); h.now += 10_000
        m.failWrites = true                                                // the phone fills up between the last edit and Close
        h.stroke(listOf(5f to 20f, 30f to 20f))                            // an edit that has not been saved yet (the debounce has not run)
        h.now -= 5_000                                                      // Close within the 5 s window
        val s = h.s.flushAndWait(500)
        assertTrue("state $s", s == SaveState.NO_SPACE || s == SaveState.FAILED)
    }
    @Test fun leavingAfterASuccessfulSaveReportsSaved() {
        val m = MemFs(); val h = Harness(fs = m, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); h.s.setBrush(Brush(diameter = 6.0, pressureSize = false)); h.now += 10_000
        h.stroke(listOf(5f to 5f, 30f to 5f))
        assertEquals(SaveState.SAVED, h.s.flushAndWait(500))
    }
    @Test fun aDuplicateThatFailsHalfWayLeavesNoCopy() {
        val m = MemFs(); val h = Harness(fs = m, doc = blank(), pixels = mapOf("a" to white()))
        h.s.start(); h.now += 6_000; h.fireTimers()
        var n = 0; m.onWrite = { if (++n > 2) throw java.io.IOException("No space left on device") }
        try { ProjectCatalog.duplicate(m, "files/studio", "p1", "p2", 5); fail("should fail") } catch (e: java.io.IOException) {}
        assertTrue("half copy: ${m.files.keys.filter { it.contains("/p2") }}", m.files.keys.none { it.contains("files/studio/p2") })
    }
}
