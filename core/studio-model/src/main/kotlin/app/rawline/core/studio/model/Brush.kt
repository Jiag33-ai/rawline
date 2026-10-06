package app.rawline.core.studio.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One sample of a stroke in layer pixels. [pressure] is 0..1 (1 for a finger). */
class StrokePoint(val x: Double, val y: Double, val pressure: Double = 1.0)

/** A stamp the GPU draws: centre and radius in layer pixels. */
class Stamp(val x: Double, val y: Double, val radius: Double)

/**
 * Brush settings (spec 2.4 S1 subset and 4.3). [diameter] is in layer pixels, [hardness] 0..1, [opacity] is the stroke's ceiling,
 * [flow] is applied per stamp, [spacing] is a fraction of the diameter (minimum 0.5 px between stamps).
 */
data class Brush(
    val diameter: Double = 24.0,
    val hardness: Double = 0.8,
    val opacity: Double = 1.0,
    val flow: Double = 1.0,
    val spacing: Double = 0.1,
    val pressureSize: Boolean = true,
    val erase: Boolean = false,
) {
    init {
        require(diameter in 1.0..2000.0) { "diameter $diameter" }
        require(hardness in 0.0..1.0 && opacity in 0.0..1.0 && flow in 0.0..1.0 && spacing in 0.01..2.0) { "brush out of range" }
    }
}

/** The maths of spec 4.3 and the deterministic stamp walker. Mirrors tools/studio/studio_brush.py; the GPU stamp shader mirrors [falloff]. */
object BrushMath {
    /** Coverage of a round stamp at distance [r] from its centre: 1 inside hardness * radius, 0 outside radius, smoothstep between. */
    fun falloff(r: Double, radius: Double, hardness: Double): Double {
        if (r >= radius) return 0.0
        val inner = hardness * radius
        if (r <= inner) return 1.0
        val u = 1.0 - (r - inner) / (radius - inner)
        return u * u * (3.0 - 2.0 * u)
    }

    /** Diameter of a stamp at [pressure]: a light touch gives a fifth of the size (S Pen pressure to size). */
    fun diameterAt(base: Double, pressure: Double, pressureSize: Boolean): Double = if (pressureSize) base * (0.2 + 0.8 * pressure.coerceIn(0.0, 1.0)) else base

    /**
     * Stamps along [points]: one at the first point, then every max(0.5, d * spacing) pixels along the path where d is the diameter of the
     * previous stamp (the step is fixed when a stamp is placed). The distance since the last stamp is carried across segments, so the result does not depend on how the
     * input was sliced into events (a stroke fed in two halves places the same stamps as the whole).
     */
    fun walk(points: List<StrokePoint>, brush: Brush): List<Stamp> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<Stamp>()
        out.add(Stamp(points[0].x, points[0].y, diameterAt(brush.diameter, points[0].pressure, brush.pressureSize) / 2.0))
        var step = max(0.5, diameterAt(brush.diameter, points[0].pressure, brush.pressureSize) * brush.spacing)   // set when a stamp is placed
        var carry = 0.0                                                                                          // distance travelled since the last stamp
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val seg = hypot(b.x - a.x, b.y - a.y)
            if (seg == 0.0) continue
            var t = 0.0
            while (true) {
                val need = step - carry
                if (t + need > seg + 1e-9) { carry += seg - t; break }
                t += need; carry = 0.0
                val f = t / seg
                val pp = a.pressure + (b.pressure - a.pressure) * f
                val d = diameterAt(brush.diameter, pp, brush.pressureSize)
                out.add(Stamp(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, d / 2.0))
                step = max(0.5, d * brush.spacing)
            }
        }
        return out
    }
}

/** [BrushMath.walk] one point at a time, for live input: `add` returns the stamps that point completes. Feeding points one by one gives exactly the stamps of walking them all at once. */
class StrokeWalker(private val brush: Brush) {
    private var last: StrokePoint? = null
    private var step = 0.0
    private var carry = 0.0

    fun add(p: StrokePoint): List<Stamp> {
        val a = last
        last = p
        if (a == null) {
            val d = BrushMath.diameterAt(brush.diameter, p.pressure, brush.pressureSize)
            step = max(0.5, d * brush.spacing)
            return listOf(Stamp(p.x, p.y, d / 2.0))
        }
        val out = ArrayList<Stamp>()
        val seg = hypot(p.x - a.x, p.y - a.y)
        if (seg == 0.0) return out
        var t = 0.0
        while (true) {
            val need = step - carry
            if (t + need > seg + 1e-9) { carry += seg - t; break }
            t += need; carry = 0.0
            val f = t / seg
            val d = BrushMath.diameterAt(brush.diameter, a.pressure + (p.pressure - a.pressure) * f, brush.pressureSize)
            out.add(Stamp(a.x + (p.x - a.x) * f, a.y + (p.y - a.y) * f, d / 2.0))
            step = max(0.5, d * brush.spacing)
        }
        return out
    }
}

