package app.rawline.core.studio.model

import kotlin.math.floor

/** What a layer mask or the selection is, for history entries that edit tiles. */
sealed class PlaneTarget {
    object Selection : PlaneTarget() { override fun toString() = "Selection" }
    data class Mask(val layerId: String) : PlaneTarget()
}

/** Mask maths (spec 2.1, decision D6 and D7). A mask is an 8 bit plane in the layer's own pixels: 255 reveals, 0 hides. */
object MaskOps {
    /** The grey the brush paints on a mask: black hides, white reveals, grey is partial. [inverted] flips it so the picture the user sees follows the colour even when the mask is shown inverted. */
    fun target(colour: FloatArray, inverted: Boolean): Float {
        val l = (0.2126f * colour[0] + 0.7152f * colour[1] + 0.0722f * colour[2]).coerceIn(0f, 1f)
        return if (inverted) 1f - l else l
    }

    fun white(w: Int, h: Int) = TilePlane(w, h).also { it.fill(255) }
    fun black(w: Int, h: Int) = TilePlane(w, h)

    /** The selection seen through a layer: each layer pixel takes the selection value under its centre (nearest pixel of the canvas). */
    fun fromSelection(sel: Selection, w: Int, h: Int, layerX: Int, layerY: Int, scale: Float): TilePlane {
        val p = TilePlane(w, h)
        if (sel.isEmpty()) { p.fill(255); return p }   // no selection is the whole layer (D3)
        p.transform(IRect(0, 0, w, h)) { x, y, _ -> sel.plane[canvasX(layerX, scale, x), canvasY(layerY, scale, y)] }
        return p
    }

    /** The canvas pixel under the centre of layer pixel [x] (the same arithmetic the shader uses). */
    fun canvasX(layerX: Int, scale: Float, x: Int): Int = floor(layerX + (x + 0.5f) * scale).toInt()
    fun canvasY(layerY: Int, scale: Float, y: Int): Int = floor(layerY + (y + 0.5f) * scale).toInt()

    /** Bakes one tile rectangle of a mask stroke: `m + (value - m) * coverage * opacity`, rounded to nearest. [cov] is the rectangle's coverage after the selection clip. */
    fun bake(plane: TilePlane, rect: IntArray, cov: FloatArray, value: Float, opacity: Float) {
        val rx = rect[0]; val ry = rect[1]; val rw = rect[2]
        plane.transform(IRect(rx, ry, rx + rw, ry + rect[3])) { x, y, cur ->
            val a = minOf(cov[(y - ry) * rw + (x - rx)], 1f) * opacity
            if (a <= 0f) cur else {
                val m = cur / 255f
                (minOf(maxOf(m + (value - m) * a, 0f), 1f) * 255f + 0.5f).toInt()
            }
        }
    }
}

/** The selection as a clip on painting (decision D3: no selection clips nothing). */
object SelectionClip {
    /** Multiplies the coverage of [rect] (x, y, w, h in layer pixels) by the selection under each pixel, in place. A layer at ([layerX], [layerY]) and [scale] maps pixels to the canvas as the shader does. */
    fun apply(sel: Selection, cov: FloatArray, rect: IntArray, layerX: Int, layerY: Int, scale: Float) {
        if (sel.isEmpty()) return
        val rw = rect[2]
        for (j in 0 until rect[3]) { val cy = MaskOps.canvasY(layerY, scale, rect[1] + j)
            for (i in 0 until rw) { val cx = MaskOps.canvasX(layerX, scale, rect[0] + i)
                cov[j * rw + i] = cov[j * rw + i] * (sel.plane[cx, cy] / 255f) } }
    }
}

/** The outline of a selection for the marching ants (first cut, spec 2.2): exact for a plain rectangle, else the 50 percent contour of a plane sampled every [cell] pixels. */
object SelectionContour {
    /** Line segments as x0, y0, x1, y1 in canvas pixels. Empty for no selection. */
    fun segments(sel: Selection, pureRect: IRect? = null, cell: Int = 4): FloatArray {
        if (sel.isEmpty()) return FloatArray(0)
        if (pureRect != null && !pureRect.empty) {
            val r = pureRect
            return floatArrayOf(r.x0.toFloat(), r.y0.toFloat(), r.x1.toFloat(), r.y0.toFloat(), r.x1.toFloat(), r.y0.toFloat(), r.x1.toFloat(), r.y1.toFloat(),
                r.x1.toFloat(), r.y1.toFloat(), r.x0.toFloat(), r.y1.toFloat(), r.x0.toFloat(), r.y1.toFloat(), r.x0.toFloat(), r.y0.toFloat())
        }
        val b = sel.bounds
        val gx0 = Math.floorDiv(b.x0, cell) - 1; val gy0 = Math.floorDiv(b.y0, cell) - 1
        val gw = (b.x1 + cell - 1) / cell + 2 - gx0; val gh = (b.y1 + cell - 1) / cell + 2 - gy0
        val out = ArrayList<Float>()
        fun v(gx: Int, gy: Int): Int { val x = gx * cell; val y = gy * cell; return if (x < 0 || y < 0 || x >= sel.w || y >= sel.h) 0 else sel.plane[x, y] }
        fun seg(ax: Float, ay: Float, bx: Float, by: Float) { out += ax; out += ay; out += bx; out += by }
        var top = IntArray(gw) { v(gx0 + it, gy0) }
        for (j in 0 until gh - 1) {
            val bottom = IntArray(gw) { v(gx0 + it, gy0 + j + 1) }
            for (i in 0 until gw - 1) {
                val tl = top[i]; val tr = top[i + 1]; val bl = bottom[i]; val br = bottom[i + 1]
                var mask = 0
                if (tl >= 128) mask = mask or 1; if (tr >= 128) mask = mask or 2; if (br >= 128) mask = mask or 4; if (bl >= 128) mask = mask or 8
                if (mask == 0 || mask == 15) continue
                val x = (gx0 + i) * cell.toFloat(); val y = (gy0 + j) * cell.toFloat(); val c = cell.toFloat()
                // edge midpoints: top, right, bottom, left
                val tx = x + c / 2; val ty = y; val rx = x + c; val ry = y + c / 2; val bx = x + c / 2; val by = y + c; val lx = x; val ly = y + c / 2
                when (mask) {
                    1, 14 -> seg(lx, ly, tx, ty)
                    2, 13 -> seg(tx, ty, rx, ry)
                    3, 12 -> seg(lx, ly, rx, ry)
                    4, 11 -> seg(rx, ry, bx, by)
                    6, 9 -> seg(tx, ty, bx, by)
                    7, 8 -> seg(lx, ly, bx, by)
                    5 -> { seg(lx, ly, tx, ty); seg(rx, ry, bx, by) }
                    10 -> { seg(tx, ty, rx, ry); seg(lx, ly, bx, by) }
                }
            }
            top = bottom
        }
        return out.toFloatArray()
    }
}
