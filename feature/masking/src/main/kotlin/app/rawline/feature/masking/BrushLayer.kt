package app.rawline.feature.masking

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import app.rawline.core.model.BrushStroke
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * CPU raster of brush strokes for one mask component. Strokes are kept as vectors in the recipe and rendered here at layer
 * resolution, then uploaded as an alpha layer. Both the preview and the full resolution export sample this same layer, so
 * they always agree.
 *
 * Points are normalised positions in the oriented, uncropped frame; sizes are fractions of the frame height.
 */
class BrushLayer(val w: Int, val h: Int, var reference: IntArray? = null) {
    val alpha = ByteArray(w * h)
    private val tmpBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    private val tmpCanvas = Canvas(tmpBitmap)
    private val tmpBytes = ByteArray(w * h)
    private var base = ByteArray(0)
    private var seed = intArrayOf(0, 0, 0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF000000.toInt()
    }
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    fun clear() { alpha.fill(0) }

    fun renderAll(strokes: List<BrushStroke>) {
        clear()
        strokes.forEach { s -> beginStroke(s); update(s); }
        endStroke()
    }

    /** Call before the first [update] of a stroke. */
    fun beginStroke(s: BrushStroke) {
        base = alpha.copyOf()
        tmpCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), clear)
        if (s.autoMask && reference != null && s.points.size >= 2) seed = meanColour((s.points[0] * w).toInt(), (s.points[1] * h).toInt(), (s.size * h * 0.5f).toInt().coerceAtLeast(2))
    }

    /** Draws the stroke as it is so far and merges it into [alpha]. Returns the dirty rectangle (x0, y0, x1, y1) in layer pixels. */
    fun update(s: BrushStroke): IntArray {
        val radius = s.size * h / 2f
        paint.strokeWidth = radius * 2f
        paint.alpha = (s.flow.coerceIn(0.05f, 1f) * 255).toInt()
        paint.maskFilter = if (s.feather > 0.02f) BlurMaskFilter((radius * s.feather).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL) else null
        tmpCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), clear)
        val pts = s.points
        if (pts.size == 2) tmpCanvas.drawPoint(pts[0] * w, pts[1] * h, paint)
        else {
            val path = android.graphics.Path()
            path.moveTo(pts[0] * w, pts[1] * h)
            var i = 2
            while (i + 1 < pts.size) { path.lineTo(pts[i] * w, pts[i + 1] * h); i += 2 }
            tmpCanvas.drawPath(path, paint)
        }
        tmpBytes.fill(0)
        tmpBitmap.copyPixelsToBuffer(ByteBuffer.wrap(tmpBytes))
        // dirty box
        var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
        var i = 0
        while (i < pts.size) { x0 = min(x0, (pts[i] * w).toInt()); x1 = max(x1, (pts[i] * w).toInt()); y0 = min(y0, (pts[i + 1] * h).toInt()); y1 = max(y1, (pts[i + 1] * h).toInt()); i += 2 }
        val pad = (radius * (1f + s.feather) * 1.5f).toInt() + 4
        x0 = (x0 - pad).coerceAtLeast(0); y0 = (y0 - pad).coerceAtLeast(0); x1 = (x1 + pad).coerceAtMost(w - 1); y1 = (y1 + pad).coerceAtMost(h - 1)
        val useAuto = s.autoMask && reference != null
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val t = tmpBytes[row + x].toInt() and 0xFF
                val b = base[row + x].toInt() and 0xFF
                var a = t
                if (useAuto && t > 0) a = (t * similarity(reference!![row + x])).toInt()
                alpha[row + x] = (if (s.erase) b * (255 - a) / 255 else max(b, a)).toByte()
            }
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    fun endStroke() { base = ByteArray(0) }

    private fun meanColour(cx: Int, cy: Int, r: Int): IntArray {
        val ref = reference ?: return intArrayOf(0, 0, 0)
        var rr = 0L; var gg = 0L; var bb = 0L; var n = 0
        for (y in (cy - r).coerceAtLeast(0)..(cy + r).coerceAtMost(h - 1)) for (x in (cx - r).coerceAtLeast(0)..(cx + r).coerceAtMost(w - 1)) {
            if (hypot((x - cx).toFloat(), (y - cy).toFloat()) > r) continue
            val c = ref[y * w + x]; rr += c shr 16 and 255; gg += c shr 8 and 255; bb += c and 255; n++
        }
        return if (n == 0) intArrayOf(128, 128, 128) else intArrayOf((rr / n).toInt(), (gg / n).toInt(), (bb / n).toInt())
    }

    private fun similarity(c: Int): Float {
        val dr = (c shr 16 and 255) - seed[0]; val dg = (c shr 8 and 255) - seed[1]; val db = (c and 255) - seed[2]
        val d2 = (dr * dr + dg * dg + db * db).toFloat()
        return exp(-d2 / (2f * 38f * 38f))
    }

    companion object {
        /** Layer dimensions for a frame aspect (width / height): long edge 2048. */
        fun dims(aspect: Float): Pair<Int, Int> = if (aspect >= 1f) 2048 to (2048f / aspect).toInt().coerceAtLeast(64) else (2048f * aspect).toInt().coerceAtLeast(64) to 2048
    }
}
