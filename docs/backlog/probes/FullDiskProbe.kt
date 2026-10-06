package app.rawline.core.studio.render

import app.rawline.core.studio.model.*
import org.junit.Assert.*
import org.junit.Test

class FullDiskProbe {
    private fun blank(w: Int, h: Int) = Document("p1", "Test", w, h, layers = listOf(Layer.Pixel(LayerCommon("a", "a"), w, h)), created = 1, modified = 1)
    private fun white(w: Int, h: Int) = RawPixels(w, h, ByteArray(w * h * 4) { 255.toByte() })

    @Test fun storageThatStaysFullKeepsRetryingForever() {
        val mem = MemFs()
        val h = Harness(fs = mem, doc = blank(64, 48), pixels = mapOf("a" to white(64, 48)))
        h.s.start()
        h.s.setBrush(Brush(diameter = 6.0, pressureSize = false))
        h.now += 10_000
        mem.failWrites = true
        h.stroke(listOf(5f to 5f, 30f to 5f))
        var toasts = 0; var lastId = -1L
        for (i in 1..200) {                         // 200 retries, 5.1 s apart: 17 minutes of an open Studio on a full phone
            h.now += 5_100; h.fireTimers()
            val m = h.st.message; if (m != null && m.id != lastId) { toasts++; lastId = m.id }
        }
        val saveErrors = h.errors.count { it.contains("studio save") }
        println("PROBE retries=$saveErrors toasts=$toasts save=${h.st.save}")
        assertTrue("expected an unbounded retry loop", saveErrors >= 200)
    }
}
