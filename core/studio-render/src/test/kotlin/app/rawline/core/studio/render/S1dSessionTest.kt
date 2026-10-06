package app.rawline.core.studio.render

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.RawPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BK-507: the project thumbnail is written from the autosave path, never on the Close path. */
class S1dSessionTest {
    private fun doc() = Document("p1", "Test", 64, 48, layers = listOf(Layer.Pixel(LayerCommon("a", "a"), 64, 48)), created = 1, modified = 1)
    private fun white() = RawPixels(64, 48, ByteArray(64 * 48 * 4) { 255.toByte() })
    private class Writes { val snaps = ArrayList<ExportSnapshot>(); var ok = true; var onWrite: () -> Unit = {} }

    private fun harness(w: Writes, onDisk: Boolean = false): Harness {
        val h = Harness(doc = doc(), pixels = if (onDisk) emptyMap() else mapOf("a" to white()))
        h.s.thumbnailer = StudioSession.Thumbnailer { snap -> w.snaps += snap; w.onWrite(); w.ok }
        return h
    }
    private fun paint(h: Harness) {
        h.s.setBrush(Brush(diameter = 10.0, hardness = 1.0, opacity = 1.0, flow = 1.0, spacing = 0.1, pressureSize = false))
        h.stroke(listOf(10f to 20f, 40f to 20f))
    }
    private fun advance(h: Harness, ms: Long) { h.now += ms; h.fireTimers() }

    @Test fun aNewProjectGetsItsThumbnailAfterTheFirstQuietMoment() {
        val w = Writes(); val h = harness(w)
        h.s.start()
        advance(h, 1_000); assertTrue("not yet: under 2 s since the edit", w.snaps.isEmpty())
        advance(h, 1_500); assertEquals(1, w.snaps.size)
        assertEquals(64, w.snaps[0].width)
    }

    @Test fun aSecondThumbnailWaitsForTheMinimumGapAndOnlyComesIfSomethingChanged() {
        val w = Writes(); val h = harness(w)
        h.s.start(); advance(h, 3_000); assertEquals(1, w.snaps.size)
        h.now += 7_000; paint(h)
        advance(h, 3_000); assertEquals("a stroke, but under a minute since the last write", 1, w.snaps.size)
        advance(h, 60_000); assertEquals(2, w.snaps.size)
        advance(h, 120_000); assertEquals("nothing changed since: no third write", 2, w.snaps.size)
    }

    @Test fun keepingOnPaintingKeepsPostponingTheThumbnail() {
        val w = Writes(); val h = harness(w)
        h.s.start(); advance(h, 3_000); assertEquals(1, w.snaps.size)
        h.now += 70_000                               // a minute has passed, so only the quiet time is in the way
        paint(h); advance(h, 1_500); paint(h); advance(h, 1_500)
        assertEquals("edits every 1.5 s: never 2 s of quiet", 1, w.snaps.size)
        advance(h, 2_500); assertEquals(2, w.snaps.size)
    }

    @Test fun closingNeverWritesOrWaitsForAThumbnail() {
        val w = Writes(); val h = harness(w)
        h.s.start(); advance(h, 3_000); assertEquals(1, w.snaps.size)
        paint(h)
        h.s.flush(); h.s.release()
        advance(h, 200_000)
        assertEquals("the edit before Close is not rendered: the home keeps the old thumbnail", 1, w.snaps.size)
    }

    @Test fun aFailedThumbnailIsOneTryNotALoop() {
        val w = Writes(); w.ok = false; val h = harness(w)
        h.s.start(); advance(h, 3_000)
        assertEquals(1, w.snaps.size); assertTrue(h.errors.any { it.contains("thumbnail") })
        advance(h, 200_000); assertEquals(1, w.snaps.size)
        w.ok = true; paint(h); advance(h, 62_000); assertEquals("the next edit asks again", 2, w.snaps.size)
    }

    @Test fun anEditDuringTheWriteKeepsTheThumbnailDue() {
        val w = Writes(); val h = harness(w)
        w.onWrite = { paint(h) }                      // the user paints while the thumbnail is being made
        h.s.start(); advance(h, 3_000); assertEquals(1, w.snaps.size)
        w.onWrite = {}
        advance(h, 62_000); assertEquals(2, w.snaps.size)
    }

    @Test fun noThumbnailIsMadeBeforeTheFirstSaveWorked() {
        val w = Writes()
        val h = Harness(doc = doc(), pixels = mapOf("a" to white()))
        h.fs.failWrites = true
        h.s.thumbnailer = StudioSession.Thumbnailer { snap -> w.snaps += snap; true }
        h.s.start(); advance(h, 3_000); advance(h, 3_000)
        assertTrue("no folder to put it in yet", w.snaps.isEmpty())
        assertNotNull(h.st)
    }

    @Test fun withoutAThumbnailerNothingIsScheduled() {
        val h = Harness(doc = doc(), pixels = mapOf("a" to white()))
        h.s.start(); val before = h.timers.size
        paint(h)
        assertFalse("edits arm no extra timer when nobody asked for a thumbnail", h.timers.size > before + 1)   // at most the autosave's own timer
    }
}
