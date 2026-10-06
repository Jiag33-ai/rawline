package app.rawline.feature.masking

import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskComponent
import app.rawline.core.model.MaskOp
import app.rawline.core.model.MaskType
import app.rawline.core.render.P
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Pure mask logic: no Android, no Compose. Everything here runs in host unit tests. The UI (MaskTray, MaskOverlay) and the gesture
 * code in MaskingFeature only call into this and keep no rules of their own.
 */

/** A point or vector. Used for both view pixels and normalised frame positions; each function says which. */
data class V2(val x: Float, val y: Float) {
    operator fun plus(o: V2) = V2(x + o.x, y + o.y)
    operator fun minus(o: V2) = V2(x - o.x, y - o.y)
    operator fun times(k: Float) = V2(x * k, y * k)
    fun len() = hypot(x, y)
    fun dist(o: V2) = hypot(x - o.x, y - o.y)
}

object MaskRules {
    /** Parts per mask: the shader loop and the mask texture row hold six. */
    const val MAX_PARTS = 6
    const val MAX_NAME = 40

    fun layerKeys(r: EditRecipe): Set<String> = r.masks.flatMap { it.components }.mapNotNull { it.layerKey }.toSet()

    /** [newLayers] is how many GPU layer slots the thing being added needs (a brush or an AI part needs one). */
    fun canAddMask(r: EditRecipe, newLayers: Int = 0) = r.masks.size < P.MAX_MASKS && layerKeys(r).size + newLayers <= P.MAX_LAYERS

    fun canAddPart(r: EditRecipe, maskId: String, newLayers: Int = 0): Boolean {
        val m = r.masks.firstOrNull { it.id == maskId } ?: return false
        return m.components.size < MAX_PARTS && layerKeys(r).size + newLayers <= P.MAX_LAYERS
    }

    /** "Brush 1", "Brush 2": the lowest number not already used by a mask with the same base, so names stay unique after deletes. */
    fun nextName(base: String, masks: List<Mask>): String {
        val used = masks.map { it.name }.toSet()
        var n = 1
        while ("$base $n" in used) n++
        return "$base $n"
    }

    /** Trims, collapses spaces, caps the length. Returns null for a blank name (keep the old one). */
    fun cleanName(raw: String): String? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        return if (s.length > MAX_NAME) s.take(MAX_NAME).trimEnd() else s
    }

    fun indexOf(r: EditRecipe, id: String?): Int = if (id == null) -1 else r.masks.indexOfFirst { it.id == id }

    /** Keeps the selection pointing at something that exists: (id, part index). The part index is clamped into the mask's parts. */
    fun reconcile(selectedId: String?, selectedPart: Int, masks: List<Mask>): Pair<String?, Int> {
        val m = masks.firstOrNull { it.id == selectedId } ?: return null to 0
        return m.id to selectedPart.coerceIn(0, (m.components.size - 1).coerceAtLeast(0))
    }

    // ---- recipe edits, all addressed by mask id so they stay right after undo, redo and deletes ----

    fun mapMask(r: EditRecipe, id: String, f: (Mask) -> Mask): EditRecipe =
        if (r.masks.none { it.id == id }) r else r.copy(masks = r.masks.map { if (it.id == id) f(it) else it })

    fun mapPart(r: EditRecipe, id: String, part: Int, f: (MaskComponent) -> MaskComponent): EditRecipe =
        mapMask(r, id) { m -> m.copy(components = m.components.mapIndexed { i, c -> if (i == part) f(c) else c }) }

    fun rename(r: EditRecipe, id: String, raw: String): EditRecipe {
        val n = cleanName(raw) ?: return r
        return mapMask(r, id) { it.copy(name = n) }
    }

    fun setPartOp(r: EditRecipe, id: String, part: Int, op: MaskOp) = mapPart(r, id, part) { it.copy(op = op) }
    fun togglePartInvert(r: EditRecipe, id: String, part: Int) = mapPart(r, id, part) { it.copy(invert = !it.invert) }

    /** A mask always keeps at least one part: removing the last one is a delete of the mask, which the caller does explicitly. */
    fun removePart(r: EditRecipe, id: String, part: Int): EditRecipe = mapMask(r, id) { m ->
        if (m.components.size <= 1 || part !in m.components.indices) m else m.copy(components = m.components.filterIndexed { i, _ -> i != part })
    }

    fun addPart(r: EditRecipe, id: String, c: MaskComponent): EditRecipe = mapMask(r, id) { m ->
        if (m.components.size >= MAX_PARTS) m else m.copy(components = m.components + c)
    }

    fun deleteMask(r: EditRecipe, id: String): EditRecipe = r.copy(masks = r.masks.filter { it.id != id })

    fun removeLastStroke(r: EditRecipe, id: String, part: Int): EditRecipe = mapPart(r, id, part) { c ->
        if (c.strokes.isEmpty()) c else c.copy(strokes = c.strokes.dropLast(1))
    }

    fun part(r: EditRecipe, id: String?, part: Int): MaskComponent? = r.masks.firstOrNull { it.id == id }?.components?.getOrNull(part)

    fun partParam(c: MaskComponent, i: Int, default: Float) = c.params.getOrElse(i) { default }

    /** Replaces one parameter, padding the list with zeros if it is shorter (older saved edits may have fewer). */
    fun withParam(c: MaskComponent, i: Int, v: Float): MaskComponent =
        c.copy(params = c.params.toMutableList().also { l -> while (l.size <= i) l.add(0f); l[i] = v })

    fun withParams(c: MaskComponent, p: List<Float>) = c.copy(params = p)

    fun isBrush(c: MaskComponent) = c.type == MaskType.BITMAP && c.layerKey?.startsWith("brush_") == true
}

