package app.rawline.core.render

import app.rawline.core.model.Geometry
import app.rawline.core.model.Optics
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** CPU copy of the shader's geometry (geometry.glsl). Keep the two in step. */
object Geo {
    fun orientationRot(o: Int) = when (o) { 5, 6 -> 1; 3, 4 -> 2; 7, 8 -> 3; else -> 0 }
    fun orientationFlip(o: Int) = o == 2 || o == 4 || o == 5 || o == 7

    /**
     * Frame position (normalised, crop independent) to source uv. Returns (u, v, inside).
     * [lensDist] is the lens profile polynomial p0..p4 when lens correction is on (null when off), exactly as the shader applies it.
     */
    fun frameToSource(fx: Float, fy: Float, g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int, lensDist: FloatArray? = null): FloatArray {
        val out = FloatArray(2)
        map(fx, fy, g, o, orientation, srcW, srcH, lensDist, out)
        val u = out[0]; val v = out[1]
        return floatArrayOf(u, v, if (u in 0f..1f && v in 0f..1f) 1f else 0f)
    }

    private fun map(fx: Float, fy: Float, g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int, lensDist: FloatArray?, out: FloatArray) {
        val rot = ((orientationRot(orientation) + g.rotate90) % 4 + 4) % 4
        val odd = rot % 2 == 1
        val dw = (if (odd) srcH else srcW).toFloat(); val dh = (if (odd) srcW else srcH).toFloat()
        var qx = (fx - 0.5f) * dw; var qy = (fy - 0.5f) * dh
        val nx = qx / dw; val ny = qy / dh
        qx *= 1f + g.keystoneV / 100f * 0.5f * ny
        qy *= 1f + g.keystoneH / 100f * 0.5f * nx
        val a = Math.toRadians(g.angle.toDouble()).toFloat()
        val s = sin(a); val c = cos(a)
        var rx = c * qx - s * qy; var ry = s * qx + c * qy
        if (lensDist != null) {
            val rn = sqrt(rx * rx + ry * ry) / (0.5f * min(dw, dh))
            val f = lensDist[0] + rn * (lensDist[1] + rn * (lensDist[2] + rn * (lensDist[3] + rn * lensDist[4])))
            rx *= f; ry *= f
        }
        val r2 = (rx * rx + ry * ry) / (dh * dh * 0.25f + dw * dw * 0.25f)
        val k = 1f + o.distortion / 100f * 0.5f * r2
        var bx = rx * k / dw + 0.5f; var by = ry * k / dh + 0.5f
        if (orientationFlip(orientation) xor g.flipH) bx = 1f - bx
        if (g.flipV) by = 1f - by
        when (rot) { 1 -> { out[0] = by; out[1] = 1f - bx }; 2 -> { out[0] = 1f - bx; out[1] = 1f - by }; 3 -> { out[0] = 1f - by; out[1] = bx }; else -> { out[0] = bx; out[1] = by } }
    }

    /** Frame height in source pixels (how many source pixels one frame-height unit spans). */
    fun frameHeightPx(g: Geometry, orientation: Int, srcW: Int, srcH: Int): Float {
        val rot = ((orientationRot(orientation) + g.rotate90) % 4 + 4) % 4
        return (if (rot % 2 == 1) srcW else srcH).toFloat()
    }

    // ---- Constrain to image: the visible and exported picture never contains pixels from outside the source ----

    /**
     * Safety band, as a fraction of the source on each side, kept between the picture and the source edge. It covers bilinear
     * taps, the chromatic aberration shift (up to about 0.1 percent) and float differences between this code and the shader.
     * It is a fraction (not pixels) so the half size preview and the full size export frame identically.
     */
    const val EDGE_MARGIN = 0.0015f

    /** Samples per rectangle edge when testing a candidate crop. */
    private const val EDGE_SAMPLES = 64

    /** True when the frame position maps to a source point at least [EDGE_MARGIN] inside the source. */
    fun frameValid(fx: Float, fy: Float, g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int, lensDist: FloatArray? = null): Boolean {
        val out = FloatArray(2)
        return frameValid(fx, fy, g, o, orientation, srcW, srcH, lensDist, out)
    }

    private fun frameValid(fx: Float, fy: Float, g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int, lensDist: FloatArray?, out: FloatArray): Boolean {
        map(fx, fy, g, o, orientation, srcW, srcH, lensDist, out)
        val u = out[0]; val v = out[1]
        return u >= EDGE_MARGIN && u <= 1f - EDGE_MARGIN && v >= EDGE_MARGIN && v <= 1f - EDGE_MARGIN
    }

