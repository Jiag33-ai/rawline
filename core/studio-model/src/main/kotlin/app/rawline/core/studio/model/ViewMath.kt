package app.rawline.core.studio.model

import kotlin.math.max
import kotlin.math.min

/**
 * The canvas view: [x], [y] are the document coordinates of the top left corner of the screen, [zoom] is screen pixels per document pixel.
 * The same three numbers go to the compositor as the view (`render(layers, vx, vy, zoom, ...)`).
 */
data class CanvasView(val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f) {
    fun toDocX(sx: Float) = x + sx / zoom
    fun toDocY(sy: Float) = y + sy / zoom
    fun toScreenX(dx: Float) = (dx - x) * zoom
    fun toScreenY(dy: Float) = (dy - y) * zoom

    /** Zoom by [factor] keeping the document point under the screen point ([sx], [sy]) fixed (pinch about its centre). Zoom is clamped to [MIN_ZOOM], [MAX_ZOOM]. */
    fun zoomAbout(factor: Float, sx: Float, sy: Float): CanvasView {
        val z = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val dx = toDocX(sx); val dy = toDocY(sy)
        return CanvasView(dx - sx / z, dy - sy / z, z)
    }

    /** Moves the picture with the fingers: a drag of ([dsx], [dsy]) screen pixels. */
    fun panBy(dsx: Float, dsy: Float) = copy(x = x - dsx / zoom, y = y - dsy / zoom)

    /** Keeps at least [margin] screen pixels of the canvas on screen, so the picture cannot be thrown away off screen. */
    fun clamped(canvasW: Int, canvasH: Int, screenW: Int, screenH: Int, margin: Float = 64f): CanvasView {
        val minX = (margin - screenW) / zoom; val maxX = canvasW - margin / zoom
        val minY = (margin - screenH) / zoom; val maxY = canvasH - margin / zoom
        return copy(x = x.coerceIn(min(minX, maxX), max(minX, maxX)), y = y.coerceIn(min(minY, maxY), max(minY, maxY)))
    }

    companion object {
        const val MIN_ZOOM = 0.1f
        const val MAX_ZOOM = 32f

        /** The whole canvas centred inside the screen with [pad] screen pixels around it. */
        fun fit(canvasW: Int, canvasH: Int, screenW: Int, screenH: Int, pad: Float = 16f): CanvasView {
            val z = min((screenW - 2 * pad) / canvasW, (screenH - 2 * pad) / canvasH).coerceIn(MIN_ZOOM, MAX_ZOOM)
            return CanvasView(-(screenW / z - canvasW) / 2f, -(screenH / z - canvasH) / 2f, z)
        }
    }
}

/** Move and Scale of a layer (spec S1 tools). */
object Placement {
    /** Scales a layer about the document point ([fx], [fy]) so that point stays where it is. Returns x, y, scale (scale clamped to 0.25..4). */
    fun scaleAbout(x: Int, y: Int, scale: Float, newScale: Float, fx: Float, fy: Float): Triple<Int, Int, Float> {
        val s = newScale.coerceIn(0.25f, 4f)
        val k = s / scale
        return Triple(Math.round(fx - (fx - x) * k), Math.round(fy - (fy - y) * k), s)
    }
}

/** The minimal colour picker: HSV in 0..1 to straight RGB in 0..1 and back (hue 0..1). */
object Hsv {
    fun toRgb(h: Float, s: Float, v: Float): FloatArray {
        val hh = (h - Math.floor(h.toDouble()).toFloat()) * 6f
        val i = hh.toInt(); val f = hh - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        return when (i % 6) { 0 -> floatArrayOf(v, t, p); 1 -> floatArrayOf(q, v, p); 2 -> floatArrayOf(p, v, t); 3 -> floatArrayOf(p, q, v); 4 -> floatArrayOf(t, p, v); else -> floatArrayOf(v, p, q) }
    }

    fun fromRgb(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> (((g - b) / d) % 6f + 6f) % 6f / 6f
            mx == g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }
}
