package app.rawline.feature.editor

import android.graphics.Bitmap
import app.rawline.core.model.Adjust
import app.rawline.core.render.EditorSession
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

object AutoTools {

    private fun toLinear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    /** One tap Light settings from the histogram of the unedited image. */
    fun autoLight(s: EditorSession.ImageStats): Adjust {
        val target = 0.45f.pow(2.2f)
        val med = s.p50.coerceAtLeast(0.02f).pow(2.2f)
        val ev = (log2(target / med) * 0.7f).coerceIn(-2f, 2f)
        // percentiles after the exposure shift (rough, in display units)
        val gain = 2f.pow(ev)
        val hi = (s.p99.pow(2.2f) * gain).pow(1f / 2.2f)
        val lo = (s.p5.pow(2.2f) * gain).pow(1f / 2.2f)
        return Adjust(
            exposure = (ev * 100).roundToInt() / 100f,
            contrast = 8f,
            highlights = (-(hi - 0.85f) * 350f).coerceIn(-100f, 0f).roundToInt().toFloat(),
            shadows = ((0.2f - lo) * 300f).coerceIn(0f, 80f).roundToInt().toFloat(),
            whites = ((0.96f - hi) * 120f).coerceIn(-40f, 40f).roundToInt().toFloat(),
            blacks = (-(s.p1 - 0.03f) * 300f).coerceIn(-60f, 30f).roundToInt().toFloat(),
        )
    }

    /** Gray world white balance relative to the current temperature and tint. Returns (temp, tint). */
    fun autoWb(s: EditorSession.ImageStats): Pair<Float, Float> {
        val r = toLinear(s.meanR).coerceAtLeast(1e-4f); val g = toLinear(s.meanG).coerceAtLeast(1e-4f); val b = toLinear(s.meanB).coerceAtLeast(1e-4f)
        val temp = (log2(b / r) / 0.024f).coerceIn(-100f, 100f)
        val rr = r * 2f.pow(0.012f * temp); val bb = b * 2f.pow(-0.012f * temp)
        val tint = (-log2(((rr + bb) / 2f) / g) / 0.006f).coerceIn(-100f, 100f)
        return temp to tint
    }

    /** WB from a sampled display colour of the picked grey point, relative to the current temp/tint. */
    fun wbFromSample(rgb: FloatArray, temp: Float, tint: Float): Pair<Float, Float> {
        val r = toLinear(rgb[0]).coerceAtLeast(1e-4f); val g = toLinear(rgb[1]).coerceAtLeast(1e-4f); val b = toLinear(rgb[2]).coerceAtLeast(1e-4f)
        val dt = log2(b / r) / 0.024f
        val rr = r * 2f.pow(0.012f * dt); val bb = b * 2f.pow(-0.012f * dt)
        val dtint = -log2(((rr + bb) / 2f) / g) / 0.006f
        return (temp + dt).coerceIn(-100f, 100f) to (tint + dtint).coerceIn(-100f, 100f)
    }

