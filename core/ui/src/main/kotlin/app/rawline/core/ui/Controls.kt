package app.rawline.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

// ---------------- value feedback ----------------

/** The small pill at the top centre of the canvas that shows "Shadows: +80" while a slider is held. */
@Stable
class ValueFeedback {
    var text by mutableStateOf<String?>(null)
    var held by mutableStateOf(false)
    fun show(label: String, value: String) { text = "$label: $value"; held = true }
    fun release() { held = false }
}

val LocalValueFeedback = compositionLocalOf { ValueFeedback() }

@Composable
fun ValueFeedbackPill(feedback: ValueFeedback, modifier: Modifier = Modifier) {
    // hold about 350 ms after release, then fade (160 ms)
    LaunchedEffect(feedback.held, feedback.text) { if (!feedback.held && feedback.text != null) { delay(350); feedback.text = null } }
    AnimatedVisibility(
        feedback.text != null && (feedback.held || true), modifier,
        enter = fadeIn(tween(LrMotion.instant, easing = LrMotion.enter)) + scaleIn(tween(LrMotion.instant, easing = LrMotion.enter), initialScale = 0.97f),
        exit = fadeOut(tween(160)),
    ) {
        val shown = remember(feedback.text) { feedback.text ?: "" }
        Box(Modifier.height(24.dp).background(Lr.ValuePill, CircleShape).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(shown, color = Lr.TextPrimary, style = ValueStyle.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
    }
}

// ---------------- slider ----------------

/**
 * AdjustmentSlider: label left, value right (tabular), a 1 dp track with an 18 dp ring thumb. The block is 46 dp tall and the whole
 * block is the touch area. Only a sideways drag moves it (a vertical drag scrolls the panel). Double tap resets, tap the number to type.
 * No easing on the data while dragging.
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
    val feedback = LocalValueFeedback.current
    val text = format?.invoke(value) ?: if (decimals == 0) value.toInt().toString() else String.format(Locale.US, "%.${decimals}f", value)
    val shownValue = if (value > 0f && range.start < 0f && format == null) "+$text$unit" else "$text$unit"
    val currentChange by rememberUpdatedState(onChange)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentText by rememberUpdatedState(shownValue)
    Box(
        modifier.fillMaxWidth().height(LrDim.sliderBlock).semantics { contentDescription = "$label $text$unit" }
            .pointerInput(range) { detectTapGestures(onDoubleTap = { currentChange(default); currentCommit() }) }
            .pointerInput(range) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pad = 9.dp.toPx() + 14.dp.toPx()
                    fun at(x: Float) = range.start + ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * (range.endInclusive - range.start)
                    // only a sideways drag moves the slider; a vertical drag is left alone so the panel can scroll
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (slop != null) {
                        currentChange(at(slop.position.x))
                        feedback.show(label, currentText)
                        horizontalDrag(slop.id) { c -> currentChange(at(c.position.x)); feedback.show(label, currentText); c.consume() }
                        feedback.release()
                        currentCommit()
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp), color = Lr.TextPrimary)
            Text(shownValue, style = ValueStyle, color = Lr.TextSecondary, modifier = Modifier.clickable { typing = true }.defaultMinSize(minWidth = 44.dp, minHeight = 20.dp).wrapContentWidth(Alignment.End))
        }
        Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(26.dp)) {
            val thumbR = 9.dp.toPx(); val pad = 14.dp.toPx() + thumbR; val cy = size.height / 2f + 2.dp.toPx()
            val span = range.endInclusive - range.start
            fun px(v: Float) = pad + ((v - range.start) / span).coerceIn(0f, 1f) * (size.width - 2 * pad)
            if (trackColors != null) {
                drawRoundRect(Brush.horizontalGradient(trackColors, startX = pad, endX = size.width - pad), Offset(pad, cy - 1.5.dp.toPx()), Size(size.width - 2 * pad, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
            } else {
                drawLine(Lr.SliderTrack, Offset(pad, cy), Offset(size.width - pad, cy), 1.dp.toPx())
                drawLine(Lr.SliderTrackStrong, Offset(px(default), cy), Offset(px(value), cy), 1.5.dp.toPx())
            }
            if (default > range.start && default < range.endInclusive) drawLine(Lr.SliderTrack, Offset(px(default), cy - 7.dp.toPx()), Offset(px(default), cy - 4.dp.toPx()), 1.dp.toPx())
            drawCircle(Lr.Surface1, thumbR, Offset(px(value), cy))
            drawCircle(Lr.SliderThumb, thumbR - 1.dp.toPx(), Offset(px(value), cy), style = Stroke(2.dp.toPx()))
        }
    }
    if (typing) {
        var input by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(label) },
            text = { OutlinedTextField(input, { input = it }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(4.dp)) },
            confirmButton = { TextButton(onClick = { input.toFloatOrNull()?.let { onChange(it.coerceIn(range.start, range.endInclusive)); onCommit() }; typing = false }) { Text("Set") } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- tabs, buttons, toggles ----------------

/** PanelTabs: 44 dp, light underline on the active one, no filled pills. */
@Composable
fun LrTabs(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(LrDim.tabBar).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEach { (id, label) ->
            val on = id == selected
            val colour by animateColorAsState(if (on) Lr.TextPrimary else Lr.TextSecondary.copy(alpha = 0.85f), tween(140), label = "tab")
            val line by animateColorAsState(if (on) Lr.TextPrimary else Color.Transparent, tween(140), label = "tabline")
            Column(
                Modifier.height(LrDim.tabBar).clickable { onSelect(id) }.padding(horizontal = 4.dp).semantics { contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(label, color = colour, style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                Box(Modifier.padding(top = 3.dp).height(2.dp).width(IntrinsicSize.Max).fillMaxWidth().background(line))
            }
        }
    }
}

/** FunctionButton: Curve, B & W, Grading, Mix. Technical, outlined, 4 dp. */
@Composable
fun LrOutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: LrIcon? = null, active: Boolean = false, small: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier.height(if (small) LrDim.smallButton else LrDim.button).clip(RoundedCornerShape(4.dp))
            .background(if (pressed) Color(0xFF2E2E2E) else Lr.Button)
            .border(1.dp, if (active) Lr.Accent else Lr.FunctionBorder, RoundedCornerShape(4.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { LrIconView(icon, if (active) Lr.Accent else Lr.IconPrimary, size = 18.dp); Spacer(Modifier.width(7.dp)) }
        Text(label, color = if (active) Lr.Accent else Lr.TextPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

/** Option tile / chip: neutral selection (#303030), never blue. */
@Composable
fun ChipButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) Lr.SurfaceSelected else Color.Transparent, tween(LrMotion.fast), label = "chip")
    Box(
        modifier.defaultMinSize(minWidth = 44.dp, minHeight = LrDim.smallButton).clip(RoundedCornerShape(4.dp)).background(bg)
            .border(1.dp, if (selected) Lr.BorderDefault else Lr.BorderSubtle, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Lr.TextPrimary, style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.height(LrDim.button).clip(RoundedCornerShape(4.dp)).background(if (enabled) Lr.Accent else Lr.SurfaceSelected).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else Lr.TextDisabled, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.height(LrDim.button).clip(RoundedCornerShape(4.dp)).border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color(0xFFE9E9E9) else Lr.TextDisabled, style = MaterialTheme.typography.labelLarge) }
}

/** 32 x 18 toggle, 14 dp thumb, 140 ms. */
@Composable
fun LrToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val track by animateColorAsState(if (checked) Lr.Accent else Lr.ToggleOff, tween(140), label = "toggle")
    val x by animateDpAsState(if (checked) 16.dp else 2.dp, tween(140, easing = LrMotion.standard), label = "thumb")
    Box(
        modifier.size(width = 44.dp, height = 44.dp).clickable(enabled = enabled) { onChange(!checked) }.semantics { contentDescription = if (checked) "On" else "Off" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 32.dp, height = 18.dp).clip(CircleShape).background(if (enabled) track else track.copy(alpha = 0.38f))) {
            Box(Modifier.offset(x = x, y = 2.dp).size(14.dp).clip(CircleShape).background(Lr.SliderThumb))
        }
    }
}

/** Label left, toggle right, 44 dp. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Lr.TextPrimary else Lr.TextDisabled, modifier = Modifier.weight(1f))
        LrToggle(checked, onChange, enabled = enabled)
    }
}

/** 44 dp touch target, 22 dp icon, no permanent backing. */
@Composable
fun LrIconButton(icon: LrIcon, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Lr.IconPrimary, size: Dp = 22.dp) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) Color(0x17FFFFFF) else Color.Transparent, tween(if (pressed) LrMotion.instant else LrMotion.fast), label = "press")
    Box(
        modifier.size(LrDim.hit).clip(RoundedCornerShape(6.dp)).background(bg).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { LrIconView(icon, if (enabled) tint else Lr.TextDisabled, size = size) }
}

