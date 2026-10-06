# W19 Studio S1b: canvas, brush, eraser, move and scale, layers panel, history, autosave

Written 6 Oct 2026 against main ee38b8b. Starts after W18 (S1a) has merged. Parent: `docs/STUDIO_SPEC.md` section 7, milestone S1, slice 2 of 3 (BK-398). Next: S1c (home, mode switch, `studio.db`, flatten export).

What was verified here, so a worker knows where the risk is: the pure Kotlin in section 7 was compiled with the Kotlin 2.4.10 compiler and its 40 tests run under JUnit 4.13.2 together with the S1a model (the stroke walker, stamp coverage, commit, view maths, history, input router, pixel container and the crash safe project store, including a test that kills the store at every file operation of 40 saves); the GPU brush (stamp shader, R16F stroke buffer, live preview through the compositor) was built and run on Mesa llvmpipe against an independent Python reference in five scenes (hard, soft, low flow, pressure, erase), all within 1 level; two deliberately broken shaders fail those scenes; `jni_studio.cpp` was syntax checked against the JDK's `jni.h`. Not verified here, and so the real work of this task: all Compose and Android code (canvas view, GL surface, panels, picker, tool options, gestures from real `MotionEvent`s, autosave thread, Copy report rows), the Gradle and CMake wiring, and every phone number.

## 1. Scope

In: Studio canvas screen (debug entry only; the real entry is S1c), layers panel, Brush and Eraser, Move and Scale, minimal HSV colour picker, undo and redo buttons, pan and zoom with two fingers, a new project (blank, 12 MP cap shown) or a photo from the system picker as the first layer, autosave with crash recovery, Copy report rows.
Out (later milestones): tiles (S2), selections (S4), other blend modes (S3), stabiliser and brush presets (S7), text and shapes (S8), two-finger and three-finger tap undo (the S2 recogniser), the mode switch and home screen (S1c).
The earlier NEXT 25 text for this task left Move and Scale out by mistake; the spec's S1 list has them, so they are in here (section 5).

## 2. Decisions made here (so the worker does not ask)

| # | Decision | Reason |
|---|---|---|
| D1 | Layer pixels are saved as `PixelContainer` (deflated straight RGBA8, section 7), not lossless WebP | Android bitmap codecs premultiply alpha and lose colour under low alpha; the container is lossless by construction, JVM testable, and a sparse paint layer shrinks to almost nothing. S2 revisits WebP with libwebp directly and a device test that every alpha level survives. This is a deviation from the spec's S1 text and is recorded in docs/DECISIONS.md by this task. |
| D2 | A stroke is stamped on the GPU into an R16F coverage buffer (`over` accumulation), shown live through the compositor, and committed on pointer up by reading the dirty rectangle back and baking it on the CPU copy | Spec 2.4 and 3.3 pipeline. One definition of the result (`StrokeReference.commitRect`) means what is saved is what was previewed (GPU and reference agree within 1 level in the golden). R16F because 32 bit float blending is not guaranteed in ES 3.2. |
| D3 | History keeps before and after pixels of the stroke's dirty rectangle (200 MB budget, 100 entries), not whole-layer snapshots | Smaller and simpler than the spec's allowed S1 shortcut (10 whole layer snapshots), and byte identical on undo (fuzz test). Layer operations are document states in the same linear history (`StudioHistory`). |
| D4 | CPU copy of the ACTIVE layer only; other layers live on disk and in GPU textures | A 12 MP layer is 48 MB; ten CPU copies would be 480 MB. Switching the active layer flushes a save, drops the old copy and decodes the new one. |
| D5 | GPU budget guard: adding a layer or creating a canvas that would take layer textures plus the ping-pong pair and stroke buffer over 600 MB is refused with "Not enough memory for another layer at this size." | S1 has no tile cache; 10 full layers at 12 MP is 480 MB plus about 60 MB of targets. `Compositor.textureBytes()` is the figure. |
| D6 | Colours are straight 0 to 1 in the document's blend space (gamma, as stored); the picker's HSV is that space | Matches S1a (GAMMA only). |
| D7 | Strokes and layer transforms are in LAYER pixels; the session converts screen to layer with the view and the layer's x, y, scale | A moved or scaled layer is painted where the finger is, at the layer's own resolution. |
| D8 | Autosave: at stroke end, after a layer operation, on pause and on Back, at most every 5 s while dirty, on one background thread, never on the GL thread; the save is coalesced (a save in flight is followed by one more if dirty again) | Spec 2.15. |
| D9 | Pen: a stylus draws and a finger never paints while a stylus is down (palm rejection); a second finger cancels a finger stroke and becomes a two finger pan and zoom, with no history entry | Spec 2.14 subset, `InputRouter`. |

## 3. Architecture

```
MotionEvent (Compose pointerInput / GLSurfaceView)
   -> InputEvent (per pointer, screen px)         InputRouter (pure, tested)        -> Action
Action -> StudioSession.onAction                  (skeleton in section 7; all StudioNative calls go through post on the GL thread)
   StrokeStart : beginStroke(slot, colour, opacity, erase, hardness, flow) ; StrokeWalker.add(point) -> stamps
   StrokeMove  : walker.add -> new stamps -> addStamps(xyr) -> requestRender            (live preview = compositor with uStroke)
   StrokeEnd   : Dirty.rect(stamps) -> readStroke(rect) -> StrokeReference.commitRect on the CPU copy -> updateLayerRegion(rect)
                 -> history.commitStroke(PixelDelta(before, after)) -> endStroke -> markDirty (autosave)
   StrokeCancel: endStroke, nothing else (no history entry)
   GestureUpdate: view = view.zoomAbout(scale, cx, cy).panBy(dx, dy).clamped(...) -> requestRender
Undo/redo: StudioHistory.undo() -> Step.SetPixels (rect only: CPU copy + updateLayerRegion) or Step.SetDocument (sync slots)
Render: StudioNative.render(layers[6 floats each], view.x, view.y, view.zoom, outW, outH, out) -> bitmap/GL display (see below)
Save: ProjectStore.save(doc, pixels, changed) on the autosave thread; ProjectStore.open() at load (opens the newest complete generation)
```
Display of the compositor output: S1b keeps it simple and correct: the compositor renders straight RGBA8 at screen size; a second small pass (or the Compose `Canvas` with `drawImage`) puts it over a checkerboard. Premultiply for display only. The studio GL view is a separate `GLSurfaceView` with its own context (spec 3.3); Develop's engine is not touched. If drawing the result through Compose is too slow on the phone, the fix is a display pass in the compositor (later), not a change to the maths.

Files and ownership: new `core/studio-render/**` (the session, JNI plumbing, autosave thread, Android codecs for the photo import), new `feature/studio/**` (canvas, panels), `app/src/debug/**` (`StudioDebugActivity`), additions inside `core/studio-model` (section 7), and the native additions in section 8. No Develop file changes.

## 4. Memory and thread rules

- GPU: layer textures 4 bytes a pixel each, ping-pong pair 2 x 8 bytes a pixel of the output (screen sized, about 36 MB at 3120 x 1440), resolve target 4 bytes, stroke buffer 2 bytes a pixel of the active layer (24 MB at 12 MP). Guard D5.
- CPU: the active layer (48 MB at 12 MP), history deltas (at most 200 MB), the encoded save (transient). `onTrimMemory`: drop to the active layer only and trim history to 50 MB (entries are dropped oldest first; tell the user once with a toast "Older undo steps were cleared to free memory").
- Threads: GL thread (compositor, stroke), main (UI), one autosave thread (encode and file writes), pixel decode on a worker when switching layer. No file or codec work on the GL thread.

## 5. UI to build (Compose, Lr components, spec section 5; none of this was compiled here)

- Canvas screen portrait: 44 dp status strip (close, project name, undo, redo, overflow with Export placeholder disabled in S1b), canvas on black, floating 40 dp colour chips bottom right (foreground and background; tap opens the picker), tool options row 64 dp (Brush and Eraser: Size, Hardness, Opacity, Flow sliders, each with the value shown and a numeric field via `SliderInput`; Move and Scale: numeric scale 25 to 400 percent), tool rail (Layers, Brush, Eraser, Move, Scale) 56 dp, selected tile `#303030`, accent `#437EE4`. Landscape per spec section 5 (tool column left, 280 dp panel right).
- Layers panel: list bottom to top shown top first; row 56 dp: thumbnail 40 dp (refreshed within 200 ms of a stroke ending, from the CPU copy of the active layer, 96 px), name, eye 48 dp, lock, blend chip (Normal, Multiply, Screen), opacity chip (slider on tap). Reorder with 48 dp up and down buttons (also the TalkBack actions "Move layer up" and "Move layer down"), add (pixel), duplicate, delete (confirm only for a layer with pixels). Cap message from `LayerOps.add` is shown as is.
- Move: drag moves the active layer (`LayerOps.setPlacement` per drag step, one history entry on release). Scale: two fingers pinch scales the active layer about their centre (`Placement.scaleAbout`), numeric field in the options row; both snap x and y to whole document pixels.
- Colour picker: a sheet with hue bar, saturation and value square, RGB hex field; uses `Hsv`. Recent colours: 8 chips kept in memory per project (persisting them is S2).
- Errors in plain Australian English, no em dashes: "Layer is locked", "Layer is hidden", "A project can have 10 layers for now. Delete one to add another.", "Not enough memory for another layer at this size.", "This project was made by a newer version of Rawline." (open read-only, no save), "Recovered from autosave" (one line on open when `OpenResult.recovered`).

## 6. Copy report and PERF rows

Timers (reuse PerfLog): `studio_frame_ms` (per render), `studio_stroke_stamp_ms` (addStamps), `studio_commit_ms`, `studio_autosave_ms`, `studio_input_to_pixel_ms` (event time to the render that shows it; record, do not claim a target), plus gauges `studio_texture_mb` (`textureBytes`), `studio_history_mb`. A Studio section in the report with canvas size, layer count and the 600 MB guard state. docs/PERF.md rows stay "not measured" until a pasted report fills them.

## 7. Tested source (copy verbatim into core/studio-model; the golden and Python tools are in section 8)

Generated test resources (commit them; do not hand edit):
```
python3 tools/studio/studio_brush.py vectors core/studio-model/src/test/resources/walker_vectors.tsv
python3 tools/studio/studio_brush.py small   core/studio-model/src/test/resources/stroke_small.txt
```
The S1a resources (`blend_vectors.tsv`, `scene_small.txt`, `sample_v1.json`) already exist.

