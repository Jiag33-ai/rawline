package app.rawline.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rawline.core.model.Geometry
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LocalValueFeedback
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView
import app.rawline.core.ui.LrOutlineButton
import app.rawline.core.ui.PanelHeader
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.TouchChip
import app.rawline.core.ui.ValueStyle
import kotlin.math.abs

/** Aspect presets in chip order. "original" and "free" are special; the rest are width:height. */
private val AspectNames = listOf(
    "original", "free", "1:1", "4:5", "5:4", "3:2", "2:3", "4:3", "3:4", "16:9", "9:16", "3:1", "1:2", "5:7", "8.5:11", "10:16",
)

/** Height of the crop controls above the Cancel and Done bar: chips 48, dial 84, tools 56 and breathing room. */
val CropPanelHeight = 192.dp

/**
 * Crop controls, shown in the bottom stack while the crop workspace is open.
 * [sub] is "crop" (aspect chips, straighten dial, rotate, flip, level, reset) or "perspective" (auto, vertical, horizontal).
 */
@Composable
fun GeometryPanel(
    state: EditorState, frameAspect: Float, sub: String,
    onAutoLevel: (() -> Unit)?, onAutoPerspective: (() -> Unit)?, onStraightening: (Boolean) -> Unit = {},
) {
    val feedback = LocalValueFeedback.current
    val g = state.recipe.geometry
    val dist = state.recipe.optics.distortion
    fun geo(label: String, f: (Geometry) -> Geometry) = state.edit(label) { it.copy(geometry = f(it.geometry)) }
    if (sub == "perspective") {
        PanelColumn {
            PanelHeader("Perspective", Resets.perspectiveModified(g), { geo("Reset perspective") { Resets.perspective(it) } }, trailing = {
                if (onAutoPerspective != null) LrOutlineButton("Auto", onAutoPerspective, small = true)
            })
            RawSlider("Vertical", g.keystoneV, -100f..100f, 0f,
                onChange = { v -> state.live { it.copy(geometry = it.geometry.copy(keystoneV = v)) } }, onCommit = { state.commit("Vertical perspective") })
            RawSlider("Horizontal", g.keystoneH, -100f..100f, 0f,
                onChange = { v -> state.live { it.copy(geometry = it.geometry.copy(keystoneH = v)) } }, onCommit = { state.commit("Horizontal perspective") })
        }
        return
    }
    // The crop that was in force when the dial was first touched: straightening refits from it, so the crop grows back if the angle returns.
    var dialBase by remember { mutableStateOf<CropRect?>(null) }
    PanelColumn {
        // ---- aspect chips with the landscape / portrait swap pinned at the left ----
        Row(Modifier.fillMaxWidth().height(LrDim.touch), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(LrDim.touch).clickable {
                    geo("Swap landscape and portrait") { CropMath.swapLandscapePortrait(it, dist, frameAspect) }
                    feedback.flash("Crop", "orientation swapped")
                }.semantics { contentDescription = "Swap landscape and portrait" },
                contentAlignment = Alignment.Center,
            ) { LrIconView(LrIcon.SWAP_ORIENTATION, Lr.IconPrimary, size = 24.dp) }
            Box(Modifier.width(1.dp).height(24.dp).background(Lr.Divider))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                val custom = AspectNames.none { it.equals(g.aspect, true) }
                if (custom) TouchChip("Custom", true, {})
                AspectNames.forEach { name ->
                    TouchChip(
                        CropMath.label(name), g.aspect.equals(name, true),
                        { geo("Crop ${CropMath.label(name)}") { CropMath.pickAspect(it, name, dist, frameAspect) } },
                        // double tap: that shape at the biggest size the picture allows
                        onDoubleTap = { geo("Crop ${CropMath.label(name)} largest") { CropMath.pickAspectLargest(it, name, dist, frameAspect) }; feedback.flash("Crop", "${CropMath.label(name)} reset") },
                    )
                }
            }
        }
        // ---- straighten ----
        StraightenDial(
            g.angle,
            onStart = { dialBase = CropRect.of(state.recipe.geometry); onStraightening(true) },
            onChange = { a -> state.live { it.withStraighten(a, frameAspect, dialBase ?: CropRect.of(it.geometry)) } },
            onCommit = { state.commit("Straighten"); dialBase = null; onStraightening(false) },
            onReset = { state.edit("Reset straighten") { it.withStraighten(0f, frameAspect) }; feedback.flash("Straighten", "0.0° (reset)") },
        )
        // ---- turn, flip, level, reset ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            CropToolButton(LrIcon.ROTATE_LEFT, "Rotate L", { geo("Rotate left") { CropMath.rotate(it, false, dist, frameAspect) } })
            CropToolButton(LrIcon.ROTATE, "Rotate R", { geo("Rotate right") { CropMath.rotate(it, true, dist, frameAspect) } })
            CropToolButton(LrIcon.FLIP_H, "Flip H", { geo("Flip horizontal") { CropMath.flip(it, true) } }, active = g.flipH)
            CropToolButton(LrIcon.FLIP_V, "Flip V", { geo("Flip vertical") { CropMath.flip(it, false) } }, active = g.flipV)
            CropToolButton(LrIcon.LEVEL, "Level", { onAutoLevel?.invoke() }, enabled = onAutoLevel != null)
            CropToolButton(LrIcon.RESET, "Reset", {
                geo("Reset crop") { Geometry() }; feedback.flash("Crop", "reset")
            })
        }
    }
}

