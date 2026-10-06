package app.rawline.core.studio.render

import app.rawline.core.studio.model.BlendMode
import app.rawline.core.studio.model.ColourSpace
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.RawPixels
import app.rawline.core.studio.model.RefImage
import app.rawline.core.studio.model.RefLayer
import app.rawline.core.studio.model.ReferenceCompositor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater

private class ArrayJpeg(val w: Int, val h: Int) : JpegCanvas {
    val argb = IntArray(w * h)
    var released = false
    var quality = -1
    override fun putRows(argb: IntArray, y: Int, rows: Int) { System.arraycopy(argb, 0, this.argb, y * w, rows * w) }
    override fun compress(out: java.io.OutputStream, quality: Int): Boolean { this.quality = quality; out.write(byteArrayOf(1, 2, 3)); return true }
    override fun release() { released = true }
}

class StudioExporterTest {
    private val w = 60; private val h = 45

    private fun rnd(seed: Long, alpha: Boolean): RawPixels = RawPixels(w, h, ByteArray(w * h * 4).also { Random(seed).nextBytes(it); if (!alpha) for (i in 3 until it.size step 4) it[i] = 255.toByte() })

    /** Two layers with real transparency and a Multiply, so a flatten is more than a copy. */
    private fun harness(space: ColourSpace = ColourSpace.SRGB): Harness {
        val doc = Document("p1", "Test", w, h, colourSpace = space, layers = listOf(
            Layer.Pixel(LayerCommon("a", "A"), w, h),
            Layer.Pixel(LayerCommon("b", "B", blend = BlendMode.MULTIPLY, opacity = 80), w, h)), created = 1, modified = 1)
        val hn = Harness(doc = doc, pixels = mapOf("a" to rnd(1, false), "b" to rnd(2, true)))
        hn.s.start()
        return hn
    }

    private fun whole(hn: Harness, snap: ExportSnapshot): ByteArray {
        val refs = (0 until snap.layers.size / 6).map { i -> val o = i * 6; val t = hn.gl.gpu.tex[snap.layers[o].toInt()]!!; RefLayer(RefImage(t.w, t.h, t.rgba), snap.layers[o + 1], snap.layers[o + 2], snap.layers[o + 3], snap.layers[o + 4], BlendMode.entries.first { it.id == snap.layers[o + 5].toInt() }) }
        return ReferenceCompositor.render(refs, 0f, 0f, 1f, w, h)
    }

