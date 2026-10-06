package app.rawline.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.heightIn
import app.rawline.core.ui.LocalValueFeedback
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.PanelHeader
import app.rawline.core.ui.TouchChip
import app.rawline.core.ui.tapOrDoubleTap
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
    val feedback = LocalValueFeedback.current
    PanelHeader("Mix", Resets.mixerModified(target.get(state.recipe)), { editAdjust(state, target, "Reset colour mix") { Resets.mixer(it) } })
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Hue", "Saturation", "Luminance").forEachIndexed { i, n -> TouchChip(n, mode == i, { onMode(i) }) }
        if (onTarget != null) TouchChip("Target on photo", false, onTarget)
    }
    // eight colour swatches, 48 dp each. Tap selects; double tap resets that colour's hue, saturation and luminance.
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 8) {
            Box(
                Modifier.size(LrDim.touch)
                    .tapOrDoubleTap(onTap = { onBand(i) }, onDoubleTap = { editAdjust(state, target, "Reset ${BandNames[i]}") { Resets.mixerBand(it, i) }; feedback.flash(BandNames[i], "reset") })
                    .semantics { contentDescription = BandNames[i] + if (i == activeBand) ", selected" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(18.dp).background(BandColors[i], CircleShape))
                if (i == activeBand) Box(Modifier.size(30.dp).border(1.5.dp, Color(0xFFF2F2F2), CircleShape))
            }
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
    SectionTitle("All colours: " + listOf("Hue", "Saturation", "Luminance")[mode]) { editAdjust(state, target, "Reset colour mix") { a -> when (mode) { 0 -> a.copy(mixHue = List(8) { 0f }); 1 -> a.copy(mixSat = List(8) { 0f }); else -> a.copy(mixLum = List(8) { 0f }) } } }
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

/**
 * Hue/saturation wheel: angle = hue, distance from centre = saturation. Drag to set; double tap to put hue and saturation back to zero.
 * Callbacks and the current luminance are read through rememberUpdatedState, so switching between the shadows, midtones and highlights
 * wheels can never leave the gesture editing the wheel that was showing before.
 */
@Composable
private fun Wheel(h: Hsl, onChange: (Hsl) -> Unit, onCommit: () -> Unit, onReset: () -> Unit, size: androidx.compose.ui.unit.Dp = 120.dp) {
    val hNow by rememberUpdatedState(h)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val reset by rememberUpdatedState(onReset)
    Canvas(
        Modifier.size(size)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
            .pointerInput(Unit) {
                val update = { p: Offset ->
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val d = p - c
                    val r = hypot(d.x, d.y) / (this.size.width / 2f)
                    var ang = atan2(d.y, d.x) / (2 * PI.toFloat())
                    if (ang < 0) ang += 1f
                    change(Hsl(ang * 360f, (r.coerceIn(0f, 1f)) * 100f, hNow.lum))
                }
                detectDragGestures(onDragStart = { update(it) }, onDragEnd = { commit() }, onDragCancel = { commit() }) { change, _ -> update(change.position) }
            }
            .semantics { contentDescription = "Colour wheel. Drag to set hue and saturation. Double tap to reset." },
    ) {
        val r = this.size.minDimension / 2f
        drawCircle(Brush.sweepGradient(List(13) { Color.hsv(it * 30f, 1f, 1f) }), r)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = r), r)
        drawCircle(Color(0x55000000), r, style = Stroke(2f))
        val a = h.hue / 360f * 2 * PI.toFloat()
        val d = h.sat / 100f * r
        val p = Offset(this.size.width / 2f + cos(a) * d, this.size.height / 2f + sin(a) * d)
        drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3f))
        drawCircle(Color.Black, 11.dp.toPx(), p, style = Stroke(1.5f))
    }
}

@Composable
fun GradingPanel(state: EditorState, target: AdjustTarget) {
    val g = target.get(state.recipe).grading
    var which by remember { mutableStateOf("mid") }
    fun update(f: (Grading) -> Grading) = state.live { r -> target.set(r, target.get(r).copy(grading = f(target.get(r).grading))) }
    val names = mapOf("shadows" to "Shadows", "mid" to "Midtones", "highlights" to "Highlights", "global" to "Global")
    fun resetWheel(id: String) = state.edit("Reset grade ${names[id]}") { r -> target.set(r, target.get(r).let { a -> a.copy(grading = Resets.gradingWheel(a.grading, id)) }) }
    PanelColumn {
        PanelHeader("Grading", Resets.gradingModified(target.get(state.recipe)), { editAdjust(state, target, "Reset grading") { Resets.grading(it) } })
        app.rawline.core.ui.LrTabs(listOf("shadows" to "Shadows", "mid" to "Midtones", "highlights" to "Highlights", "global" to "Global"), which, { which = it },
            onDoubleTap = { id -> which = id; resetWheel(id) })
        val (h, setH, label) = when (which) {
            "shadows" -> Triple(g.shadows, { x: Hsl -> update { it.copy(shadows = x) } }, "Grade shadows")
            "highlights" -> Triple(g.highlights, { x: Hsl -> update { it.copy(highlights = x) } }, "Grade highlights")
            "global" -> Triple(g.global, { x: Hsl -> update { it.copy(global = x) } }, "Grade global")
            else -> Triple(g.mid, { x: Hsl -> update { it.copy(mid = x) } }, "Grade midtones")
        }
        val feedback = LocalValueFeedback.current
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Wheel(h, setH, { state.commit(label) }, { resetWheel(which); feedback.flash(names[which] ?: "Wheel", "reset") }, size = 152.dp)
        }
        RawSlider("Luminance", h.lum, -100f..100f, 0f, onChange = { v -> setH(h.copy(lum = v)) }, onCommit = { state.commit(label) })
        RawSlider("Blending", g.blending, 0f..100f, 50f, onChange = { v -> update { it.copy(blending = v) } }, onCommit = { state.commit("Grade blending") })
        RawSlider("Balance", g.balance, -100f..100f, 0f, onChange = { v -> update { it.copy(balance = v) } }, onCommit = { state.commit("Grade balance") })
    }
}
