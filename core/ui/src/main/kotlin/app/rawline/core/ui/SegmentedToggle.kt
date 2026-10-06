package app.rawline.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Joined segments, one selected, 48 dp tall. Used for the two product switch in the home top bars. [selected] is an index into [labels]. */
@Composable
fun LrSegmentedToggle(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, segmentWidth: Dp = 64.dp) {
    val shape = RoundedCornerShape(LrRadius.sm)
    Row(modifier.height(48.dp).clip(shape).border(1.dp, Lr.BorderDefault, shape)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.width(segmentWidth).fillMaxHeight().background(if (on) Lr.SurfaceSelected else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { if (!on) onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Lr.TextPrimary else Lr.TextSecondary, style = MaterialTheme.typography.labelLarge) }
            if (i < labels.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(Lr.BorderSubtle))
        }
    }
}
