package app.rawline.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
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

/** Slider with a typed-value dialog (tap the number) and reset (double tap the label). 48 dp tall for one-handed use. */
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
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = "$label $text$unit" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.pointerInput(label) {
                    detectTapGestures(onDoubleTap = { onChange(default); onCommit() })
                }.defaultMinSize(minHeight = 24.dp),
            )
            Text(
                "$text$unit",
                style = MaterialTheme.typography.labelLarge,
                color = if (value != default) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { typing = true }.defaultMinSize(minWidth = 48.dp, minHeight = 24.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            if (trackColors != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(4.dp).background(Brush.horizontalGradient(trackColors), RoundedCornerShape(2.dp)))
            }
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                onValueChangeFinished = onCommit,
                valueRange = range,
                colors = if (trackColors != null) SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent) else SliderDefaults.colors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (typing) {
        var input by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(input, { input = it }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = {
                TextButton(onClick = {
                    input.toFloatOrNull()?.let { onChange(it.coerceIn(range.start, range.endInclusive)); onCommit() }
                    typing = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
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
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.defaultMinSize(minWidth = 48.dp, minHeight = 40.dp).background(bg, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
}
