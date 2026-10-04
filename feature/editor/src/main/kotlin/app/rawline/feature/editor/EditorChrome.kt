package app.rawline.feature.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrCheckbox
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.LrDropdown
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView
import app.rawline.core.ui.LrMotion
import app.rawline.core.ui.ValueStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/** AnimatedVisibility without an implicit layout scope (avoids picking the Column or Row scoped overload inside nested layouts). */
@Composable
fun FlatVisibility(visible: Boolean, modifier: Modifier = Modifier, enter: androidx.compose.animation.EnterTransition, exit: androidx.compose.animation.ExitTransition, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(visible, modifier, enter, exit) { content() }
}

/** A tool in one of the rails. */
class Tool(val id: String, val title: String, val icon: LrIcon)

/** Idle state: six labelled tools in a flat inset dock (66 dp, 4 dp radius, no shadow). */
@Composable
fun FloatingMasterDock(modes: List<Tool>, onSelect: (Tool) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = LrDim.dockMargin).widthIn(max = 420.dp).fillMaxWidth().height(LrDim.idleDock).clip(RoundedCornerShape(4.dp)).background(Lr.Surface1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { t ->
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) }.semantics { contentDescription = t.title },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                LrIconView(t.icon, Lr.IconPrimary, size = 22.dp)
                Spacer(Modifier.height(5.dp))
                Text(t.title, style = MaterialTheme.typography.labelMedium, color = Lr.TextSecondary, maxLines = 1)
            }
        }
    }
}

/** Focused editing state: full width, 54 dp, icons only, the active mode in a blue 42 dp tile. */
@Composable
fun CompactMasterRail(modes: List<Tool>, isActive: (Tool) -> Boolean, onSelect: (Tool) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(LrDim.masterRail).background(Lr.Surface1), verticalAlignment = Alignment.CenterVertically) {
        modes.forEach { t ->
            val on = isActive(t)
            val bg by animateColorAsState(if (on) Lr.Accent else Color.Transparent, tween(LrMotion.fast), label = "master")
            Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) }.semantics { contentDescription = t.title }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(LrDim.activeTile).clip(RoundedCornerShape(6.dp)).background(bg), contentAlignment = Alignment.Center) {
                    LrIconView(t.icon, if (on) Color.White else Lr.IconSecondary, size = 24.dp)
                }
            }
        }
    }
}

/** Category rail above the master rail: Auto, Light, Color... A neutral #303030 tile marks the selection. */
@Composable
fun CategoryRail(items: List<Tool>, selected: String, onSelect: (Tool) -> Unit, onAuto: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(LrDim.categoryRail).background(Lr.Surface1).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(Modifier.size(width = 52.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).clickable { onAuto() }.semantics { contentDescription = "Auto" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LrIconView(LrIcon.AUTO, Lr.IconSecondary, size = 22.dp); Spacer(Modifier.height(5.dp)); Text("Auto", style = MaterialTheme.typography.labelMedium, color = Lr.TextMuted)
        }
        Box(Modifier.width(1.dp).height(32.dp).background(Lr.Divider))
        items.forEach { t ->
            val on = selected == t.id
            val bg by animateColorAsState(if (on) Lr.SurfaceSelected else Color.Transparent, tween(LrMotion.fast), label = "cat")
            Column(
                Modifier.size(width = 56.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).background(bg).clickable { onSelect(t) }.semantics { contentDescription = t.title },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                LrIconView(t.icon, if (on) Lr.IconPrimary else Lr.IconSecondary, size = 22.dp)
                Spacer(Modifier.height(5.dp))
                Text(t.title, style = MaterialTheme.typography.labelMedium, color = if (on) Lr.TextPrimary else Lr.TextMuted, maxLines = 1)
            }
        }
    }
}

// ---------------- crop workspace pieces ----------------

@Composable
fun CropStatusPill(text: String, modifier: Modifier = Modifier) {
    Box(modifier.height(LrDim.pill).defaultMinSize(minWidth = 80.dp).background(Lr.Surface2, CircleShape).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Lr.TextPrimary)
    }
}

/** 44 dp circle, #2A2A2A, 1 dp #414141, 20 dp icon. */
@Composable
fun CropUtilityButton(icon: LrIcon, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2A2A2A)).border(1.dp, if (active) Lr.Accent else Color(0xFF414141), CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { LrIconView(icon, if (active) Lr.Accent else Lr.IconPrimary, size = 20.dp) }
}

