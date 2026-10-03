package app.rawline.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Lightroom style slider: label left, value right, a thin track with a round thumb. Drag the track to change the value,
 * double tap to reset, tap the number to type a value. 48 dp+ tall for one-handed use.
 */
@Composable
fun RawSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float = 0f,
    decimals: Int = 0,
    unit: String = "",
    trackColors: List<Color>? = null,
    format: ((Float) -> String)? = null,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typing by remember { mutableStateOf(false) }
    val text = format?.invoke(value) ?: if (decimals == 0) value.toInt().toString() else String.format(Locale.US, "%.${decimals}f", value)
    val changed = kotlin.math.abs(value - default) > 1e-4f
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { contentDescription = "$label $text$unit" }) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (changed) Lr.Text else Lr.TextDim,
                modifier = Modifier.pointerInput(label) { detectTapGestures(onDoubleTap = { onChange(default); onCommit() }) }.defaultMinSize(minHeight = 24.dp))
            Text("$text$unit", style = MaterialTheme.typography.bodyMedium, color = if (changed) Lr.Accent else Lr.TextDim,
                modifier = Modifier.clickable { typing = true }.defaultMinSize(minWidth = 56.dp, minHeight = 24.dp).wrapContentWidth(Alignment.End))
        }
        LrTrack(value, range, default, trackColors, onChange, onCommit, Modifier.fillMaxWidth().height(32.dp))
    }
    if (typing) {
        var input by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(label) },
            text = { OutlinedTextField(input, { input = it }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
            confirmButton = { TextButton(onClick = { input.toFloatOrNull()?.let { onChange(it.coerceIn(range.start, range.endInclusive)); onCommit() }; typing = false }) { Text("Set") } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LrTrack(
    value: Float, range: ClosedFloatingPointRange<Float>, default: Float, colors: List<Color>?,
    onChange: (Float) -> Unit, onCommit: () -> Unit, modifier: Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val currentChange by androidx.compose.runtime.rememberUpdatedState(onChange)
    val currentCommit by androidx.compose.runtime.rememberUpdatedState(onCommit)
    Canvas(
        modifier
            .pointerInput(range) { detectTapGestures(onDoubleTap = { currentChange(default); currentCommit() }) }
            .pointerInput(range) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    val pad = 12.dp.toPx()
                    fun at(x: Float) = range.start + ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * (range.endInclusive - range.start)
                    currentChange(at(down.position.x))
                    down.consume()
                    do {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (c.pressed) { currentChange(at(c.position.x)); c.consume() }
                    } while (ev.changes.any { it.pressed })
                    dragging = false
                    currentCommit()
                }
            },
    ) {
        val pad = 12.dp.toPx(); val cy = size.height / 2f
        val span = range.endInclusive - range.start
        fun px(v: Float) = pad + ((v - range.start) / span).coerceIn(0f, 1f) * (size.width - 2 * pad)
        if (colors != null) {
            drawRoundRect(Brush.horizontalGradient(colors, startX = pad, endX = size.width - pad), Offset(pad, cy - 2.dp.toPx()), Size(size.width - 2 * pad, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        } else {
            drawLine(Lr.TrackOff, Offset(pad, cy), Offset(size.width - pad, cy), 2.dp.toPx(), StrokeCap.Round)
            // the part between the default and the thumb is drawn bright, as in Lightroom
            drawLine(Lr.Text, Offset(px(default), cy), Offset(px(value), cy), 2.5.dp.toPx(), StrokeCap.Round)
        }
        if (default > range.start && default < range.endInclusive) drawLine(Lr.TextDim, Offset(px(default), cy - 6.dp.toPx()), Offset(px(default), cy - 3.dp.toPx()), 1.5.dp.toPx())
        // hollow ring thumb as in Lightroom mobile
        val r = (if (dragging) 13 else 11).dp.toPx()
        drawCircle(Lr.Panel, r, Offset(px(value), cy))
        drawCircle(Color.White, r - 1.dp.toPx(), Offset(px(value), cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

/** Lightroom style section tabs: large text, the selected one white with an underline. */
@Composable
fun LrTabs(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEach { (id, label) ->
            val on = id == selected
            Column(
                Modifier.height(48.dp).clickable { onSelect(id) }.semantics { contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(label, color = if (on) Lr.Text else Lr.TextDim, style = MaterialTheme.typography.titleMedium)
                Box(Modifier.padding(top = 4.dp).height(2.dp).width(IntrinsicSize.Max).fillMaxWidth().background(if (on) Lr.Text else Color.Transparent))
            }
        }
    }
}

/** Outlined rounded button used for Curve, B and W, Grading and Mix. */
@Composable
fun LrOutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: LrIcon? = null, active: Boolean = false) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).background(Lr.Black).border(1.dp, if (active) Lr.Accent else Lr.TrackOff, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { LrIconView(icon, Lr.Text, size = 22.dp); Spacer(Modifier.width(10.dp)) }
        Text(label, color = if (active) Lr.Accent else Lr.Text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Live histogram with clipping markers. hist = 3 channels x 256 bins (R, G, B). */
@Composable
fun Histogram(hist: IntArray?, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0x66000000), RoundedCornerShape(4.dp)).semantics { contentDescription = "Histogram" }) {
        if (hist == null) return@Canvas
        val max = (hist.maxOrNull() ?: 1).coerceAtLeast(1).toFloat() * 0.85f
        val cols = listOf(Color(0xAAE57373), Color(0xAA81C784), Color(0xAA64B5F6))
        for (c in 0 until 3) {
            val path = Path()
            path.moveTo(0f, size.height)
            for (i in 0 until 256) {
                val x = i / 255f * size.width
                val y = size.height - (hist[c * 256 + i] / max).coerceAtMost(1f) * size.height
                path.lineTo(x, y)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, cols[c], style = Stroke(1.2f))
        }
        val shadowClip = (0 until 3).sumOf { hist[it * 256] } > 0
        val highClip = (0 until 3).sumOf { hist[it * 256 + 255] } > 0
        if (shadowClip) drawRect(Color(0xFF64B5F6), Offset(0f, 0f), androidx.compose.ui.geometry.Size(6f, 6f))
        if (highClip) drawRect(Color(0xFFE57373), Offset(size.width - 6f, 0f), androidx.compose.ui.geometry.Size(6f, 6f))
    }
}

@Composable
fun ChipButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (selected) Lr.Accent else Lr.Surface
    val fg = if (selected) Color(0xFF00121F) else Lr.Text
    Box(
        modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).background(bg, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
}