    private class Ctx(val g: Geometry, val o: Optics, val orientation: Int, val srcW: Int, val srcH: Int, val lensDist: FloatArray?) {
        val tmp = FloatArray(2)
        fun valid(x: Float, y: Float) = frameValid(x, y, g, o, orientation, srcW, srcH, lensDist, tmp)

        /** Valid on the whole rectangle border (the warp is a smooth one to one map, so the inside follows from the border). */
        fun rectValid(x: Float, y: Float, w: Float, h: Float): Boolean {
            for (i in 0..EDGE_SAMPLES) {
                val t = i.toFloat() / EDGE_SAMPLES
                if (!valid(x + t * w, y) || !valid(x + t * w, y + h) || !valid(x, y + t * h) || !valid(x + w, y + t * h)) return false
            }
            return true
        }
    }

    private data class FitKey(val g: Geometry, val o: Optics, val orientation: Int, val srcW: Int, val srcH: Int, val lensDist: List<Float>?)
    @Volatile private var lastKey: FitKey? = null
    @Volatile private var lastFit: FloatArray? = null

    /**
     * The crop rectangle (x, y, w, h in the oriented base frame, the same units as [Geometry.cropX]) actually shown and exported.
     * It is the user's crop when that is entirely valid source; otherwise the largest rectangle with the same aspect, moved
     * the shortest way towards the frame centre, that has no pixel from outside the source. Straighten, keystone, manual
     * distortion and the lens profile are all part of the test, so the same call serves preview, histogram and tiled export.
     * Pure function: the stored recipe is not changed.
     */
    fun fitCrop(g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int, lensDist: FloatArray? = null): FloatArray {
        val key = FitKey(g, o, orientation, srcW, srcH, lensDist?.toList())
        if (key == lastKey) lastFit?.let { return it.copyOf() }
        val r = solveCrop(g, o, orientation, srcW, srcH, lensDist)
        lastFit = r; lastKey = key
        return r.copyOf()
    }

    /** [g] with its crop replaced by [fitCrop]. Additive helper for code that maps between the view and the frame. */
    fun constrainGeometry(g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int, lensDist: FloatArray? = null): Geometry {
        val c = fitCrop(g, o, orientation, srcW, srcH, lensDist)
        return g.copy(cropX = c[0], cropY = c[1], cropW = c[2], cropH = c[3])
    }

    private fun solveCrop(g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int, lensDist: FloatArray?): FloatArray {
        val w = g.cropW.coerceIn(0.001f, 1f); val h = g.cropH.coerceIn(0.001f, 1f)
        val x = g.cropX; val y = g.cropY
        val original = floatArrayOf(x, y, w, h)
        if (srcW <= 0 || srcH <= 0) return original
        // Axis aligned and undistorted: the crop is inside the source by construction, no margin needed.
        if (g.angle == 0f && g.keystoneV == 0f && g.keystoneH == 0f && o.distortion == 0f && lensDist == null) {
            val x0 = x.coerceIn(0f, 1f - w); val y0 = y.coerceIn(0f, 1f - h)
            return floatArrayOf(x0, y0, w, h)
        }
        val ctx = Ctx(g, o, orientation, srcW, srcH, lensDist)
        if (ctx.rectValid(x, y, w, h)) return original
        val cx = x + w / 2f; val cy = y + h / 2f
        if (!ctx.valid(0.5f, 0.5f)) return original   // degenerate warp: nothing sensible to constrain to
        val steps = 16
        // Smallest move towards the frame centre that makes a rectangle of scale s valid, or -1.
        fun place(s: Float): Float {
            val rw = w * s; val rh = h * s
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val mx = cx + t * (0.5f - cx); val my = cy + t * (0.5f - cy)
                if (ctx.rectValid(mx - rw / 2f, my - rh / 2f, rw, rh)) return t
            }
            return -1f
        }
        var lo = 0f; var hi = 1f
        for (i in 0 until 22) {
            val mid = (lo + hi) / 2f
            if (place(mid) >= 0f) lo = mid else hi = mid
        }
        // lo is feasible (or 0, a point at the centre). Refine the placement for it.
        val s = lo
        val rw = w * s; val rh = h * s
        var t = place(s)
        if (t < 0f) t = 1f
        val mx = cx + t * (0.5f - cx); val my = cy + t * (0.5f - cy)
        return floatArrayOf(mx - rw / 2f, my - rh / 2f, rw, rh)
    }
}
