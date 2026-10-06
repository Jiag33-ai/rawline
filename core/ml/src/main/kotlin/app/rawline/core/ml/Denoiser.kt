package app.rawline.core.ml

import android.content.Context
import app.rawline.core.cache.PerfLog
import app.rawline.core.nativelib.Native
import app.rawline.core.render.ColorSpaces
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * AI denoise on a decoded raw, tile by tile (256 px with 32 px overlap). The model's output is only used for its fine detail
 * change: the low frequency part of (denoised - original) is removed, so tone and colour never shift, and the rest is added to
 * the original scaled by the amount. The result replaces the pixels in the decoded raw before it goes to the GPU.
 */
class Denoiser(private val context: Context, private val store: ModelStore) {
    private val model = lazy { TfModel(context, store.file("nafnet_denoise.tflite"), "denoise") }

    /** Frees the model's interpreter and GPU delegate (only if it was ever started). */
    fun release() { if (model.isInitialized()) model.value.release() }

    suspend fun run(handle: Long, amountPercent: Float, onProgress: (Float) -> Unit): Boolean {
        if (!store.ensure(Models.DENOISE)) return false
        val info = Native.rawInfo(handle)
        val w = info[0]; val h = info[1]
        val tile = TILE; val overlap = OVERLAP
        val amount = (amountPercent / 100f).coerceIn(0f, 1f)
        val t0 = System.nanoTime()
        val inBuf = TfModel.floats(tile * tile * 3)
        val outBuf = TfModel.floats(tile * tile * 3)
        // per tile scratch, allocated once (it used to be about 6 MB of arrays per tile, 4 GB of churn at 24 MP)
        val disp = FloatArray(tile * tile * 3)
        val den = FloatArray(tile * tile * 3)
        val chan = Array(3) { FloatArray(tile * tile) }
        val tmp = FloatArray(3)
        denoiseTiles(w, h, tile, overlap, read = { x0, y0 -> Native.rawRead(handle, x0, y0, tile, tile) }, process = { lin, cw, ch ->
            for (i in 0 until tile * tile) {
                ColorSpaces.workingToDisplay(lin[i * 3], lin[i * 3 + 1], lin[i * 3 + 2], tmp)
                disp[i * 3] = tmp[0]; disp[i * 3 + 1] = tmp[1]; disp[i * 3 + 2] = tmp[2]
            }
            inBuf.rewind(); inBuf.asFloatBuffer().put(disp)
            outBuf.rewind()
            model.value.run(arrayOf(inBuf), mapOf(0 to outBuf))
            outBuf.rewind()
            outBuf.asFloatBuffer().get(den)
            // delta = denoised - original, keep only its fine detail
            for (c in 0 until 3) { val ch2 = chan[c]; for (k in 0 until tile * tile) ch2[k] = den[k * 3 + c] - disp[k * 3 + c] }
            val low = Array(3) { c -> GuidedFilter.box(chan[c], tile, tile, 8) }
            val outLin = FloatArray(cw * ch * 3)
            for (y in 0 until ch) for (x in 0 until cw) {
                val i = (y + overlap) * tile + (x + overlap)
                val r = (disp[i * 3] + amount * (chan[0][i] - low[0][i])).coerceIn(0f, 1f)
                val g = (disp[i * 3 + 1] + amount * (chan[1][i] - low[1][i])).coerceIn(0f, 1f)
                val b = (disp[i * 3 + 2] + amount * (chan[2][i] - low[2][i])).coerceIn(0f, 1f)
                ColorSpaces.displayToWorking(r, g, b, tmp)
                // Keep the original where nothing changed, to avoid rounding drift through the curve
                val o = (y * cw + x) * 3
                val changed = kotlin.math.abs(chan[0][i] - low[0][i]) + kotlin.math.abs(chan[1][i] - low[1][i]) + kotlin.math.abs(chan[2][i] - low[2][i])
                if (changed < 1e-4f) { outLin[o] = lin[i * 3]; outLin[o + 1] = lin[i * 3 + 1]; outLin[o + 2] = lin[i * 3 + 2] }
                else { outLin[o] = tmp[0]; outLin[o + 1] = tmp[1]; outLin[o + 2] = tmp[2] }
            }
            outLin
        }, write = { x, y, cw, ch, rgb -> Native.rawWrite(handle, x, y, cw, ch, rgb) }, onProgress = onProgress)
        PerfLog.record("denoise_total_ms (${w}x$h)", (System.nanoTime() - t0) / 1_000_000)
        return true
    }

    companion object {
        const val TILE = 256
        const val OVERLAP = 32
    }
}

/**
 * Holds tile results back until no later tile can still read the pixels they will overwrite. Tiles are read row by row and a row of
 * tiles reaches [overlap] pixels into the row above it, so the results of row n are safe to write once row n + 1 has been read.
 * At most two rows of results are ever held, whatever the picture size (they used to be held for the whole picture).
 */
internal class RowDelayedWriter<T>(private val write: (T) -> Unit) {
    private var previous = ArrayList<T>()
    private var current = ArrayList<T>()
    fun add(item: T) { current.add(item) }
    /** Call when every tile of a row has been read. */
    fun rowRead() { previous.forEach(write); previous = current; current = ArrayList() }
    fun finish() { previous.forEach(write); current.forEach(write); previous = ArrayList(); current = ArrayList() }
    val pending: Int get() = previous.size + current.size
}

/**
 * The tile loop of the denoiser, without the model: for every tile of [tile] px (core = tile - 2 * overlap) it reads the tile with
 * [read] (edges are clamped by the reader), lets [process] turn it into the new core pixels (rgb floats, cw x ch) and writes them
 * back through a [RowDelayedWriter]. Overlaps therefore only ever see original pixels, exactly as if every result were written at the end.
 */
internal suspend fun denoiseTiles(
    w: Int, h: Int, tile: Int, overlap: Int,
    read: (x0: Int, y0: Int) -> FloatArray,
    process: (lin: FloatArray, cw: Int, ch: Int) -> FloatArray,
    write: (x: Int, y: Int, w: Int, h: Int, rgb: FloatArray) -> Unit,
    onProgress: (Float) -> Unit,
) {
    val stride = tile - 2 * overlap
    val nx = (w + stride - 1) / stride; val ny = (h + stride - 1) / stride
    val total = nx * ny
    var done = 0
    class Out(val x: Int, val y: Int, val w: Int, val h: Int, val rgb: FloatArray)
    val writer = RowDelayedWriter<Out> { write(it.x, it.y, it.w, it.h, it.rgb) }
    for (ty in 0 until ny) {
        for (tx in 0 until nx) {
            coroutineContext.ensureActive()
            val cx = tx * stride; val cy = ty * stride            // core origin
            val lin = read(cx - overlap, cy - overlap)            // tile origin
            val cw = min(stride, w - cx); val ch = min(stride, h - cy)
            writer.add(Out(cx, cy, cw, ch, process(lin, cw, ch)))
            done++
            onProgress(done / total.toFloat())
        }
        writer.rowRead()
    }
    writer.finish()
}