/** Which GPU layer keys and saved images a recipe needs. */
object LayerPlan {
    /** Keys that hold a slot but no mask refers to them any more (after delete, undo, redo, reset). */
    fun unused(live: Collection<String>, r: EditRecipe): List<String> {
        val used = MaskRules.layerKeys(r)
        return live.filter { it !in used }
    }

    /** Signature of what a brush layer should hold: its strokes and the size it is drawn at. A resize changes it, so the image is saved again. */
    fun brushSignature(strokesHash: Int, w: Int, h: Int): Int = (strokesHash * 31 + w) * 31 + h
}

/** Radius in view pixels of the brush ring. [size] is a fraction of the uncropped frame height, [outHeightPx] the shown (cropped) image height in the view. */
object BrushMath {
    fun ringRadiusPx(size: Float, outHeightPx: Float, cropH: Float): Float = size * (outHeightPx / cropH.coerceAtLeast(0.01f)) / 2f

    /** A brush point closer than this to the last one (frame height units) adds nothing but cost. */
    const val MIN_STEP = 0.0012f

    fun farEnough(lastX: Float, lastY: Float, x: Float, y: Float, aspect: Float) = hypot((x - lastX) * aspect, y - lastY) >= MIN_STEP
}

// ------------------------------------------------------------------------------------------------------------------
// Linear gradient. params = x1 y1 x2 y2, normalised in the uncropped frame. The effect is 0 at the start and full at the end.
// ------------------------------------------------------------------------------------------------------------------

enum class LinearPart { START, END, MOVE }

object LinearGeom {
    fun padded(p: List<Float>): List<Float> = List(4) { p.getOrElse(it) { 0f } }

    /** Length in frame height units. */
    fun length(p: List<Float>, asp: Float): Float { val q = padded(p); return hypot((q[2] - q[0]) * asp, q[3] - q[1]) }

    /** Direction start to end in degrees, 0 pointing right, positive clockwise on screen (y grows downward). */
    fun angleDeg(p: List<Float>, asp: Float): Float { val q = padded(p); return Math.toDegrees(atan2((q[3] - q[1]).toDouble(), ((q[2] - q[0]) * asp).toDouble())).toFloat() }

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    /** Same centre, new direction and/or length. Ends are kept inside the frame so both handles stay reachable. */
    fun withAngleLength(p: List<Float>, deg: Float, len: Float, asp: Float): List<Float> {
        val q = padded(p)
        val mx = (q[0] + q[2]) / 2f; val my = (q[1] + q[3]) / 2f
        val a = Math.toRadians(deg.toDouble()); val half = len.coerceAtLeast(0.01f) / 2f
        val dx = (cos(a) * half / asp).toFloat(); val dy = (sin(a) * half).toFloat()
        return listOf(clamp01(mx - dx), clamp01(my - dy), clamp01(mx + dx), clamp01(my + dy))
    }

    fun flip(p: List<Float>): List<Float> { val q = padded(p); return listOf(q[2], q[3], q[0], q[1]) }

    /** Moves the whole gradient by (dx, dy), reducing the move so neither end leaves the frame (the shape never changes). */
    fun translate(p: List<Float>, dx: Float, dy: Float): List<Float> {
        val q = padded(p)
        val minX = min(q[0], q[2]); val maxX = max(q[0], q[2]); val minY = min(q[1], q[3]); val maxY = max(q[1], q[3])
        val ddx = dx.coerceIn(-minX, 1f - maxX); val ddy = dy.coerceIn(-minY, 1f - maxY)
        return listOf(q[0] + ddx, q[1] + ddy, q[2] + ddx, q[3] + ddy)
    }

