package app.rawline.core.studio.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Review F1 (BK-479): a stroke is committed per touched 256 px tile instead of one bounding box. A thin diagonal line across a 12 MP layer touches a few dozen tiles
 * but its bounding box is the whole layer. Everything here is pure maths over the same [Stamp]s the GPU draws.
 */
object StrokeTiles {
    /**
     * The rectangles (x, y, w, h) a stroke can touch, one per 256 px tile, each the union of the stamp boxes inside that tile, clipped to the layer, ordered by tile row then column.
     * A stamp box is the one [Dirty.rect] uses for a single stamp (radius plus one pixel each way), so the union of all rectangles is covered by [Dirty.rect] of the whole stroke.
     */
    fun rects(stamps: List<Stamp>, width: Int, height: Int): List<IntArray> {
        val acc = HashMap<Long, IntArray>()   // key: ty * 1_000_000 + tx; value: x0, y0, x1, y1 (inclusive)
        for (s in stamps) {
            val x0 = max(0, floor(s.x - s.radius - 1).toInt()); val y0 = max(0, floor(s.y - s.radius - 1).toInt())
            val x1 = min(width - 1, ceil(s.x + s.radius + 1).toInt()); val y1 = min(height - 1, ceil(s.y + s.radius + 1).toInt())
            if (x1 < x0 || y1 < y0) continue
            for (ty in y0 / Tiles.SIZE..y1 / Tiles.SIZE) for (tx in x0 / Tiles.SIZE..x1 / Tiles.SIZE) {
                val cx0 = max(x0, tx * Tiles.SIZE); val cy0 = max(y0, ty * Tiles.SIZE)
                val cx1 = min(x1, tx * Tiles.SIZE + Tiles.SIZE - 1); val cy1 = min(y1, ty * Tiles.SIZE + Tiles.SIZE - 1)
                val key = ty.toLong() * 1_000_000L + tx
                val r = acc[key]
                if (r == null) acc[key] = intArrayOf(cx0, cy0, cx1, cy1)
                else { r[0] = min(r[0], cx0); r[1] = min(r[1], cy0); r[2] = max(r[2], cx1); r[3] = max(r[3], cy1) }
            }
        }
        return acc.entries.sortedBy { it.key }.map { (_, r) -> intArrayOf(r[0], r[1], r[2] - r[0] + 1, r[3] - r[1] + 1) }
    }

    /** Pixels in all rectangles: what the commit reads back, bakes and stores, to compare with the bounding box area. */
    fun area(rects: List<IntArray>): Long = rects.sumOf { it[2].toLong() * it[3] }

    /**
     * [StrokeReference.commitRect] without a per pixel object: the same float operations in the same order, so the bytes are identical (tested), about an order of magnitude less garbage.
     * [cov] is the coverage of the rectangle only (row 0 = the rectangle's top row).
     */
    fun bake(pixels: ByteArray, layerW: Int, rect: IntArray, cov: FloatArray, colour: FloatArray, brush: Brush) {
        val rx = rect[0]; val ry = rect[1]; val rw = rect[2]; val rh = rect[3]
        val opacity = brush.opacity.toFloat()
        val cr = colour[0]; val cg = colour[1]; val cb = colour[2]
        for (y in 0 until rh) for (x in 0 until rw) {
            val a = min(cov[y * rw + x], 1f) * opacity
            if (a <= 0f) continue
            val o = ((ry + y) * layerW + rx + x) * 4
            val ab = (pixels[o + 3].toInt() and 255) / 255f
            if (brush.erase) {
                pixels[o + 3] = Math.round(ab * (1f - a) * 255f).toByte()
            } else {
                val ar = a + ab * (1f - a)
                if (ar <= 0f) { pixels[o] = 0; pixels[o + 1] = 0; pixels[o + 2] = 0; pixels[o + 3] = 0 }
                else {
                    pixels[o] = q((((1f - a) * ab * ((pixels[o].toInt() and 255) / 255f) + a * ((1f - ab) * cr + ab * cr))) / ar)
                    pixels[o + 1] = q((((1f - a) * ab * ((pixels[o + 1].toInt() and 255) / 255f) + a * ((1f - ab) * cg + ab * cg))) / ar)
                    pixels[o + 2] = q((((1f - a) * ab * ((pixels[o + 2].toInt() and 255) / 255f) + a * ((1f - ab) * cb + ab * cb))) / ar)
                    pixels[o + 3] = q(ar)
                }
            }
            if (pixels[o + 3].toInt() == 0) { pixels[o] = 0; pixels[o + 1] = 0; pixels[o + 2] = 0 }
        }
    }

    private fun q(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** What a stroke commit does for a list of tile rectangles: before bytes, bake, after bytes, one [PixelDelta] per rectangle. [coverageOf] reads the coverage of one rectangle (the GPU readback). */
    fun commit(pixels: ByteArray, layerW: Int, rects: List<IntArray>, colour: FloatArray, brush: Brush, coverageOf: (IntArray) -> FloatArray): List<PixelDelta> =
        rects.map { r ->
            val before = PixelDelta.cut(pixels, layerW, r[0], r[1], r[2], r[3])
            bake(pixels, layerW, r, coverageOf(r), colour, brush)
            PixelDelta(r[0], r[1], r[2], r[3], before, PixelDelta.cut(pixels, layerW, r[0], r[1], r[2], r[3]))
        }
}
