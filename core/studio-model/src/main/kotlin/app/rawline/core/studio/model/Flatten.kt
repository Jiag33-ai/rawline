package app.rawline.core.studio.model

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Flatten export (spec 2.18 S1: JPEG and PNG). The picture is rendered in full-width strips so memory stays bounded whatever the canvas
 * size (a strip is at most 4 megapixels), the same idea as Develop's tiled export. Each strip is the compositor's straight RGBA8 for
 * `view = (0, y)` at zoom 1.
 */
object Flatten {
    const val MAX_STRIP_PIXELS = 4_000_000

    fun stripRows(w: Int, h: Int) = minOf(h, maxOf(16, MAX_STRIP_PIXELS / w))

    /** Strips as (y, rows) covering 0 until h exactly once. */
    fun strips(w: Int, h: Int, rows: Int = stripRows(w, h)): List<IntArray> {
        return (0 until h step rows).map { y -> intArrayOf(y, minOf(rows, h - y)) }
    }

    /** Renders every strip in order. Returns false when [cancelled] stopped it (nothing half written is the caller's to delete). */
    fun run(w: Int, h: Int, render: (y: Int, rows: Int) -> ByteArray, onStrip: (y: Int, rows: Int, rgba: ByteArray) -> Unit, cancelled: () -> Boolean = { false }, stripRows: Int = stripRows(w, h)): Boolean {
        for ((y, rows) in strips(w, h, stripRows)) {
            if (cancelled()) return false
            val px = render(y, rows)
            require(px.size == w * rows * 4) { "strip is ${px.size} bytes, expected ${w * rows * 4}" }
            onStrip(y, rows, px)
        }
        return true
    }

    /** JPEG has no alpha: transparent areas become white (the colour is straight, so c * a + 255 * (1 - a)). In place; alpha becomes 255. */
    fun matteOverWhite(rgba: ByteArray) {
        var i = 0
        while (i < rgba.size) {
            val a = rgba[i + 3].toInt() and 255
            if (a != 255) {
                for (c in 0..2) { val v = rgba[i + c].toInt() and 255; rgba[i + c] = ((v * a + 255 * (255 - a) + 127) / 255).toByte() }
                rgba[i + 3] = 255.toByte()
            }
            i += 4
        }
    }
}

/**
 * Streaming PNG writer for straight RGBA8 (colour type 6, filter Sub). It never builds a bitmap, so semi transparent pixels keep their exact
 * colour (Android's Bitmap.compress premultiplies first and loses it), and memory is one strip plus the deflater. Rows are written as strips arrive.
 */
class PngWriter(private val out: OutputStream, private val width: Int, private val height: Int, private val iccProfile: ByteArray? = null) {
    private val deflater = Deflater(4)
    private val buf = ByteArray(64 * 1024)
    private var rowsWritten = 0
    private var finished = false

    init {
        require(width > 0 && height > 0)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array())
        if (iccProfile != null) {   // iCCP: name, 0, compression method 0, deflated profile
            val d = Deflater(); d.setInput(iccProfile); d.finish()
            val z = java.io.ByteArrayOutputStream(); val b = ByteArray(4096); while (!d.finished()) z.write(b, 0, d.deflate(b)); d.end()
            chunk("iCCP", "ICC".toByteArray() + byteArrayOf(0, 0) + z.toByteArray())
        }
    }

    /** [rows] rows of straight RGBA8, in order from the top. */
    fun writeRows(rgba: ByteArray, rows: Int) {
        check(!finished && rowsWritten + rows <= height)
        val stride = width * 4
        val line = ByteArray(stride + 1)
        for (r in 0 until rows) {
            line[0] = 1   // Sub: each byte minus the byte one pixel to the left
            val o = r * stride
            for (i in 0 until stride) line[1 + i] = (rgba[o + i] - (if (i >= 4) rgba[o + i - 4] else 0)).toByte()
            deflater.setInput(line, 0, line.size)
            while (!deflater.needsInput()) { val n = deflater.deflate(buf); if (n > 0) chunk("IDAT", buf.copyOf(n)) else break }
        }
        rowsWritten += rows
    }

    fun finish() {
        check(rowsWritten == height) { "wrote $rowsWritten of $height rows" }
        deflater.finish()
        while (!deflater.finished()) { val n = deflater.deflate(buf); if (n > 0) chunk("IDAT", buf.copyOf(n)) }
        deflater.end()
        chunk("IEND", ByteArray(0))
        finished = true
        out.flush()
    }

    private fun chunk(type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array()); out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}
