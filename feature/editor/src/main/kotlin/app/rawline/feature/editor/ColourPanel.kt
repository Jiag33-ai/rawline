package app.rawline.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.rawline.core.model.Adjust
import app.rawline.core.model.Grading
import app.rawline.core.model.Hsl
import app.rawline.core.ui.ChipButton
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.SectionTitle
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private val BandNames = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
private val BandColors = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60).map { Color(it) }

fun bandForHue(hue01: Float): Int {
    val centres = floatArrayOf(0f, 0.0833f, 0.1667f, 0.3333f, 0.5f, 0.6667f, 0.7778f, 0.8889f)
    var best = 0; var bd = 9f
    for (i in 0 until 8) { var d = kotlin.math.abs(hue01 - centres[i]); d = min(d, 1f - d); if (d < bd) { bd = d; best = i } }
    return best
}

/** HSL colour mixer: 8 bands, each with hue, saturation and luminance. */
@Composable
fun MixerPanel(state: EditorState, target: AdjustTarget, activeBand: Int, onBand: (Int) -> Unit, mode: Int, onMode: (Int) -> Unit, onTarget: (() -> Unit)?) = PanelColumn {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Hue", "Saturation", "Luminance").forEachIndexed { i, n -> ChipButton(n, mode == i, { onMode(i) }) }
        if (onTarget != null) ChipButton("Target on photo", false, onTarget)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until 8) {
            Box(
                Modifier.size(if (i == activeBand) 40.dp else 32.dp).background(BandColors[i], CircleShape)
                    .pointerInput(i) { detectTapGestures { onBand(i) } },
            )
        }
    }
    val i = activeBand
    val name = BandNames[i]
    val tc = bandGradient(i, mode)
    when (mode) {
        0 -> AdjSlider(state, target, "$name hue", -100f..100f, { it.mixHue[i] }, { a, v -> a.copy(mixHue = a.mixHue.toMutableList().also { l -> l[i] = v }) }, trackColors = tc)
        1 -> AdjSlider(state, target, "$name saturation", -100f..100f, { it.mixSat[i] }, { a, v -> a.copy(mixSat = a.mixSat.toMutableList().also { l -> l[i] = v }) }, trackColors = tc)
        else -> AdjSlider(state, target, "$name luminance", -100f..100f, { it.mixLum[i] }, { a, v -> a.copy(mixLum = a.mixLum.toMutableList().also { l -> l[i] = v }) }, trackColors = tc)
    }
    SectionTitle("All $name values")
    Column {
        for (k in 0 until 8) {
            val label = BandNames[k]
            when (mode) {
                0 -> AdjSlider(state, target, label, -100f..100f, { it.mixHue[k] }, { a, v -> a.copy(mixHue = a.mixHue.toMutableList().also { l -> l[k] = v }) }, trackColors = bandGradient(k, 0))
                1 -> AdjSlider(state, target, label, -100f..100f, { it.mixSat[k] }, { a, v -> a.copy(mixSat = a.mixSat.toMutableList().also { l -> l[k] = v }) }, trackColors = bandGradient(k, 1))
                else -> AdjSlider(state, target, label, -100f..100f, { it.mixLum[k] }, { a, v -> a.copy(mixLum = a.mixLum.toMutableList().also { l -> l[k] = v }) }, trackColors = bandGradient(k, 2))
            }
        }
    }
}

private fun bandGradient(i: Int, mode: Int): List<Color> {
    val c = BandColors[i]
    return when (mode) {
        0 -> listOf(BandColors[(i + 7) % 8], c, BandColors[(i + 1) % 8])
        1 -> listOf(Color(0xFF888888), c)
        else -> listOf(Color.Black, c, Color.White)
    }
}

/** Hue/saturation wheel: angle = hue, distance from centre = saturation. */
@Composable
private fun Wheel(h: Hsl, onChange: (Hsl) -> Unit, onCommit: () -> Unit, size: androidx.compose.ui.unit.Dp = 120.dp) {
    Canvas(
        Modifier.size(size).pointerInput(Unit) {
            val update = { p: Offset ->
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val d = p - c
                val r = hypot(d.x, d.y) / (this.size.width / 2f)
                var ang = atan2(d.y, d.x) / (2 * PI.toFloat())
                if (ang < 0) ang += 1f
                onChange(Hsl(ang * 360f, (r.coerceIn(0f, 1f)) * 100f, h.lum))
            }
            detectDragGestures(onDragStart = { update(it) }, onDragEnd = onCommit, onDragCancel = onCommit) { change, _ -> update(change.position) }
        },
    ) {
        val r = this.size.minDimension / 2f
        drawCircle(Brush.sweepGradient(List(13) { Color.hsv(it * 30f, 1f, 1f) }), r)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = r), r)
        drawCircle(Color(0x55000000), r, style = Stroke(2f))
        val a = h.hue / 360f * 2 * PI.toFloat()
        val d = h.sat / 100f * r
        val p = Offset(this.size.width / 2f + cos(a) * d, this.size.height / 2f + sin(a) * d)
        drawCircle(Color.White, 9f, p, style = Stroke(3f))
        drawCircle(Color.Black, 11f, p, style = Stroke(1.5f))
    }
}

@Composable
fun GradingPanel(state: EditorState, target: AdjustTarget) = PanelColumn {
    val g = target.get(state.recipe).grading
    fun update(f: (Grading) -> Grading) = state.live { r -> target.set(r, target.get(r).copy(grading = f(target.get(r).grading))) }
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        WheelBlock("Shadows", g.shadows, { h -> update { it.copy(shadows = h) } }, { state.commit("Grade shadows") })
        WheelBlock("Midtones", g.mid, { h -> update { it.copy(mid = h) } }, { state.commit("Grade midtones") })
        WheelBlock("Highlights", g.highlights, { h -> update { it.copy(highlights = h) } }, { state.commit("Grade highlights") })
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        WheelBlock("Global", g.global, { h -> update { it.copy(global = h) } }, { state.commit("Grade global") })
    }
    RawSlider("Blending", g.blending, 0f..100f, 50f, onChange = { v -> update { it.copy(blending = v) } }, onCommit = { state.commit("Grade blending") })
    RawSlider("Balance", g.balance, -100f..100f, 0f, onChange = { v -> update { it.copy(balance = v) } }, onCommit = { state.commit("Grade balance") })
}

@Composable
private fun WheelBlock(name: String, h: Hsl, onChange: (Hsl) -> Unit, onCommit: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelMedium)
        Wheel(h, onChange, onCommit, size = 96.dp)
        androidx.compose.material3.Slider(
            h.lum, { onChange(h.copy(lum = it)) }, onValueChangeFinished = onCommit, valueRange = -100f..100f,
            modifier = Modifier.size(width = 110.dp, height = 28.dp),
        )
    }
}
