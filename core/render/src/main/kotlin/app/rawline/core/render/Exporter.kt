package app.rawline.core.render

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.rawline.core.cache.MaskStore
import app.rawline.core.cache.PatchStore
import app.rawline.core.cache.PerfLog
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Kind
import app.rawline.core.model.MaskType
import app.rawline.core.model.Photo
import app.rawline.core.nativelib.Native
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ExportFormat(val ext: String, val mime: String) { JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), TIFF16("tif", "image/tiff") }
enum class ColorSpaceOut(val label: String) { SRGB("sRGB"), DISPLAY_P3("Display P3") }
enum class SharpenFor(val label: String) { NONE("None"), SCREEN("Screen"), PRINT("Print") }
enum class SharpenAmount(val label: String) { LOW("Low"), STANDARD("Standard"), HIGH("High") }
enum class MetadataMode(val label: String) { ALL("All"), COPYRIGHT("Copyright only"), NONE("None") }

data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 92,
    /** 0 = full size, otherwise the long edge in pixels */
    val longEdge: Int = 0,
    val sharpenFor: SharpenFor = SharpenFor.SCREEN,
    val sharpenAmount: SharpenAmount = SharpenAmount.STANDARD,
    val colorSpace: ColorSpaceOut = ColorSpaceOut.SRGB,
    val metadata: MetadataMode = MetadataMode.ALL,
    val copyright: String = "",
    val pattern: String = "{name}",
    val destination: String? = null,   // SAF tree uri
) {
    fun toJson(): String = org.json.JSONObject().put("format", format.name).put("quality", quality).put("longEdge", longEdge).put("sf", sharpenFor.name)
        .put("sa", sharpenAmount.name).put("cs", colorSpace.name).put("md", metadata.name).put("copyright", copyright).put("pattern", pattern)
        .put("dest", destination ?: org.json.JSONObject.NULL).toString()

    companion object {
        fun fromJson(s: String?): ExportSettings = runCatching {
            val o = org.json.JSONObject(s!!)
            ExportSettings(ExportFormat.valueOf(o.getString("format")), o.optInt("quality", 92), o.optInt("longEdge", 0), SharpenFor.valueOf(o.optString("sf", "SCREEN")),
                SharpenAmount.valueOf(o.optString("sa", "STANDARD")), ColorSpaceOut.valueOf(o.optString("cs", "SRGB")), MetadataMode.valueOf(o.optString("md", "ALL")),
                o.optString("copyright", ""), o.optString("pattern", "{name}"), if (o.isNull("dest")) null else o.getString("dest"))
        }.getOrDefault(ExportSettings())
    }
}

