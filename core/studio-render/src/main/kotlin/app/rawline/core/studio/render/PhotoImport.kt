package app.rawline.core.studio.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.RawPixels
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.sqrt

/** Picks a picture from the system picker and makes straight sRGB RGBA8 out of it (call off the main thread). */
object PhotoImport {
    /** Largest edge-fit scale (never above 1) that keeps the picture inside [maxW] x [maxH] and under [maxPixels]. */
    fun fitScale(w: Int, h: Int, maxW: Int, maxH: Int, maxPixels: Long = Document.MAX_PIXELS_S1): Double =
        minOf(1.0, maxW.toDouble() / w, maxH.toDouble() / h, sqrt(maxPixels.toDouble() / (w.toDouble() * h)))

    /** Null when the file cannot be read as a picture. */
    fun decode(context: Context, uri: Uri, maxW: Int = Document.MAX_EDGE, maxH: Int = Document.MAX_EDGE, maxPixels: Long = Document.MAX_PIXELS_S1): RawPixels? =
        decodeResult(context, uri, maxW, maxH, maxPixels).pixels

    /** The outcome of [decodeResult]: the pixels, or why there are none ([errorCode] is ImageDecoder.DecodeException.getError(): 1 source exception, 2 incomplete, 3 source error; null for other causes). */
    class Result(val pixels: RawPixels?, val errorCode: Int? = null, val tooLarge: Boolean = false)

    /** As [decode], but says why a file could not be read (BK-505). A picture too big for memory is reported as such instead of crashing. */
    fun decodeResult(context: Context, uri: Uri, maxW: Int = Document.MAX_EDGE, maxH: Int = Document.MAX_EDGE, maxPixels: Long = Document.MAX_PIXELS_S1): Result = try {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isUnpremultipliedRequired = true            // straight alpha: premultiplied bitmaps lose colour under low alpha
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val s = fitScale(info.size.width, info.size.height, maxW, maxH, maxPixels)
            if (s < 1.0) decoder.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt()))
        }
        Result(toPixels(bmp, maxW, maxH, maxPixels))
    } catch (e: ImageDecoder.DecodeException) { Result(null, errorCode = e.error)
    } catch (e: OutOfMemoryError) { Result(null, tooLarge = true)
    } catch (e: Exception) { Result(null) }

    /** The display name and MIME type the picker gave for [uri] (name is "" when unknown): what [app.rawline.core.studio.model.RawPick] needs. Reads the provider: call off the main thread. */
    fun describe(context: Context, uri: Uri): Pair<String, String?> {
        var name = ""
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0) ?: ""
            }
        }
        return name to runCatching { context.contentResolver.getType(uri) }.getOrNull()
    }

    private fun toPixels(bmp0: Bitmap, maxW: Int, maxH: Int, maxPixels: Long): RawPixels {
        var bmp = bmp0
        // the decoder may report the size before the EXIF turn: check what really came out and shrink once more if it is still too big
        val s = fitScale(bmp.width, bmp.height, maxW, maxH, maxPixels)
        if (s < 1.0) bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
        if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val buf = ByteBuffer.allocate(bmp.width * bmp.height * 4)
        bmp.copyPixelsToBuffer(buf)   // ARGB_8888 is stored as R, G, B, A bytes
        return RawPixels(bmp.width, bmp.height, buf.array())
    }
}