// ---------------- drop-in replacements for the Material buttons (same call shape, locked look) ----------------

@Composable
fun LrButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (enabled) Color.White else Lr.TextDisabled) {
        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier.height(LrDim.button).clip(RoundedCornerShape(4.dp)).background(if (enabled) Lr.Accent else Lr.SurfaceSelected)
                    .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content,
            )
        }
    }
}

@Composable
fun LrOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (enabled) Color(0xFFE9E9E9) else Lr.TextDisabled) {
        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier.height(LrDim.button).clip(RoundedCornerShape(4.dp)).border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content,
            )
        }
    }
}

@Composable
fun LrTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (enabled) Lr.TextPrimary else Lr.TextDisabled) {
        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier.height(LrDim.button).defaultMinSize(minWidth = 44.dp).clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content,
            )
        }
    }
}

/** Same call shape as Material's Switch. */
@Composable
fun LrSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) =
    LrToggle(checked, { onCheckedChange?.invoke(it) }, modifier, enabled)

/** 16 dp checkbox with a 40 dp touch target. */
@Composable
fun LrCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.size(40.dp).clickable(enabled = enabled && onCheckedChange != null) { onCheckedChange?.invoke(!checked) }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(2.dp)).background(if (checked) Lr.Accent else Color.Transparent)
                .border(1.dp, if (checked) Lr.Accent else Lr.FunctionBorder, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) LrIconView(LrIcon.CHECK, Color.White, size = 12.dp, strokeWidth = 2.4f) }
    }
}

