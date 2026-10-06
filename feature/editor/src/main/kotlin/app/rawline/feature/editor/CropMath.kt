package app.rawline.feature.editor

import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Geometry
import app.rawline.core.model.Optics
import app.rawline.core.render.Geo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A crop rectangle in normalised units of the oriented, straightened but uncropped frame (the units Geometry stores). */
data class CropRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val w: Float get() = x1 - x0
    val h: Float get() = y1 - y0
    val cx: Float get() = (x0 + x1) / 2f
    val cy: Float get() = (y0 + y1) / 2f

    fun translate(dx: Float, dy: Float) = CropRect(x0 + dx, y0 + dy, x1 + dx, y1 + dy)
    fun applyTo(g: Geometry): Geometry = g.copy(cropX = x0, cropY = y0, cropW = w, cropH = h)

    companion object {
        val Full = CropRect(0f, 0f, 1f, 1f)
        fun of(g: Geometry) = CropRect(g.cropX, g.cropY, g.cropX + g.cropW, g.cropY + g.cropH)
        fun lerp(a: CropRect, b: CropRect, t: Float) = CropRect(
            a.x0 + (b.x0 - a.x0) * t, a.y0 + (b.y0 - a.y0) * t, a.x1 + (b.x1 - a.x1) * t, a.y1 + (b.y1 - a.y1) * t,
        )
    }
}

/** What a finger landed on in the crop overlay. */
enum class CropHandle { TL, T, TR, R, BR, B, BL, L, MOVE, NONE }

/**
 * The part of the frame that actually holds picture. Straightening, keystone and distortion leave empty wedges in the frame corners;
 * a crop must stay clear of them. [inside] answers for one frame point (normalised, crop independent).
 */
class ValidArea(private val inside: (Float, Float) -> Boolean) {
    fun contains(x: Float, y: Float) = inside(x, y)

    /** True when the whole rectangle (its outline, sampled every 1/16 of a side, plus its centre) lies on picture. */
    fun contains(r: CropRect): Boolean {
        val n = 16
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = r.x0 + (r.x1 - r.x0) * t; val y = r.y0 + (r.y1 - r.y0) * t
            if (!inside(x, r.y0) || !inside(x, r.y1) || !inside(r.x0, y) || !inside(r.x1, y)) return false
        }
        return inside(r.cx, r.cy)
    }

    companion object {
        val Everything = ValidArea { _, _ -> true }

        /**
         * Built from the same CPU copy of the shader's geometry the renderer constrains with ([Geo.frameValid], so it includes
         * [Geo.EDGE_MARGIN] and the lens profile polynomial [lensDist]). The frame is already oriented, so the geometry is
         * evaluated with no extra orientation and a fake source whose shape is the frame's. Flips are irrelevant to whether a
         * point lands on picture and are left in unchanged. A box valid here is (up to sampling) the box [Geo.fitCrop] renders.
         */
        fun forGeometry(g: Geometry, distortion: Float, frameAspect: Float, lensDist: FloatArray? = null): ValidArea {
            // a level, undistorted, uncorrected picture fills its frame exactly (as in Geo.fitCrop): the whole frame is a valid crop
            if (g.angle == 0f && g.keystoneV == 0f && g.keystoneH == 0f && distortion == 0f && lensDist == null) return Everything
            val geo = g.copy(cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f, rotate90 = 0)
            val optics = Optics(lensCorrection = false, removeCa = false, distortion = distortion)
            val srcH = 1000
            val srcW = (frameAspect.coerceIn(0.05f, 20f) * srcH).roundToInt().coerceAtLeast(1)
            return ValidArea { x, y -> Geo.frameValid(x, y, geo, optics, 1, srcW, srcH, lensDist) }
        }
    }
}

/** Pure crop rectangle maths: aspect names, handle hit testing, dragging, pinch, and keeping the crop on picture. */
object CropMath {
    /** Smallest crop side as a share of the frame. */
    const val MIN_SIDE = 0.05f

    // ---------------- aspect names ----------------

