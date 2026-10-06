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
        Box(Modifier.size(width = 50.dp, height = 56.dp).clickable { onAuto() }.semantics { contentDescription = "Auto" }, contentAlignment = Alignment.Center) {
            Column(Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LrIconView(LrIcon.AUTO, Lr.IconSecondary, size = 22.dp); Spacer(Modifier.height(5.dp)); Text("Auto", style = MaterialTheme.typography.labelMedium, color = Lr.TextMuted)
            }
        }
        Box(Modifier.width(1.dp).height(32.dp).background(Lr.Divider))
        items.forEach { t ->
            val on = selected == t.id
            val bg by animateColorAsState(if (on) Lr.SurfaceSelected else Color.Transparent, tween(LrMotion.fast), label = "cat")
            Box(Modifier.size(width = 50.dp, height = 56.dp).clickable { onSelect(t) }.semantics { contentDescription = t.title }, contentAlignment = Alignment.Center) {
                Column(
                    Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).background(bg),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    LrIconView(t.icon, if (on) Lr.IconPrimary else Lr.IconSecondary, size = 22.dp)
                    Spacer(Modifier.height(5.dp))
                    Text(t.title, style = MaterialTheme.typography.labelMedium, color = if (on) Lr.TextPrimary else Lr.TextMuted, maxLines = 1)
                }
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

/**
 * Bottom bar of the crop workspace: Cancel on the left (puts the crop back as it was when the tool opened), the Crop | Perspective
 * switch in the middle, and Done in the single accent blue on the right. Every part is at least 48 dp tall.
 */
@Composable
fun CropConfirmationBar(sub: String, onSub: (String) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Lr.Surface1)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lr.Divider))
        Row(Modifier.fillMaxWidth().height(LrDim.confirmBar).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            app.rawline.core.ui.SecondaryButton("Cancel", onCancel, Modifier.height(LrDim.touch).semantics { contentDescription = "Cancel crop" })
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                listOf("crop" to "Crop", "perspective" to "Perspective").forEach { (id, label) ->
                    val on = sub == id
                    Column(Modifier.height(LrDim.touch).clickable { onSub(id) }.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = if (on) Lr.TextPrimary else Lr.TextMuted)
                        Box(Modifier.padding(top = 3.dp).height(2.dp).width(24.dp).background(if (on) Lr.TextPrimary else Color.Transparent))
                    }
                }
            }
            app.rawline.core.ui.PrimaryButton("Done", onConfirm, Modifier.height(LrDim.touch).semantics { contentDescription = "Apply crop" })
        }
    }
}
