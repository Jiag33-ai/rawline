package app.rawline.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rawline.core.model.CurvePoint
import app.rawline.core.render.CurveMath
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LocalValueFeedback
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.LrOutlinedButton
import app.rawline.core.ui.PanelHeader
import app.rawline.core.ui.SectionTitle
import app.rawline.core.ui.TouchChip

private val ChannelColors = listOf(Color(0xFFEDEDED), Color(0xFFDF6464), Color(0xFF55A86A), Color(0xFF5A8FE8))
private val ChannelNames = listOf("RGB", "Red", "Green", "Blue")
private val ChannelChipText = listOf("RGB", "R", "G", "B")

/** What the readout shows while a point is held. */
private class CurveDrag(val index: Int, val pendingDelete: Boolean, val point: CurvePoint)

/**
 * Tone curve drawn straight over the photo. Touch it anywhere to add a point and drag on; points have 48 dp touch targets (24 dp radius)
 * and are drawn 14 dp wide. While a point is held a readout shows its input and output (0 to 255) and guide lines run to the axes.
 * Drag a point well off the graph (it turns red and says so) and let go to delete it; double tap an interior point to delete it, or an
 * end point to put it back in its corner. End points move up and down only.
 *
 * [inset] is empty margin inside the box, so a point on the very edge of the curve still has its whole touch target; the graph itself is
 * drawn inside that margin. The caller grows the box by the same amount to keep the graph over the photo.
 */