    /** Pixel width over height for an aspect name, or null for Free. "original" is the frame's own shape; "a:b" is any ratio. */
    fun aspectValue(name: String, frameAspect: Float): Float? {
        val n = name.trim()
        if (n.equals("free", true)) return null
        if (n.equals("original", true)) return frameAspect
        val i = n.indexOf(':')
        if (i > 0) {
            val a = n.substring(0, i).toFloatOrNull(); val b = n.substring(i + 1).toFloatOrNull()
            if (a != null && b != null && a > 0f && b > 0f) return a / b
        }
        return null
    }

    /** A ratio that is not a preset, written so [aspectValue] reads it back. */
    fun customAspect(ratio: Float): String = String.format(java.util.Locale.US, "%.4f:1", ratio)

    /** The chip label for a stored aspect name. Anything that is not Original, Free or a clean a:b ratio reads "Custom". */
    fun label(name: String): String {
        val n = name.trim()
        if (n.equals("original", true)) return "Original"
        if (n.equals("free", true)) return "Free"
        if (n.endsWith(":1") && n.substringBefore(':').contains('.')) return "Custom"
        return n
    }

    /** Aspect name after turning the crop a quarter turn or swapping landscape and portrait: "3:2" becomes "2:3". Original and Free stay. */
    fun swapName(name: String, frameAspect: Float): String {
        val n = name.trim()
        val i = n.indexOf(':')
        if (n.equals("free", true) || n.equals("original", true) || i <= 0) return name
        val swapped = n.substring(i + 1) + ":" + n.substring(0, i)
        val v = aspectValue(swapped, frameAspect)
        return if (v != null && abs(v - frameAspect) < 1e-3f * frameAspect) "original" else swapped
    }

    /** Aspect name for "swap landscape and portrait": like [swapName], but Original becomes a custom ratio (the frame's inverse). */
    fun orientationName(name: String, frameAspect: Float): String {
        if (name.trim().equals("original", true)) return if (abs(frameAspect - 1f) < 1e-3f) name else customAspect(1f / frameAspect)
        return swapName(name, frameAspect)
    }

    // ---------------- rectangle transforms ----------------

    /** The crop after the whole frame turns a quarter turn clockwise (frame point (x, y) lands on (1 - y, x)). */
    fun rotateCw(r: CropRect) = CropRect(1f - r.y1, r.x0, 1f - r.y0, r.x1)
    /** Quarter turn anticlockwise: (x, y) lands on (y, 1 - x). */
    fun rotateCcw(r: CropRect) = CropRect(r.y0, 1f - r.x1, r.y1, 1f - r.x0)
    fun mirrorX(r: CropRect) = CropRect(1f - r.x1, r.y0, 1f - r.x0, r.y1)
    fun mirrorY(r: CropRect) = CropRect(r.x0, 1f - r.y1, r.x1, 1f - r.y0)

    /** Scales about the centre, then slides back inside the frame if that pushed it out. */
    fun scaleAbout(r: CropRect, s: Float): CropRect {
        val w = r.w * s; val h = r.h * s
        return within(CropRect(r.cx - w / 2f, r.cy - h / 2f, r.cx + w / 2f, r.cy + h / 2f))
    }

    /** Slides (and only if bigger than the frame, shrinks) the rectangle so it lies inside 0..1. */
    fun within(r: CropRect): CropRect {
        val k = min(1f, min(1f / r.w.coerceAtLeast(1e-6f), 1f / r.h.coerceAtLeast(1e-6f)))
        val w = r.w * k; val h = r.h * k
        val x0 = (r.cx - w / 2f).coerceIn(0f, 1f - w); val y0 = (r.cy - h / 2f).coerceIn(0f, 1f - h)
        return CropRect(x0, y0, x0 + w, y0 + h)
    }

    /** Swaps the crop's pixel width and height about its centre (landscape to portrait and back). [frameAspect] is frame width over height. */
    fun swapOrientation(r: CropRect, frameAspect: Float): CropRect {
        val w = r.h / frameAspect; val h = r.w * frameAspect
        return within(CropRect(r.cx - w / 2f, r.cy - h / 2f, r.cx + w / 2f, r.cy + h / 2f))
    }

