package app.rawline.core.render

import android.util.Half
import app.rawline.core.nativelib.Native
import kotlin.math.max
import kotlin.math.min

/**
 * Healed and removed areas, stored as premultiplied linear working-space pixels in source space at up to 3072 px on the
 * long edge. The shader lays this over the raw before any adjustment, so edits apply on top of the repair.
 * A CPU copy is kept so later patches can be composited over earlier ones.
 */
class HealOverlay(private val session: OverlaySink, srcW: Int, srcH: Int) {
    val w: Int
    val h: Int
    private val buf: ShortArray

    init {
        val s = min(1f, 3072f / max(srcW, srcH))
        w = max(16, (srcW * s).toInt()); h = max(16, (srcH * s).toInt())
        buf = ShortArray(w * h * 4)
        session.setOverlay(buf, w, h)
    }

    /**
     * Composites a patch over the overlay. [rgba] is straight (non premultiplied) display sRGB with alpha, size pw x ph, covering
     * the source rectangle [region] (normalised x, y, w, h).
     */
    fun apply(region: List<Float>, rgba: IntArray, pw: Int, ph: Int) {
        val x0 = (region[0] * w).toInt().coerceIn(0, w - 1); val y0 = (region[1] * h).toInt().coerceIn(0, h - 1)
        val x1 = ((region[0] + region[2]) * w).toInt().coerceIn(x0 + 1, w); val y1 = ((region[1] + region[3]) * h).toInt().coerceIn(y0 + 1, h)
        val rw = x1 - x0; val rh = y1 - y0
        val out = ShortArray(rw * rh * 4)
        val tmp = FloatArray(3)
        for (y in 0 until rh) {
            val fy = ((y + 0.5f) / rh * ph - 0.5f).coerceIn(0f, ph - 1f)
            val sy = fy.toInt().coerceAtMost(ph - 2).coerceAtLeast(0); val ty = fy - sy
            for (x in 0 until rw) {
                val fx = ((x + 0.5f) / rw * pw - 0.5f).coerceIn(0f, pw - 1f)
                val sx = fx.toInt().coerceAtMost(pw - 2).coerceAtLeast(0); val tx = fx - sx
                // bilinear on straight colour weighted by alpha
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (j in 0..1) for (i in 0..1) {
                    val px = rgba[min(sy + j, ph - 1) * pw + min(sx + i, pw - 1)]
                    val wgt = (if (i == 0) 1 - tx else tx) * (if (j == 0) 1 - ty else ty)
                    val pa = (px ushr 24) / 255f * wgt
                    a += pa; r += (px shr 16 and 255) / 255f * pa; g += (px shr 8 and 255) / 255f * pa; b += (px and 255) / 255f * pa
                }
                val o = (y * rw + x) * 4
                val di = ((y0 + y) * w + (x0 + x)) * 4
                if (a < 1e-4f) {
                    // keep what was there
                    out[o] = buf[di]; out[o + 1] = buf[di + 1]; out[o + 2] = buf[di + 2]; out[o + 3] = buf[di + 3]; continue
                }
                ColorSpaces.displayToWorking(r / a, g / a, b / a, tmp)
                val na = a.coerceIn(0f, 1f)
                val oldA = Half.toFloat(buf[di + 3])
                val keep = 1f - na
                val vals = floatArrayOf(tmp[0] * na + Half.toFloat(buf[di]) * keep, tmp[1] * na + Half.toFloat(buf[di + 1]) * keep, tmp[2] * na + Half.toFloat(buf[di + 2]) * keep, na + oldA * keep)
                for (k in 0 until 4) { val hv = Half.toHalf(vals[k]); buf[di + k] = hv; out[o + k] = hv }
            }
        }
        // upload row by row region at once
        session.updateOverlay(x0, y0, rw, rh, out)
    }

    fun clear() { buf.fill(0); session.setOverlay(buf, w, h) }
}

/** Where overlay pixels go: the live editor (GL thread queue) or an export engine. */
interface OverlaySink {
    fun setOverlay(rgbaHalf: ShortArray?, w: Int, h: Int)
    fun updateOverlay(x: Int, y: Int, w: Int, h: Int, rgbaHalf: ShortArray)
}