@Composable
fun CurveGraph(state: EditorState, target: AdjustTarget, hist: IntArray?, channel: Int, modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    val targetNow by rememberUpdatedState(target)
    val channelNow by rememberUpdatedState(channel)
    val insetNow by rememberUpdatedState(inset)
    val feedback = LocalValueFeedback.current
    val curves = target.get(state.recipe).curves
    val stored = CurveEdit.points(curves, channel)
    val shown = CurveEdit.shown(stored)
    var drag by remember { mutableStateOf<CurveDrag?>(null) }
    val measurer = rememberTextMeasurer()
    val col = ChannelColors[channel]

    fun currentPoints() = CurveEdit.shown(CurveEdit.points(targetNow.get(state.recipe).curves, channelNow))
    fun setPoints(p: List<CurvePoint>) = state.live { r ->
        targetNow.set(r, targetNow.get(r).let { a -> a.copy(curves = CurveEdit.withPoints(a.curves, channelNow, CurveEdit.normalise(p))) })
    }

    Canvas(
        modifier.pointerInput(Unit) {
            var lastTapUp = 0L
            var lastTapPos = Offset.Zero
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val ins = insetNow.toPx()
                val pw = size.width - 2 * ins; val ph = size.height - 2 * ins
                if (pw <= 0f || ph <= 0f) return@awaitEachGesture
                val local = Offset(down.position.x - ins, down.position.y - ins)
                var idx = CurveEdit.hit(currentPoints(), local.x, local.y, pw, ph, 24.dp.toPx())
                val isDouble = idx >= 0 && down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis && (down.position - lastTapPos).getDistance() <= 48.dp.toPx()
                var created = false
                if (idx < 0) {
                    // touch anywhere to add a point there; the finger carries on dragging it
                    val ins2 = CurveEdit.insert(currentPoints(), local.x / pw, 1f - local.y / ph)
                    if (ins2 == null) { down.consume(); return@awaitEachGesture }
                    setPoints(ins2.first); idx = ins2.second; created = true
                }
                val p0 = currentPoints()[idx]
                val grab = Offset(p0.x * pw - local.x, (1f - p0.y) * ph - local.y)   // keeps the point from hopping under the finger
                down.consume()
                drag = CurveDrag(idx, false, p0)
                var moved = false
                var pendingDelete = false
                var upTime = down.uptimeMillis
                val slop = viewConfiguration.touchSlop
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        upTime = c.uptimeMillis
                        if (!c.pressed) { c.consume(); break }
                        if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                        if (moved) {
                            val pos = Offset(c.position.x - ins, c.position.y - ins)
                            val pts = currentPoints()
                            pendingDelete = !CurveEdit.isEnd(pts[idx]) && CurveEdit.offGraph(pos.x, pos.y, pw, ph, 28.dp.toPx())
                            if (!pendingDelete) {
                                val t = pos + grab
                                setPoints(CurveEdit.move(pts, idx, t.x / pw, 1f - t.y / ph))
                            }
                            drag = CurveDrag(idx, pendingDelete, currentPoints()[idx])
                        }
                        c.consume()
                    }
                } finally {
                    drag = null
                    if (moved || created) {
                        if (pendingDelete) { setPoints(CurveEdit.remove(currentPoints(), idx)); state.commit("Remove curve point") }
                        else state.commit(if (created && !moved) "Add curve point" else "Curve")
                    }
                }
                if (!moved) {
                    if (isDouble) {
                        val end = CurveEdit.isEnd(currentPoints().getOrElse(idx) { CurvePoint(0.5f, 0.5f) })
                        setPoints(CurveEdit.remove(currentPoints(), idx))
                        state.commit(if (end) "Reset curve end point" else "Remove curve point")
                        feedback.flash("Curve", if (end) "end point reset" else "point removed")
                        lastTapUp = 0L
                    } else { lastTapUp = upTime; lastTapPos = down.position }
                } else lastTapUp = 0L
            }
        }.semantics { contentDescription = "Tone curve, ${ChannelNames[channel]}. Touch to add a point, drag to move it, double tap a point to remove it." },
    ) {
        val ins = inset.toPx()
        val w = size.width - 2 * ins; val h = size.height - 2 * ins
        translate(ins, ins) {
            for (i in 1..3) { drawLine(Color(0x24FFFFFF), Offset(w * i / 4, 0f), Offset(w * i / 4, h), 1f); drawLine(Color(0x24FFFFFF), Offset(0f, h * i / 4), Offset(w, h * i / 4), 1f) }
            drawRect(Color(0x33FFFFFF), Offset.Zero, Size(w, h), style = Stroke(1f))
            if (hist != null) {
                val mx = (hist.maxOrNull() ?: 1).toFloat().coerceAtLeast(1f)
                for (i in 0 until 256) {
                    val v = (0 until 3).maxOf { hist[it * 256 + i] } / mx
                    drawLine(Color(0x1FFFFFFF), Offset(i / 255f * w, h), Offset(i / 255f * w, h - v * h * 0.5f))
                }
            }
            drawLine(Color(0x40FFFFFF), Offset(0f, h), Offset(w, 0f), 1f)
            val lut = CurveMath.lut(shown)
            val path = Path()
            for (i in 0 until 256) {
                val x = i / 255f * w; val y = h - lut[i] * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, col, style = Stroke(2.dp.toPx()))
            val d = drag
            // points: 14 dp wide, a dark centre inside a light ring, so they read on any photo; the held one is bigger and solid
            if (stored.isNotEmpty() || d != null) shown.forEachIndexed { i, pt ->
                val c = Offset(pt.x * w, h - pt.y * h)
                val held = d != null && d.index == i
                val rad = if (held) 10.dp.toPx() else 7.dp.toPx()
                if (held) drawCircle(Color(0x26FFFFFF), 22.dp.toPx(), c)
                val ring = if (held && d!!.pendingDelete) Lr.Error else Color(0xFFF2F2F2)
                drawCircle(if (held) ring else Color(0xE61C1C1C), rad, c)
                drawCircle(ring, rad, c, style = Stroke(2.dp.toPx()))
            }
            if (d != null && d.index in shown.indices) {
                val pt = shown[d.index]
                val c = Offset(pt.x * w, h - pt.y * h)
                drawLine(Color(0x66FFFFFF), Offset(c.x, c.y), Offset(c.x, h), 1.dp.toPx())
                drawLine(Color(0x66FFFFFF), Offset(0f, c.y), Offset(c.x, c.y), 1.dp.toPx())
                val (xi, yi) = CurveEdit.readout(pt)
                val text = if (d.pendingDelete) "Release to delete" else "In $xi   Out $yi"
                val layout = measurer.measure(text, TextStyle(fontSize = 12.sp, color = Lr.TextPrimary))
                val bw = layout.size.width + 20.dp.toPx(); val bh = layout.size.height + 10.dp.toPx()
                // above the finger so the thumb does not hide it; below if the point is near the top
                val above = c.y - 48.dp.toPx() - bh >= -ins + 4.dp.toPx()
                val bx = (c.x - bw / 2f).coerceIn(-ins + 4.dp.toPx(), (w + ins - bw - 4.dp.toPx()).coerceAtLeast(-ins + 4.dp.toPx()))
                val by = if (above) c.y - 48.dp.toPx() - bh else c.y + 40.dp.toPx()
                drawRoundRect(Lr.ValuePill, Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2f))
                drawText(layout, topLeft = Offset(bx + 10.dp.toPx(), by + 5.dp.toPx()))
            }
        }
    }
}