    /** Trims the crop about its centre to the target pixel aspect, keeping as much as possible. */
    fun fitToAspect(r: CropRect, aspectPx: Float, frameAspect: Float): CropRect {
        var w = r.w; var h = r.h
        if (w * frameAspect / h > aspectPx) w = h * aspectPx / frameAspect else h = w * frameAspect / aspectPx
        return within(CropRect(r.cx - w / 2f, r.cy - h / 2f, r.cx + w / 2f, r.cy + h / 2f))
    }

    /** The biggest crop of the given pixel aspect (null: the frame's own) centred in the frame that stays on picture. */
    fun largest(aspectPx: Float?, frameAspect: Float, area: ValidArea): CropRect {
        val t = aspectPx ?: frameAspect
        val k = frameAspect / t                         // height = width * k in frame units
        val wMax = min(1f, 1f / k)
        fun rect(w: Float) = CropRect(0.5f - w / 2f, 0.5f - w * k / 2f, 0.5f + w / 2f, 0.5f + w * k / 2f)
        if (area.contains(rect(wMax))) return rect(wMax)
        var lo = 0f; var hi = wMax
        repeat(28) { val m = (lo + hi) / 2f; if (area.contains(rect(m))) lo = m else hi = m }
        return rect(lo)
    }

    // ---------------- keeping the crop on picture ----------------

    /**
     * [proposed] if it is entirely on picture, otherwise the furthest point of the straight line from [safe] (which must be on picture)
     * towards [proposed] that still is. With a safe rectangle that is not valid it falls back to [shrinkInto].
     */
    fun clampToValid(proposed: CropRect, safe: CropRect, area: ValidArea): CropRect {
        if (area.contains(proposed)) return proposed
        if (!area.contains(safe)) return shrinkInto(proposed, area)
        var lo = 0f; var hi = 1f
        repeat(24) { val m = (lo + hi) / 2f; if (area.contains(CropRect.lerp(safe, proposed, m))) lo = m else hi = m }
        return CropRect.lerp(safe, proposed, lo)
    }

    /**
     * Brings a crop that has been left partly off picture (by straightening, a turn, a flip) back on, keeping its shape and
     * position as far as it can: it shrinks about its own centre, or slides towards the frame centre if its own centre is off picture.
     */
    fun shrinkInto(r: CropRect, area: ValidArea): CropRect {
        if (area.contains(r)) return r
        if (area.contains(r.cx, r.cy)) {
            var lo = 0f; var hi = 1f
            repeat(28) { val m = (lo + hi) / 2f; if (area.contains(CropRect(r.cx - r.w * m / 2f, r.cy - r.h * m / 2f, r.cx + r.w * m / 2f, r.cy + r.h * m / 2f))) lo = m else hi = m }
            val s = lo
            return CropRect(r.cx - r.w * s / 2f, r.cy - r.h * s / 2f, r.cx + r.w * s / 2f, r.cy + r.h * s / 2f)
        }
        val k = r.h / r.w.coerceAtLeast(1e-6f)
        val tiny = CropRect(0.5f - 0.01f, 0.5f - 0.01f * k, 0.5f + 0.01f, 0.5f + 0.01f * k)
        return if (area.contains(tiny)) clampToValid(r, tiny, area) else tiny
    }

    // ---------------- hit testing ----------------

