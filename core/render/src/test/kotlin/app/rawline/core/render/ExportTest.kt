package app.rawline.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExportTest {
    @Test fun settingsSurviveJson() {
        val s = ExportSettings(ExportFormat.TIFF16, 77, 2048, SharpenFor.PRINT, SharpenAmount.HIGH, ColorSpaceOut.DISPLAY_P3, MetadataMode.COPYRIGHT, "Jai", "{date}_{name}", "content://tree/x")
        assertEquals(s, ExportSettings.fromJson(s.toJson()))
    }

    @Test fun noDestinationStaysNull() {
        assertNull(ExportSettings.fromJson(ExportSettings().toJson()).destination)
    }

    @Test fun brokenJsonFallsBackToDefaults() {
        assertEquals(ExportSettings(), ExportSettings.fromJson("{not json"))
        assertEquals(ExportSettings(), ExportSettings.fromJson(null))
    }

    @Test fun tiffHeaderMatchesPixelData() {
        val w = 5; val h = 3
        val out = ByteArrayOutputStream()
        val t = Tiff16Writer(out, w, h, "Jai")
        t.writeRows(ShortArray(w * h * 3), h)
        t.finish()
        val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4949, b.getShort(0).toInt() and 0xFFFF)
        assertEquals(42, b.getShort(2).toInt())
        val n = b.getShort(8).toInt()
        var stripOffset = -1; var stripBytes = -1
        for (i in 0 until n) {
            val e = 10 + i * 12
            when (b.getShort(e).toInt() and 0xFFFF) { 273 -> stripOffset = b.getInt(e + 8); 279 -> stripBytes = b.getInt(e + 8) }
        }
        assertEquals(w * h * 6, stripBytes)
        assertEquals(out.size(), stripOffset + stripBytes)   // pixel data runs exactly to the end of the file
    }

    @Test(expected = IllegalStateException::class)
    fun tiffFinishDetectsMissingRows() {
        val t = Tiff16Writer(ByteArrayOutputStream(), 4, 4, null)
        t.writeRows(ShortArray(4 * 2 * 3), 2)
        t.finish()
    }

    // ---- ICC profiles and the colour space tag (AE-018) ----

    private class Icc(val b: ByteBuffer) {
        val tags = HashMap<String, Pair<Int, Int>>()   // signature -> offset, size
        init {
            val n = b.getInt(128)
            for (i in 0 until n) {
                val o = 132 + i * 12
                val sig = String(ByteArray(4) { b.get(o + it) }, Charsets.US_ASCII)
                tags[sig] = b.getInt(o + 4) to b.getInt(o + 8)
            }
        }
        fun xyz(sig: String): DoubleArray { val o = tags.getValue(sig).first; return DoubleArray(3) { b.getInt(o + 8 + it * 4) / 65536.0 } }
        fun curve(sig: String): IntArray { val o = tags.getValue(sig).first; val n = b.getInt(o + 8); return IntArray(n) { b.getShort(o + 12 + it * 2).toInt() and 0xFFFF } }
    }

    private fun icc(bytes: ByteArray) = Icc(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))

    @Test fun iccHeaderAndTagTableAreValid() {
        for (bytes in listOf(IccProfiles.srgb(), IccProfiles.displayP3())) {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            assertEquals(bytes.size, b.getInt(0))
            assertEquals(0, bytes.size % 4)
            assertEquals("acsp", String(bytes, 36, 4, Charsets.US_ASCII))
            assertEquals("mntr", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals("RGB ", String(bytes, 16, 4, Charsets.US_ASCII))
            assertEquals("XYZ ", String(bytes, 20, 4, Charsets.US_ASCII))
            assertEquals(0x02400000, b.getInt(8))
            val p = icc(bytes)
            for (sig in listOf("desc", "cprt", "wtpt", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC")) {
                val (off, size) = p.tags.getValue(sig)
                assertEquals("$sig offset is 4 byte aligned", 0, off % 4)
                assertTrue("$sig lies inside the file", off >= 132 && off + size <= bytes.size)
            }
        }
    }

    @Test fun srgbProfileHasTheKnownD50Colorants() {
        val p = icc(IccProfiles.srgb())
        // the values every sRGB profile carries (Bradford adapted to D50)
        val r = p.xyz("rXYZ"); val g = p.xyz("gXYZ"); val bl = p.xyz("bXYZ")
        assertEquals(0.4361, r[0], 2e-3); assertEquals(0.2225, r[1], 2e-3); assertEquals(0.0139, r[2], 2e-3)
        assertEquals(0.3851, g[0], 2e-3); assertEquals(0.7169, g[1], 2e-3); assertEquals(0.0971, g[2], 2e-3)
        assertEquals(0.1431, bl[0], 2e-3); assertEquals(0.0606, bl[1], 2e-3); assertEquals(0.7142, bl[2], 2e-3)
    }

    @Test fun displayP3ProfileIsWiderThanSrgb() {
        val p = icc(IccProfiles.displayP3())
        val r = p.xyz("rXYZ"); val g = p.xyz("gXYZ"); val bl = p.xyz("bXYZ")
        // Apple's Display P3 profile: R 0.5151 0.2412 -0.0011, G 0.2919 0.6922 0.0419, B 0.1571 0.0666 0.7841
        assertEquals(0.5151, r[0], 2e-3); assertEquals(0.2412, r[1], 2e-3); assertEquals(-0.0011, r[2], 2e-3)
        assertEquals(0.2919, g[0], 2e-3); assertEquals(0.6922, g[1], 2e-3); assertEquals(0.0419, g[2], 2e-3)
        assertEquals(0.1571, bl[0], 2e-3); assertEquals(0.0666, bl[1], 2e-3); assertEquals(0.7841, bl[2], 2e-3)
        // it must differ from the sRGB profile, or a P3 export would not be tagged as P3
        assertTrue(p.xyz("gXYZ")[0] < icc(IccProfiles.srgb()).xyz("gXYZ")[0])
    }

    @Test fun colorantsAddUpToTheD50White() {
        for (bytes in listOf(IccProfiles.srgb(), IccProfiles.displayP3())) {
            val p = icc(bytes)
            val sum = DoubleArray(3) { p.xyz("rXYZ")[it] + p.xyz("gXYZ")[it] + p.xyz("bXYZ")[it] }
            for (i in 0 until 3) assertEquals(IccProfiles.d50[i], sum[i], 2e-3)
            val w = p.xyz("wtpt")
            for (i in 0 until 3) assertEquals(IccProfiles.d50[i], w[i], 1e-4)
        }
    }

    @Test fun iccCurveIsTheSrgbTransferFunction() {
        val c = icc(IccProfiles.srgb()).curve("rTRC")
        assertEquals(1024, c.size)
        assertEquals(0, c.first()); assertEquals(65535, c.last())
        for (i in 1 until c.size) assertTrue("monotone at $i", c[i] >= c[i - 1])
        // encoded 0.5 is linear 0.2140; check by interpolating the table
        val x = 0.5 * 1023; val i0 = x.toInt(); val v = c[i0] + (c[i0 + 1] - c[i0]) * (x - i0)
        assertEquals(0.2140, v / 65535.0, 1e-3)
        // all three channels share one curve (same table offset), as in the standard sRGB profile
        val t = icc(IccProfiles.srgb()).tags
        assertEquals(t.getValue("rTRC"), t.getValue("gTRC")); assertEquals(t.getValue("rTRC"), t.getValue("bTRC"))
    }

    @Test fun tiffCarriesTheProfileAndKeepsItsPixelOffsetsConsistent() {
        for (profile in listOf(IccProfiles.srgb(), IccProfiles.displayP3())) {
            val w = 7; val h = 2
            val out = ByteArrayOutputStream()
            val t = Tiff16Writer(out, w, h, "Jai", profile)
            val px = ShortArray(w * h * 3) { (it * 997).toShort() }
            t.writeRows(px, h)
            t.finish()
            val bytes = out.toByteArray()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = b.getShort(8).toInt()
            var iccOff = -1; var iccLen = -1; var type = -1; var strip = -1; var stripBytes = -1; var lastTag = -1
            for (i in 0 until n) {
                val e = 10 + i * 12
                val tag = b.getShort(e).toInt() and 0xFFFF
                assertTrue("tags ascend", tag > lastTag); lastTag = tag
                when (tag) { 34675 -> { type = b.getShort(e + 2).toInt(); iccLen = b.getInt(e + 4); iccOff = b.getInt(e + 8) }; 273 -> strip = b.getInt(e + 8); 279 -> stripBytes = b.getInt(e + 8) }
            }
            assertEquals(7, type)   // UNDEFINED
            assertEquals(profile.size, iccLen)
            assertTrue(profile.contentEquals(bytes.copyOfRange(iccOff, iccOff + iccLen)))
            assertEquals(0, iccOff % 2); assertEquals(0, strip % 2)
            assertEquals(w * h * 6, stripBytes)
            assertEquals(bytes.size, strip + stripBytes)
            // the samples are stored as given (little endian unsigned 16 bit), with no float or half conversion in the writer
            for (i in 0 until w * h * 3) assertEquals(px[i], b.getShort(strip + i * 2))
        }
    }

    @Test fun tiffWithoutProfileStillHasNoIccTag() {
        val out = ByteArrayOutputStream()
        val t = Tiff16Writer(out, 2, 2, null)
        t.writeRows(ShortArray(12), 2); t.finish()
        val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val tags = (0 until b.getShort(8).toInt()).map { b.getShort(10 + it * 12).toInt() and 0xFFFF }
        assertTrue(34675 !in tags)
    }

    @Test fun tiffWriterWritesALargeBandWithoutLosingSamples() {
        val w = 5000; val h = 3   // more than one internal write chunk
        val out = ByteArrayOutputStream()
        val t = Tiff16Writer(out, w, h, null)
        val px = ShortArray(w * h * 3) { (it * 31).toShort() }
        t.writeRows(px, h); t.finish()
        val bytes = out.toByteArray()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val strip = bytes.size - w * h * 6
        for (i in 0 until w * h * 3 step 997) assertEquals(px[i], b.getShort(strip + i * 2))
        assertEquals(px.last(), b.getShort(bytes.size - 2))
    }
}
