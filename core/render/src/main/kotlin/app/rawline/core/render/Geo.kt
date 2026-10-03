package app.rawline.core.render

import app.rawline.core.model.Geometry
import app.rawline.core.model.Optics
import kotlin.math.cos
import kotlin.math.sin

/** CPU copy of the shader's geometry (geometry.glsl). Keep the two in step. */
object Geo {
    fun orientationRot(o: Int) = when (o) { 5, 6 -> 1; 3, 4 -> 2; 7, 8 -> 3; else -> 0 }
    fun orientationFlip(o: Int) = o == 2 || o == 4 || o == 5 || o == 7

    /** Frame position (normalised, crop independent) to source uv. Returns (u, v, inside). */
    fun frameToSource(fx: Float, fy: Float, g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int): FloatArray {
        val rot = ((orientationRot(orientation) + g.rotate90) % 4 + 4) % 4
        val odd = rot % 2 == 1
        val dw = (if (odd) srcH else srcW).toFloat(); val dh = (if (odd) srcW else srcH).toFloat()
        var qx = (fx - 0.5f) * dw; var qy = (fy - 0.5f) * dh
        val nx = qx / dw; val ny = qy / dh
        qx *= 1f + g.keystoneV / 100f * 0.5f * ny
        qy *= 1f + g.keystoneH / 100f * 0.5f * nx
        val a = Math.toRadians(g.angle.toDouble()).toFloat()
        val s = sin(a); val c = cos(a)
        val rx = c * qx - s * qy; val ry = s * qx + c * qy
        val r2 = (rx * rx + ry * ry) / (dh * dh * 0.25f + dw * dw * 0.25f)
        val k = 1f + o.distortion / 100f * 0.5f * r2
        var bx = rx * k / dw + 0.5f; var by = ry * k / dh + 0.5f
        if (orientationFlip(orientation) xor g.flipH) bx = 1f - bx
        if (g.flipV) by = 1f - by
        val u: Float; val v: Float
        when (rot) { 1 -> { u = by; v = 1f - bx }; 2 -> { u = 1f - bx; v = 1f - by }; 3 -> { u = 1f - by; v = bx }; else -> { u = bx; v = by } }
        return floatArrayOf(u, v, if (u in 0f..1f && v in 0f..1f) 1f else 0f)
    }

    /** Frame height in source pixels (how many source pixels one frame-height unit spans). */
    fun frameHeightPx(g: Geometry, orientation: Int, srcW: Int, srcH: Int): Float {
        val rot = ((orientationRot(orientation) + g.rotate90) % 4 + 4) % 4
        return (if (rot % 2 == 1) srcW else srcH).toFloat()
    }
}
