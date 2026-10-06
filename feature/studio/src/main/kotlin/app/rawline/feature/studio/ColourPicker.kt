package app.rawline.feature.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.rawline.core.studio.model.Hsv
import app.rawline.core.studio.render.Rgb
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrTextButton
import app.rawline.core.ui.PrimaryButton
import app.rawline.core.ui.SecondaryButton

private fun Rgb.toColor() = Color(r, g, b)

/**
 * The minimal colour picker (S1b): hue bar, saturation and value square, hex field, the 8 recent colours. [onChange] runs on every drag step (the stroke colour follows at
 * once), [onCommit] when the finger lifts or a hex value is entered (the colour joins the recent ones).
 */
@Composable
fun ColourPickerDialog(title: String, initial: Rgb, recent: List<Rgb>, onChange: (Rgb) -> Unit, onCommit: (Rgb) -> Unit, onSwap: () -> Unit, onDismiss: () -> Unit) {
    val start = remember { Hsv.fromRgb(initial.r, initial.g, initial.b) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var hex by remember { mutableStateOf(ColourHex.format(initial)) }
    var hexBad by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }   // the hex field is being typed in: the square and bar must not rewrite it under the user's fingers
    val rgb = Hsv.toRgb(hue, sat, value).let { Rgb(it[0], it[1], it[2]) }
    val currentChange by rememberUpdatedState(onChange)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentRgb by rememberUpdatedState(rgb)
    // the hex field follows the square and the bar, unless it is the thing being typed in
    LaunchedEffect(rgb) { if (!typing) { hex = ColourHex.format(rgb); hexBad = false } }

    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Lr.Modal).border(1.dp, Lr.BorderDefault, RoundedCornerShape(6.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Lr.TextPrimary)
            // saturation (left to right) and value (top to bottom) at the current hue
            Canvas(
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(4.dp)).semantics { contentDescription = "Saturation and brightness" }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun at(o: Offset) { typing = false; sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); currentChange(currentRgb) }
                        at(down.position)
                        drag(down.id) { at(it.position); it.consume() }
                        currentCommit(currentRgb)
                    }
                },
            ) {
                val pure = Hsv.toRgb(hue, 1f, 1f).let { Color(it[0], it[1], it[2]) }
                drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val c = Offset(sat * size.width, (1f - value) * size.height)
                drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(2.dp.toPx())); drawCircle(Color.Black, 10.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            }
            Canvas(
                Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(4.dp)).semantics { contentDescription = "Hue" }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun at(o: Offset) { typing = false; hue = (o.x / size.width).coerceIn(0f, 0.9999f); currentChange(currentRgb) }
                        at(down.position)
                        drag(down.id) { at(it.position); it.consume() }
                        currentCommit(currentRgb)
                    }
                },
            ) {
                val stops = (0..6).map { k -> Hsv.toRgb(k / 6f, 1f, 1f).let { Color(it[0], it[1], it[2]) } }
                drawRect(Brush.horizontalGradient(stops))
                val x = hue * size.width
                drawRect(Color.White, Offset(x - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), style = Stroke(1.5f.dp.toPx()))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(rgb.toColor()).border(1.dp, Lr.BorderStrong, RoundedCornerShape(4.dp)))
                // spec 7.22: 40 dp, #242424, 1 px #454545, 4 dp radius, 12 dp padding, 14 sp
                BasicTextField(
                    hex, {
                        hex = it; typing = true
                        // only a full six digit value counts while typing: "#123" on the way to "#123456" must not turn into a short colour at once
                        val digits = it.trim().removePrefix("#")
                        val c = if (digits.length == 6) ColourHex.parse(it) else null
                        hexBad = c == null && (digits.length > 6 || digits.any { ch -> !(ch.isDigit() || ch.lowercaseChar() in 'a'..'f') })
                        if (c != null) { val h = Hsv.fromRgb(c.r, c.g, c.b); hue = h[0].coerceIn(0f, 0.9999f); sat = h[1]; value = h[2]; currentChange(c); currentCommit(c) }
                    },
                    singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Lr.TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(Lr.Focus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(4.dp)).background(Lr.Input).border(1.dp, if (hexBad) Lr.Error else Lr.InputBorder, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp).semantics { contentDescription = "Hex colour" },
                    decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() } },
                )
                SecondaryButton("Swap", onSwap)
            }
            if (recent.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.labelSmall, color = Lr.TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in recent) Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)).background(c.toColor()).border(1.dp, Lr.BorderStrong, RoundedCornerShape(4.dp))
                            .clickable {
                                val h = Hsv.fromRgb(c.r, c.g, c.b); hue = h[0].coerceIn(0f, 0.9999f); sat = h[1]; value = h[2]
                                typing = false; hex = ColourHex.format(c); onChange(c); onCommit(c)
                            }.semantics { contentDescription = "Recent colour ${ColourHex.format(c)}" },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton("Done", onDismiss)
            }
        }
    }
}
