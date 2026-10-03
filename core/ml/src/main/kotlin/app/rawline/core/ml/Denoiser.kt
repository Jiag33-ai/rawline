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

    suspend fun run(handle: Long, amountPercent: Float, onProgress: (Float) -> Unit): Boolean {
        if (!store.ensure(Models.DENOISE)) return false
        val info = Native.rawInfo(handle)
        val w = info[0]; val h = info[1]
        val tile = 256; val overlap = 32; val stride = tile - 2 * overlap
        val amount = (amountPercent / 100f).coerceIn(0f, 1f)
        val nx = (w + stride - 1) / stride; val ny = (h + stride - 1) / stride
        val total = nx * ny
        var done = 0
        val t0 = System.nanoTime()
        val inBuf = TfModel.floats(tile * tile * 3)
        val outBuf = TfModel.floats(tile * tile * 3)
        val disp = FloatArray(tile * tile * 3)
        val tmp = FloatArray(3)
        // Results are written after all tiles are read, so overlaps never see already denoised pixels.
        class Out(val x: Int, val y: Int, val w: Int, val h: Int, val rgb: FloatArray)
        val results = ArrayList<Out>()
        for (ty in 0 until ny) for (tx in 0 until nx) {
            coroutineContext.ensureActive()
            val cx = tx * stride; val cy = ty * stride            // core origin
            val x0 = cx - overlap; val y0 = cy - overlap          // tile origin (edges are clamped by rawRead)
            val lin = Native.rawRead(handle, x0, y0, tile, tile)
            for (i in 0 until tile * tile) {
                ColorSpaces.workingToDisplay(lin[i * 3], lin[i * 3 + 1], lin[i * 3 + 2], tmp)
                disp[i * 3] = tmp[0]; disp[i * 3 + 1] = tmp[1]; disp[i * 3 + 2] = tmp[2]
            }
            inBuf.rewind(); inBuf.asFloatBuffer().put(disp)
            outBuf.rewind()
            model.value.run(arrayOf(inBuf), mapOf(0 to outBuf))
            outBuf.rewind()
            val den = FloatArray(tile * tile * 3); outBuf.asFloatBuffer().get(den)
            // delta = denoised - original, keep only its fine detail
            val chan = Array(3) { c -> FloatArray(tile * tile) { den[it * 3 + c] - disp[it * 3 + c] } }
            val low = Array(3) { c -> GuidedFilter.box(chan[c], tile, tile, 8) }
            val cw = min(stride, w - cx); val ch = min(stride, h - cy)
            val outLin = FloatArray(cw * ch * 3)
            for (y in 0 until ch) for (x in 0 until cw) {
                val i = (y + overlap) * tile + (x + overlap)
                val r = (disp[i * 3] + amount * (chan[0][i] - low[0][i])).coerceIn(0f, 1f)
                val g = (disp[i * 3 + 1] + amount * (chan[1][i] - low[1][i])).coerceIn(0f, 1f)
                val b = (disp[i * 3 + 2] + amount * (chan[2][i] - low[2][i])).coerceIn(0f, 1f)
                ColorSpaces.displayToWorking(r, g, b, tmp)
                // Keep the original where nothing changed, to avoid rounding drift through the curve
                val o = (y * cw + x) * 3
                val oi = i
                val changed = kotlin.math.abs(chan[0][oi] - low[0][oi]) + kotlin.math.abs(chan[1][oi] - low[1][oi]) + kotlin.math.abs(chan[2][oi] - low[2][oi])
                if (changed < 1e-4f) { outLin[o] = lin[oi * 3]; outLin[o + 1] = lin[oi * 3 + 1]; outLin[o + 2] = lin[oi * 3 + 2] }
                else { outLin[o] = tmp[0]; outLin[o + 1] = tmp[1]; outLin[o + 2] = tmp[2] }
            }
            results.add(Out(cx, cy, cw, ch, outLin))
            done++
            onProgress(done / total.toFloat())
        }
        results.forEach { Native.rawWrite(handle, it.x, it.y, it.w, it.h, it.rgb) }
        PerfLog.record("denoise_total_ms (${w}x$h)", (System.nanoTime() - t0) / 1_000_000)
        return true
    }
}