    /**
     * Finds how far the picture is tilted from level. Edge directions near horizontal or vertical vote for an angle;
     * returns the straighten angle in degrees to apply (positive turns the image clockwise as the slider does).
     */
    fun autoLevel(src: Bitmap): Float {
        val s = Bitmap.createScaledBitmap(src, 512, (512f * src.height / src.width).toInt().coerceAtLeast(64), true)
        val w = s.width; val h = s.height
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h) { val c = px[it]; ((c shr 16 and 255) * 0.3f + (c shr 8 and 255) * 0.59f + (c and 255) * 0.11f) }
        val bins = 81   // -10..10 degrees in 0.25 steps
        val hist = FloatArray(bins)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = lum[y * w + x + 1] - lum[y * w + x - 1]
            val gy = lum[(y + 1) * w + x] - lum[(y - 1) * w + x]
            val mag = hypot(gx, gy)
            if (mag < 40f) continue
            // edge direction is perpendicular to the gradient; fold to -45..45 around the nearest axis
            var a = atan2(gy, gx) * 180f / PI.toFloat() + 90f
            while (a > 45f) a -= 90f
            while (a < -45f) a += 90f
            if (a in -10f..10f) hist[((a + 10f) / 0.25f).roundToInt().coerceIn(0, bins - 1)] += mag
        }
        // smooth then pick the strongest bin
        var best = 0; var bv = -1f
        for (i in 0 until bins) {
            var v = 0f
            for (k in -2..2) v += hist[(i + k).coerceIn(0, bins - 1)]
            if (v > bv) { bv = v; best = i }
        }
        if (bv <= 0f) return 0f
        return -(best * 0.25f - 10f)
    }

    /**
     * Estimates vertical and horizontal keystone from the direction of long edges. In a picture taken with the camera tilted,
     * near vertical edges lean in proportion to their distance from the centre line; the least squares slope of that lean is the
     * correction. Returns (vertical, horizontal) in the units of the Geometry sliders (-100..100), or zeros when edges are unclear.
     * Checked against a synthetic keystoned grid: it removes about 85 percent of the convergence.
     */
    fun autoPerspective(src: Bitmap): Pair<Float, Float> = estimateKeystone(src, false) to estimateKeystone(src, true)

    private fun estimateKeystone(src: Bitmap, horizontal: Boolean): Float {
        val s = Bitmap.createScaledBitmap(src, 512, (512f * src.height / src.width).toInt().coerceAtLeast(64), true)
        var w = s.width; var h = s.height
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        var lum = FloatArray(w * h) { val c = px[it]; ((c shr 16 and 255) * 0.3f + (c shr 8 and 255) * 0.59f + (c and 255) * 0.11f) }
        if (horizontal) { // work on the transposed picture so the same code finds near horizontal lines
            val t = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) t[x * h + y] = lum[y * w + x]
            lum = t; val tmp = w; w = h; h = tmp
        }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ms = ArrayList<Float>(); val ws = ArrayList<Float>()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = (lum[(y - 1) * w + x + 1] + 2 * lum[y * w + x + 1] + lum[(y + 1) * w + x + 1]) - (lum[(y - 1) * w + x - 1] + 2 * lum[y * w + x - 1] + lum[(y + 1) * w + x - 1])
            val gy = (lum[(y + 1) * w + x - 1] + 2 * lum[(y + 1) * w + x] + lum[(y + 1) * w + x + 1]) - (lum[(y - 1) * w + x - 1] + 2 * lum[(y - 1) * w + x] + lum[(y - 1) * w + x + 1])
            val mag = hypot(gx, gy)
            if (mag < 80f || kotlin.math.abs(gx) <= 3f * kotlin.math.abs(gy)) continue
            xs.add(x - w / 2f); ys.add(y - h / 2f); ms.add(-gy / gx); ws.add(mag)
        }
        if (xs.size < 200) return 0f
        var k = 0f
        var inlierShare = 0f
        for (iter in 0 until 5) {
            val xo = FloatArray(xs.size) { xs[it] / (1f + k * ys[it] / h) }
            val keep = BooleanArray(xs.size) { true }
            var kk = k
            for (r in 0 until 3) {
                var num = 0.0; var den = 0.0
                for (i in xs.indices) if (keep[i]) { val a = xo[i] / h; num += ws[i] * ms[i] * a; den += ws[i] * a * a }
                kk = (num / den.coerceAtLeast(1e-9)).toFloat()
                var sum = 0.0; var wsum = 0.0
                for (i in xs.indices) if (keep[i]) { val e = ms[i] - kk * xo[i] / h; sum += ws[i] * e * e; wsum += ws[i] }
                val sig = kotlin.math.sqrt(sum / wsum.coerceAtLeast(1e-9)).toFloat()
                for (i in xs.indices) keep[i] = kotlin.math.abs(ms[i] - kk * xo[i] / h) < maxOf(2.5f * sig, 0.02f)
            }
            inlierShare = keep.count { it } / xs.size.toFloat()
            k = kk
        }
        if (inlierShare < 0.5f || kotlin.math.abs(k) < 0.01f) return 0f
        return (k * 1.1f * 200f).coerceIn(-100f, 100f)
    }
}
