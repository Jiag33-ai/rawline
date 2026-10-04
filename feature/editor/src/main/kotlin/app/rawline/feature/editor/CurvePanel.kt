package app.rawline.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.rawline.core.model.CurvePoint
import app.rawline.core.model.Curves
import app.rawline.core.render.CurveMath
import app.rawline.core.ui.ChipButton
import app.rawline.core.ui.SectionTitle
import kotlin.math.abs
import kotlin.math.hypot

private val ChannelColors = listOf(Color(0xFFEDEDED), Color(0xFFDF6464), Color(0xFF55A86A), Color(0xFF5A8FE8))

private fun pointsOf(c: Curves, channel: Int) = when (channel) { 0 -> c.master; 1 -> c.red; 2 -> c.green; else -> c.blue }
private fun withPoints(c: Curves, channel: Int, p: List<CurvePoint>) = when (channel) { 0 -> c.copy(master = p); 1 -> c.copy(red = p); 2 -> c.copy(green = p); else -> c.copy(blue = p) }

/** Tone curve drawn straight over the photo: a fine grid, a 1.5 dp light curve and 9 dp points. */
@Composable
fun CurveGraph(state: EditorState, target: AdjustTarget, hist: IntArray?, channel: Int, modifier: Modifier = Modifier) {
    val curves = target.get(state.recipe).curves
    fun update(f: (List<CurvePoint>) -> List<CurvePoint>) = state.live { r -> target.set(r, target.get(r).let { a -> a.copy(curves = withPoints(a.curves, channel, f(pointsOf(a.curves, channel)))) }) }
    val shown = pointsOf(curves, channel).ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }
    var dragging by remember { mutableIntStateOf(-1) }
    Canvas(
        modifier
            .pointerInput(channel) {
                detectTapGestures(
                    onTap = { p ->
                        val w = size.width.toFloat()
                        val pts = pointsOf(target.get(state.recipe).curves, channel).ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }
                        val near = pts.indexOfFirst { hypot(it.x * w - p.x, (1 - it.y) * w - p.y) < 28f }
                        if (near < 0) {
                            val x = (p.x / w).coerceIn(0f, 1f); val y = (1 - p.y / w).coerceIn(0f, 1f)
                            update { (if (it.isEmpty()) pts else it) + CurvePoint(x, y) }
                            state.commit("Add curve point")
                        }
                    },
                    onDoubleTap = { p ->
                        val w = size.width.toFloat()
                        val pts = pointsOf(target.get(state.recipe).curves, channel)
                        val near = pts.indexOfFirst { hypot(it.x * w - p.x, (1 - it.y) * w - p.y) < 28f }
                        if (near >= 0 && pts.size > 2) { update { l -> l.filterIndexed { i, _ -> i != near } }; state.commit("Remove curve point") }
                    },
                )
            }
            .pointerInput(channel) {
                detectDragGestures(
                    onDragStart = { p ->
                        val w = size.width.toFloat()
                        val pts = pointsOf(target.get(state.recipe).curves, channel).ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)).also { l -> update { l } } }
                        dragging = pts.indexOfFirst { hypot(it.x * w - p.x, (1 - it.y) * w - p.y) < 40f }
                    },
                    onDragEnd = { dragging = -1; state.commit("Curve") },
                    onDragCancel = { dragging = -1; state.commit("Curve") },
                ) { change, _ ->
                    if (dragging >= 0) {
                        val w = size.width.toFloat()
                        val x = (change.position.x / w).coerceIn(0f, 1f); val y = (1 - change.position.y / w).coerceIn(0f, 1f)
                        update { l -> l.mapIndexed { i, pt -> if (i == dragging) CurvePoint(if (pt.x <= 0f || pt.x >= 1f) pt.x else x.coerceIn(0.01f, 0.99f), y) else pt } }
                        change.consume()
                    }
                }
            },
    ) {
        val w = size.width
        for (i in 1..3) { drawLine(Color(0x24FFFFFF), Offset(w * i / 4, 0f), Offset(w * i / 4, w), 1f); drawLine(Color(0x24FFFFFF), Offset(0f, w * i / 4), Offset(w, w * i / 4), 1f) }
        drawRect(Color(0x33FFFFFF), Offset.Zero, androidx.compose.ui.geometry.Size(w, w), style = Stroke(1f))
        if (hist != null) {
            val mx = (hist.maxOrNull() ?: 1).toFloat().coerceAtLeast(1f)
            for (i in 0 until 256) {
                val v = (0 until 3).maxOf { hist[it * 256 + i] } / mx
                drawLine(Color(0x1FFFFFFF), Offset(i / 255f * w, w), Offset(i / 255f * w, w - v * w * 0.5f))
            }
        }
        drawLine(Color(0x40FFFFFF), Offset(0f, w), Offset(w, 0f), 1f)
        val lut = CurveMath.lut(shown)
        val path = Path()
        for (i in 0 until 256) {
            val x = i / 255f * w; val y = w - lut[i] * w
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ChannelColors[channel], style = Stroke(1.5.dp.toPx()))
        if (pointsOf(curves, channel).isNotEmpty()) shown.forEachIndexed { i, pt ->
            val c = Offset(pt.x * w, w - pt.y * w)
            drawCircle(if (i == dragging) Color(0xFFF2F2F2) else Color(0xFF1C1C1C), 4.5.dp.toPx(), c)
            drawCircle(Color(0xFFF2F2F2), 4.5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Tray content while the curve is open: "Curve   DONE", the channel selectors, then the parametric sliders. */
@Composable
fun CurvePanel(state: EditorState, target: AdjustTarget, channel: Int, onChannel: (Int) -> Unit, onDone: () -> Unit) = PanelColumn {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Text("Curve", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = app.rawline.core.ui.Lr.TextPrimary, modifier = Modifier.weight(1f))
        app.rawline.core.ui.LrOutlinedButton(onDone, Modifier.height(34.dp)) { androidx.compose.material3.Text("Done") }
    }
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        ChannelColors.forEachIndexed { i, col ->
            androidx.compose.foundation.layout.Box(
                Modifier.size(28.dp).clickable { onChannel(i) }.semantics { contentDescription = listOf("RGB", "Red", "Green", "Blue")[i] + if (channel == i) ", selected" else "" },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.foundation.layout.Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(col))
                if (channel == i) androidx.compose.foundation.layout.Box(Modifier.size(24.dp).border(1.5.dp, Color(0xFFF2F2F2), androidx.compose.foundation.shape.CircleShape))
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        app.rawline.core.ui.LrOutlinedButton({ state.edit("Reset curve") { r -> target.set(r, target.get(r).copy(curves = Curves(parametric = target.get(r).curves.parametric))) } }, Modifier.height(34.dp)) { androidx.compose.material3.Text("Reset") }
    }
    if (channel == 0) {
        SectionTitle("Parametric")
        val labels = listOf("Highlights", "Lights", "Darks", "Shadows")
        for (i in 0 until 4) AdjSliderParam(state, target, labels[i], i)
    }
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
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ChannelColors.forEachIndexed { i, col ->
                androidx.compose.foundation.layout.Box(Modifier.size(28.dp).clickable { channel = i }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.foundation.layout.Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(col))
                    if (channel == i) androidx.compose.foundation.layout.Box(Modifier.size(24.dp).border(1.5.dp, Color(0xFFF2F2F2), androidx.compose.foundation.shape.CircleShape))
                }
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CurveGraph(state, target, hist, channel, Modifier.size(240.dp).background(Color(0xFF111111)))
        }
        if (channel == 0) {
            SectionTitle("Parametric")
            val labels = listOf("Highlights", "Lights", "Darks", "Shadows")
            for (i in 0 until 4) AdjSliderParam(state, target, labels[i], i)
        }
    }
}