    /**
     * What a touch at ([px], [py]) grabs on a crop drawn at the given pixel rectangle. Corners win, then edges (a band either side of
     * the edge line, so the outside of a full-frame crop can still be grabbed), then the inside moves the crop. The radii shrink on a
     * small crop so there is always some inside left to move it by.
     */
    fun hitTest(l: Float, t: Float, r: Float, b: Float, px: Float, py: Float, cornerRadius: Float, edgeRadius: Float): CropHandle {
        val side = min(r - l, b - t)
        val cr = min(cornerRadius, side / 2.5f).coerceAtLeast(8f)
        val er = min(edgeRadius, side / 3f).coerceAtLeast(8f)
        fun d(x: Float, y: Float) = kotlin.math.hypot(px - x, py - y)
        val corners = listOf(CropHandle.TL to d(l, t), CropHandle.TR to d(r, t), CropHandle.BL to d(l, b), CropHandle.BR to d(r, b))
        val nearCorner = corners.minByOrNull { it.second }!!
        if (nearCorner.second <= cr) return nearCorner.first
        val edges = ArrayList<Pair<CropHandle, Float>>()
        if (px in l..r) { edges += CropHandle.T to abs(py - t); edges += CropHandle.B to abs(py - b) }
        if (py in t..b) { edges += CropHandle.L to abs(px - l); edges += CropHandle.R to abs(px - r) }
        val edge = edges.filter { it.second <= er }.minByOrNull { it.second }
        if (edge != null) return edge.first
        return if (px in l..r && py in t..b) CropHandle.MOVE else CropHandle.NONE
    }

    // ---------------- dragging ----------------

    /**
     * The crop after dragging [handle] by ([dx], [dy]) (total since the touch began, in frame units) from [start]. With [aspectPx] the
     * pixel aspect is kept: a corner keeps its opposite corner fixed and takes the average of what the two finger movements imply,
     * an edge keeps the opposite edge and recentres the other dimension. Never leaves the frame, never smaller than [minSide].
     * This does not know about picture area: pass the result through [clampToValid].
     */
    fun drag(start: CropRect, handle: CropHandle, dx: Float, dy: Float, aspectPx: Float?, frameAspect: Float, minSide: Float = MIN_SIDE): CropRect {
        if (handle == CropHandle.MOVE) {
            val x0 = (start.x0 + dx).coerceIn(0f, 1f - start.w); val y0 = (start.y0 + dy).coerceIn(0f, 1f - start.h)
            return CropRect(x0, y0, x0 + start.w, y0 + start.h)
        }
        if (handle == CropHandle.NONE) return start
        val left = handle == CropHandle.TL || handle == CropHandle.L || handle == CropHandle.BL
        val right = handle == CropHandle.TR || handle == CropHandle.R || handle == CropHandle.BR
        val top = handle == CropHandle.TL || handle == CropHandle.T || handle == CropHandle.TR
        val bottom = handle == CropHandle.BL || handle == CropHandle.B || handle == CropHandle.BR
        if (aspectPx == null) {
            var x0 = start.x0; var x1 = start.x1; var y0 = start.y0; var y1 = start.y1
            if (left) x0 = (start.x0 + dx).coerceIn(0f, start.x1 - minSide)
            if (right) x1 = (start.x1 + dx).coerceIn(start.x0 + minSide, 1f)
            if (top) y0 = (start.y0 + dy).coerceIn(0f, start.y1 - minSide)
            if (bottom) y1 = (start.y1 + dy).coerceIn(start.y0 + minSide, 1f)
            return CropRect(x0, y0, x1, y1)
        }
        val k = frameAspect / aspectPx                       // height = width * k
        val wMin = max(minSide, minSide / k)
        val corner = (left || right) && (top || bottom)
        if (corner) {
            val ax = if (left) start.x1 else start.x0; val ay = if (top) start.y1 else start.y0
            val sx = if (left) -1f else 1f; val sy = if (top) -1f else 1f
            val mx = (if (left) start.x0 else start.x1) + dx; val my = (if (top) start.y0 else start.y1) + dy
            val wx = max(sx * (mx - ax), 0f); val hy = max(sy * (my - ay), 0f)
            var w = (wx + hy / k) / 2f
            val wMax = min(if (sx > 0) 1f - ax else ax, (if (sy > 0) 1f - ay else ay) / k)
            w = min(max(w, wMin), wMax)
            val h = w * k
            val nx0 = if (sx > 0) ax else ax - w; val ny0 = if (sy > 0) ay else ay - h
            return CropRect(nx0, ny0, nx0 + w, ny0 + h)
        }
        if (top || bottom) {
            var h = if (top) start.y1 - (start.y0 + dy) else (start.y1 + dy) - start.y0
            val hMax = min(if (top) start.y1 else 1f - start.y0, k)
            h = min(max(h, wMin * k), hMax)
            val w = h / k
            val y0 = if (top) start.y1 - h else start.y0
            val x0 = (start.cx - w / 2f).coerceIn(0f, 1f - w)
            return CropRect(x0, y0, x0 + w, y0 + h)
        }
        var w = if (left) start.x1 - (start.x0 + dx) else (start.x1 + dx) - start.x0
        val wMax = min(if (left) start.x1 else 1f - start.x0, 1f / k)
        w = min(max(w, wMin), wMax)
        val h = w * k
        val x0 = if (left) start.x1 - w else start.x0
        val y0 = (start.cy - h / 2f).coerceIn(0f, 1f - h)
        return CropRect(x0, y0, x0 + w, y0 + h)
    }