/** Channel chips with their colours. Tap selects, double tap resets that channel's points. */
@Composable
private fun ChannelRow(state: EditorState, target: AdjustTarget, channel: Int, onChannel: (Int) -> Unit) {
    val feedback = LocalValueFeedback.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 4) {
            TouchChip(
                ChannelChipText[i], channel == i, { onChannel(i) }, accent = ChannelColors[i],
                onDoubleTap = { state.edit("Reset ${ChannelNames[i]} curve") { r -> target.set(r, target.get(r).copy(curves = Resets.curveChannel(target.get(r).curves, i))) }; feedback.flash("${ChannelNames[i]} curve", "reset") },
                leading = { Box(Modifier.size(10.dp).background(ChannelColors[i], CircleShape)) },
            )
        }
    }
}

@Composable
private fun ParametricSliders(state: EditorState, target: AdjustTarget) {
    SectionTitle("Parametric") { state.edit("Reset parametric curve") { r -> target.set(r, target.get(r).let { a -> a.copy(curves = a.curves.copy(parametric = listOf(0f, 0f, 0f, 0f))) }) } }
    val labels = listOf("Highlights", "Lights", "Darks", "Shadows")
    for (i in 0 until 4) AdjSliderParam(state, target, labels[i], i)
}

/** Tray content while the curve is open: the "Curve" header (Reset and Done), the channel chips, a hint, then the parametric sliders. */
@Composable
fun CurvePanel(state: EditorState, target: AdjustTarget, channel: Int, onChannel: (Int) -> Unit, onDone: () -> Unit) = PanelColumn {
    PanelHeader(
        "Curve", Resets.curvesModified(target.get(state.recipe)),
        { state.edit("Reset curve") { r -> target.set(r, Resets.curves(target.get(r))) } },
        trailingEnd = { LrOutlinedButton(onDone, Modifier.height(40.dp).minimumInteractiveComponentSize()) { Text("Done") }; Spacer(Modifier.width(4.dp)) },
    )
    ChannelRow(state, target, channel, onChannel)
    Text(
        "Touch the picture to add a point and drag it. Drag a point off the edge or double tap it to remove it. Double tap a channel to reset it.",
        style = MaterialTheme.typography.labelSmall, color = Lr.TextMuted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
    )
    if (channel == 0) ParametricSliders(state, target)
}

@Composable
private fun AdjSliderParam(state: EditorState, target: AdjustTarget, label: String, i: Int) {
    app.rawline.core.ui.RawSlider(
        label, target.get(state.recipe).curves.parametric[i], -100f..100f, 0f,
        onChange = { v -> state.live { r -> target.set(r, target.get(r).let { a -> a.copy(curves = a.curves.copy(parametric = a.curves.parametric.toMutableList().also { it[i] = v })) }) } },
        onCommit = { state.commit("Parametric $label") },
    )
}

/** In-panel curve for mask adjustments (there the photo is not the graph's backdrop). */
@Composable
fun CurvePanel(state: EditorState, target: AdjustTarget, hist: IntArray?) {
    var channel by remember { mutableIntStateOf(0) }
    PanelColumn {
        PanelHeader("Curve", Resets.curvesModified(target.get(state.recipe)), { state.edit("Reset curve") { r -> target.set(r, Resets.curves(target.get(r))) } })
        ChannelRow(state, target, channel) { channel = it }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            CurveGraph(state, target, hist, channel, Modifier.size(280.dp).background(Color(0xFF111111)), inset = 20.dp)
        }
        if (channel == 0) ParametricSliders(state, target)
    }
}