    /** Signature, every chunk CRC, IHDR, IDAT inflated and un-filtered (Sub); also reports whether an iCCP chunk was seen. */
    private fun decodePng(png: ByteArray): Triple<ByteArray, Boolean, Pair<Int, Int>> {
        val b = java.nio.ByteBuffer.wrap(png); val sig = ByteArray(8); b.get(sig)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), sig)
        var pw = 0; var ph = 0; val idat = ByteArrayOutputStream(); var icc = false
        while (b.remaining() > 0) {
            val len = b.getInt(); val t = ByteArray(4); b.get(t); val d = ByteArray(len); b.get(d); val crc = b.getInt()
            val c = CRC32(); c.update(t); c.update(d); assertEquals(c.value.toInt(), crc)
            when (String(t)) { "IHDR" -> { val bb = java.nio.ByteBuffer.wrap(d); pw = bb.getInt(); ph = bb.getInt() }; "IDAT" -> idat.write(d); "iCCP" -> icc = true }
        }
        val inf = Inflater(); inf.setInput(idat.toByteArray())
        val raw = ByteArray((pw * 4 + 1) * ph); var n = 0; while (n < raw.size) { val k = inf.inflate(raw, n, raw.size - n); if (k == 0) break; n += k }
        assertEquals(raw.size, n)
        val out = ByteArray(pw * ph * 4)
        for (y in 0 until ph) for (i in 0 until pw * 4) out[y * pw * 4 + i] = (raw[y * (pw * 4 + 1) + 1 + i] + (if (i >= 4) out[y * pw * 4 + i - 4] else 0)).toByte()
        return Triple(out, icc, pw to ph)
    }

    @Test fun aPngOfSeveralStripsIsExactlyTheWholeCompositeAndKeepsPartialAlphaColour() {
        val hn = harness(); val snap = hn.s.exportSnapshot()!!
        val bos = ByteArrayOutputStream(); val seen = ArrayList<Float>()
        val r = StudioExporter(hn.s, stripRows = 16).flatten(snap, FlattenFormat.PNG, 92, bos, { false }, { seen += it })
        assertEquals(StudioExporter.Result.DONE, r)
        val (px, icc, size) = decodePng(bos.toByteArray())
        assertEquals(w to h, size); assertFalse(icc)
        assertArrayEquals(whole(hn, snap), px)
        assertEquals(listOf(16f / h, 32f / h, 1f), seen)                  // 16, 16 and 13 rows
        assertTrue(hn.gl.gpu.lastRender!!.let { it.first == w && it.second == 13 })   // the last strip is the short one
    }

    @Test fun aDisplayP3DocumentEmbedsItsProfileInThePng() {
        val hn = harness(ColourSpace.DISPLAY_P3); val snap = hn.s.exportSnapshot()!!
        val bos = ByteArrayOutputStream()
        StudioExporter(hn.s).flatten(snap, FlattenFormat.PNG, 92, bos, { false }, {})
        assertTrue(decodePng(bos.toByteArray()).second)
    }

    @Test fun aJpegGetsOpaqueRowsWithWhereverTheCanvasWasTransparentWhite() {
        val hn = harness(); val snap = hn.s.exportSnapshot()!!
        val jpeg = ArrayJpeg(w, h)
        val r = StudioExporter(hn.s, jpeg = { _, _, _ -> jpeg }, stripRows = 20).flatten(snap, FlattenFormat.JPEG, 77, ByteArrayOutputStream(), { false }, {})
        assertEquals(StudioExporter.Result.DONE, r)
        assertEquals(77, jpeg.quality); assertTrue(jpeg.released)
        val src = whole(hn, snap)
        for (i in 0 until w * h) {
            val a = src[i * 4 + 3].toInt() and 255
            val expect = IntArray(3) { c -> val v = src[i * 4 + c].toInt() and 255; if (a == 255) v else (v * a + 255 * (255 - a) + 127) / 255 }
            val got = jpeg.argb[i]
            assertEquals(255, got ushr 24)
            assertEquals(expect[0], (got shr 16) and 255); assertEquals(expect[1], (got shr 8) and 255); assertEquals(expect[2], got and 255)
        }
    }

    @Test fun aFullyTransparentCanvasIsWhiteInAJpeg() {
        val doc = Document("p1", "T", 8, 8, layers = listOf(Layer.Pixel(LayerCommon("a", "A"), 8, 8)), created = 1, modified = 1)
        val hn = Harness(doc = doc); hn.s.start()
        val jpeg = ArrayJpeg(8, 8)
        StudioExporter(hn.s, jpeg = { _, _, _ -> jpeg }).flatten(hn.s.exportSnapshot()!!, FlattenFormat.JPEG, 90, ByteArrayOutputStream(), { false }, {})
        assertTrue(jpeg.argb.all { it == -1 })   // 0xFFFFFFFF
    }

    @Test fun cancelStopsBetweenStripsAndReportsIt() {
        val hn = harness(); val snap = hn.s.exportSnapshot()!!
        var strips = 0
        val r = StudioExporter(hn.s, stripRows = 10).flatten(snap, FlattenFormat.PNG, 92, ByteArrayOutputStream(), { strips >= 2 }, { strips++ })
        assertEquals(StudioExporter.Result.CANCELLED, r); assertEquals(2, strips)
    }

    @Test fun aGpuFailureIsAFailedExportNotACrash() {
        val hn = harness(); val snap = hn.s.exportSnapshot()!!
        hn.gl.gpu.renderFails = true
        val r = StudioExporter(hn.s, StudioPerf.None).flatten(snap, FlattenFormat.PNG, 92, ByteArrayOutputStream(), { false }, {})
        assertEquals(StudioExporter.Result.FAILED, r)
    }

    @Test fun theExportTimeIsReported() {
        val hn = harness(); val snap = hn.s.exportSnapshot()!!
        val names = ArrayList<String>()
        var t = 0L
        StudioExporter(hn.s, object : StudioPerf { override fun record(name: String, value: Long) { names += name }; override fun error(message: String) {} }, nanos = { t += 5_000_000; t })
            .flatten(snap, FlattenFormat.PNG, 92, ByteArrayOutputStream(), { false }, {})
        assertEquals(listOf("studio_export_ms"), names)
    }

    @Test fun hiddenLayersAreLeftOutOfTheSnapshotAndTheExport() {
        val hn = harness(); hn.s.setVisible("b", false)
        val snap = hn.s.exportSnapshot()!!
        assertEquals(6, snap.layers.size)
        assertEquals(w to h, snap.width to snap.height)
    }

    @Test fun thereIsNoSnapshotBeforeTheProjectIsOpenOrAfterItIsReleased() {
        val doc = Document("p1", "T", 8, 8, layers = listOf(Layer.Pixel(LayerCommon("a", "A"), 8, 8)), created = 1, modified = 1)
        val hn = Harness(doc = doc)
        assertNull(hn.s.exportSnapshot())          // start() has not run
        hn.s.start(); assertNotNull(hn.s.exportSnapshot())
        hn.s.release(); assertNull(hn.s.exportSnapshot())
    }

    @Test fun theThumbnailFitsTheLongEdgeAndKeepsTheShape() {
        val hn = harness(); val snap = hn.s.exportSnapshot()!!
        val (tw, th, px) = hn.s.renderThumbnail(snap, 30)!!
        assertEquals(30, tw); assertEquals(23, th)       // 60 x 45 at half size (22.5 rounds to 23)
        assertEquals(tw * th * 4, px.size)
        val (bw, bh, _) = hn.s.renderThumbnail(snap, 512)!!   // never enlarged
        assertEquals(w to h, bw to bh)
    }
}