/** Compact dotted ruler with the live angle above it. Drag sideways; it snaps gently at zero. */
@Composable
fun CropRotationRuler(angle: Float, onChange: (Float) -> Unit, onCommit: () -> Unit, modifier: Modifier = Modifier) {
    val cur by rememberUpdatedState(angle)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Column(modifier.fillMaxWidth().height(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(String.format(java.util.Locale.US, "%.2f°", angle), style = ValueStyle.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Lr.TextPrimary)
        Canvas(
            Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {
                val perDeg = 9.dp.toPx()
                detectHorizontalDragGestures(onDragEnd = { commit() }, onDragCancel = { commit() }) { c, dx ->
                    c.consume()
                    var a = (cur - dx / perDeg).coerceIn(-45f, 45f)
                    if (abs(a) < 0.25f) a = 0f
                    change(a)
                }
            },
        ) {
            val perDeg = 9.dp.toPx(); val cx = size.width / 2f
            val first = (angle - cx / perDeg).toInt() - 1; val last = (angle + cx / perDeg).toInt() + 1
            for (d in first..last) {
                if (d < -45 || d > 45) continue
                val x = cx + (d - angle) * perDeg
                val bow = (x - cx) / cx; val y0 = 4.dp.toPx() + bow * bow * 10.dp.toPx()
                val major = d % 5 == 0
                val len = if (d == 0) 14.dp.toPx() else if (major) 9.dp.toPx() else 4.dp.toPx()
                val col = if (d == 0) Lr.TextPrimary else if (major) Lr.IconSecondary else Lr.TextDisabled
                drawLine(col, Offset(x, y0), Offset(x, y0 + len), if (d == 0) 2.dp.toPx() else 1.2.dp.toPx())
            }
        }
    }
}

/** Icon above text, 52 to 62 dp wide, neutral #303030 when selected. */
@Composable
fun AspectOptionTile(icon: LrIcon, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) Lr.SurfaceSelected else Color.Transparent, tween(LrMotion.fast), label = "aspect")
    Column(
        modifier.size(width = 58.dp, height = 60.dp).clip(RoundedCornerShape(4.dp)).background(bg).clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        LrIconView(icon, if (selected) Lr.IconPrimary else Lr.IconSecondary, size = 22.dp)
        Spacer(Modifier.height(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Lr.TextPrimary else Lr.TextMuted, maxLines = 1)
    }
}

/** Anchored list of ratios: narrow, dense, 14 dp checkboxes. */
@Composable
fun RatioPopover(expanded: Boolean, onDismiss: () -> Unit, selected: String, onPick: (String) -> Unit) {
    LrDropdown(expanded, onDismiss, width = 124.dp) {
        listOf("10:16", "9:16", "8.5:11", "5:7", "4:5", "3:4", "2:3", "1:2", "1:1").forEach { id ->
            Row(Modifier.fillMaxWidth().height(32.dp).clickable { onPick(id); onDismiss() }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(14.dp).clip(RoundedCornerShape(2.dp)).background(if (selected == id) Lr.Accent else Color.Transparent)
                        .border(1.dp, if (selected == id) Lr.Accent else Lr.FunctionBorder, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (selected == id) LrIconView(LrIcon.CHECK, Color.White, size = 10.dp, strokeWidth = 2.6f) }
                Spacer(Modifier.width(10.dp))
                Text(id.replace(":", " × "), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Lr.TextPrimary)
            }
        }
    }
}

/** X, title, check. 56 dp, 1 dp divider above. */
@Composable
fun CropConfirmationBar(title: String, onCancel: () -> Unit, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Lr.Surface1)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lr.Divider))
        Row(Modifier.fillMaxWidth().height(LrDim.confirmBar), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable(onClick = onCancel).semantics { contentDescription = "Cancel" }, contentAlignment = Alignment.Center) { LrIconView(LrIcon.CLOSE, Lr.IconPrimary, size = 24.dp) }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Lr.TextPrimary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.size(48.dp).clickable(onClick = onConfirm).semantics { contentDescription = "Apply crop" }, contentAlignment = Alignment.Center) { LrIconView(LrIcon.CHECK, Lr.IconPrimary, size = 24.dp) }
        }
    }
}