    /** Pinch: [factor] above 1 enlarges the crop about its centre, within the frame and the minimum size. */
    fun pinch(start: CropRect, factor: Float, minSide: Float = MIN_SIDE): CropRect {
        val sMin = minSide / min(start.w, start.h)
        val sMax = min(1f / start.w, 1f / start.h)
        return scaleAbout(start, factor.coerceIn(min(sMin, sMax), max(sMin, sMax)))
    }

    /**
     * Applies a drag result while keeping the crop on picture. A move or a free resize is settled one axis at a time so it slides along
     * a slanted edge of the picture instead of sticking; an aspect locked resize has to scale as a whole.
     */
    fun settle(proposed: CropRect, last: CropRect, area: ValidArea, perAxis: Boolean): CropRect {
        if (area.contains(proposed)) return proposed
        if (!perAxis) return clampToValid(proposed, last, area)
        val xOnly = CropRect(proposed.x0, last.y0, proposed.x1, last.y1)
        val a = clampToValid(xOnly, last, area)
        val yStep = CropRect(a.x0, proposed.y0, a.x1, proposed.y1)
        return clampToValid(yStep, a, area)
    }

    // ---------------- straighten, turn, flip ----------------

    /** Straighten angle: limited to plus or minus 45, rounded to 0.01 and snapped to 0 within a quarter degree. */
    fun snapAngle(raw: Float): Float {
        val a = raw.coerceIn(-45f, 45f)
        if (abs(a) < 0.25f) return 0f
        return (a * 100f).roundToInt() / 100f
    }

    /**
     * THE single place the crop tool asks "where may the crop be?". It uses the renderer's own test ([Geo.frameValid]: edge margin,
     * straighten, keystone, distortion and the session's lens profile [lens]) so the box shown is the box rendered.
     */
    fun validArea(g: Geometry, distortion: Float, fa: Float, lens: FloatArray? = null): ValidArea = ValidArea.forGeometry(g, distortion, fa, lens)
    private fun valid(g: Geometry, distortion: Float, fa: Float, lens: FloatArray?) = validArea(g, distortion, fa, lens)

    /**
     * [base] (the crop as it was when straightening began) refitted for [angle]: it shrinks only as much as the picture needs, and grows back
     * towards [base] if the angle comes back.
     */
    fun withAngle(g: Geometry, angle: Float, base: CropRect, distortion: Float, frameAspect: Float, lens: FloatArray? = null): Geometry {
        val ng = g.copy(angle = angle)
        val r = shrinkInto(base, valid(ng, distortion, frameAspect, lens))
        return r.applyTo(ng)
    }

    /** Turns the picture a quarter turn; the crop turns with it and its aspect name swaps. [fa] is the frame aspect before the turn. */
    fun rotate(g: Geometry, clockwise: Boolean, distortion: Float, fa: Float, lens: FloatArray? = null): Geometry {
        val r = if (clockwise) rotateCw(CropRect.of(g)) else rotateCcw(CropRect.of(g))
        val name = if (g.aspect.trim().equals("original", true)) g.aspect else swapName(g.aspect, 1f / fa)
        val ng = r.applyTo(g.copy(rotate90 = (g.rotate90 + (if (clockwise) 1 else 3)) % 4, aspect = name))
        return CropRect.of(ng).let { shrinkInto(it, valid(ng, distortion, 1f / fa, lens)).applyTo(ng) }
    }

