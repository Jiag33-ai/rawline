package app.rawline.core.studio.render

import android.graphics.Bitmap
import android.graphics.ColorSpace
import app.rawline.core.render.IccProfiles
import app.rawline.core.studio.model.ColourSpace
import app.rawline.core.studio.model.Flatten
import app.rawline.core.studio.model.PngWriter
import java.io.OutputStream

/** Where the rows of a JPEG go. The Android one holds one opaque bitmap; the tests use a plain array. Rows arrive in order from the top as opaque ARGB ints. */
interface JpegCanvas {
    fun putRows(argb: IntArray, y: Int, rows: Int)
    fun compress(out: OutputStream, quality: Int): Boolean
    fun release()
}

/** One opaque ARGB_8888 bitmap (48 MB at 12 MP, decision D5). A Display P3 document gets a P3 bitmap, so the platform encoder embeds the P3 profile. */
class BitmapJpegCanvas(private val w: Int, private val h: Int, space: ColourSpace) : JpegCanvas {
    private val bmp: Bitmap = if (space == ColourSpace.DISPLAY_P3) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
    else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    override fun putRows(argb: IntArray, y: Int, rows: Int) = bmp.setPixels(argb, 0, w, 0, y, w, rows)
    override fun compress(out: OutputStream, quality: Int) = bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    override fun release() = bmp.recycle()
}

enum class FlattenFormat(val ext: String, val mime: String) { JPEG("jpg", "image/jpeg"), PNG("png", "image/png") }

/**
 * Flatten export (spec 2.18 S1): the canvas rendered by the compositor in full-width strips of at most 4 megapixels ([Flatten]), written as a PNG by the streaming
 * [PngWriter] (exact colour under partial alpha, no bitmap) or as a JPEG (transparent areas become white). Worker thread only: it waits for the GPU.
 */
class StudioExporter(private val session: StudioSession, private val perf: StudioPerf = StudioPerf.None, private val jpeg: (Int, Int, ColourSpace) -> JpegCanvas = ::BitmapJpegCanvas, private val nanos: () -> Long = System::nanoTime, private val stripRows: Int? = null) {
    enum class Result { DONE, CANCELLED, FAILED }

    /** Writes the picture into [out] (not closed). [progress] reports 0 to 1 after each strip. A cancelled or failed run leaves [out] incomplete: the caller deletes what it made. */
    fun flatten(snap: ExportSnapshot, format: FlattenFormat, quality: Int, out: OutputStream, cancelled: () -> Boolean, progress: (Float) -> Unit): Result {
        val t0 = nanos()
        val w = snap.width; val h = snap.height
        val p3 = snap.colourSpace == ColourSpace.DISPLAY_P3
        val png = if (format == FlattenFormat.PNG) PngWriter(out, w, h, if (p3) IccProfiles.displayP3() else null) else null
        val canvas = if (format == FlattenFormat.JPEG) jpeg(w, h, snap.colourSpace) else null
        var ints = IntArray(0)
        try {
            val ok = Flatten.run(w, h,
                render = { y, rows -> session.renderStrip(snap, y, rows) ?: throw StripFailed() },
                onStrip = { y, rows, px ->
                    if (png != null) png.writeRows(px, rows)
                    else {
                        Flatten.matteOverWhite(px)
                        if (ints.size != w * rows) ints = IntArray(w * rows)
                        var o = 0
                        for (i in ints.indices) { ints[i] = (255 shl 24) or ((px[o].toInt() and 255) shl 16) or ((px[o + 1].toInt() and 255) shl 8) or (px[o + 2].toInt() and 255); o += 4 }
                        canvas!!.putRows(ints, y, rows)
                    }
                    progress((y + rows).toFloat() / h)
                },
                cancelled = cancelled, stripRows = stripRows ?: Flatten.stripRows(w, h))
            if (!ok) return Result.CANCELLED
            png?.finish()
            if (canvas != null && !canvas.compress(out, quality)) return Result.FAILED
            perf.record("studio_export_ms", (nanos() - t0) / 1_000_000)
            return Result.DONE
        } catch (e: StripFailed) {
            perf.error("studio export: the GPU could not render a strip")
            return Result.FAILED
        } finally {
            canvas?.release()
        }
    }

    private class StripFailed : RuntimeException()

    /** A JPEG of the whole canvas, [maxEdge] pixels on the long side, over white, for the Studio home. Null when it could not be made. */
    fun thumbnailJpeg(snap: ExportSnapshot, maxEdge: Int = 512, quality: Int = 80, timeoutMs: Long = 3_000): ByteArray? {
        val (w, h, px) = session.renderThumbnail(snap, maxEdge, timeoutMs) ?: return null
        Flatten.matteOverWhite(px)
        val ints = IntArray(w * h)
        var o = 0
        for (i in ints.indices) { ints[i] = (255 shl 24) or ((px[o].toInt() and 255) shl 16) or ((px[o + 1].toInt() and 255) shl 8) or (px[o + 2].toInt() and 255); o += 4 }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            bmp.setPixels(ints, 0, w, 0, 0, w, h)
            val bos = java.io.ByteArrayOutputStream()
            if (bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)) bos.toByteArray() else null
        } finally { bmp.recycle() }
    }
}