/** The pixel rectangle a set of stamps can touch, clipped to the layer: x, y, w, h (w or h 0 when nothing is touched). */
object Dirty {
    fun rect(stamps: List<Stamp>, width: Int, height: Int): IntArray {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (s in stamps) {
            x0 = min(x0, floor(s.x - s.radius - 1).toInt()); y0 = min(y0, floor(s.y - s.radius - 1).toInt())
            x1 = max(x1, ceil(s.x + s.radius + 1).toInt()); y1 = max(y1, ceil(s.y + s.radius + 1).toInt())
        }
        x0 = max(0, x0); y0 = max(0, y0); x1 = min(width - 1, x1); y1 = min(height - 1, y1)
        return if (x1 < x0 || y1 < y0) intArrayOf(0, 0, 0, 0) else intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}

/**
 * CPU reference for a stroke: coverage accumulation and the commit into straight RGBA8 pixels. The GPU draws the same stamps into an
 * R16F stroke buffer for the live preview; at stroke end the buffer's dirty rectangle is read back and [commit] bakes it into the
 * layer's CPU copy (the one autosave and undo use), so what is saved is what the preview showed.
 */
object StrokeReference {
    /** Accumulated coverage, row 0 at the top, pixel centres: a = a + s (1 - a), s = flow * falloff. Returns a w*h array. */
    fun coverage(w: Int, h: Int, stamps: List<Stamp>, brush: Brush): FloatArray {
        val acc = FloatArray(w * h)
        for (s in stamps) {
            val x0 = max(0, floor(s.x - s.radius - 1).toInt()); val x1 = min(w - 1, ceil(s.x + s.radius + 1).toInt())
            val y0 = max(0, floor(s.y - s.radius - 1).toInt()); val y1 = min(h - 1, ceil(s.y + s.radius + 1).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val c = BrushMath.falloff(hypot(x + 0.5 - s.x, y + 0.5 - s.y), s.radius, brush.hardness)
                if (c > 0.0) { val sa = (brush.flow * c).toFloat(); val i = y * w + x; acc[i] = acc[i] + sa * (1f - acc[i]) }
            }
        }
        return acc
    }

    /**
     * Bakes [coverage] (full layer size) into [pixels] in place for the rectangle [rect] (x, y, w, h). See [commitRect].
     */
    fun commit(pixels: ByteArray, layerW: Int, coverage: FloatArray, rect: IntArray, colour: FloatArray, brush: Brush) =
        bake(pixels, layerW, rect, coverage, layerW, 0, 0, colour, brush)

    /**
     * Same, with coverage for the rectangle only (w * h values, row 0 = the rectangle's top row), which is what `readStroke` returns, so no
     * layer-sized array is needed. Paint is the normal "over" of the colour at coverage * opacity; erase scales alpha by
     * (1 - coverage * opacity) and leaves colour alone. A pixel that ends with alpha 0 has its colour zeroed, so equal pictures are equal
     * bytes (dedupe by hash, stable tests).
     */
    fun commitRect(pixels: ByteArray, layerW: Int, rect: IntArray, rectCoverage: FloatArray, colour: FloatArray, brush: Brush) =
        bake(pixels, layerW, rect, rectCoverage, rect[2], rect[0], rect[1], colour, brush)

    private fun bake(pixels: ByteArray, layerW: Int, rect: IntArray, cov: FloatArray, covW: Int, covX: Int, covY: Int, colour: FloatArray, brush: Brush) {
        for (y in rect[1] until rect[1] + rect[3]) for (x in rect[0] until rect[0] + rect[2]) {
            val a = min(cov[(y - covY) * covW + (x - covX)], 1f) * brush.opacity.toFloat()
            if (a <= 0f) continue
            val o = (y * layerW + x) * 4
            val old = Rgba((pixels[o].toInt() and 255) / 255f, (pixels[o + 1].toInt() and 255) / 255f, (pixels[o + 2].toInt() and 255) / 255f, (pixels[o + 3].toInt() and 255) / 255f)
            if (brush.erase) {
                pixels[o + 3] = Math.round(old.a * (1f - a) * 255f).toByte()
            } else {
                val r = Blend.over(old, Rgba(colour[0], colour[1], colour[2], 1f), BlendMode.NORMAL, opacity = a)
                pixels[o] = q(r.r); pixels[o + 1] = q(r.g); pixels[o + 2] = q(r.b); pixels[o + 3] = q(r.a)
            }
            if (pixels[o + 3].toInt() == 0) { pixels[o] = 0; pixels[o + 1] = 0; pixels[o + 2] = 0 }   // canonical transparent: no hidden colour under alpha 0
        }
    }

    private fun q(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
}