/** Icon over a short label, a full 48 dp wide and 56 dp tall touch area. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.CropToolButton(icon: LrIcon, label: String, onClick: () -> Unit, active: Boolean = false, enabled: Boolean = true) {
    val tint = if (!enabled) Lr.TextDisabled else if (active) Lr.Accent else Lr.IconPrimary
    Column(
        Modifier.weight(1f).height(56.dp).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        LrIconView(icon, tint, size = 22.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (!enabled) Lr.TextDisabled else if (active) Lr.Accent else Lr.TextMuted, maxLines = 1)
    }
}

/**
 * Straighten dial: the angle in large figures with 0.1 degree steppers either side, a ruler under it that follows the finger
 * (drag sideways, 9 dp per degree), and a fixed needle at the centre. Double tap anywhere on it puts the angle back to zero.
 * It snaps to zero within a quarter degree, from an unsnapped running value so it can still be dragged away from zero.
 */
@Composable
fun StraightenDial(angle: Float, onStart: () -> Unit, onChange: (Float) -> Unit, onCommit: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val cur by rememberUpdatedState(angle)
    val start by rememberUpdatedState(onStart)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val reset by rememberUpdatedState(onReset)
    fun step(d: Float) { start(); change(CropMath.snapAngle(((cur + d) * 10f).let { Math.round(it) / 10f })); commit() }
    Column(modifier.fillMaxWidth().height(84.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 64.dp, height = 48.dp).clickable { step(-0.1f) }.semantics { contentDescription = "Straighten minus 0.1 degrees" }, contentAlignment = Alignment.Center) { Text("−0.1", style = MaterialTheme.typography.labelMedium, color = Lr.TextMuted) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(String.format(java.util.Locale.US, "%+.1f°", angle).replace("+0.0", "0.0").replace("-0.0", "0.0"), style = ValueStyle.copy(fontSize = 15.sp, lineHeight = 20.sp), color = if (angle == 0f) Lr.TextSecondary else Lr.TextPrimary)
            }
            Box(Modifier.size(width = 64.dp, height = 48.dp).clickable { step(0.1f) }.semantics { contentDescription = "Straighten plus 0.1 degrees" }, contentAlignment = Alignment.Center) { Text("+0.1", style = MaterialTheme.typography.labelMedium, color = Lr.TextMuted) }
        }
        Canvas(
            Modifier.fillMaxWidth().weight(1f)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
                .pointerInput(Unit) {
                    val perDeg = 9.dp.toPx()
                    var raw = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { raw = cur; start() },
                        onDragEnd = { commit() }, onDragCancel = { commit() },
                    ) { c, dx ->
                        c.consume()
                        raw = (raw - dx / perDeg).coerceIn(-45f, 45f)
                        change(CropMath.snapAngle(raw))
                    }
                }
                .semantics { contentDescription = "Straighten dial. Drag sideways. Double tap to reset." },
        ) {
            val perDeg = 9.dp.toPx(); val cx = size.width / 2f
            val first = (angle - cx / perDeg).toInt() - 1; val last = (angle + cx / perDeg).toInt() + 1
            for (d in first..last) {
                if (d < -45 || d > 45) continue
                val x = cx + (d - angle) * perDeg
                val bow = (x - cx) / cx; val y0 = 6.dp.toPx() + bow * bow * 8.dp.toPx()
                val major = d % 5 == 0
                val len = if (d == 0) 16.dp.toPx() else if (major) 11.dp.toPx() else 5.dp.toPx()
                val col = if (d == 0) Lr.TextPrimary else if (major) Lr.IconSecondary else Lr.TextDisabled
                drawLine(col, Offset(x, y0), Offset(x, y0 + len), if (d == 0) 2.dp.toPx() else 1.2.dp.toPx())
            }
            drawLine(Lr.Accent, Offset(cx, 2.dp.toPx()), Offset(cx, 24.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

/**
 * The crop box over the uncropped frame. [fit] is the frame's rectangle in view pixels (left, top, width, height).
 *
 * One finger on a corner or an edge resizes (keeping the aspect when one is chosen), inside moves, two fingers pinch the crop about its
 * centre (out makes it smaller, as it zooms the picture inside the box). Everything is held on picture with [CropMath.settle], so the
 * box can never take in the empty wedges that straightening leaves. Double tap on the box or a handle resets the crop to the biggest
 * one of the current aspect. The thirds grid brightens while anything is held; [fineGrid] (straightening) adds a finer grid.
 */
@Composable
fun CropOverlay(state: EditorState, fit: FloatArray, frameAspect: Float, fineGrid: Boolean = false, modifier: Modifier = Modifier) {
    val g = state.recipe.geometry
    val fitNow by rememberUpdatedState(fit)
    val faNow by rememberUpdatedState(frameAspect)
    val feedback = LocalValueFeedback.current
    var held by remember { mutableStateOf(CropHandle.NONE) }
    var touching by remember { mutableStateOf(false) }
    Canvas(
        modifier.fillMaxSize().pointerInput(Unit) {
            var lastTapUp = 0L
            var lastTapPos = Offset.Zero
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val f = fitNow; val fa = faNow
                val geo0 = state.recipe.geometry
                val dist = state.recipe.optics.distortion
                val area = CropMath.validArea(geo0, dist, fa)
                val aspectPx = CropMath.aspectValue(geo0.aspect, fa)
                var rect = CropRect.of(geo0)
                val l = f[0] + rect.x0 * f[2]; val t = f[1] + rect.y0 * f[3]; val r = f[0] + rect.x1 * f[2]; val b = f[1] + rect.y1 * f[3]
                val handle = CropMath.hitTest(l, t, r, b, down.position.x, down.position.y, 34.dp.toPx(), 26.dp.toPx())
                val perAxis = aspectPx == null || handle == CropHandle.MOVE
                var startRect = rect
                var moved = false; var pinching = false; var pinchTotal = 1f
                var upTime = down.uptimeMillis
                val slop = viewConfiguration.touchSlop
                touching = true; held = handle
                fun apply(nr: CropRect) { rect = nr; state.live { it.copy(geometry = nr.applyTo(it.geometry)) } }
                try {
                    do {
                        val ev = awaitPointerEvent()
                        upTime = ev.changes.maxOf { it.uptimeMillis }
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed >= 2) {
                            if (!pinching) { pinching = true; startRect = rect; pinchTotal = 1f; held = CropHandle.MOVE }
                            pinchTotal *= ev.calculateZoom()
                            apply(CropMath.settle(CropMath.pinch(startRect, 1f / pinchTotal), rect, area, false))
                            moved = true
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (!pinching && handle != CropHandle.NONE) {
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (c.pressed) {
                                if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                                if (moved) {
                                    val dx = (c.position.x - down.position.x) / f[2]; val dy = (c.position.y - down.position.y) / f[3]
                                    apply(CropMath.settle(CropMath.drag(startRect, handle, dx, dy, aspectPx, fa), rect, area, perAxis))
                                }
                                c.consume()
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                } finally {
                    touching = false; held = CropHandle.NONE
                    if (moved) state.commit("Crop")
                }
                if (!moved && handle != CropHandle.NONE) {
                    if (upTime - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis && (down.position - lastTapPos).getDistance() <= 48.dp.toPx()) {
                        state.edit("Reset crop") { it.copy(geometry = CropMath.resetCrop(it.geometry, it.optics.distortion, fa)) }
                        feedback.flash("Crop", "reset")
                        lastTapUp = 0L
                    } else { lastTapUp = upTime; lastTapPos = down.position }
                } else lastTapUp = 0L
            }
        },
    ) {
        val l = fit[0] + g.cropX * fit[2]; val t = fit[1] + g.cropY * fit[3]
        val w = g.cropW * fit[2]; val h = g.cropH * fit[3]
        // dim everything outside the box
        val shade = Color(0xB8000000)
        drawRect(shade, Offset(fit[0], fit[1]), Size(fit[2], t - fit[1]))
        drawRect(shade, Offset(fit[0], t + h), Size(fit[2], fit[1] + fit[3] - t - h))
        drawRect(shade, Offset(fit[0], t), Size(l - fit[0], h))
        drawRect(shade, Offset(l + w, t), Size(fit[0] + fit[2] - l - w, h))
        // grid: thirds always faint, clear while held; finer while straightening
        val gridAlpha = if (touching) 0.75f else 0.28f
        for (i in 1..2) {
            drawLine(Color.White.copy(alpha = gridAlpha), Offset(l + w * i / 3, t), Offset(l + w * i / 3, t + h), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = gridAlpha), Offset(l, t + h * i / 3), Offset(l + w, t + h * i / 3), 1.dp.toPx())
        }
        if (fineGrid) for (i in 1..8) {
            drawLine(Color.White.copy(alpha = 0.22f), Offset(l + w * i / 9, t), Offset(l + w * i / 9, t + h), 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.22f), Offset(l, t + h * i / 9), Offset(l + w, t + h * i / 9), 0.5.dp.toPx())
        }
        drawRect(Color.White, Offset(l, t), Size(w, h), style = Stroke(1.5.dp.toPx()))
        // handles: heavy corner brackets and edge bars; the one being held turns blue
        val sw = 4.dp.toPx()
        val cl = minOf(30.dp.toPx(), w / 3f, h / 3f)
        val sl = minOf(40.dp.toPx(), w / 3f, h / 3f)
        fun on(hd: CropHandle) = held == hd
        fun seg(hd: CropHandle, a: Offset, b: Offset) = drawLine(if (on(hd)) Lr.Accent else Color.White, a, b, sw, StrokeCap.Square)
        seg(CropHandle.TL, Offset(l, t), Offset(l + cl, t)); seg(CropHandle.TL, Offset(l, t), Offset(l, t + cl))
        seg(CropHandle.TR, Offset(l + w, t), Offset(l + w - cl, t)); seg(CropHandle.TR, Offset(l + w, t), Offset(l + w, t + cl))
        seg(CropHandle.BL, Offset(l, t + h), Offset(l + cl, t + h)); seg(CropHandle.BL, Offset(l, t + h), Offset(l, t + h - cl))
        seg(CropHandle.BR, Offset(l + w, t + h), Offset(l + w - cl, t + h)); seg(CropHandle.BR, Offset(l + w, t + h), Offset(l + w, t + h - cl))
        seg(CropHandle.T, Offset(l + w / 2 - sl / 2, t), Offset(l + w / 2 + sl / 2, t)); seg(CropHandle.B, Offset(l + w / 2 - sl / 2, t + h), Offset(l + w / 2 + sl / 2, t + h))
        seg(CropHandle.L, Offset(l, t + h / 2 - sl / 2), Offset(l, t + h / 2 + sl / 2)); seg(CropHandle.R, Offset(l + w, t + h / 2 - sl / 2), Offset(l + w, t + h / 2 + sl / 2))
    }
}

/** Centre-crops the current crop to a target aspect (width / height in image pixels; -1 free, 0 original). Kept for callers and tests. */
fun fitAspect(g: Geometry, aspect: Float, imageAspect: Float): Geometry {
    if (aspect < 0f) return g
    val target = if (aspect == 0f) imageAspect else aspect
    return CropMath.fitToAspect(CropRect.of(g), target, imageAspect).applyTo(g)
}