    /**
     * Mirrors the picture. The engine straightens before it flips, so a flip alone would mirror the tilt and un-level the horizon:
     * the angle changes sign with it (and so does the keystone along the mirrored axis) to keep the picture looking the same but mirrored.
     */
    fun flip(g: Geometry, horizontal: Boolean): Geometry {
        val r = if (horizontal) mirrorX(CropRect.of(g)) else mirrorY(CropRect.of(g))
        val ng = if (horizontal) g.copy(flipH = !g.flipH, keystoneH = -g.keystoneH, angle = -g.angle) else g.copy(flipV = !g.flipV, keystoneV = -g.keystoneV, angle = -g.angle)
        return r.applyTo(ng)
    }

    /** Swap landscape and portrait: the crop's width and height trade places about its centre and the aspect name follows. */
    fun swapLandscapePortrait(g: Geometry, distortion: Float, fa: Float, lens: FloatArray? = null): Geometry {
        val r = swapOrientation(CropRect.of(g), fa)
        val ng = r.applyTo(g.copy(aspect = orientationName(g.aspect, fa)))
        return shrinkInto(CropRect.of(ng), valid(ng, distortion, fa, lens)).applyTo(ng)
    }

    /** Picks an aspect: the current crop is trimmed about its centre to the new shape and kept on picture. */
    fun pickAspect(g: Geometry, name: String, distortion: Float, fa: Float, lens: FloatArray? = null): Geometry {
        val t = aspectValue(name, fa)
        val ng0 = g.copy(aspect = name)
        if (t == null) return ng0
        val r = fitToAspect(CropRect.of(g), t, fa)
        val ng = r.applyTo(ng0)
        return shrinkInto(CropRect.of(ng), valid(ng, distortion, fa, lens)).applyTo(ng)
    }

    /** Picks an aspect and takes the biggest crop of that shape the picture allows. */
    fun pickAspectLargest(g: Geometry, name: String, distortion: Float, fa: Float, lens: FloatArray? = null): Geometry {
        val ng = g.copy(aspect = name)
        return largest(aspectValue(name, fa), fa, valid(ng, distortion, fa, lens)).applyTo(ng)
    }

    /** Resets the crop to the biggest one of the current aspect that stays on picture. */
    fun resetCrop(g: Geometry, distortion: Float, fa: Float, lens: FloatArray? = null): Geometry =
        largest(aspectValue(g.aspect, fa), fa, valid(g, distortion, fa, lens)).applyTo(g)
}

/** The recipe with its straighten angle set and the crop refitted so it never shows empty frame. Used by Auto level and the dial alike. */
fun EditRecipe.withStraighten(angle: Float, frameAspect: Float, base: CropRect = CropRect.of(geometry), lens: FloatArray? = null): EditRecipe =
    copy(geometry = CropMath.withAngle(geometry, angle, base, optics.distortion, frameAspect, lens))

/** The lens profile polynomial the renderer applies for this recipe (null when lens correction is off or no profile), as the crop tool must also use it. */
fun app.rawline.core.render.EditorSession.cropLens(r: EditRecipe): FloatArray? = app.rawline.core.render.RenderParams.lensDistFor(r, lens)

/**
 * The geometry with its crop replaced by the crop the renderer actually shows ([Geo.constrainGeometry]), so the stored crop equals the
 * rendered one. Returns [g] itself (same instance) when they already agree to within [tolerance], so nothing needs saving.
 */
fun fitStoredCrop(g: Geometry, o: Optics, orientation: Int, srcW: Int, srcH: Int, lens: FloatArray?, tolerance: Float = 1e-4f): Geometry {
    val c = Geo.constrainGeometry(g, o, orientation, srcW, srcH, lens)
    val same = abs(c.cropX - g.cropX) <= tolerance && abs(c.cropY - g.cropY) <= tolerance && abs(c.cropW - g.cropW) <= tolerance && abs(c.cropH - g.cropH) <= tolerance
    return if (same) g else c
}