/** Renders a photo at export size with the same shader graph as the screen, in 2048 px tiles so memory stays bounded. */
class Exporter(
    private val context: Context,
    private val masks: MaskStore,
    private val patches: PatchStore,
    private val denoise: SourceHook? = null,
) {
    /**
     * Renders to a bitmap (JPEG/PNG) and returns it, or writes 16 bit TIFF to [tiff]. Must run on a worker thread.
     * @return output size, or null on failure
     */
    fun render(photo: Photo, recipe: EditRecipe, s: ExportSettings, tiff: OutputStream?, onProgress: (Float) -> Unit, cancelled: () -> Boolean = { false }): Result? {
        val t0 = System.nanoTime()
        val gl = OffscreenGl()
        var engine = 0L
        var handle = 0L
        try {
            gl.makeCurrent()
            engine = Native.engineCreate()
            Native.engineInit(engine)?.let { throw IllegalStateException("GPU init failed: $it") }
            Native.engineSetOutputSpace(engine, if (s.colorSpace == ColorSpaceOut.DISPLAY_P3) 1 else 0)

            handle = decode(photo)
            if (handle == 0L) throw IllegalStateException("Could not decode ${photo.name}")
            val info = Native.rawInfo(handle)
            if (recipe.detail.aiDenoise && denoise != null) {
                kotlinx.coroutines.runBlocking { denoise.invoke(handle, recipe.detail.aiDenoiseAmount) { onProgress(it * 0.4f) } }
            }
            if (!Native.engineSetSource(engine, handle)) throw IllegalStateException("GPU upload failed")
            handle = 0L

            // Mask layers (brush and AI) and heal overlay
            val layers = HashMap<String, Int>()
            recipe.masks.flatMap { it.components }.filter { it.type == MaskType.BITMAP }.mapNotNull { it.layerKey }.distinct().forEach { key ->
                if (layers.size >= P.MAX_LAYERS) return@forEach
                masks.load(key)?.let { (a, w, h) -> val idx = layers.size; Native.engineSetLayer(engine, idx, a, w, h); layers[key] = idx }
            }
            var overlayOn = false
            if (recipe.heals.isNotEmpty()) {
                val sink = object : OverlaySink {
                    override fun setOverlay(rgbaHalf: ShortArray?, w: Int, h: Int) = Native.engineSetOverlay(engine, rgbaHalf, w, h)
                    override fun updateOverlay(x: Int, y: Int, w: Int, h: Int, rgbaHalf: ShortArray) = Native.engineUpdateOverlay(engine, x, y, w, h, rgbaHalf)
                }
                val ov = HealOverlay(sink, info[0], info[1])
                recipe.heals.forEach { op ->
                    val key = op.patchKey ?: return@forEach
                    patches.load(key)?.let { (px, w, h) -> if (op.region.size == 4) ov.apply(op.region, px, w, h) }
                }
                overlayOn = true
            }
            val lens = LensProfiles.get(context).find(photo.lens, photo.focal.toFloat(), photo.aperture.toFloat())
            val params = RenderParams.build(recipe, info[2], layers, overlayOn = overlayOn, lens = lens)
            params[P.G_DETAIL] += outputSharpening(s)
            val size = Native.engineOutputSize(engine, params)
            var tw = size[0]; var th = size[1]
            if (s.longEdge in 1 until max(tw, th)) { val f = s.longEdge.toFloat() / max(tw, th); tw = max(1, (tw * f).roundToInt()); th = max(1, (th * f).roundToInt()) }

            val tile = 2048
            val tilesX = (tw + tile - 1) / tile; val tilesY = (th + tile - 1) / tile
            var done = 0
            if (s.format == ExportFormat.TIFF16) {
                val out = tiff ?: throw IllegalArgumentException("TIFF needs an output stream")
                val writer = Tiff16Writer(out, tw, th, s.copyright.takeIf { s.metadata != MetadataMode.NONE })
                for (ty in 0 until tilesY) {
                    val y0 = ty * tile; val bh = min(tile, th - y0)
                    val band = ShortArray(tw * bh * 3)
                    for (tx in 0 until tilesX) {
                        if (cancelled()) return null
                        val x0 = tx * tile; val bw = min(tile, tw - x0)
                        val half = ShortArray(bw * bh * 4)
                        if (!Native.engineRenderRegionHalf(engine, params, bw, bh, x0.toFloat() / tw, y0.toFloat() / th, bw.toFloat() / tw, bh.toFloat() / th, half)) throw IllegalStateException("Render failed")
                        for (y in 0 until bh) for (x in 0 until bw) {
                            val si = (y * bw + x) * 4; val di = (y * tw + x0 + x) * 3
                            for (c in 0 until 3) band[di + c] = (android.util.Half.toFloat(half[si + c]).coerceIn(0f, 1f) * 65535f + 0.5f).toInt().toShort()
                        }
                        done++; onProgress(0.4f + 0.6f * done / (tilesX * tilesY))
                    }
                    writer.writeRows(band, bh)
                }
                writer.finish()
                PerfLog.record("export_ms (${tw}x$th tiff)", (System.nanoTime() - t0) / 1_000_000)
                return Result(null, tw, th)
            }
            // Raw RGBA copy (no colour conversion): the pixels are already in the chosen output space.
            val all = ByteBuffer.allocateDirect(tw * th * 4)
            for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
                if (cancelled()) return null
                val x0 = tx * tile; val y0 = ty * tile
                val bw = min(tile, tw - x0); val bh = min(tile, th - y0)
                val buf = ByteArray(bw * bh * 4)
                if (!Native.engineRenderRegion(engine, params, bw, bh, x0.toFloat() / tw, y0.toFloat() / th, bw.toFloat() / tw, bh.toFloat() / th, buf)) throw IllegalStateException("Render failed")
                for (y in 0 until bh) { all.position(((y0 + y) * tw + x0) * 4); all.put(buf, y * bw * 4, bw * 4) }
                done++; onProgress(0.4f + 0.6f * done / (tilesX * tilesY))
            }
            all.rewind()
            val space = android.graphics.ColorSpace.get(if (s.colorSpace == ColorSpaceOut.DISPLAY_P3) android.graphics.ColorSpace.Named.DISPLAY_P3 else android.graphics.ColorSpace.Named.SRGB)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888, false, space)
            bmp.copyPixelsFromBuffer(all)
            PerfLog.record("export_render_ms (${tw}x$th)", (System.nanoTime() - t0) / 1_000_000)
            return Result(bmp, tw, th)
        } finally {
            if (handle != 0L) Native.freeRaw(handle)
            if (engine != 0L) Native.engineDestroy(engine)
            gl.release()
        }
    }

    class Result(val bitmap: Bitmap?, val width: Int, val height: Int)

    private fun outputSharpening(s: ExportSettings): Float {
        val base = when (s.sharpenFor) { SharpenFor.NONE -> return 0f; SharpenFor.SCREEN -> 18f; SharpenFor.PRINT -> 32f }
        return base * when (s.sharpenAmount) { SharpenAmount.LOW -> 0.6f; SharpenAmount.STANDARD -> 1f; SharpenAmount.HIGH -> 1.6f }
    }

    private fun decode(photo: Photo): Long {
        val uri = Uri.parse(photo.uri)
        if (photo.kind == Kind.RAW) return context.contentResolver.openFileDescriptor(uri, "r")?.use { Native.decodeRaw(it.fd, false) } ?: 0L
        val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ -> d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
        val buf = ByteBuffer.allocate(bmp.byteCount); bmp.copyPixelsToBuffer(buf)
        return Native.rawFromRgba(buf.array(), bmp.width, bmp.height)
    }
}

