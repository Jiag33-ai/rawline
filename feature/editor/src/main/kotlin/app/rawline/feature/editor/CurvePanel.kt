package app.rawline.feature.editor

import androidx.compose.foundation.Canvas
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

private val ChannelColors = listOf(Color.White, Color(0xFFE57373), Color(0xFF81C784), Color(0xFF64B5F6))

@Composable
fun CurvePanel(state: EditorState, target: AdjustTarget, hist: IntArray?) = PanelColumn {
    var channel by remember { mutableIntStateOf(0) }
    val curves = target.get(state.recipe).curves
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("RGB", "Red", "Green", "Blue").forEachIndexed { i, n -> ChipButton(n, channel == i, { channel = i }) }
        ChipButton("Reset curve", false, {
            state.edit("Reset curve") { r -> target.set(r, target.get(r).copy(curves = Curves(parametric = target.get(r).curves.parametric))) }
        })
    }
    fun pointsOf(c: Curves) = when (channel) { 0 -> c.master; 1 -> c.red; 2 -> c.green; else -> c.blue }
    fun withPoints(c: Curves, p: List<CurvePoint>) = when (channel) { 0 -> c.copy(master = p); 1 -> c.copy(red = p); 2 -> c.copy(green = p); else -> c.copy(blue = p) }
    fun update(f: (List<CurvePoint>) -> List<CurvePoint>) = state.live { r -> target.set(r, target.get(r).let { a -> a.copy(curves = withPoints(a.curves, f(pointsOf(a.curves)))) }) }

    val shown = pointsOf(curves).ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }
    var dragging by remember { mutableIntStateOf(-1) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(
            Modifier.size(280.dp).background(Color(0xFF1E1E1E))
                .pointerInput(channel) {
                    detectTapGestures(
                        onTap = { p ->
                            val w = size.width.toFloat()
                            val pts = pointsOf(target.get(state.recipe).curves).ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }
                            val near = pts.indexOfFirst { hypot(it.x * w - p.x, (1 - it.y) * w - p.y) < 28f }
                            if (near < 0) {
                                val x = (p.x / w).coerceIn(0f, 1f); val y = (1 - p.y / w).coerceIn(0f, 1f)
                                update { (if (it.isEmpty()) pts else it) + CurvePoint(x, y) }
                                state.commit("Add curve point")
                            }
                        },
                        onDoubleTap = { p ->
                            val w = size.width.toFloat()
                            val pts = pointsOf(target.get(state.recipe).curves)
                            val near = pts.indexOfFirst { hypot(it.x * w - p.x, (1 - it.y) * w - p.y) < 28f }
                            if (near >= 0 && pts.size > 2) { update { l -> l.filterIndexed { i, _ -> i != near } }; state.commit("Remove curve point") }
                        },
                    )
                }
                .pointerInput(channel) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val w = size.width.toFloat()
                            val pts = pointsOf(target.get(state.recipe).curves).ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)).also { l -> update { l } } }
                            dragging = pts.indexOfFirst { hypot(it.x * w - p.x, (1 - it.y) * w - p.y) < 40f }
                        },
                        onDragEnd = { dragging = -1; state.commit("Curve") },
                        onDragCancel = { dragging = -1; state.commit("Curve") },
                    ) { change, _ ->
                        if (dragging >= 0) {
                            val w = size.width.toFloat()
                            val x = (change.position.x / w).coerceIn(0f, 1f); val y = (1 - change.position.y / w).coerceIn(0f, 1f)
                            update { l -> l.mapIndexed { i, pt -> if (i == dragging) CurvePoint(if (pt.x <= 0f || pt.x >= 1f) pt.x else x.coerceIn(0.01f, 0.99f), y) else pt } }
                        }
                    }
                },
        ) {
            val w = size.width
            for (i in 1..3) { drawLine(Color(0x33FFFFFF), Offset(w * i / 4, 0f), Offset(w * i / 4, w)); drawLine(Color(0x33FFFFFF), Offset(0f, w * i / 4), Offset(w, w * i / 4)) }
            if (hist != null) {
                val mx = (hist.maxOrNull() ?: 1).toFloat().coerceAtLeast(1f)
                for (i in 0 until 256) {
                    val v = (0 until 3).maxOf { hist[it * 256 + i] } / mx
                    drawLine(Color(0x22FFFFFF), Offset(i / 255f * w, w), Offset(i / 255f * w, w - v * w * 0.6f))
                }
            }
            drawLine(Color(0x55FFFFFF), Offset(0f, w), Offset(w, 0f))
            val lut = CurveMath.lut(shown)
            val path = Path()
            for (i in 0 until 256) {
                val x = i / 255f * w; val y = w - lut[i] * w
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ChannelColors[channel], style = Stroke(3f))
            if (pointsOf(curves).isNotEmpty()) shown.forEach { drawCircle(ChannelColors[channel], 9f, Offset(it.x * w, w - it.y * w)) }
        }
    }
    if (channel == 0) {
        SectionTitle("Parametric")
        val labels = listOf("Highlights", "Lights", "Darks", "Shadows")
        for (i in 0 until 4) {
            AdjSliderParam(state, target, labels[i], i)
        }
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
