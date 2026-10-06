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
class StudioExporter(private val session: StudioSession, private val perf: StudioPerf = StudioPerf.None, private val jpeg: (Int, Int, ColourSpace) -> JpegCanvas = ::BitmapJpegCanvas, private val nanos: () -> Long = System::nanoTime, private val stripRows: Int? = null,
    /** How long one export waits in total for the app to come back to the foreground (review R2, decision D4), and how it sleeps (a test passes a fake). */
    private val foregroundWaitMs: Long = 10 * 60_000L, private val sleep: (Long) -> Unit = { Thread.sleep(it) }) {
    /** [BACKGROUND_TIMEOUT]: the app stayed in the background for the whole waiting limit; the file is incomplete like any other failure. */
    enum class Result { DONE, CANCELLED, FAILED, BACKGROUND_TIMEOUT }

    /** Writes the picture into [out] (not closed). [progress] reports 0 to 1 after each strip. A cancelled or failed run leaves [out] incomplete: the caller deletes what it made. */
    fun flatten(snap: ExportSnapshot, format: FlattenFormat, quality: Int, out: OutputStream, cancelled: () -> Boolean, progress: (Float) -> Unit): Result {
        val t0 = nanos(); waitedMs = 0L
        val w = snap.width; val h = snap.height
        val p3 = snap.colourSpace == ColourSpace.DISPLAY_P3
        val png = if (format == FlattenFormat.PNG) PngWriter(out, w, h, if (p3) IccProfiles.displayP3() else null) else null
        val canvas = if (format == FlattenFormat.JPEG) jpeg(w, h, snap.colourSpace) else null
        var ints = IntArray(0)
        try {
            val ok = Flatten.run(w, h,
                render = { y, rows -> strip(snap, y, rows, cancelled) },
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
        } catch (e: BackgroundTimeout) {
            perf.error("studio export: the app stayed in the background for ${foregroundWaitMs / 1000} s")
            return Result.BACKGROUND_TIMEOUT
        } catch (e: StripCancelled) {
            return Result.CANCELLED
        } catch (e: StripFailed) {
            perf.error("studio export: the GPU could not render a strip")
            return Result.FAILED
        } finally {
            canvas?.release()
        }
    }

    private class StripFailed : RuntimeException()
    private class StripCancelled : RuntimeException()
    private class BackgroundTimeout : RuntimeException()

    private var waitedMs = 0L

    /**
     * One strip. While the app is in the background the GPU does not run (the view is paused), so the export waits for the foreground in 1 s steps, up to [foregroundWaitMs]
     * in all, honouring cancel. A strip that fails because the app went to the background meanwhile is tried once more; any other failure ends the export.
     */
    private fun strip(snap: ExportSnapshot, y: Int, rows: Int, cancelled: () -> Boolean): ByteArray {
        for (attempt in 0..1) {
            while (session.isBackgrounded()) {
                if (cancelled()) throw StripCancelled()
                if (waitedMs >= foregroundWaitMs) throw BackgroundTimeout()
                sleep(1_000); waitedMs += 1_000
            }
            session.renderStrip(snap, y, rows)?.let { return it }
            if (!session.isBackgrounded()) break
        }
        throw StripFailed()
    }

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