/** Minimal uncompressed 16 bit RGB TIFF (little endian, one strip per call). */
class Tiff16Writer(private val out: OutputStream, private val w: Int, private val h: Int, private val copyright: String?) {
    private var rowsWritten = 0

    init {
        // Header + IFD are written up front: the pixel data comes right after them.
        val entries = 12 + (if (copyright.isNullOrEmpty()) 0 else 1)
        val ifdSize = 2 + entries * 12 + 4
        val extraStart = 8 + ifdSize
        val copy = copyright?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.US_ASCII)?.let { it + 0 }
        val bitsOffset = extraStart
        val resOffset = bitsOffset + 6
        val copyOffset = resOffset + 16
        val dataStart = copyOffset + (copy?.size ?: 0)
        val b = ByteBuffer.allocate(dataStart).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        b.putShort(entries.toShort())
        fun e(tag: Int, type: Int, count: Int, value: Int) { b.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value) }
        e(256, 4, 1, w); e(257, 4, 1, h); e(258, 3, 3, bitsOffset); e(259, 3, 1, 1); e(262, 3, 1, 2)
        e(273, 4, 1, dataStart); e(277, 3, 1, 3); e(278, 4, 1, h); e(279, 4, 1, w * h * 6)
        e(282, 5, 1, resOffset); e(283, 5, 1, resOffset + 8); e(284, 3, 1, 1)
        if (copy != null) e(33432, 2, copy.size, copyOffset)
        b.putInt(0)
        b.putShort(16).putShort(16).putShort(16)
        b.putInt(300).putInt(1).putInt(300).putInt(1)
        if (copy != null) b.put(copy)
        out.write(b.array())
    }

    fun writeRows(rgb: ShortArray, rows: Int) {
        val bb = ByteBuffer.allocate(rgb.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(rgb)
        out.write(bb.array())
        rowsWritten += rows
    }

    fun finish() { out.flush(); check(rowsWritten == h) { "TIFF rows $rowsWritten of $h" } }

}
