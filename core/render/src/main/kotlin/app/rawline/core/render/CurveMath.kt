package app.rawline.core.render

import app.rawline.core.model.CurvePoint
import app.rawline.core.model.Curves

/** Builds 256 entry lookup tables from curve control points (monotone cubic, so no overshoot). */
object CurveMath {
    fun lut(points: List<CurvePoint>, parametric: List<Float> = emptyList()): FloatArray {
        val out = FloatArray(256) { it / 255f }
        if (points.size >= 2) {
            val p = (listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)).takeIf { points.none { it.x <= 0f } || points.none { it.x >= 1f } }
                ?.let { ends -> (points + ends.filter { e -> points.none { it.x == e.x } }) } ?: points).sortedBy { it.x }
            val n = p.size
            val dx = FloatArray(n - 1) { p[it + 1].x - p[it].x }
            val dy = FloatArray(n - 1) { p[it + 1].y - p[it].y }
            val m = FloatArray(n - 1) { if (dx[it] <= 1e-6f) 0f else dy[it] / dx[it] }
            val t = FloatArray(n)
            t[0] = m[0]; t[n - 1] = m[n - 2]
            for (i in 1 until n - 1) t[i] = if (m[i - 1] * m[i] <= 0f) 0f else (m[i - 1] + m[i]) / 2f
            for (i in 0 until n - 1) {
                if (m[i] == 0f) { t[i] = 0f; t[i + 1] = 0f } else {
                    val a = t[i] / m[i]; val b = t[i + 1] / m[i]
                    val h = a * a + b * b
                    if (h > 9f) { val s = 3f / kotlin.math.sqrt(h); t[i] = s * a * m[i]; t[i + 1] = s * b * m[i] }
                }
            }
            for (k in 0 until 256) {
                val x = k / 255f
                var i = 0
                while (i < n - 2 && x > p[i + 1].x) i++
                val h = dx[i].coerceAtLeast(1e-6f)
                val s = ((x - p[i].x) / h).coerceIn(0f, 1f)
                val s2 = s * s; val s3 = s2 * s
                out[k] = ((2 * s3 - 3 * s2 + 1) * p[i].y + (s3 - 2 * s2 + s) * h * t[i] + (-2 * s3 + 3 * s2) * p[i + 1].y + (s3 - s2) * h * t[i + 1]).coerceIn(0f, 1f)
            }
        }
        if (parametric.size >= 4 && parametric.any { it != 0f }) {
            // highlights, lights, darks, shadows: broad bumps centred at 0.875, 0.625, 0.375, 0.125
            val centres = floatArrayOf(0.875f, 0.625f, 0.375f, 0.125f)
            for (k in 0 until 256) {
                val x = k / 255f
                var d = 0f
                for (i in 0 until 4) {
                    val w = (1f - kotlin.math.abs(x - centres[i]) / 0.25f).coerceAtLeast(0f)
                    d += parametric[i] / 100f * 0.18f * w * w * (3f - 2f * w)
                }
                out[k] = (out[k] + d).coerceIn(0f, 1f)
            }
            for (k in 1 until 256) if (out[k] < out[k - 1]) out[k] = out[k - 1]
        }
        return out
    }

    fun hasAny(c: Curves) = !c.isIdentity
}