### Brush.kt (StrokePoint, Stamp, Brush, BrushMath, StrokeWalker, Dirty, StrokeReference)
```kotlin
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
```
### ViewMath.kt (CanvasView, Placement, Hsv)
```kotlin
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
```
### History.kt (PixelDelta, Step, StudioHistory)
```kotlin
package app.rawline.core.studio.model

/** Before and after pixels of one rectangle of one layer (straight RGBA8, row 0 top, tightly packed rect rows). */
class PixelDelta(val x: Int, val y: Int, val w: Int, val h: Int, val before: ByteArray, val after: ByteArray) {
    val bytes: Long get() = (before.size + after.size).toLong()

    /** Writes [src] (the before or the after bytes) into [layer] (layerW wide). */
    fun apply(layer: ByteArray, layerW: Int, src: ByteArray) {
        for (r in 0 until h) System.arraycopy(src, r * w * 4, layer, ((y + r) * layerW + x) * 4, w * 4)
    }

    companion object {
        /** Cuts the rectangle out of [layer]. */
        fun cut(layer: ByteArray, layerW: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
            val out = ByteArray(w * h * 4)
            for (r in 0 until h) System.arraycopy(layer, ((y + r) * layerW + x) * 4, out, r * w * 4, w * 4)
            return out
        }
    }
}

/** What the session must do for an undo or a redo. */
sealed class Step {
    /** Replace the document (layer stack operations). The pixels of layers that are unchanged stay as they are. */
    class SetDocument(val document: Document) : Step()
    /** Write [bytes] (the delta's before or after) into the rectangle of the layer, in the CPU copy and in the GPU texture. */
    class SetPixels(val layerId: String, val delta: PixelDelta, val bytes: ByteArray) : Step()
}

/**
 * One linear history for layer operations and strokes (spec 2.13 in the S1 form): document states for layer operations, pixel deltas for
 * strokes. Bounded by [maxEntries] and by [maxBytes] of pixel deltas (the oldest entries go first). A new edit after an undo drops the redo tail.
 */
class StudioHistory(initial: Document, private val maxEntries: Int = 100, private val maxBytes: Long = 200L * 1024 * 1024) {
    private sealed class Entry {
        class Doc(val before: Document, val after: Document) : Entry()
        class Stroke(val layerId: String, val delta: PixelDelta) : Entry()
    }
    private val undo = ArrayDeque<Entry>()
    private val redo = ArrayDeque<Entry>()
    var document: Document = initial
        private set
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    var deltaBytes = 0L
        private set

    /** Records a layer operation result. An identical document is not recorded. */
    fun commitDocument(next: Document) {
        if (next == document) return
        push(Entry.Doc(document, next)); document = next
    }

    /** Records a finished stroke. The pixels are already in place; only the delta is kept. */
    fun commitStroke(layerId: String, delta: PixelDelta) { push(Entry.Stroke(layerId, delta)); deltaBytes += delta.bytes; trim() }

    /** Replaces the current document without a history entry (the storage layer filling in pixel file names after a save). */
    fun replaceCurrent(doc: Document) { document = doc }

    fun undo(): Step? {
        val e = undo.removeLastOrNull() ?: return null
        redo.addLast(e)
        return when (e) {
            is Entry.Doc -> { document = e.before; Step.SetDocument(e.before) }
            is Entry.Stroke -> Step.SetPixels(e.layerId, e.delta, e.delta.before)
        }
    }

    fun redo(): Step? {
        val e = redo.removeLastOrNull() ?: return null
        undo.addLast(e)
        return when (e) {
            is Entry.Doc -> { document = e.after; Step.SetDocument(e.after) }
            is Entry.Stroke -> Step.SetPixels(e.layerId, e.delta, e.delta.after)
        }
    }

    private fun push(e: Entry) {
        undo.addLast(e)
        dropRedo()
        trim()
    }

    private fun dropRedo() { for (e in redo) if (e is Entry.Stroke) deltaBytes -= e.delta.bytes; redo.clear() }

    private fun trim() {
        while (undo.size > maxEntries || (deltaBytes > maxBytes && undo.size > 1)) {
            val e = undo.removeFirst()
            if (e is Entry.Stroke) deltaBytes -= e.delta.bytes
        }
    }
}
```
### InputRouter.kt
```kotlin
package app.rawline.core.studio.model

import kotlin.math.hypot

enum class PointerKind { FINGER, STYLUS }
enum class Phase { DOWN, MOVE, UP, CANCEL }

/** One pointer event in screen pixels, already split per pointer (the Compose layer turns a MotionEvent and its historical samples into these). */
class InputEvent(val phase: Phase, val pointerId: Int, val x: Float, val y: Float, val pressure: Float, val kind: PointerKind, val timeMs: Long = 0)

/** What the canvas must do. Strokes use screen coordinates; the session converts them to layer pixels with the current view. */
sealed class Action {
    class StrokeStart(val x: Float, val y: Float, val pressure: Float, val kind: PointerKind) : Action()
    class StrokeMove(val x: Float, val y: Float, val pressure: Float) : Action()
    object StrokeEnd : Action()
    /** Roll the stroke back without a history entry (a second finger landed, the system cancelled). */
    object StrokeCancel : Action()
    class GestureStart(val cx: Float, val cy: Float) : Action()
    /** Pan by (dx, dy) screen pixels of the centre, zoom by [scale] about ([cx], [cy]). */
    class GestureUpdate(val dx: Float, val dy: Float, val scale: Float, val cx: Float, val cy: Float) : Action()
    object GestureEnd : Action()
}

/**
 * Pointer routing of the canvas (spec 2.14 S1 subset), a pure state machine over [InputEvent]:
 * - one pointer draws (finger or pen); a second FINGER landing cancels a stroke begun by a finger and starts a two finger pan and zoom;
 * - palm rejection: while a stylus draws, finger touches are ignored; a finger never starts a stroke while a stylus is down;
 * - during and after a two finger gesture the remaining finger does nothing until every pointer is up (no stray stroke at the end of a pinch);
 * - a stylus landing during a gesture is ignored until the gesture ends.
 */
class InputRouter {
    private enum class State { IDLE, STROKING, GESTURE, WAIT_FOR_UP }
    private var state = State.IDLE
    private var stroke = -1
    private var strokeKind = PointerKind.FINGER
    private val pos = LinkedHashMap<Int, FloatArray>()   // pointers of the gesture, by id
    private val down = HashSet<Int>()
    private var lastCx = 0f; private var lastCy = 0f; private var lastDist = 1f

    fun onEvent(e: InputEvent): List<Action> {
        val out = ArrayList<Action>(2)
        when (e.phase) {
            Phase.DOWN -> {
                down.add(e.pointerId)
                when (state) {
                    State.IDLE -> { state = State.STROKING; stroke = e.pointerId; strokeKind = e.kind; out += Action.StrokeStart(e.x, e.y, e.pressure, e.kind) }
                    State.STROKING -> {
                        if (strokeKind == PointerKind.STYLUS && e.kind == PointerKind.FINGER) return out    // palm
                        if (e.kind == PointerKind.STYLUS) return out
                        out += Action.StrokeCancel
                        val first = strokePos ?: floatArrayOf(e.x, e.y)
                        pos.clear(); pos[stroke] = first; pos[e.pointerId] = floatArrayOf(e.x, e.y)
                        state = State.GESTURE; beginGesture(); out += Action.GestureStart(lastCx, lastCy)
                    }
                    State.GESTURE, State.WAIT_FOR_UP -> {}
                }
            }
            Phase.MOVE -> when (state) {
                State.STROKING -> if (e.pointerId == stroke) { strokePos = floatArrayOf(e.x, e.y); out += Action.StrokeMove(e.x, e.y, e.pressure) }
                State.GESTURE -> if (pos.containsKey(e.pointerId)) { pos[e.pointerId] = floatArrayOf(e.x, e.y); update(out) }
                else -> {}
            }
            Phase.UP, Phase.CANCEL -> {
                down.remove(e.pointerId)
                when (state) {
                    State.STROKING -> if (e.pointerId == stroke) { out += if (e.phase == Phase.UP) Action.StrokeEnd else Action.StrokeCancel; state = State.IDLE; strokePos = null }
                    State.GESTURE -> if (pos.containsKey(e.pointerId)) { out += Action.GestureEnd; pos.clear(); state = if (down.isEmpty()) State.IDLE else State.WAIT_FOR_UP }
                    else -> {}
                }
                if (down.isEmpty() && state == State.WAIT_FOR_UP) state = State.IDLE
            }
        }
        if (e.phase == Phase.DOWN && state == State.STROKING && e.pointerId == stroke) strokePos = floatArrayOf(e.x, e.y)
        return out
    }

    private var strokePos: FloatArray? = null

    private fun beginGesture() {
        val (cx, cy, d) = measure(); lastCx = cx; lastCy = cy; lastDist = d.coerceAtLeast(1f)
    }

    private fun update(out: MutableList<Action>) {
        val (cx, cy, d) = measure()
        val dist = d.coerceAtLeast(1f)
        out += Action.GestureUpdate(cx - lastCx, cy - lastCy, dist / lastDist, cx, cy)
        lastCx = cx; lastCy = cy; lastDist = dist
    }

    private fun measure(): Triple<Float, Float, Float> {
        val p = pos.values.toList()
        val a = p[0]; val b = p[1]
        return Triple((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, hypot(a[0] - b[0], a[1] - b[1]))
    }
}
```
### ProjectStore.kt (Fs, RawPixels, PixelContainer, OpenedFrom, OpenResult, ProjectStore)
```kotlin
package app.rawline.core.studio.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The file system as the store needs it. [rename] is atomic and replaces the target (POSIX rename); [write] is not atomic: a process that dies in the middle leaves a torn file. */
interface Fs {
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray?
    fun write(path: String, data: ByteArray)
    fun rename(from: String, to: String)
    fun delete(path: String)
    /** File names (not paths) directly inside [dir]. */
    fun list(dir: String): List<String>
}

/** Straight RGBA8 pixels of one layer. */
class RawPixels(val w: Int, val h: Int, val rgba: ByteArray) {
    init { require(rgba.size == w * h * 4) { "pixel buffer is ${rgba.size} bytes, expected ${w * h * 4}" } }
}

/**
 * Layer pixel container used in S1: "RLPX", version 1, width, height (big endian ints), then the straight RGBA8 bytes deflated. Lossless by
 * construction (no premultiplication, which Android bitmap codecs apply and which loses colour at low alpha); sparse paint layers shrink to
 * almost nothing. S2 replaces it by lossless WebP tiles written through libwebp, after a device test proves straight alpha survives.
 */
object PixelContainer {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), 'X'.code.toByte())

    fun encode(p: RawPixels): ByteArray {
        val out = ByteArrayOutputStream(1024 + p.rgba.size / 8)
        out.write(MAGIC); out.write(1)
        out.write(ByteBuffer.allocate(8).putInt(p.w).putInt(p.h).array())
        val d = Deflater(1)
        d.setInput(p.rgba); d.finish()
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** Throws [ProjectFormatException] for anything that is not a complete container. */
    fun decode(bytes: ByteArray): RawPixels {
        if (bytes.size < 13 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4].toInt() != 1) throw ProjectFormatException("not a layer pixel file")
        val bb = ByteBuffer.wrap(bytes, 5, 8)
        val w = bb.getInt(); val h = bb.getInt()
        if (w !in 1..Document.MAX_EDGE || h !in 1..Document.MAX_EDGE || w.toLong() * h > Document.MAX_PIXELS_S1) throw ProjectFormatException("layer pixel file has an invalid size")
        val out = ByteArray(w * h * 4)
        val inf = Inflater()
        try {
            inf.setInput(bytes, 13, bytes.size - 13)
            var n = 0
            while (n < out.size) {
                val k = inf.inflate(out, n, out.size - n)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            if (n != out.size) throw ProjectFormatException("layer pixel file is cut short")
        } catch (e: DataFormatException) { throw ProjectFormatException("layer pixel file is damaged", e) }
        finally { inf.end() }
        return RawPixels(w, h, out)
    }
}

/** Which of the three generations of project.json was opened. BACKUP means the newest one was unreadable: show "Recovered from autosave". */
enum class OpenedFrom { NEW, CURRENT, BACKUP }

class OpenResult(val document: Document, val from: OpenedFrom) { val recovered get() = from == OpenedFrom.BACKUP }

/**
 * Project directory (spec 2.15, S1 form): `project.json` (+ `.new` while committing, `.bak` the generation before), and
 * `layers/{layerId}-{hash12}.rlpx`, named by content so a file a previous generation refers to is never overwritten.
 *
 * Save protocol, every step safe to be killed after:
 *  1. each changed layer: write `layers/x.tmp`, rename to its hashed name (skipped when that file already exists);
 *  2. write `project.json.tmp`, rename to `project.json.new`  (the new generation is complete and visible);
 *  3. rename `project.json` to `project.json.bak`;
 *  4. rename `project.json.new` to `project.json`;
 *  5. delete layer files neither generation refers to, and stray `.tmp` files.
 * Open picks the newest complete generation: `.new`, `project.json`, `.bak` in that order of preference among those that parse and whose
 * layer files all exist and decode; a damaged newest generation falls back to the one before and reports it.
 */
class ProjectStore(private val fs: Fs, private val root: String, private val appVersion: String = "0") {
    private val json = "$root/project.json"
    private val jsonNew = "$root/project.json.new"
    private val jsonBak = "$root/project.json.bak"

    /** Writes [doc] and returns it with the pixel file names filled in for the layers in [changed] (their pixels come from [pixels]). Layers not in [changed] keep their file. */
    fun save(doc: Document, pixels: (Layer.Pixel) -> RawPixels?, changed: Set<String>): Document {
        val layers = doc.layers.map { l ->
            val p = l as? Layer.Pixel ?: return@map l
            if (p.common.id !in changed && p.pixelsFile != null) return@map l
            val px = pixels(p)
            if (px == null) return@map p.copy(pixelsFile = null)
            val bytes = PixelContainer.encode(px)
            val name = "layers/${p.common.id}-${hash12(bytes)}.rlpx"
            if (!fs.exists("$root/$name")) {
                fs.write("$root/$name.tmp", bytes)
                fs.rename("$root/$name.tmp", "$root/$name")
            }
            p.copy(pixelsFile = name)
        }
        val saved = doc.copy(layers = layers)
        fs.write("$json.tmp", ProjectJson.write(saved, appVersion).toByteArray(Charsets.UTF_8))
        fs.rename("$json.tmp", jsonNew)
        if (fs.exists(json)) fs.rename(json, jsonBak)
        fs.rename(jsonNew, json)
        collect(saved)
        return saved
    }

    /** Opens the newest complete generation. Throws [ProjectFormatException] when none is usable and [NewerSchemaException] for a project from a newer app (open read-only, never save it back). */
    fun open(): OpenResult {
        var newer: NewerSchemaException? = null
        var firstError: ProjectFormatException? = null
        for ((path, from) in listOf(jsonNew to OpenedFrom.NEW, json to OpenedFrom.CURRENT, jsonBak to OpenedFrom.BACKUP)) {
            val bytes = fs.read(path) ?: continue
            try {
                val doc = ProjectJson.read(String(bytes, Charsets.UTF_8))
                verify(doc)
                // a candidate that is older than a damaged newer one is still the best there is; a valid .new always wins (it is complete before it becomes visible)
                return OpenResult(doc, from)
            } catch (e: NewerSchemaException) { newer = e; break }
            catch (e: ProjectFormatException) { if (firstError == null) firstError = e }
        }
        newer?.let { throw it }
        throw firstError ?: ProjectFormatException("There is no project here.")
    }

    /** The pixels of a layer, or null for a layer that is still transparent. */
    fun load(layer: Layer.Pixel): RawPixels? {
        val f = layer.pixelsFile ?: return null
        val px = PixelContainer.decode(fs.read("$root/$f") ?: throw ProjectFormatException("layer file $f is missing"))
        if (px.w != layer.width || px.h != layer.height) throw ProjectFormatException("layer file $f has the wrong size")
        return px
    }

    private fun verify(doc: Document) {
        for (l in doc.layers) if (l is Layer.Pixel) load(l)
    }

    private fun collect(current: Document) {
        val keep = HashSet<String>()
        keep += current.layers.mapNotNull { (it as? Layer.Pixel)?.pixelsFile?.substringAfter("layers/") }
        fs.read(jsonBak)?.let { b -> try { ProjectJson.read(String(b, Charsets.UTF_8)).layers.forEach { l -> (l as? Layer.Pixel)?.pixelsFile?.let { keep += it.substringAfter("layers/") } } } catch (e: Exception) { /* an unreadable .bak refers to nothing */ } }
        for (n in fs.list("$root/layers")) if (n !in keep) fs.delete("$root/layers/$n")
        if (fs.exists("$json.tmp")) fs.delete("$json.tmp")
    }

    private fun hash12(b: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(12)
}
```
### JavaFs.kt
```kotlin
package app.rawline.core.studio.model

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** [Fs] on real files under [base]. [write] syncs before returning, so a renamed file is complete even after a power cut. */
class JavaFs(private val base: File) : Fs {
    private fun f(path: String) = File(base, path)

    override fun exists(path: String) = f(path).exists()
    override fun read(path: String): ByteArray? = f(path).takeIf { it.isFile }?.readBytes()

    override fun write(path: String, data: ByteArray) {
        val file = f(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(data); it.fd.sync() }
    }

    override fun rename(from: String, to: String) {
        Files.move(f(from).toPath(), f(to).toPath(), StandardCopyOption.ATOMIC_MOVE)   // rename(2): replaces the target atomically
    }

    override fun delete(path: String) { f(path).delete() }
    override fun list(dir: String): List<String> = f(dir).list()?.toList() ?: emptyList()
}
```
### ProjectStoreTest.kt (includes the kill-at-every-file-operation test)
```kotlin
package app.rawline.core.studio.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/** In memory file system. Keys are full paths; directories are implied. */
open class MemFs : Fs {
    val files = LinkedHashMap<String, ByteArray>()
    override fun exists(path: String) = files.containsKey(path)
    override fun read(path: String) = files[path]
    override fun write(path: String, data: ByteArray) { files[path] = data.copyOf() }
    override fun rename(from: String, to: String) { files[to] = files.remove(from) ?: throw IllegalStateException("no $from") }
    override fun delete(path: String) { files.remove(path) }
    override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") && !it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1) }
}

class Crash : RuntimeException("simulated kill")

/** Dies at the [failAt]th mutating operation. A write that dies leaves half the bytes (a torn file); a rename or delete that dies does not happen. */
class CrashFs(private val inner: MemFs, private val failAt: Int) : Fs by inner {
    var ops = 0
    private fun tick(): Boolean = (ops++ == failAt)
    override fun write(path: String, data: ByteArray) { if (tick()) { inner.write(path, data.copyOf(data.size / 2)); throw Crash() }; inner.write(path, data) }
    override fun rename(from: String, to: String) { if (tick()) throw Crash(); inner.rename(from, to) }
    override fun delete(path: String) { if (tick()) throw Crash(); inner.delete(path) }
}

class ProjectStoreTest {
    private val root = "files/studio/p1"
    private fun px(seed: Int, w: Int = 16, h: Int = 12): RawPixels { val r = Random(seed.toLong()); return RawPixels(w, h, ByteArray(w * h * 4).also { r.nextBytes(it) }) }
    private fun doc(vararg ids: String) = Document("p1", "P", 16, 12, layers = ids.map { Layer.Pixel(LayerCommon(it, it), 16, 12) }, modified = 1)

    @Test fun containerRoundTripsEveryByteIncludingLowAlphaColour() {
        // straight RGBA with colour under alpha 0 and 1: premultiplying codecs would lose it, this container must not
        val rgba = ByteArray(16 * 12 * 4) { i -> if (i % 4 == 3) (i / 4 % 3).toByte() else (i * 37).toByte() }
        val p = PixelContainer.decode(PixelContainer.encode(RawPixels(16, 12, rgba)))
        assertArrayEquals(rgba, p.rgba); assertEquals(16, p.w); assertEquals(12, p.h)
    }

    @Test fun containerRejectsGarbageAndCutOffData() {
        val good = PixelContainer.encode(px(1))
        for (bad in listOf(ByteArray(0), ByteArray(40), good.copyOf(good.size / 2), good.copyOf().also { it[0] = 0 })) {
            try { PixelContainer.decode(bad); fail() } catch (e: ProjectFormatException) { assertTrue(e.message!!.isNotEmpty()) }
        }
    }

    @Test fun saveThenOpenReturnsTheSameDocumentAndPixels() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val p = mapOf("a" to px(1), "b" to px(2))
        val saved = store.save(doc("a", "b"), { p[it.common.id] }, setOf("a", "b"))
        val r = ProjectStore(fs, root).open()
        assertEquals(OpenedFrom.CURRENT, r.from); assertEquals(saved, r.document)
        assertArrayEquals(p["a"]!!.rgba, store.load(r.document.layer("a") as Layer.Pixel)!!.rgba)
    }

    @Test fun anUnchangedLayerIsNotWrittenAgain() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        var d = store.save(doc("a", "b"), { px(it.common.id.hashCode()) }, setOf("a", "b"))
        val before = fs.files.keys.filter { it.startsWith("$root/layers/") }.toSet()
        var calls = 0
        d = store.save(d.copy(modified = 2), { calls++; px(99) }, setOf("b"))   // only b changed
        assertEquals(1, calls)
        val after = fs.files.keys.filter { it.startsWith("$root/layers/") }
        assertTrue(before.any { it.contains("/a-") && it in after })
        assertEquals(3, after.size)   // a untouched, the new b, and the old b which the .bak generation still refers to
        d = store.save(d.copy(modified = 3), { px(98) }, setOf("b"))
        assertEquals(3, fs.files.keys.count { it.startsWith("$root/layers/") })   // now the first b is no longer needed by either generation
    }

    @Test fun aLayerWithoutPixelsHasNoFileAndOpensAsNull() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val saved = store.save(doc("a"), { null }, setOf("a"))
        assertNull((saved.layers[0] as Layer.Pixel).pixelsFile)
        assertNull(store.load(ProjectStore(fs, root).open().document.layers[0] as Layer.Pixel))
    }

    @Test fun twoGenerationsAreKeptAndTheOlderOneStillOpens() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val d1 = store.save(doc("a"), { px(1) }, setOf("a"))
        store.save(d1.copy(modified = 2), { px(2) }, setOf("a"))
        assertTrue(fs.exists("$root/project.json.bak"))
        fs.files["$root/project.json"] = "{ torn".toByteArray()      // the newest file is damaged
        val r = ProjectStore(fs, root).open()
        assertEquals(OpenedFrom.BACKUP, r.from); assertTrue(r.recovered); assertEquals(1L, r.document.modified)
    }

    @Test fun aNewerSchemaIsNeverOpenedAsAFallback() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        store.save(doc("a"), { px(1) }, setOf("a"))
        fs.files["$root/project.json"] = String(fs.files["$root/project.json"]!!).replace("\"schemaVersion\": 1", "\"schemaVersion\": 9").toByteArray()
        try { ProjectStore(fs, root).open(); fail() } catch (e: NewerSchemaException) { assertEquals(9, e.version) }
    }

    @Test fun missingLayerFileMakesThatGenerationUnusable() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val d1 = store.save(doc("a"), { px(1) }, setOf("a"))
        store.save(d1.copy(modified = 2), { px(2) }, setOf("a"))
        val newest = (ProjectStore(fs, root).open().document.layers[0] as Layer.Pixel).pixelsFile!!
        fs.files.remove("$root/$newest")
        assertEquals(1L, ProjectStore(fs, root).open().document.modified)
    }

    @Test fun nothingSavedIsAnError() {
        try { ProjectStore(MemFs(), root).open(); fail() } catch (e: ProjectFormatException) { assertTrue(e.message!!.contains("no project")) }
    }

    /**
     * The exit test of S1b: a long run of strokes (each a save of one layer, sometimes adding or deleting a layer), killed at every single
     * mutating file operation of every save. After each kill the project must open, to the state before that save or the state after it,
     * with every layer's pixels readable. Nothing older is ever shown, and no kill point loses a committed save.
     */
    @Test fun killedAtEveryFileOperationTheProjectAlwaysOpensAndLosesAtMostTheLastStroke() {
        val rnd = Random(42)
        val states = ArrayList<Pair<Document, Map<String, ByteArray>>>()   // committed (document as saved, pixels by layer id)
        // dry run to learn the operations of each save, recording states
        val dry = MemFs(); val dryStore = ProjectStore(dry, root)
        var d = doc("l0"); var pix = mapOf("l0" to px(0).rgba)
        val opsPerSave = ArrayList<Int>()
        val steps = ArrayList<Triple<Document, Map<String, ByteArray>, Set<String>>>()
        for (i in 1..40) {
            val ids = d.layers.map { it.common.id }
            when {
                i % 9 == 0 && ids.size < 5 -> { d = LayerOps.add(d, Layer.Pixel(LayerCommon("l$i", "l$i"), 16, 12)); pix = pix + ("l$i" to px(i).rgba) }
                i % 13 == 0 && ids.size > 1 -> { val gone = ids.first(); d = LayerOps.delete(d, gone); pix = pix - gone }
                else -> { val target = ids[rnd.nextInt(ids.size)]; pix = pix + (target to px(1000 + i).rgba) }
            }
            d = d.copy(modified = d.modified + 1)
            steps.add(Triple(d, pix, d.layers.map { it.common.id }.toSet()))
        }
        var prevSaved: Document? = null
        var prevPix: Map<String, ByteArray>? = null
        for ((idx, st) in steps.withIndex()) {
            val (docNow, pixNow, _) = st
            // count the mutating ops of this save on a copy of the current state
            val probe = MemFs().also { it.files.putAll(dry.files) }
            val counter = CrashFs(probe, Int.MAX_VALUE)
            val changed = if (prevPix == null) pixNow.keys else pixNow.keys.filter { prevPix!![it] == null || !prevPix!![it]!!.contentEquals(pixNow[it]) }.toSet()
            val savedProbe = ProjectStore(counter, root).save(docNow, { RawPixels(16, 12, pixNow[it.common.id]!!) }, changed.toSet())
            val total = counter.ops
            for (k in 0 until total) {
                val fs = MemFs().also { it.files.putAll(dry.files) }
                try { ProjectStore(CrashFs(fs, k), root).save(docNow, { RawPixels(16, 12, pixNow[it.common.id]!!) }, changed.toSet()); fail("save $idx survived kill point $k of $total") } catch (e: Crash) {}
                var r: OpenResult? = null
                try { r = ProjectStore(fs, root).open() } catch (e: ProjectFormatException) { if (prevSaved != null) fail("save $idx killed at $k: project does not open: ${e.message}") }
                if (r != null) {
                    val ok = (prevSaved != null && r.document.modified == prevSaved!!.modified) || r.document.modified == savedProbe.modified
                    assertTrue("save $idx killed at op $k: opened an unexpected generation (modified ${r.document.modified})", ok)
                    val expectPix = if (r.document.modified == savedProbe.modified) pixNow else prevPix!!
                    for (l in r.document.layers) {
                        val got = ProjectStore(fs, root).load(l as Layer.Pixel)!!.rgba
                        assertArrayEquals("save $idx kill $k layer ${l.common.id}", expectPix[l.common.id], got)
                    }
                }
            }
            // now do the save for real on the dry run
            prevSaved = dryStore.save(docNow, { RawPixels(16, 12, pixNow[it.common.id]!!) }, changed.toSet())
            prevPix = pixNow
            opsPerSave.add(total)
        }
        assertTrue("every save has several file operations to kill: $opsPerSave", opsPerSave.all { it >= 5 })
        // and the finished store opens to the last state
        val last = ProjectStore(dry, root).open()
        assertEquals(steps.last().first.modified, last.document.modified)
        assertEquals(OpenedFrom.CURRENT, last.from)
    }
}
```
### BrushViewTest.kt (brush maths, walker, commit, view, placement, colour, history fuzz, input router)
```kotlin
package app.rawline.core.studio.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushMathTest {
    @Test fun falloffIsOneInsideTheHardCoreZeroOutsideAndSmoothBetween() {
        assertEquals(1.0, BrushMath.falloff(0.0, 10.0, 0.5), 0.0)
        assertEquals(1.0, BrushMath.falloff(5.0, 10.0, 0.5), 0.0)
        assertEquals(0.0, BrushMath.falloff(10.0, 10.0, 0.5), 0.0)
        assertEquals(0.5, BrushMath.falloff(7.5, 10.0, 0.5), 1e-12)   // halfway through the ramp: smoothstep(0.5) = 0.5
        assertEquals(0.0, BrushMath.falloff(10.5, 10.0, 1.0), 0.0)
        assertEquals(1.0, BrushMath.falloff(9.99, 10.0, 1.0), 0.0)    // hardness 1 is a hard edge
    }

    @Test fun pressureScalesTheDiameterFromAFifthToFull() {
        assertEquals(2.0, BrushMath.diameterAt(10.0, 0.0, true), 1e-12)
        assertEquals(10.0, BrushMath.diameterAt(10.0, 1.0, true), 1e-12)
        assertEquals(10.0, BrushMath.diameterAt(10.0, 0.3, false), 1e-12)
    }

    @Test fun stampsSitEveryStepAlongAStraightLine() {
        val b = Brush(diameter = 20.0, spacing = 0.1, pressureSize = false)
        val s = BrushMath.walk(listOf(StrokePoint(10.0, 10.0), StrokePoint(60.0, 10.0)), b)
        assertEquals(26, s.size)                       // 0, 2, ..., 50
        assertEquals(10.0, s[0].x, 1e-12); assertEquals(12.0, s[1].x, 1e-9); assertEquals(60.0, s.last().x, 1e-9)
        assertEquals(10.0, s[0].radius, 1e-12)
    }

    @Test fun theSpacingNeverGoesBelowHalfAPixel() {
        val s = BrushMath.walk(listOf(StrokePoint(0.0, 0.0), StrokePoint(10.0, 0.0)), Brush(diameter = 3.0, spacing = 0.1, pressureSize = false))
        assertEquals(21, s.size)                       // 0.5 px steps, not 0.3
    }

    @Test fun slicingTheInputDoesNotMoveTheStamps() {
        val b = Brush(diameter = 17.0, spacing = 0.13, pressureSize = true)
        val whole = BrushMath.walk(listOf(StrokePoint(3.0, 4.0, 0.2), StrokePoint(40.0, 30.0, 0.9), StrokePoint(90.0, 10.0, 0.5)), b)
        // the same path with an extra point on the first segment (an input event in the middle): same stamps
        val sliced = BrushMath.walk(listOf(StrokePoint(3.0, 4.0, 0.2), StrokePoint(21.5, 17.0, 0.55), StrokePoint(40.0, 30.0, 0.9), StrokePoint(90.0, 10.0, 0.5)), b)
        assertEquals(whole.size, sliced.size)
        for (i in whole.indices) { assertEquals(whole[i].x, sliced[i].x, 1e-6); assertEquals(whole[i].y, sliced[i].y, 1e-6) }
    }

    @Test fun walkerMatchesTheIndependentPythonReference() {
        val text = javaClass.getResourceAsStream("/walker_vectors.tsv")!!.bufferedReader().readText()
        var n = 0
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (head, pts, out) = line.split(" | ")
            val h = head.trim().split(" ")
            val v = pts.trim().split(" ").map { it.toDouble() }
            val points = v.chunked(3).map { StrokePoint(it[0], it[1], it[2]) }
            val exp = out.removePrefix("-> ").trim().split(" ").filter { it.isNotEmpty() }.map { it.toDouble() }.chunked(3)
            val got = BrushMath.walk(points, Brush(diameter = h[0].toDouble(), spacing = h[1].toDouble(), pressureSize = h[2] == "1"))
            assertEquals("count in: $line", exp.size, got.size)
            for (i in exp.indices) { assertEquals(exp[i][0], got[i].x, 1e-5); assertEquals(exp[i][1], got[i].y, 1e-5); assertEquals(exp[i][2], got[i].radius, 1e-5) }
            n++
        }
        assertTrue(n >= 40)
    }

    @Test fun coverageAccumulatesWithOverAndFlow() {
        // two stamps on the same pixel at flow 0.5: 0.5 then 0.5 + 0.5 * 0.5 = 0.75
        val cov = StrokeReference.coverage(4, 4, listOf(Stamp(2.0, 2.0, 1.4), Stamp(2.0, 2.0, 1.4)), Brush(hardness = 1.0, flow = 0.5))
        assertEquals(0.75f, cov[1 * 4 + 1], 1e-6f)
        assertEquals(0f, cov[0], 0f)   // the corner pixel centre is 2.12 away
    }

    @Test fun commitPaintsOverAndEraseRemovesAlphaOnly() {
        val w = 2
        val cov = floatArrayOf(1f, 0.5f, 0f, 0f)
        val paint = ByteArray(w * 2 * 4) { if (it % 4 == 3) 255.toByte() else 0 }          // opaque black
        StrokeReference.commit(paint, w, cov, intArrayOf(0, 0, 2, 1), floatArrayOf(1f, 0f, 0f), Brush(opacity = 1.0))
        assertEquals(listOf(255, 0, 0, 255), (0..3).map { paint[it].toInt() and 255 })        // full coverage: pure red
        assertEquals(listOf(128, 0, 0, 255), (4..7).map { paint[it].toInt() and 255 })        // half coverage over black: half red
        val er = ByteArray(w * 2 * 4) { if (it % 4 == 3) 200.toByte() else 77 }
        StrokeReference.commit(er, w, cov, intArrayOf(0, 0, 2, 1), floatArrayOf(0f, 0f, 0f), Brush(opacity = 0.5, erase = true))
        assertEquals(listOf(77, 77, 77, 100), (0..3).map { er[it].toInt() and 255 })           // alpha 200 * (1 - 0.5)
        assertEquals(listOf(77, 77, 77, 150), (4..7).map { er[it].toInt() and 255 })           // alpha 200 * (1 - 0.25)
    }

    @Test fun commitRectEqualsCommitWithAFullCoverageArray() {
        val w = 30; val h = 22
        val rnd = java.util.Random(4)
        val layer = ByteArray(w * h * 4).also { rnd.nextBytes(it) }
        val brush = Brush(diameter = 9.0, hardness = 0.4, opacity = 0.8, flow = 0.7, pressureSize = false)
        val st = BrushMath.walk(listOf(StrokePoint(5.0, 5.0), StrokePoint(20.0, 14.0)), brush)
        val full = StrokeReference.coverage(w, h, st, brush)
        val r = Dirty.rect(st, w, h)
        val rectCov = FloatArray(r[2] * r[3]) { i -> full[(r[1] + i / r[2]) * w + r[0] + i % r[2]] }
        val a = layer.copyOf(); val b = layer.copyOf()
        StrokeReference.commit(a, w, full, r, floatArrayOf(0.2f, 0.7f, 0.1f), brush)
        StrokeReference.commitRect(b, w, r, rectCov, floatArrayOf(0.2f, 0.7f, 0.1f), brush)
        assertArrayEquals(a, b)
    }

    @Test fun dirtyRectangleCoversTheStampsAndIsClipped() {
        val r = Dirty.rect(listOf(Stamp(5.0, 5.0, 3.0), Stamp(95.0, 40.0, 10.0)), 100, 50)
        assertEquals(1, r[0]); assertEquals(1, r[1]); assertEquals(100, r[0] + r[2]); assertEquals(50, r[1] + r[3])   // x0 + w is the exclusive end: clipped to the layer
        assertEquals(0, Dirty.rect(listOf(Stamp(500.0, 500.0, 3.0)), 100, 50)[2])
    }
}

class ViewMathTest {
    @Test fun screenAndDocumentCoordinatesRoundTrip() {
        val v = CanvasView(10f, 20f, 2f)
        assertEquals(15f, v.toDocX(10f), 1e-6f); assertEquals(10f, v.toScreenX(15f), 1e-6f)
        assertEquals(v.toDocY(33f), v.toDocY(v.toScreenY(v.toDocY(33f))) , 1e-4f)
    }

    @Test fun zoomAboutAPointKeepsThatPointStill() {
        val v = CanvasView(10f, 20f, 1f)
        val z = v.zoomAbout(3f, 200f, 100f)
        assertEquals(v.toDocX(200f), z.toDocX(200f), 1e-4f); assertEquals(v.toDocY(100f), z.toDocY(100f), 1e-4f)
        assertEquals(3f, z.zoom, 0f)
    }

    @Test fun zoomIsClamped() {
        assertEquals(CanvasView.MAX_ZOOM, CanvasView().zoomAbout(1000f, 0f, 0f).zoom, 0f)
        assertEquals(CanvasView.MIN_ZOOM, CanvasView().zoomAbout(0.0001f, 0f, 0f).zoom, 0f)
    }

    @Test fun panMovesTheCanvasWithTheFinger() {
        val v = CanvasView(0f, 0f, 2f).panBy(40f, -20f)   // finger moves right and up by 40, 20 screen pixels: the picture follows
        assertEquals(-20f, v.x, 1e-6f); assertEquals(10f, v.y, 1e-6f)
    }

    @Test fun clampKeepsSomeCanvasOnScreen() {
        val v = CanvasView(5000f, -5000f, 1f).clamped(1000, 800, 400, 600)
        assertTrue(v.x <= 1000f - 64f); assertTrue(v.y >= (64f - 600f))
    }

    @Test fun fitCentresTheCanvas() {
        val v = CanvasView.fit(1000, 500, 400, 800)
        assertEquals(0.368f, v.zoom, 1e-3f)                                       // (400 - 32) / 1000
        assertEquals(16f, v.toScreenX(0f), 1e-3f); assertEquals(384f, v.toScreenX(1000f), 1e-2f)
        assertEquals(800f / 2f, (v.toScreenY(0f) + v.toScreenY(500f)) / 2f, 1e-2f)
    }
}

class PlacementAndColourTest {
    @Test fun scaleAboutAPointKeepsThePointFixed() {
        val (x, y, s) = Placement.scaleAbout(100, 50, 1f, 2f, 300f, 150f)
        assertEquals(2f, s, 0f)
        // the document point under (300, 150) was layer pixel (200, 100); after scaling by 2 it must still be under (300, 150)
        assertEquals(300f, x + 200f * 2f, 1f); assertEquals(150f, y + 100f * 2f, 1f)
    }

    @Test fun scaleIsClampedToAQuarterAndFour() {
        assertEquals(4f, Placement.scaleAbout(0, 0, 1f, 40f, 0f, 0f).third, 0f)
        assertEquals(0.25f, Placement.scaleAbout(0, 0, 1f, 0.001f, 0f, 0f).third, 0f)
    }

    @Test fun hsvRoundTripsAndKnownColours() {
        val red = Hsv.toRgb(0f, 1f, 1f); assertEquals(1f, red[0], 0f); assertEquals(0f, red[1], 0f)
        val g = Hsv.toRgb(1f / 3f, 1f, 1f); assertEquals(1f, g[1], 1e-6f); assertEquals(0f, g[0], 1e-6f)
        val rnd = java.util.Random(1)
        repeat(200) {
            val r = rnd.nextFloat(); val gg = rnd.nextFloat(); val b = rnd.nextFloat()
            val back = Hsv.fromRgb(r, gg, b).let { Hsv.toRgb(it[0], it[1], it[2]) }
            assertEquals(r, back[0], 1e-5f); assertEquals(gg, back[1], 1e-5f); assertEquals(b, back[2], 1e-5f)
        }
    }
}

/** stroke_small.txt is written by the independent Python reference (tools/studio/studio_brush.py small): one layer, then a paint and an erase case with the baked result. */
class StrokeCommitMatchesPythonTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun walkerCoverageAndCommitEqualThePythonReferenceWithinOneLevel() {
        val lines = javaClass.getResourceAsStream("/stroke_small.txt")!!.bufferedReader().readLines()
        val head = lines[0].split(" "); val w = head[1].toInt(); val h = head[2].toInt(); val layer = hex(head[3])
        var cases = 0
        for (line in lines.drop(1)) {
            val (a, pts, expHex) = line.split(" | ")
            val t = a.split(" ")
            val brush = Brush(diameter = t[2].toDouble(), hardness = t[3].toDouble(), flow = t[4].toDouble(), opacity = t[5].toDouble(), spacing = t[6].toDouble(), pressureSize = t[7] == "1", erase = t[1] == "erase")
            val colour = floatArrayOf(t[8].toFloat(), t[9].toFloat(), t[10].toFloat())
            val points = pts.trim().split(" ").map { it.toDouble() }.chunked(3).map { StrokePoint(it[0], it[1], it[2]) }
            val stamps = BrushMath.walk(points, brush)
            val cov = StrokeReference.coverage(w, h, stamps, brush)
            val px = layer.copyOf()
            StrokeReference.commit(px, w, cov, intArrayOf(0, 0, w, h), colour, brush)
            val exp = hex(expHex.trim())
            var worst = 0
            for (i in px.indices) worst = maxOf(worst, Math.abs((px[i].toInt() and 255) - (exp[i].toInt() and 255)))
            assertTrue("${t[1]}: worst difference $worst levels", worst <= 1)
            cases++
        }
        assertEquals(2, cases)
    }
}

class StrokeWalkerTest {
    @Test fun pointByPointEqualsAllAtOnce() {
        val rnd = java.util.Random(9)
        repeat(20) {
            val b = Brush(diameter = 4.0 + rnd.nextInt(40), spacing = 0.05 + rnd.nextDouble() * 0.3, pressureSize = rnd.nextBoolean())
            val pts = List(2 + rnd.nextInt(8)) { StrokePoint(rnd.nextDouble() * 200, rnd.nextDouble() * 200, rnd.nextDouble()) }
            val whole = BrushMath.walk(pts, b)
            val w = StrokeWalker(b); val live = pts.flatMap { w.add(it) }
            assertEquals(whole.size, live.size)
            for (i in whole.indices) { assertEquals(whole[i].x, live[i].x, 1e-9); assertEquals(whole[i].y, live[i].y, 1e-9); assertEquals(whole[i].radius, live[i].radius, 1e-9) }
        }
    }
}

class HistoryTest {
    private fun layer(id: String) = Layer.Pixel(LayerCommon(id, id), 40, 30)
    private fun doc() = Document("d", "D", 40, 30, layers = listOf(layer("a")))

    /** The exit check: random strokes and layer operations, then every undo returns byte-identical pixels and equal documents; redo returns the final state. */
    @Test fun fuzzUndoAllThenRedoAllIsByteIdentical() {
        val rnd = java.util.Random(5)
        val w = 40; val h = 30
        val pixels = ByteArray(w * h * 4).also { rnd.nextBytes(it) }
        for (i in 0 until w * h) if (rnd.nextInt(3) == 0) { pixels[i * 4 + 3] = 0; pixels[i * 4] = 0; pixels[i * 4 + 1] = 0; pixels[i * 4 + 2] = 0 }
        val original = pixels.copyOf()
        val hist = StudioHistory(doc())
        val docs = ArrayList<Document>(); docs.add(hist.document)
        repeat(60) { n ->
            if (n % 7 == 3) {
                hist.commitDocument(LayerOps.setOpacity(hist.document, "a", rnd.nextInt(101)))
            } else {
                val brush = Brush(diameter = 3.0 + rnd.nextInt(14), hardness = rnd.nextDouble(), opacity = 0.3 + rnd.nextDouble() * 0.7, flow = 0.3 + rnd.nextDouble() * 0.7, erase = rnd.nextInt(4) == 0, pressureSize = false)
                val st = BrushMath.walk(List(3) { StrokePoint(rnd.nextDouble() * w, rnd.nextDouble() * h) }, brush)
                val r = Dirty.rect(st, w, h)
                if (r[2] > 0) {
                    val before = PixelDelta.cut(pixels, w, r[0], r[1], r[2], r[3])
                    StrokeReference.commit(pixels, w, StrokeReference.coverage(w, h, st, brush), r, floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()), brush)
                    hist.commitStroke("a", PixelDelta(r[0], r[1], r[2], r[3], before, PixelDelta.cut(pixels, w, r[0], r[1], r[2], r[3])))
                }
            }
            docs.add(hist.document)
        }
        val finalPixels = pixels.copyOf(); val finalDoc = hist.document
        var guard = 0
        while (hist.canUndo) { val s = hist.undo()!!; if (s is Step.SetPixels) s.delta.apply(pixels, w, s.bytes); guard++ }
        assertArrayEquals(original, pixels)
        assertEquals(doc(), hist.document)
        while (hist.canRedo) { val s = hist.redo()!!; if (s is Step.SetPixels) s.delta.apply(pixels, w, s.bytes) }
        assertArrayEquals(finalPixels, pixels); assertEquals(finalDoc, hist.document)
        assertTrue(guard > 40)
    }

    @Test fun aNewEditAfterUndoDropsTheRedoTail() {
        val h = StudioHistory(doc())
        h.commitDocument(LayerOps.setOpacity(h.document, "a", 50))
        h.undo(); assertTrue(h.canRedo)
        h.commitDocument(LayerOps.setOpacity(h.document, "a", 70)); assertFalse(h.canRedo)
    }

    @Test fun byteBudgetDropsTheOldestStrokes() {
        val h = StudioHistory(doc(), maxEntries = 100, maxBytes = 1000)
        repeat(10) { h.commitStroke("a", PixelDelta(0, 0, 5, 5, ByteArray(100), ByteArray(100))) }   // 200 bytes each
        assertTrue(h.deltaBytes <= 1000)
        var n = 0; while (h.undo() != null) n++
        assertEquals(5, n)
    }

    @Test fun identicalDocumentIsNotRecorded() { val h = StudioHistory(doc()); h.commitDocument(h.document); assertFalse(h.canUndo) }
}

class InputRouterTest {
    private fun ev(p: Phase, id: Int, x: Float, y: Float, kind: PointerKind = PointerKind.FINGER, t: Long = 0) = InputEvent(p, id, x, y, 1f, kind, t)
    private fun names(a: List<Action>) = a.map { it::class.simpleName }

    @Test fun oneFingerDrawsADownMoveUpStroke() {
        val r = InputRouter()
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 0, 10f, 10f))))
        assertEquals(listOf("StrokeMove"), names(r.onEvent(ev(Phase.MOVE, 0, 12f, 10f))))
        assertEquals(listOf("StrokeEnd"), names(r.onEvent(ev(Phase.UP, 0, 12f, 10f))))
    }

    @Test fun aSecondFingerCancelsTheStrokeAndStartsAPanAndZoom() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 0, 100f, 100f)); r.onEvent(ev(Phase.MOVE, 0, 102f, 100f))
        assertEquals(listOf("StrokeCancel", "GestureStart"), names(r.onEvent(ev(Phase.DOWN, 1, 200f, 100f))))
        // the fingers start 98 apart with their centre at x = 151; the second moves out to x = 298: 196 apart, centre at x = 200, so zoom 2 and a pan of 49
        r.onEvent(ev(Phase.MOVE, 0, 102f, 100f))
        val a = r.onEvent(ev(Phase.MOVE, 1, 298f, 100f)).single() as Action.GestureUpdate
        assertEquals(196f / 98f, a.scale, 1e-4f); assertEquals(49f, a.dx, 1e-3f); assertEquals(200f, a.cx, 1e-3f)
        assertEquals(listOf("GestureEnd"), names(r.onEvent(ev(Phase.UP, 1, 298f, 100f))))
    }

    @Test fun theRemainingFingerDoesNotStartAStrokeAfterAPinch() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 0, 100f, 100f)); r.onEvent(ev(Phase.DOWN, 1, 200f, 100f)); r.onEvent(ev(Phase.UP, 1, 200f, 100f))
        assertTrue(r.onEvent(ev(Phase.MOVE, 0, 120f, 120f)).isEmpty())
        assertTrue(r.onEvent(ev(Phase.UP, 0, 120f, 120f)).isEmpty())
        assertEquals(listOf("StrokeStart"), names(r.onEvent(ev(Phase.DOWN, 0, 5f, 5f))))   // everything is up: a new stroke is fine
    }

    @Test fun aPalmDoesNotInterruptAPenStroke() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 7, 100f, 100f, PointerKind.STYLUS))
        assertTrue(r.onEvent(ev(Phase.DOWN, 1, 300f, 300f, PointerKind.FINGER)).isEmpty())
        assertEquals(listOf("StrokeMove"), names(r.onEvent(ev(Phase.MOVE, 7, 110f, 100f, PointerKind.STYLUS))))
        assertTrue(r.onEvent(ev(Phase.MOVE, 1, 310f, 300f, PointerKind.FINGER)).isEmpty())
        assertEquals(listOf("StrokeEnd"), names(r.onEvent(ev(Phase.UP, 7, 110f, 100f, PointerKind.STYLUS))))
    }

    @Test fun aSystemCancelRollsTheStrokeBack() {
        val r = InputRouter()
        r.onEvent(ev(Phase.DOWN, 0, 1f, 1f))
        assertEquals(listOf("StrokeCancel"), names(r.onEvent(ev(Phase.CANCEL, 0, 1f, 1f))))
    }
}
```
### StudioSession.skeleton.kt (NOT compiled: the control flow on top of the tested parts)
```kotlin
package app.rawline.core.studio.render

import app.rawline.core.nativelib.StudioNative
import app.rawline.core.studio.model.*

/**
 * NOT COMPILED HERE (needs Android and Compose). The control flow of S1b on top of the tested pieces. Every call into StudioNative happens
 * inside [post] on the GL thread (same rule as EditorSession.post: queued until the context exists, dropped with onDrop when released).
 */
class StudioSession(private val store: ProjectStore, initial: Document, private val report: (String, Long) -> Unit) {
    private var handle = 0L                              // StudioNative.create() on first surface
    private val history = StudioHistory(initial)
    private val slots = LinkedHashMap<String, Int>()     // layer id -> compositor slot (0..15)
    private var active: String = initial.layers.last().common.id
    private var activePixels: RawPixels? = null          // CPU copy of the ACTIVE layer only (autosave and the commit need it); other layers live on disk
    var view = CanvasView()
    var brush = Brush()
    var colour = floatArrayOf(0f, 0f, 0f)                // straight, 0..1, from Hsv.toRgb

    // ---- one stroke ----------------------------------------------------------------------------------------------------
    private var walker: StrokeWalker? = null
    private val stamps = ArrayList<Stamp>()

    fun onAction(a: Action) {
        when (a) {
            is Action.StrokeStart -> {
                val l = layer(active); if (l.common.locked || !l.common.visible) return          // say why in a toast: "Layer is locked"
                walker = StrokeWalker(brush); stamps.clear()
                post { StudioNative.beginStroke(handle, slot(active), colour[0], colour[1], colour[2], brush.opacity.toFloat(), brush.erase, brush.hardness.toFloat(), brush.flow.toFloat()) }
                addPoint(a.x, a.y, a.pressure)
            }
            is Action.StrokeMove -> addPoint(a.x, a.y, a.pressure)
            Action.StrokeEnd -> commitStroke()
            Action.StrokeCancel -> { walker = null; stamps.clear(); post { StudioNative.endStroke(handle) }; requestRender() }   // no history entry
            is Action.GestureUpdate -> { view = view.zoomAbout(a.scale, a.cx, a.cy).panBy(a.dx, a.dy).clamped(doc().width, doc().height, screenW, screenH); requestRender() }
            else -> {}
        }
    }

    private fun addPoint(sx: Float, sy: Float, pressure: Float) {
        val l = layer(active).common
        // screen -> document -> layer pixels (the layer may be moved and scaled)
        val lx = (view.toDocX(sx) - l.x) / l.scale; val ly = (view.toDocY(sy) - l.y) / l.scale
        val fresh = walker!!.add(StrokePoint(lx.toDouble(), ly.toDouble(), pressure.toDouble()))
        if (fresh.isEmpty()) return
        stamps += fresh
        val xyr = FloatArray(fresh.size * 3) { i -> val s = fresh[i / 3]; when (i % 3) { 0 -> s.x.toFloat(); 1 -> s.y.toFloat(); else -> s.radius.toFloat() } }
        post { StudioNative.addStamps(handle, xyr, fresh.size) }
        requestRender()
    }

    private fun commitStroke() {
        val l = layer(active) as Layer.Pixel
        val rect = Dirty.rect(stamps, l.width, l.height)
        val px = activePixels ?: return
        walker = null
        if (rect[2] == 0) { post { StudioNative.endStroke(handle) }; return }
        post {
            val t0 = System.nanoTime()
            val cov = FloatArray(rect[2] * rect[3])
            if (StudioNative.readStroke(handle, rect[0], rect[1], rect[2], rect[3], cov)) {
                val before = PixelDelta.cut(px.rgba, l.width, rect[0], rect[1], rect[2], rect[3])
                StrokeReference.commitRect(px.rgba, l.width, rect, cov, colour, brush)   // coverage of the rectangle only: no layer sized array
                val after = PixelDelta.cut(px.rgba, l.width, rect[0], rect[1], rect[2], rect[3])
                StudioNative.updateLayerRegion(handle, slot(active), rect[0], rect[1], rect[2], rect[3], after)
                history.commitStroke(active, PixelDelta(rect[0], rect[1], rect[2], rect[3], before, after))
                markDirty(active)                                                   // autosave: coalesced, background thread
            }
            StudioNative.endStroke(handle)
            report("studio_commit_ms", (System.nanoTime() - t0) / 1_000_000)
            requestRender()
        }
        stamps.clear()
    }

    // ---- undo and redo ------------------------------------------------------------------------------------------------
    fun undo() = apply(history.undo())
    fun redo() = apply(history.redo())
    private fun apply(step: Step?) {
        when (step) {
            is Step.SetPixels -> post {
                val l = layer(step.layerId) as Layer.Pixel
                if (step.layerId == active) step.delta.apply(activePixels!!.rgba, l.width, step.bytes)
                StudioNative.updateLayerRegion(handle, slot(step.layerId), step.delta.x, step.delta.y, step.delta.w, step.delta.h, step.bytes)   // only that rectangle goes to the GPU
                markDirty(step.layerId); requestRender()
            }
            is Step.SetDocument -> { syncSlots(step.document); markDirty(null); requestRender() }
            null -> {}
        }
    }
    // post, slot, layer, doc, markDirty (schedules ProjectStore.save on the autosave thread, coalescing), syncSlots (uploads textures of layers that appeared, removes slots of deleted ones),
    // switchActive (flush the save, drop the old CPU copy, load the new one with store.load), requestRender: plain plumbing, no new logic.
}
```

