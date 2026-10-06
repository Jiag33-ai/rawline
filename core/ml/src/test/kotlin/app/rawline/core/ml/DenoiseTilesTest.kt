package app.rawline.core.ml

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class DenoiseTilesTest {
    private val w = 50; private val h = 37; private val tile = 16; private val overlap = 4

    private fun image() = FloatArray(w * h * 3) { ((it * 7919) % 1009) / 1009f }

    private fun readFrom(img: FloatArray) = { x0: Int, y0: Int ->
        FloatArray(tile * tile * 3).also { out ->
            for (j in 0 until tile) for (i in 0 until tile) {
                val sx = min(max(x0 + i, 0), w - 1); val sy = min(max(y0 + j, 0), h - 1)
                for (c in 0 until 3) out[(j * tile + i) * 3 + c] = img[(sy * w + sx) * 3 + c]
            }
        }
    }

    // a "model" whose result depends on the neighbourhood, including the overlap, so any pixel seen after being rewritten changes the output
    private fun process(lin: FloatArray, cw: Int, ch: Int): FloatArray {
        val out = FloatArray(cw * ch * 3)
        for (y in 0 until ch) for (x in 0 until cw) for (c in 0 until 3) {
            var sum = 0f; var n = 0
            for (dy in -3..3) for (dx in -3..3) { sum += lin[((y + overlap + dy) * tile + (x + overlap + dx)) * 3 + c]; n++ }
            out[(y * cw + x) * 3 + c] = sum / n * 0.5f + lin[((y + overlap) * tile + (x + overlap)) * 3 + c] * 0.5f + 0.01f
        }
        return out
    }

    private fun writeInto(img: FloatArray) = { x: Int, y: Int, cw: Int, ch: Int, rgb: FloatArray ->
        for (j in 0 until ch) for (i in 0 until cw) for (c in 0 until 3) img[((y + j) * w + x + i) * 3 + c] = rgb[(j * cw + i) * 3 + c]
    }

    @Test fun delayedRowWritesGiveTheSameImageAsWritingEverythingAtTheEnd() {
        // reference: read every tile from the untouched original, write all results afterwards
        val ref = image()
        val orig = image()
        val results = ArrayList<IntArray>()
        val stride = tile - 2 * overlap
        val store = ArrayList<Pair<IntArray, FloatArray>>()
        for (ty in 0 until (h + stride - 1) / stride) for (tx in 0 until (w + stride - 1) / stride) {
            val cx = tx * stride; val cy = ty * stride
            val cw = min(stride, w - cx); val ch = min(stride, h - cy)
            store += intArrayOf(cx, cy, cw, ch) to process(readFrom(orig)(cx - overlap, cy - overlap), cw, ch)
        }
        store.forEach { (r, d) -> writeInto(ref)(r[0], r[1], r[2], r[3], d) }

        val got = image()
        runBlocking { denoiseTiles(w, h, tile, overlap, readFrom(got), ::process, writeInto(got)) {} }
        for (i in got.indices) assertEquals("sample $i", ref[i], got[i], 0f)
        assertTrue("the image really changed", ref.indices.any { ref[it] != orig[it] })
    }

    @Test fun writingImmediatelyWouldHaveChangedTheResult() {
        // guards the test above: with no delay the overlaps see denoised pixels and the output differs
        val ref = image(); val orig = image()
        val stride = tile - 2 * overlap
        val store = ArrayList<Pair<IntArray, FloatArray>>()
        for (ty in 0 until (h + stride - 1) / stride) for (tx in 0 until (w + stride - 1) / stride) {
            val cx = tx * stride; val cy = ty * stride
            val cw = min(stride, w - cx); val ch = min(stride, h - cy)
            store += intArrayOf(cx, cy, cw, ch) to process(readFrom(orig)(cx - overlap, cy - overlap), cw, ch)
        }
        store.forEach { (r, d) -> writeInto(ref)(r[0], r[1], r[2], r[3], d) }
        val eager = image()
        for (ty in 0 until (h + stride - 1) / stride) for (tx in 0 until (w + stride - 1) / stride) {
            val cx = tx * stride; val cy = ty * stride
            val cw = min(stride, w - cx); val ch = min(stride, h - cy)
            writeInto(eager)(cx, cy, cw, ch, process(readFrom(eager)(cx - overlap, cy - overlap), cw, ch))
        }
        assertTrue(ref.indices.any { ref[it] != eager[it] })
    }

    @Test fun nothingIsHeldForMoreThanTwoRows() {
        val written = ArrayList<Int>()
        val q = RowDelayedWriter<Int> { written += it }
        var maxPending = 0
        for (row in 0 until 10) {
            for (t in 0 until 7) { q.add(row * 10 + t); maxPending = max(maxPending, q.pending) }
            q.rowRead()
            // row n - 1 is written only after row n was read, and row n itself is still held
            assertEquals(max(0, row) * 7, written.size)
        }
        q.finish()
        assertEquals(70, written.size)
        assertEquals(70, written.toSet().size)   // each result written exactly once
        assertTrue("held at most two rows, held $maxPending", maxPending <= 14)
        assertEquals(0, q.pending)
    }

    @Test fun progressReachesOne() {
        val seen = ArrayList<Float>()
        val img = image()
        runBlocking { denoiseTiles(w, h, tile, overlap, readFrom(img), ::process, writeInto(img)) { seen += it } }
        assertEquals(1f, seen.last(), 0f)
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
    }
}
