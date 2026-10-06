package app.rawline.core.studio.render

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.RawPixels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BK-484: autosave waits for 1.5 s without an edit (5 s ceiling, pause immediate); ten layers with five deleted stay under a stated memory bound. */
class BK484SessionTest {
    private fun doc(w: Int, h: Int, ids: List<String>) = Document("p1", "Test", w, h, layers = ids.map { Layer.Pixel(LayerCommon(it, it), w, h) }, created = 1, modified = 1)
    private fun saves(h: Harness) = h.reports.count { it.first == "studio_autosave_ms" }
    private fun paint(h: Harness) {
        h.s.setBrush(Brush(diameter = 10.0, hardness = 1.0, opacity = 1.0, flow = 1.0, spacing = 0.1, pressureSize = false))
        h.stroke(listOf(10f to 20f, 40f to 20f))
    }
    private fun advance(h: Harness, ms: Long) { h.now += ms; h.fireTimers() }
    private fun started(): Harness {
        val h = Harness(doc = doc(64, 48, listOf("a")), pixels = mapOf("a" to RawPixels(64, 48, ByteArray(64 * 48 * 4) { 255.toByte() })))
        h.s.start(); assertEquals(1, saves(h)); return h
    }

    @Test fun aSaveComesOneAndAHalfSecondsAfterTheStrokeEnds() {
        val h = started()
        paint(h)
        advance(h, 1_000); assertEquals("under 1.5 s idle", 1, saves(h))
        advance(h, 600); assertEquals(2, saves(h))
    }

    @Test fun continuousPaintingStillSavesAtTheFiveSecondCeiling() {
        val h = started()
        repeat(4) { paint(h); advance(h, 1_000) }       // 4 s, never 1.5 s idle
        assertEquals(1, saves(h))
        paint(h); advance(h, 1_100)                       // 5.1 s after the first unsaved edit
        assertEquals(2, saves(h))
    }

    @Test fun pauseSavesAtOnce() {
        val h = started()
        paint(h); h.s.flush()
        assertEquals(2, saves(h))
    }

    @Test fun tenLayersFiveDeletedThenUndoStaysUnderTheBound() {
        // 1/100 of the real size (400 x 300 for 4000 x 3000), incompressible pixels = worst case for the encoded graveyard. The bound is per byte of layer, so it scales.
        val w = 400; val h = 300; val raw = w.toLong() * h * 4
        val ids = (0 until 10).map { "l$it" }
        val rnd = java.util.Random(7)
        val px = ids.associateWith { RawPixels(w, h, ByteArray(w * h * 4).also { b -> rnd.nextBytes(b) }) }
        val before = px.mapValues { it.value.rgba.copyOf() }
        val hn = Harness(doc = doc(w, h, ids), pixels = px, active = "l9")
        hn.s.start()
        val gone = ids.take(5)
        gone.forEach { hn.s.deleteLayer(it) }
        assertEquals(5, hn.s.graveyardSize())
        assertTrue("graveyard ${hn.s.graveyardBytes()} <= 5 layers + 1%", hn.s.graveyardBytes() <= 5 * raw * 101 / 100)
        assertTrue("history holds no pixel copies of whole layers", hn.s.historyBytes() < raw)
        repeat(5) { hn.s.undo() }
        assertEquals("every pixel came back, none was evicted", 0, hn.s.graveyardSize())
        hn.now += 10_000; hn.s.flush()
        for (id in gone) assertArrayEquals(before[id], hn.layerPixels(id))
        // at the real 12 MP the same bound is 5 x 48 MB = 240 MB, under the 256 MB graveyard cap, so nothing is evicted there either
        assertTrue(5L * 12_000_000 * 4 <= 256L * 1024 * 1024)
    }
}