## 8. Native and tools (tested on Mesa; diffs are against the S1a files)

New files `core/native/src/main/cpp/shaders/studio_stamp.glsl` (vertex) and `studio_stamp.frag`, new `tools/studio/studio_brush.py`; patches to `studio_compositor.h/.cpp`, `studio_composite.frag`, `jni_studio.cpp`, `StudioNative.kt`, `tools/golden/studio_golden.cpp`, `tools/golden/studio-golden.sh`. Apply the patches on top of the S1a files (`git apply`). The golden now runs both `studio_blend3` and the five `studio_brush` scenes; expected output of `studio-golden.sh` on the S1b tree: 3 PASS lines for blend3 (worst 1) and 5 PASS lines for brush (worst 1, 0 values off by more than 1, tolerance 2).

Measured facts worth knowing: the GPU stroke coverage differs from the Python reference by at most 0.002 (half float accumulation over 84 low flow stamps); committing from the GPU coverage and from the reference coverage differs by at most 1 level on visible pixels; with the canonical transparent rule (alpha 0 means colour 0) the baked bytes agree exactly where it matters. Breaking the stamp falloff (linear instead of smoothstep) or the accumulation (add instead of over) fails four of the five scenes by 13 to 65 levels.

### shaders/studio_stamp.glsl
```glsl
#version 300 es
// Brush stamps into the stroke buffer: one instanced quad per stamp (up to 64 per draw), in layer pixel coordinates (row 0 = layer top).
uniform vec4 uStamps[64];   // x, y, radius, unused
uniform vec2 uSize;         // layer size in pixels
flat out vec3 vC;
void main() {
    vec4 s = uStamps[gl_InstanceID];
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;
    vec2 p = s.xy + corner * (s.z + 1.0);
    vC = s.xyz;
    gl_Position = vec4(p / uSize * 2.0 - 1.0, 0.0, 1.0);
}
```
### shaders/studio_stamp.frag
```glsl
#version 300 es
// Coverage of one round stamp (spec 4.3) accumulated with 'over' by the blend state (ONE, ONE_MINUS_SRC_ALPHA): a = a + s (1 - a).
precision highp float;
flat in vec3 vC;            // centre x, y and radius in layer pixels
uniform float uHardness;
uniform float uFlow;
out vec4 oColor;
void main() {
    float r = distance(gl_FragCoord.xy, vC.xy);   // pixel centre against the stamp centre, as the CPU reference does
    float R = vC.z;
    float inner = uHardness * R;
    float c = 0.0;
    if (r < R) {
        if (r <= inner) c = 1.0;
        else { float u = 1.0 - (r - inner) / (R - inner); c = u * u * (3.0 - 2.0 * u); }
    }
    float s = uFlow * c;
    oColor = vec4(s, 0.0, 0.0, s);
}
```
### tools/studio/studio_brush.py
```python
#!/usr/bin/env python3
"""Independent reference for the Studio brush (spec 4.3): stamp walker, stamp coverage, stroke accumulation and commit. Standard library only.
usage: studio_brush.py vectors <file>     walker cases for the Kotlin test
       studio_brush.py make <dir>         golden scenes `studio_brush` (files for the GPU harness and expected images)
       studio_brush.py compare <dir>      compare out_*.rgba with expected_*.rgba"""
import math, os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import studio_ref as R

def falloff(r, radius, hardness):
    """Coverage of a round stamp at distance r from its centre: 1 inside hardness * radius, 0 outside radius, smoothstep between."""
    if r >= radius: return 0.0
    inner = hardness * radius
    if r <= inner: return 1.0
    u = 1.0 - (r - inner) / (radius - inner)
    return u * u * (3.0 - 2.0 * u)

def stamp_diameter(base, pressure, pressure_size):
    return base * (0.2 + 0.8 * pressure) if pressure_size else base

def walk(points, diameter, spacing, pressure_size):
    """points: [(x, y, pressure)] in layer pixels. Returns stamps [(x, y, radius)]: one at the first point, then every
    max(0.5, d * spacing) pixels along the path (d is the diameter of the previous stamp), the remainder carried across segments."""
    if not points: return []
    out = []
    x, y, p = points[0]
    d = stamp_diameter(diameter, p, pressure_size); out.append((x, y, d / 2.0))
    step = max(0.5, d * spacing)   # fixed when a stamp is placed
    carry = 0.0                    # distance travelled since the last stamp
    for (x0, y0, p0), (x1, y1, p1) in zip(points, points[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        if seg == 0: continue
        t = 0.0
        while True:
            need = step - carry
            if t + need > seg + 1e-9:
                carry += seg - t; break
            t += need; carry = 0.0
            f = t / seg
            pp = p0 + (p1 - p0) * f
            d = stamp_diameter(diameter, pp, pressure_size)
            out.append((x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, d / 2.0))
            step = max(0.5, d * spacing)
    return out

def coverage(w, h, stamps, hardness, flow):
    """Accumulated stroke coverage per pixel (row 0 top), pixel centres, 'over' accumulation a = a + s (1 - a) with s = flow * stamp coverage."""
    acc = [0.0] * (w * h)
    for cx, cy, r in stamps:
        x0, x1 = max(0, int(math.floor(cx - r - 1))), min(w - 1, int(math.ceil(cx + r + 1)))
        y0, y1 = max(0, int(math.floor(cy - r - 1))), min(h - 1, int(math.ceil(cy + r + 1)))
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                c = falloff(math.hypot(x + 0.5 - cx, y + 0.5 - cy), r, hardness)
                if c > 0.0:
                    s = flow * c; i = y * w + x
                    acc[i] = acc[i] + s * (1.0 - acc[i])
    return acc

def commit(pix, w, h, cov, colour, opacity, erase):
    """Bakes a stroke into straight RGBA8 layer pixels: paint = over with colour at cov * opacity, erase = alpha * (1 - cov * opacity)."""
    out = bytearray(pix)
    for i in range(w * h):
        a = min(cov[i], 1.0) * opacity
        if a <= 0.0: continue
        o = i * 4
        if erase:
            out[o + 3] = int(math.floor(pix[o + 3] * (1.0 - a) + 0.5))
        else:
            r, g, b, ar = R.composite(tuple(pix[o + k] / 255.0 for k in range(3)), pix[o + 3] / 255.0, colour, a, 0)
            out[o:o + 4] = bytes(int(min(max(v, 0.0), 1.0) * 255.0 + 0.5) for v in (r, g, b, ar))
        if out[o + 3] == 0: out[o:o + 3] = b"\0\0\0"   # canonical transparent: no hidden colour under alpha 0
    return bytes(out)

def write_vectors(path):
    rnd = random.Random(11)
    lines = ["# diameter spacing pressureSize | x y p ... | -> x y radius ..."]
    cases = [(20.0, 0.1, 0, [(10, 10, 1), (60, 10, 1)]),
             (20.0, 0.25, 0, [(10, 10, 1), (30, 10, 1), (30, 40, 1)]),
             (10.0, 0.05, 1, [(5, 5, 0.0), (45, 5, 1.0)]),
             (3.0, 0.1, 0, [(0, 0, 1), (10, 0, 1)])] + \
            [(rnd.choice([4.0, 12.0, 40.0]), rnd.choice([0.05, 0.1, 0.3]), rnd.randrange(2),
              [(rnd.uniform(0, 100), rnd.uniform(0, 100), rnd.random()) for _ in range(rnd.randrange(1, 6))]) for _ in range(40)]
    for d, sp, ps, pts in cases:
        st = walk(pts, d, sp, bool(ps))
        lines.append("%.9g %.9g %d | %s | -> %s" % (d, sp, ps, " ".join("%.9g %.9g %.9g" % p for p in pts), " ".join("%.9g %.9g %.9g" % s for s in st)))
    open(path, "w").write("\n".join(lines) + "\n")
    return len(cases)

def small(path):
    """A small stroke case with its baked result, as text, for the Kotlin tests (core/studio-model/src/test/resources/stroke_small.txt)."""
    rnd = random.Random(21)
    w, h = 30, 22
    pix = bytearray()
    for _ in range(w * h):
        a = rnd.choice([0, 0, 60, 128, 255])
        pix += bytes((rnd.randrange(256), rnd.randrange(256), rnd.randrange(256), a)) if a else bytes(4)
    cases = [("paint", 9.0, 0.5, 0.8, 0.6, 0.1, 1, [(4, 4, 0.3), (15, 12, 0.9), (26, 6, 0.5)], (0.9, 0.4, 0.1)),
             ("erase", 7.0, 0.8, 1.0, 0.7, 0.1, 0, [(3, 18, 1), (27, 3, 1)], (0, 0, 0))]
    with open(path, "w") as f:
        f.write("layer %d %d %s\n" % (w, h, bytes(pix).hex()))
        for kind, dia, hard, flow, op, sp, ps, pts, col in cases:
            st = walk(pts, dia, sp, bool(ps)); cov = coverage(w, h, st, hard, flow)
            baked = commit(bytes(pix), w, h, cov, col, op, kind == "erase")
            f.write("case %s %g %g %g %g %g %d %g %g %g | %s | %s\n" % (kind, dia, hard, flow, op, sp, ps, col[0], col[1], col[2], " ".join("%g %g %g" % p for p in pts), baked.hex()))

# ---- golden scene studio_brush -------------------------------------------------------------------------------------------------
W, H = 200, 150
def backdrop():
    p = bytearray()
    for y in range(H):
        for x in range(W):
            p += bytes((40 + x, 200 - y, 90 + (x + y) % 60, 255))
    return bytes(p)

def opaque_blue():
    return bytes((30, 60, 200, 255)) * (W * H)

CASES = {   # name: (stroke points, diameter, hardness, flow, spacing, pressureSize, colour, opacity, erase, base pixels builder)
    "hard": ([(20, 30, 1), (100, 30, 1), (160, 110, 1)], 30.0, 1.0, 1.0, 0.1, False, (0.9, 0.2, 0.1), 1.0, False, None),
    "soft": ([(30, 100, 1), (170, 40, 1)], 40.0, 0.2, 1.0, 0.1, False, (0.1, 0.8, 0.3), 0.7, False, None),
    "flow": ([(20, 75, 1), (180, 75, 1)], 24.0, 0.5, 0.15, 0.08, False, (1.0, 1.0, 1.0), 1.0, False, None),
    "pressure": ([(20, 20, 0.1), (100, 70, 0.6), (180, 120, 1.0)], 36.0, 0.8, 1.0, 0.1, True, (0.0, 0.0, 0.0), 0.9, False, None),
    "erase": ([(30, 20, 1), (170, 130, 1)], 44.0, 0.6, 1.0, 0.1, False, (0, 0, 0), 0.8, True, opaque_blue),
}

def make(d):
    os.makedirs(d, exist_ok=True)
    base = backdrop()
    open(os.path.join(d, "bg.rgba"), "wb").write(base)
    for name, (pts, dia, hard, flow, sp, ps, col, op, er, builder) in CASES.items():
        paint0 = builder() if builder else bytes(W * H * 4)
        open(os.path.join(d, "paint_%s.rgba" % name), "wb").write(paint0)
        stamps = walk(pts, dia, sp, ps)
        cov = coverage(W, H, stamps, hard, flow)
        with open(os.path.join(d, "scene_%s.txt" % name), "w") as f:
            f.write("canvas %d %d\nlayer %s %d %d 0 0 1 1 0\nlayer %s %d %d 0 0 1 1 0\n" % (W, H, os.path.join(d, "bg.rgba"), W, H, os.path.join(d, "paint_%s.rgba" % name), W, H))
            f.write("stroke 1 %g %g %g %g %d %g %g\n" % (col[0], col[1], col[2], op, 1 if er else 0, hard, flow))   # paint layer slot 1
            for x, y, r in stamps: f.write("stamp %.9g %.9g %.9g\n" % (x, y, r))
        baked = commit(paint0, W, H, cov, col, op, er)
        exp = R.render([R.Layer(base, W, H), R.Layer(baked, W, H)], (0.0, 0.0, 1.0), W, H)
        open(os.path.join(d, "expected_%s.rgba" % name), "wb").write(exp)
        open(os.path.join(d, "baked_%s.rgba" % name), "wb").write(baked)
        print("%s: %d stamps" % (name, len(stamps)))

def compare(d, tol=2):
    fails = 0
    for name in CASES:
        exp = open(os.path.join(d, "expected_%s.rgba" % name), "rb").read()
        got = open(os.path.join(d, "out_%s.rgba" % name), "rb").read()
        worst = max(abs(a - b) for a, b in zip(exp, got)); off = sum(1 for a, b in zip(exp, got) if abs(a - b) > 1)
        ok = worst <= tol
        print("%s  studio_brush %-8s worst %d level(s), %d values off by more than 1  (tolerance %d)" % ("PASS" if ok else "FAIL", name, worst, off, tol))
        fails += 0 if ok else 1
    return fails

if __name__ == "__main__":
    c = sys.argv[1]
    if c == "vectors": print(write_vectors(sys.argv[2]), "walker cases")
    elif c == "small": small(sys.argv[2])
    elif c == "make": make(sys.argv[2])
    elif c == "compare": sys.exit(1 if compare(sys.argv[2]) else 0)
```
### Patches
#### studio_compositor.h.patch
```diff
--- a/core/native/src/main/cpp/studio/studio_compositor.h
+++ b/core/native/src/main/cpp/studio/studio_compositor.h
@@ -36,6 +36,17 @@
     bool updateLayerRegion(int slot, int x, int y, int w, int h, const uint8_t *rgba);
     void removeLayer(int slot);
 
+    /**
+     * Live stroke on one layer (spec 3.3: the stroke buffer is blended in at the layer's place in the stack). beginStroke clears an R16F
+     * coverage buffer the size of the layer; addStamps draws stamps (x, y, radius triples, layer pixels) into it with `over` accumulation;
+     * render() shows the layer with the stroke applied; readStroke gives the coverage of a rectangle back for the commit; endStroke frees it.
+     */
+    bool beginStroke(int slot, float r, float g, float b, float opacity, bool erase, float hardness, float flow);
+    bool addStamps(const float *xyr, int count);
+    bool readStroke(int x, int y, int w, int h, float *coverage);
+    void endStroke();
+    bool stroking() const { return stroke_.slot >= 0; }
+
     /** Renders the view (top left vx, vy in document pixels, zoom screen pixels per document pixel) into out: outW * outH * 4 straight RGBA8, row 0 top. */
     bool render(const std::vector<LayerDraw> &layers, float vx, float vy, float zoom, int outW, int outH, uint8_t *out);
 
@@ -45,11 +56,14 @@
 private:
     struct Slot { GLuint tex = 0; int w = 0, h = 0; };
     struct Target { GLuint tex = 0, fbo = 0; int w = 0, h = 0; GLenum fmt = 0; };
+    struct Stroke { int slot = -1; float rgb[3] = {0, 0, 0}; float opacity = 1; bool erase = false; float hardness = 1, flow = 1; };
     bool ensureTarget(Target &t, int w, int h, GLenum fmt);
     void freeTarget(Target &t);
     void draw(const Target &dst, const Target *backdrop, const LayerDraw *l, int mode, float vx, float vy, float zoom);
 
-    GLuint prog_ = 0, vert_ = 0, vao_ = 0;
+    GLuint prog_ = 0, stampProg_ = 0, vert_ = 0, vao_ = 0;
+    Target strokeBuf_;
+    Stroke stroke_;
     Slot slots_[kMaxSlots];
     Target ping_[2], resolve_;
     bool ready_ = false;
```
#### studio_compositor.cpp.patch
```diff
--- a/core/native/src/main/cpp/studio/studio_compositor.cpp
+++ b/core/native/src/main/cpp/studio/studio_compositor.cpp
@@ -32,6 +32,15 @@
     GLint ok = 0;
     glGetProgramiv(prog_, GL_LINK_STATUS, &ok);
     if (!ok) { char log[4096]; glGetProgramInfoLog(prog_, sizeof log, nullptr, log); err += log; glDeleteProgram(prog_); prog_ = 0; return false; }
+    GLuint sv = compile(GL_VERTEX_SHADER, SH_studio_stamp_glsl, err);
+    GLuint sf = compile(GL_FRAGMENT_SHADER, SH_studio_stamp_frag, err);
+    if (!sv || !sf) { if (sv) glDeleteShader(sv); if (sf) glDeleteShader(sf); return false; }
+    stampProg_ = glCreateProgram();
+    glAttachShader(stampProg_, sv); glAttachShader(stampProg_, sf);
+    glLinkProgram(stampProg_);
+    glDeleteShader(sv); glDeleteShader(sf);
+    glGetProgramiv(stampProg_, GL_LINK_STATUS, &ok);
+    if (!ok) { char log[4096]; glGetProgramInfoLog(stampProg_, sizeof log, nullptr, log); err += log; glDeleteProgram(stampProg_); stampProg_ = 0; return false; }
     glGenVertexArrays(1, &vao_);
     ready_ = true;
     return true;
@@ -42,8 +51,10 @@
     for (Slot &s : slots_) { if (s.tex) glDeleteTextures(1, &s.tex); s = Slot(); }
     for (Target &t : ping_) freeTarget(t);
     freeTarget(resolve_);
-    glDeleteProgram(prog_); glDeleteVertexArrays(1, &vao_);
-    prog_ = 0; vao_ = 0; ready_ = false;
+    freeTarget(strokeBuf_);
+    stroke_ = Stroke();
+    glDeleteProgram(prog_); glDeleteProgram(stampProg_); glDeleteVertexArrays(1, &vao_);
+    prog_ = 0; stampProg_ = 0; vao_ = 0; ready_ = false;
 }
 
 void Compositor::freeTarget(Target &t) {
@@ -120,6 +131,17 @@
         glUniform2i(glGetUniformLocation(prog_, "uLayerSize"), s.w, s.h);
         glUniform4f(glGetUniformLocation(prog_, "uRect"), l->x, l->y, s.w * l->scale, s.h * l->scale);
         glUniform1f(glGetUniformLocation(prog_, "uOpacity"), l->opacity);
+        bool live = stroke_.slot == l->slot && strokeBuf_.tex;
+        glUniform1i(glGetUniformLocation(prog_, "uStrokeMode"), live ? (stroke_.erase ? 2 : 1) : 0);
+        if (live) {
+            glActiveTexture(GL_TEXTURE2);
+            glBindTexture(GL_TEXTURE_2D, strokeBuf_.tex);
+            glUniform1i(glGetUniformLocation(prog_, "uStroke"), 2);
+            glUniform3f(glGetUniformLocation(prog_, "uStrokeColor"), stroke_.rgb[0], stroke_.rgb[1], stroke_.rgb[2]);
+            glUniform1f(glGetUniformLocation(prog_, "uStrokeOpacity"), stroke_.opacity);
+        }
+    } else {
+        glUniform1i(glGetUniformLocation(prog_, "uStrokeMode"), 0);
     }
     glBindVertexArray(vao_);
     glDrawArrays(GL_TRIANGLES, 0, 3);
@@ -150,11 +172,72 @@
     return ok && glGetError() == GL_NO_ERROR;
 }
 
+bool Compositor::beginStroke(int slot, float r, float g, float b, float opacity, bool erase, float hardness, float flow) {
+    if (!ready_ || slot < 0 || slot >= kMaxSlots || !slots_[slot].tex) return false;
+    while (glGetError() != GL_NO_ERROR) {}
+    const Slot &sl = slots_[slot];
+    if (!ensureTarget(strokeBuf_, sl.w, sl.h, GL_R16F)) { stroke_ = Stroke(); return false; }
+    glBindFramebuffer(GL_FRAMEBUFFER, strokeBuf_.fbo);
+    glViewport(0, 0, sl.w, sl.h);
+    glClearColor(0.f, 0.f, 0.f, 0.f);
+    glClear(GL_COLOR_BUFFER_BIT);
+    stroke_ = Stroke{slot, {r, g, b}, opacity, erase, hardness, flow};
+    return glGetError() == GL_NO_ERROR;
+}
+
+bool Compositor::addStamps(const float *xyr, int count) {
+    if (!ready_ || stroke_.slot < 0 || !strokeBuf_.tex || count <= 0) return false;
+    GLint prevFbo = 0, prevVp[4];
+    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
+    glGetIntegerv(GL_VIEWPORT, prevVp);
+    glBindFramebuffer(GL_FRAMEBUFFER, strokeBuf_.fbo);
+    glViewport(0, 0, strokeBuf_.w, strokeBuf_.h);
+    glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST); glDisable(GL_CULL_FACE);
+    glEnable(GL_BLEND);
+    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);   // a = s + a (1 - s)
+    glUseProgram(stampProg_);
+    glUniform2f(glGetUniformLocation(stampProg_, "uSize"), float(strokeBuf_.w), float(strokeBuf_.h));
+    glUniform1f(glGetUniformLocation(stampProg_, "uHardness"), stroke_.hardness);
+    glUniform1f(glGetUniformLocation(stampProg_, "uFlow"), stroke_.flow);
+    glBindVertexArray(vao_);
+    const GLint loc = glGetUniformLocation(stampProg_, "uStamps");
+    for (int i = 0; i < count; i += 64) {
+        int n = std::min(64, count - i);
+        float buf[64 * 4];
+        for (int k = 0; k < n; k++) { buf[k * 4] = xyr[(i + k) * 3]; buf[k * 4 + 1] = xyr[(i + k) * 3 + 1]; buf[k * 4 + 2] = xyr[(i + k) * 3 + 2]; buf[k * 4 + 3] = 0.f; }
+        glUniform4fv(loc, n, buf);
+        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, n);
+    }
+    glDisable(GL_BLEND);
+    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
+    glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
+    return glGetError() == GL_NO_ERROR;
+}
+
+bool Compositor::readStroke(int x, int y, int w, int h, float *coverage) {
+    if (!ready_ || !strokeBuf_.tex || x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > strokeBuf_.w || y + h > strokeBuf_.h) return false;
+    GLint prevFbo = 0;
+    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
+    glBindFramebuffer(GL_FRAMEBUFFER, strokeBuf_.fbo);
+    glPixelStorei(GL_PACK_ALIGNMENT, 1);
+    std::vector<float> rgba(size_t(w) * h * 4);   // RGBA with FLOAT is the combination every driver accepts for a float attachment
+    glReadPixels(x, y, w, h, GL_RGBA, GL_FLOAT, rgba.data());
+    glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
+    for (size_t i = 0; i < size_t(w) * h; i++) coverage[i] = rgba[i * 4];
+    return glGetError() == GL_NO_ERROR;
+}
+
+void Compositor::endStroke() {
+    freeTarget(strokeBuf_);
+    stroke_ = Stroke();
+}
+
 int64_t Compositor::textureBytes() const {
     int64_t n = 0;
     for (const Slot &s : slots_) n += int64_t(s.w) * s.h * 4;
     for (const Target &t : ping_) n += int64_t(t.w) * t.h * 8;
     n += int64_t(resolve_.w) * resolve_.h * 4;
+    n += int64_t(strokeBuf_.w) * strokeBuf_.h * 2;
     return n;
 }
 
```
#### studio_composite.frag.patch
```diff
--- a/core/native/src/main/cpp/shaders/studio_composite.frag
+++ b/core/native/src/main/cpp/shaders/studio_composite.frag
@@ -8,12 +8,27 @@
 out vec4 oColor;
 uniform sampler2D uBackdrop;   // RGBA16F, straight alpha, output sized (not read when uMode is -2)
 uniform sampler2D uLayer;      // RGBA8, straight alpha, layer sized
+uniform sampler2D uStroke;     // R16F coverage of the stroke being drawn on this layer (unit 2), layer sized
+uniform int uStrokeMode;       // 0 none, 1 paint, 2 erase
+uniform vec3 uStrokeColor;     // straight colour of the stroke
+uniform float uStrokeOpacity;  // the stroke's opacity ceiling
 uniform ivec2 uLayerSize;
 uniform vec4 uRect;            // layer top left x, y and the w, h it covers, all in document pixels
 uniform vec3 uView;            // top left x, y of the view in document pixels, zoom (screen pixels per document pixel)
 uniform float uOpacity;
 uniform int uMode;             // 0 normal, 1 multiply, 2 screen; -1 copy the backdrop (final resolve); -2 clear
 
+// One layer texel with the live stroke applied (what the commit will bake): paint is 'normal over' of the colour at coverage * opacity, erase scales alpha.
+vec4 fetchTexel(ivec2 c) {
+    vec4 t = texelFetch(uLayer, c, 0);
+    if (uStrokeMode == 0) return t;
+    float a = min(texelFetch(uStroke, c, 0).r, 1.0) * uStrokeOpacity;
+    if (a <= 0.0) return t;
+    if (uStrokeMode == 2) return vec4(t.rgb, t.a * (1.0 - a));
+    float ar = a + t.a * (1.0 - a);
+    return vec4(((1.0 - a) * t.a * t.rgb + a * uStrokeColor) / ar, ar);
+}
+
 // Bilinear on alpha weighted straight colour in pixel centres, clamped to the layer edge, alpha 0 outside the rectangle.
 vec4 sampleLayer(vec2 d) {
     vec2 l = (d - uRect.xy) / uRect.zw * vec2(uLayerSize);
@@ -27,7 +42,7 @@
     float asum = 0.0;
     for (int j = 0; j < 2; j++) {
         for (int i = 0; i < 2; i++) {
-            vec4 t = texelFetch(uLayer, clamp(i0 + ivec2(i, j), ivec2(0), hi), 0);
+            vec4 t = fetchTexel(clamp(i0 + ivec2(i, j), ivec2(0), hi));
             float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y) * t.a;
             acc += t.rgb * w;
             asum += w;
```
#### jni_studio.cpp.patch
```diff
--- a/core/native/src/main/cpp/jni_studio.cpp
+++ b/core/native/src/main/cpp/jni_studio.cpp
@@ -95,6 +95,39 @@
     });
 }
 
+/** Clears an R16F coverage buffer the size of the slot's layer and remembers the stroke style. */
+JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_beginStroke(JNIEnv *, jobject, jlong h, jint slot, jfloat r, jfloat g, jfloat b, jfloat opacity, jboolean erase, jfloat hardness, jfloat flow) {
+    return guarded<jboolean>("studioBeginStroke", JNI_FALSE, [&]() -> jboolean { return reinterpret_cast<Compositor *>(h)->beginStroke(slot, r, g, b, opacity, erase, hardness, flow); });
+}
+
+/** xyr: count * 3 floats (x, y, radius in layer pixels). */
+JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_addStamps(JNIEnv *env, jobject, jlong h, jfloatArray xyr, jint count) {
+    return guarded<jboolean>("studioAddStamps", JNI_FALSE, [&]() -> jboolean {
+        if (!xyr || count <= 0 || size_t(env->GetArrayLength(xyr)) < size_t(count) * 3) return JNI_FALSE;
+        jfloat *p = env->GetFloatArrayElements(xyr, nullptr);
+        if (!p) throw std::bad_alloc();
+        bool ok = reinterpret_cast<Compositor *>(h)->addStamps(p, count);
+        env->ReleaseFloatArrayElements(xyr, p, JNI_ABORT);
+        return ok;
+    });
+}
+
+/** coverage: w * h floats, row 0 = the rectangle's top row. */
+JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_StudioNative_readStroke(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt, jfloatArray coverage) {
+    return guarded<jboolean>("studioReadStroke", JNI_FALSE, [&]() -> jboolean {
+        if (!coverage || w <= 0 || hgt <= 0 || w > 16384 || hgt > 16384 || size_t(env->GetArrayLength(coverage)) < size_t(w) * size_t(hgt)) return JNI_FALSE;
+        jfloat *p = env->GetFloatArrayElements(coverage, nullptr);
+        if (!p) throw std::bad_alloc();
+        bool ok = reinterpret_cast<Compositor *>(h)->readStroke(x, y, w, hgt, p);
+        env->ReleaseFloatArrayElements(coverage, p, 0);
+        return ok;
+    });
+}
+
+JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_StudioNative_endStroke(JNIEnv *, jobject, jlong h) {
+    guardedV("studioEndStroke", [&] { reinterpret_cast<Compositor *>(h)->endStroke(); });
+}
+
 JNIEXPORT jlong JNICALL Java_app_rawline_core_nativelib_StudioNative_textureBytes(JNIEnv *, jobject, jlong h) {
     return guarded<jlong>("studioTextureBytes", 0, [&]() -> jlong { return reinterpret_cast<Compositor *>(h)->textureBytes(); });
 }
```
#### StudioNative.kt.patch
```diff
--- a/core/native/src/main/kotlin/app/rawline/core/nativelib/StudioNative.kt
+++ b/core/native/src/main/kotlin/app/rawline/core/nativelib/StudioNative.kt
@@ -19,6 +19,13 @@
     external fun removeLayer(h: Long, slot: Int)
     /** [layers]: 6 floats per layer, bottom to top: slot, x, y, scale, opacity (0 to 1), blend mode id. [out]: outW * outH * 4 bytes of straight RGBA8, row 0 at the top. */
     external fun render(h: Long, layers: FloatArray, vx: Float, vy: Float, zoom: Float, outW: Int, outH: Int, out: ByteArray): Boolean
+    /** Starts a live stroke on [slot]: clears its coverage buffer. [r], [g], [b] are straight 0 to 1 in the document's blend space. */
+    external fun beginStroke(h: Long, slot: Int, r: Float, g: Float, b: Float, opacity: Float, erase: Boolean, hardness: Float, flow: Float): Boolean
+    /** [xyr]: count * 3 floats, x, y and radius of each stamp in layer pixels (from `StrokeWalker`). */
+    external fun addStamps(h: Long, xyr: FloatArray, count: Int): Boolean
+    /** Coverage 0 to 1 of the rectangle, w * h floats, for the commit (`StrokeReference.commit`). */
+    external fun readStroke(h: Long, x: Int, y: Int, w: Int, hgt: Int, coverage: FloatArray): Boolean
+    external fun endStroke(h: Long)
     /** GPU bytes held by layer textures and the ping-pong pair (for the Copy report). */
     external fun textureBytes(h: Long): Long
 }
```
#### studio_golden.cpp.patch
```diff
--- a/tools/golden/studio_golden.cpp
+++ b/tools/golden/studio_golden.cpp
@@ -36,9 +36,17 @@
     if (!f) { std::fprintf(stderr, "no scene\n"); return 5; }
     float vx = 0, vy = 0, zoom = 1; int ow = 0, oh = 0;
     std::vector<rl::studio::LayerDraw> draws;
+    std::vector<float> stamps;
     char kind[16], path[1024];
     while (std::fscanf(f, "%15s", kind) == 1) {
-        if (std::string(kind) == "view") { if (std::fscanf(f, "%f %f %f %d %d", &vx, &vy, &zoom, &ow, &oh) != 5) return 5; }
+        if (std::string(kind) == "canvas") { int cw, ch; if (std::fscanf(f, "%d %d", &cw, &ch) != 2) return 5; if (ow == 0) { ow = cw; oh = ch; } }
+        else if (std::string(kind) == "stroke") {
+            int slot, erase; float r, g, b, op, hard, flow;
+            if (std::fscanf(f, "%d %f %f %f %f %d %f %f", &slot, &r, &g, &b, &op, &erase, &hard, &flow) != 8) return 5;
+            if (!comp.beginStroke(slot, r, g, b, op, erase != 0, hard, flow)) { std::fprintf(stderr, "beginStroke failed\n"); return 6; }
+        }
+        else if (std::string(kind) == "stamp") { float x, y, rad; if (std::fscanf(f, "%f %f %f", &x, &y, &rad) != 3) return 5; stamps.insert(stamps.end(), {x, y, rad}); }
+        else if (std::string(kind) == "view") { if (std::fscanf(f, "%f %f %f %d %d", &vx, &vy, &zoom, &ow, &oh) != 5) return 5; }
         else if (std::string(kind) == "layer") {
             int w, h, mode; float x, y, sc, op;
             if (std::fscanf(f, "%1023s %d %d %f %f %f %f %d", path, &w, &h, &x, &y, &sc, &op, &mode) != 8) return 5;
@@ -52,7 +60,12 @@
         }
     }
     std::fclose(f);
+    if (!stamps.empty() && !comp.addStamps(stamps.data(), int(stamps.size() / 3))) { std::fprintf(stderr, "addStamps failed\n"); return 6; }
     std::vector<uint8_t> out(size_t(ow) * oh * 4);
+    if (comp.stroking() && getenv("STUDIO_READ_STROKE")) {   // commit path check: read the coverage back
+        std::vector<float> cov(size_t(ow) * oh); comp.readStroke(0, 0, ow, oh, cov.data());
+        FILE *cf = std::fopen(getenv("STUDIO_READ_STROKE"), "wb"); std::fwrite(cov.data(), 4, cov.size(), cf); std::fclose(cf);
+    }
     if (!comp.render(draws, vx, vy, zoom, ow, oh, out.data())) { std::fprintf(stderr, "render failed\n"); return 7; }
     std::fprintf(stderr, "rendered %dx%d, %d layers, %.2f MB of textures\n", ow, oh, int(draws.size()), comp.textureBytes() / 1048576.0);
     FILE *o = std::fopen(argv[2], "wb");
```
#### studio-golden.sh.patch
```diff
--- a/tools/golden/studio-golden.sh
+++ b/tools/golden/studio-golden.sh
@@ -11,3 +11,8 @@
 python3 "$ROOT/tools/studio/studio_scene.py" make "$W"
 for k in a b c; do "$W/studio_golden" "$W/scene_$k.txt" "$W/out_$k.rgba"; done
 python3 "$ROOT/tools/studio/studio_scene.py" compare "$W"
+
+# S1b: the live stroke (stamps into the R16F stroke buffer, shown through the compositor) against the Python reference that bakes the stroke
+python3 "$ROOT/tools/studio/studio_brush.py" make "$W"
+for k in hard soft flow pressure erase; do "$W/studio_golden" "$W/scene_$k.txt" "$W/out_$k.rgba"; done
+python3 "$ROOT/tools/studio/studio_brush.py" compare "$W"
```