// ---------------- menus ----------------

/** Anchored technical menu: #262626, 3 dp, subtle border, 165 to 175 dp wide. */
@Composable
fun LrDropdown(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, width: Dp = 170.dp, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded, onDismiss, modifier.width(width).defaultMinSize(minHeight = 1.dp),
        shape = RoundedCornerShape(3.dp), containerColor = Lr.Surface3, tonalElevation = 0.dp, shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Lr.BorderDefault),
    ) { content() }
}

@Composable
fun LrMenuItem(text: String, onClick: () -> Unit, icon: LrIcon? = null, enabled: Boolean = true) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodySmall, color = if (enabled) Lr.TextPrimary else Lr.TextDisabled) },
        onClick = onClick, enabled = enabled, modifier = Modifier.height(LrDim.menuRow),
        leadingIcon = icon?.let { { LrIconView(it, if (enabled) Lr.IconPrimary else Lr.TextDisabled, size = 18.dp) } },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    )
}

// ---------------- feedback ----------------

/** Local spinner: 2 dp stroke, linear. */
@Composable
fun LocalLoader(modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = Lr.TextSecondary) {
    CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = 2.dp, trackColor = Color.Transparent)
}

/** Empty state: monochrome icon, 15 sp heading, 13 sp body, at most one compact action. */
@Composable
fun EmptyState(icon: LrIcon, heading: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LrIconView(icon, Lr.IconSecondary, size = 32.dp)
        Spacer(Modifier.height(10.dp))
        Text(heading, style = MaterialTheme.typography.titleSmall, color = Lr.TextPrimary)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Lr.TextMuted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
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
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Lr.TextSecondary, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp))
}
