package app.rawline.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import app.rawline.core.nativelib.Native
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class PreviewResult(
    val bitmap: Bitmap, val exif: ExifSummary?, val parseMs: Long, val readMs: Long, val decodeMs: Long,
    val width: Int, val height: Int, val previewOffset: Long = 0, val previewLength: Int = 0, val ifdOrientation: Int = 1,
)

class ExifSummary(
    val camera: String?, val lens: String?, val iso: Int, val shutter: Double,
    val aperture: Double, val focal: Double, val takenAt: Long, val jpegOrientation: Int,
)

/** Decodes the embedded JPEG of a RAW (never the raw data) or a plain image file. */
object PreviewDecoder {

    /**
     * @param longEdge cap for the longest edge of the result; the source is never upscaled.
     * @param software true when the pixels must be readable (thumbnails, orientation fixes).
     * @param wantExif parse EXIF from the JPEG bytes (used by the indexer).
     */
    fun decode(context: Context, p: Photo, longEdge: Int, software: Boolean, wantExif: Boolean): PreviewResult? {
        val uri = Uri.parse(p.uri)
        return if (p.kind == Kind.RAW) decodeRaw(context, uri, p, longEdge, software, wantExif)
        else decodeImage(context, uri, p, longEdge, software, wantExif)
    }

    /** EXIF of a plain image without decoding any pixels. */
    fun readExifOnly(context: Context, uri: Uri): ExifSummary? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { readExif(it, 1) } }.getOrNull()

    private fun decodeRaw(context: Context, uri: Uri, p: Photo, longEdge: Int, software: Boolean, wantExif: Boolean): PreviewResult? {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val t0 = System.nanoTime()
            var off = p.previewOffset
            var len = p.previewLength
            var ori = p.orientation
            if (len <= 0) {
                val info = Native.findPreview(pfd.fd) ?: return null
                off = info[0]; len = info[1].toInt(); ori = info[2].toInt()
            }
            val t1 = System.nanoTime()
            val bytes = Native.readBytes(pfd.fd, off, len) ?: return null
            val t2 = System.nanoTime()
            val exif = if (wantExif) readExif(ByteArrayInputStream(bytes), ori) else null
            val jpegOri = exif?.jpegOrientation ?: runCatching {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
            }.getOrDefault(0)
            // The decoder applies the JPEG's own EXIF orientation; only add the IFD0 one when the JPEG has none.
            val extra = if (jpegOri <= 1 && ori > 1) ori else 1
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            var bmp = decodeSource(src, longEdge, software || extra > 1) ?: return null
            if (extra > 1) bmp = applyOrientation(bmp, extra)
            val t3 = System.nanoTime()
            return PreviewResult(bmp, exif, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000,
                bmp.width, bmp.height, off, len, ori)
        }
        return null
    }

    private fun decodeImage(context: Context, uri: Uri, p: Photo, longEdge: Int, software: Boolean, wantExif: Boolean): PreviewResult? {
        val t0 = System.nanoTime()
        val exif = if (wantExif) context.contentResolver.openInputStream(uri)?.use { readExif(it, 1) } else null
        val t1 = System.nanoTime()
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = decodeSource(src, longEdge, software) ?: return null
        val t2 = System.nanoTime()
        return PreviewResult(bmp, exif, 0, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, bmp.width, bmp.height)
    }

    private fun decodeSource(src: ImageDecoder.Source, longEdge: Int, software: Boolean): Bitmap? =
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = longEdge.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    fun applyOrientation(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }


    private fun readExif(stream: java.io.InputStream, ifdOrientation: Int): ExifSummary? = runCatching {
        val e = ExifInterface(stream)
        val taken = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let(::parseExifDate) ?: 0L
        ExifSummary(
            camera = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE)?.trim(), e.getAttribute(ExifInterface.TAG_MODEL)?.trim()).joinToString(" ").ifEmpty { null },
            lens = e.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim(),
            iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0),
            shutter = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0),
            aperture = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0),
            focal = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0),
            takenAt = taken,
            jpegOrientation = e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0),
        )
    }.getOrNull()

    private fun parseExifDate(s: String): Long = runCatching {
        val f = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
        f.parse(s)?.time ?: 0L
    }.getOrDefault(0L)
}
