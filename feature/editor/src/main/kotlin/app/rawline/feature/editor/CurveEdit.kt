package app.rawline.feature.editor

import app.rawline.core.model.CurvePoint
import app.rawline.core.model.Curves
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Tone curve point editing as plain functions. A channel's list is kept sorted by x. An empty list is the identity curve, and is
 * written back whenever the points are exactly the two default end points, so an untouched curve costs the engine nothing.
 * The end points (x of 0 and 1) can move up and down but never sideways and cannot be deleted; a delete or a double tap on one
 * puts it back to its corner. Interior points stay between their neighbours, so a point's index never changes during a drag.
 */
object CurveEdit {
    /** Closest two interior points may sit, in x (1 percent of the range). */
    const val MIN_GAP = 0.01f
    private val Ends = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

    /** The points to work on: the channel's own, or the two end points when it has none. */
    fun shown(points: List<CurvePoint>): List<CurvePoint> {
        if (points.isEmpty()) return Ends
        val s = points.sortedBy { it.x }.toMutableList()
        // older recipes may lack an end point; the engine treats a missing one as the corner, so show it the same way
        if (s.first().x > 0f) s.add(0, CurvePoint(0f, 0f))
        if (s.last().x < 1f) s.add(CurvePoint(1f, 1f))
        return s
    }

    /** Back to the stored form: the plain diagonal is stored as empty. */
    fun normalise(points: List<CurvePoint>): List<CurvePoint> = if (points == Ends) emptyList() else points

    fun isEnd(p: CurvePoint) = p.x <= 0f || p.x >= 1f

    /** Index of the point nearest to the touch, within [radiusPx] of it, or -1. Positions are in the graph's pixel box of [w] by [h]. */
    fun hit(points: List<CurvePoint>, px: Float, py: Float, w: Float, h: Float, radiusPx: Float): Int {
        var best = -1; var bd = radiusPx
        points.forEachIndexed { i, p ->
            val d = hypot(p.x * w - px, (1f - p.y) * h - py)
            if (d <= bd) { bd = d; best = i }
        }
        return best
    }

    /** Adds a point at (x, y), in curve units. Returns the new list and the new point's index, or null when no room is left at that x. */
    fun insert(points: List<CurvePoint>, x: Float, y: Float): Pair<List<CurvePoint>, Int>? {
        val base = shown(points)
        val found = base.indexOfFirst { it.x > x }
        val i = if (found < 0) base.size - 1 else found.coerceAtLeast(1)     // always between the two end points
        val lo = base[i - 1].x + MIN_GAP
        val hi = base[i].x - MIN_GAP
        if (lo > hi) return null
        val nx = x.coerceIn(lo, hi)
        val ny = y.coerceIn(0f, 1f)
        return base.toMutableList().also { it.add(i, CurvePoint(nx, ny)) } to i
    }

    /** Moves point [i] towards (x, y). End points keep their x. Interior points stay a gap away from their neighbours. */
    fun move(points: List<CurvePoint>, i: Int, x: Float, y: Float): List<CurvePoint> {
        val base = shown(points)
        if (i !in base.indices) return points
        val p = base[i]
        val ny = y.coerceIn(0f, 1f)
        val nx = if (isEnd(p)) p.x else {
            val lo = (base.getOrNull(i - 1)?.x ?: 0f) + MIN_GAP
            val hi = (base.getOrNull(i + 1)?.x ?: 1f) - MIN_GAP
            x.coerceIn(lo, maxOf(lo, hi))
        }
        return base.toMutableList().also { it[i] = CurvePoint(nx, ny) }
    }

    /** Deletes an interior point. End points are returned to their corner instead. */
    fun remove(points: List<CurvePoint>, i: Int): List<CurvePoint> {
        val base = shown(points)
        if (i !in base.indices) return points
        val p = base[i]
        if (isEnd(p)) return base.toMutableList().also { it[i] = CurvePoint(p.x, if (p.x <= 0f) 0f else 1f) }
        return base.filterIndexed { k, _ -> k != i }
    }

    /** True when dragging has taken the pointer far enough outside the graph that letting go deletes the point. */
    fun offGraph(px: Float, py: Float, w: Float, h: Float, marginPx: Float) = px < -marginPx || px > w + marginPx || py < -marginPx || py > h + marginPx

    /** The readout for a point: input and output on the 0 to 255 scale the picture shows. */
    fun readout(p: CurvePoint): Pair<Int, Int> = (p.x * 255f).roundToInt() to (p.y * 255f).roundToInt()

    fun points(c: Curves, channel: Int): List<CurvePoint> = when (channel) { 0 -> c.master; 1 -> c.red; 2 -> c.green; else -> c.blue }
    fun withPoints(c: Curves, channel: Int, p: List<CurvePoint>): Curves = when (channel) { 0 -> c.copy(master = p); 1 -> c.copy(red = p); 2 -> c.copy(green = p); else -> c.copy(blue = p) }

    /** Number of interior points on a channel (what a "points" badge would show). */
    fun interiorCount(points: List<CurvePoint>) = points.count { !isEnd(it) }

    fun sameX(a: Float, b: Float) = abs(a - b) < 1e-6f
}