## 9. Exit check (all must hold)

1. The debug activity opens a blank project (12 MP cap shown in the dialog) or a photo from the system picker.
2. Layers panel as in section 5; the 10 layer cap and the 600 MB guard show their messages; reorder with up and down buttons.
3. Brush (size, hardness, opacity, flow, HSV colour, S Pen pressure to size) and Eraser; Move and Scale; one history entry per stroke or per move or scale gesture; undo and redo buttons; a second finger cancels a finger stroke without a history entry; a stylus is never interrupted by a palm.
4. Two finger pan and zoom, clamped so some canvas stays on screen.
5. Autosave as in D8; layer pixels as `PixelContainer`; kill the app mid-stroke on the phone, reopen: the project opens and loses at most that stroke.
6. Host tests green, including the 40 above; `studio-golden.sh` green in CI after the S1a golden step; every Develop golden byte-identical.
7. The Copy report has the Studio timers and gauges of section 6.
8. Develop unchanged; no new permission; `./gradlew assembleDebug testDebugUnitTest` green.

Jai's check on the phone (one message, then paste the Copy report): open the debug Studio screen, make a project, add a photo, add a layer, set it to Multiply, paint 20 strokes with the finger (and the S Pen if wanted), move and scale the photo layer, undo five times and redo two, force stop the app in the middle of a stroke, reopen it and check the project is there, then Copy report.

## 10. Risks and open items (logged, none blocks the task)

- Compose or `GLSurfaceView` display path may not hold 120 Hz at full screen; the maths and the compositor are independent of that choice.
- `readStroke` stalls the GL thread once per stroke end (a rectangle readback). Expected small; the Copy report's `studio_commit_ms` decides whether a PBO is worth it.
- zlib encode of a 12 MP layer on the autosave thread: measure `studio_autosave_ms`; the content hash means an unchanged layer is never written again.
- The 600 MB guard is a guess for the S24 Ultra; the report's `studio_texture_mb` tunes it.
- S2 replaces `PixelContainer` with tiles (spec 2.15) through a schema version bump and a migration.