    fun moveEnd(p: List<Float>, end: LinearPart, fx: Float, fy: Float): List<Float> {
        val q = padded(p).toMutableList()
        val i = if (end == LinearPart.START) 0 else 2
        q[i] = clamp01(fx); q[i + 1] = clamp01(fy)
        return q
    }

    /** View space hit test. Nearest end handle wins (so two close handles never steal each other), then the middle handle, then the line itself. */
    fun hit(a: V2, b: V2, p: V2, handleR: Float, lineR: Float): LinearPart? {
        val da = p.dist(a); val db = p.dist(b)
        if (min(da, db) <= handleR) return if (da <= db) LinearPart.START else LinearPart.END
        val mid = V2((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        if (p.dist(mid) <= handleR) return LinearPart.MOVE
        return if (distToSegment(p, a, b) <= lineR) LinearPart.MOVE else null
    }

    fun distToSegment(p: V2, a: V2, b: V2): Float {
        val d = b - a
        val l2 = d.x * d.x + d.y * d.y
        if (l2 < 1e-6f) return p.dist(a)
        val t = (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / l2).coerceIn(0f, 1f)
        return p.dist(V2(a.x + d.x * t, a.y + d.y * t))
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Radial gradient. params = cx cy rx ry angle feather. Radii are in frame height units, the angle in radians.
// The ellipse axes are u = (cos a, sin a) and v = (-sin a, cos a) in aspect corrected space, exactly as in the shader.
// ------------------------------------------------------------------------------------------------------------------

enum class RadialPart { CENTER, X_POS, X_NEG, Y_POS, Y_NEG, ROTATE, BODY }

/** View space positions of the radial handles. [rotate] sits on a stem past the Y_NEG handle. */
data class RadialView(val center: V2, val xPos: V2, val xNeg: V2, val yPos: V2, val yNeg: V2, val rotate: V2)

object RadialGeom {
    const val MIN_R = 0.01f
    const val MAX_R = 2f

    fun padded(p: List<Float>): List<Float> = listOf(
        p.getOrElse(0) { 0.5f }, p.getOrElse(1) { 0.5f }, p.getOrElse(2) { 0.3f }, p.getOrElse(3) { 0.3f }, p.getOrElse(4) { 0f }, p.getOrElse(5) { 0.5f },
    )

    /** Frame position of a point at (lx, ly) along the ellipse axes from the centre, both in height units. */
    fun framePoint(p: List<Float>, asp: Float, lx: Float, ly: Float): V2 {
        val q = padded(p); val a = q[4]
        val wx = lx * cos(a) - ly * sin(a); val wy = lx * sin(a) + ly * cos(a)
        return V2(q[0] + wx / asp, q[1] + wy)
    }

    /** Maps frame positions to the view with [toView], then adds the rotate grip [stemPx] beyond the top handle, in view space. */
    fun viewHandles(p: List<Float>, asp: Float, stemPx: Float, toView: (V2) -> V2): RadialView {
        val q = padded(p)
        val c = toView(V2(q[0], q[1]))
        val xp = toView(framePoint(p, asp, q[2], 0f)); val xn = toView(framePoint(p, asp, -q[2], 0f))
        val yp = toView(framePoint(p, asp, 0f, q[3])); val yn = toView(framePoint(p, asp, 0f, -q[3]))
        var d = yn - c
        val l = d.len()
        d = if (l < 1e-3f) V2(0f, -1f) else d * (1f / l)
        return RadialView(c, xp, xn, yp, yn, yn + d * stemPx)
    }

    /** Frame position inside the ellipse? */
    fun contains(p: List<Float>, asp: Float, f: V2): Boolean {
        val q = padded(p)
        val dx = (f.x - q[0]) * asp; val dy = f.y - q[1]
        val a = q[4]
        val lx = cos(a) * dx + sin(a) * dy; val ly = -sin(a) * dx + cos(a) * dy
        val rx = q[2].coerceAtLeast(1e-4f); val ry = q[3].coerceAtLeast(1e-4f)
        return (lx / rx) * (lx / rx) + (ly / ry) * (ly / ry) <= 1f
    }

    /** Priority: rotate grip, then the nearest of the four size handles, then the centre handle, then anywhere inside the shape (moves it). */
    fun hit(v: RadialView, p: V2, handleR: Float, inside: Boolean): RadialPart? {
        if (p.dist(v.rotate) <= handleR) return RadialPart.ROTATE
        val cands = listOf(RadialPart.X_POS to v.xPos, RadialPart.X_NEG to v.xNeg, RadialPart.Y_POS to v.yPos, RadialPart.Y_NEG to v.yNeg)
        val best = cands.minByOrNull { p.dist(it.second) }!!
        val bd = p.dist(best.second); val cd = p.dist(v.center)
        if (bd <= handleR && bd < cd) return best.first
        if (cd <= handleR) return RadialPart.CENTER
        if (bd <= handleR) return best.first
        return if (inside) RadialPart.BODY else null
    }

    /** Signed distance of frame position [f] along an ellipse axis from the centre, in height units. */
    private fun along(p: List<Float>, asp: Float, f: V2, axisY: Boolean): Float {
        val q = padded(p)
        val dx = (f.x - q[0]) * asp; val dy = f.y - q[1]; val a = q[4]
        return if (axisY) -sin(a) * dx + cos(a) * dy else cos(a) * dx + sin(a) * dy
    }

    /** What a drag has to remember from the moment the finger went down, so nothing hops. */
    data class Grab(val params: List<Float>, val frame: V2, val radiusOffset: Float)

    fun grab(part: RadialPart, p: List<Float>, f: V2, asp: Float): Grab {
        val q = padded(p)
        val off = when (part) {
            RadialPart.X_POS, RadialPart.X_NEG -> q[2] - abs(along(p, asp, f, false))
            RadialPart.Y_POS, RadialPart.Y_NEG -> q[3] - abs(along(p, asp, f, true))
            else -> 0f
        }
        return Grab(q, f, off)
    }

    fun drag(part: RadialPart, g: Grab, f: V2, asp: Float): List<Float> {
        val q = g.params.toMutableList()
        when (part) {
            RadialPart.CENTER, RadialPart.BODY -> {
                q[0] = (g.params[0] + (f.x - g.frame.x)).coerceIn(0f, 1f)
                q[1] = (g.params[1] + (f.y - g.frame.y)).coerceIn(0f, 1f)
            }
            RadialPart.X_POS, RadialPart.X_NEG -> q[2] = (abs(along(g.params, asp, f, false)) + g.radiusOffset).coerceIn(MIN_R, MAX_R)
            RadialPart.Y_POS, RadialPart.Y_NEG -> q[3] = (abs(along(g.params, asp, f, true)) + g.radiusOffset).coerceIn(MIN_R, MAX_R)
            RadialPart.ROTATE -> {
                val dx = (f.x - g.params[0]) * asp; val dy = f.y - g.params[1]
                if (hypot(dx, dy) > 1e-4f) q[4] = snapAngle(normaliseAngle(atan2(dx, -dy)))
            }
        }
        return q
    }

    /** The ellipse looks the same turned half a turn, so keep the angle in (-90, 90] degrees where the sliders can show it. */
    fun normaliseAngle(a: Float): Float {
        var r = a
        val pi = PI.toFloat()
        while (r > pi / 2f) r -= pi
        while (r <= -pi / 2f) r += pi
        return r
    }

    /** Snaps to 0, 45 and 90 degrees when within 3 degrees, so a level ellipse is easy to get back to. */
    fun snapAngle(a: Float): Float {
        val deg = Math.toDegrees(a.toDouble())
        for (t in listOf(-90.0, -45.0, 0.0, 45.0, 90.0)) if (abs(deg - t) <= 3.0) return Math.toRadians(t).toFloat()
        return a
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Latest wins, one at a time
// ------------------------------------------------------------------------------------------------------------------

/**
 * Runs [work] for submitted values one at a time and never more than one in flight. Values that arrive while it is busy are folded
 * with [merge] into a single pending value (so the newest recipe wins but a "force" flag from an earlier request is not lost).
 * [launch] decides where the loop runs (a coroutine on a background dispatcher in the app, the calling thread in tests).
 * A work failure is passed to [onError] and the loop carries on.
 */
class LatestWinsSerial<T : Any>(
    private val launch: (Runnable) -> Unit,
    private val merge: (old: T?, new: T) -> T = { _, n -> n },
    private val onError: (Throwable) -> Unit = {},
    private val work: (T) -> Unit,
) {
    private val lock = Any()
    private var pending: T? = null
    private var running = false

    fun submit(v: T) {
        val start = synchronized(lock) {
            pending = merge(pending, v)
            if (running) false else { running = true; true }
        }
        if (start) {
            try { launch(Runnable { drain() }) } catch (t: Throwable) { synchronized(lock) { running = false }; throw t }
        }
    }

    private fun drain() {
        while (true) {
            val v = synchronized(lock) {
                val p = pending
                pending = null
                if (p == null) running = false
                p
            } ?: return
            try { work(v) } catch (t: Throwable) { if (t is InterruptedException) { synchronized(lock) { running = false }; throw t }; onError(t) }
        }
    }

    val isIdle: Boolean get() = synchronized(lock) { !running && pending == null }
}
