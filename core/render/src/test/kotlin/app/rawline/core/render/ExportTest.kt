package app.rawline.core.render

import org.junit.Assert.assertEquals
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

}
