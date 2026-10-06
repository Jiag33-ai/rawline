package app.rawline.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.render.StudioSession
import app.rawline.core.studio.render.StudioState
import app.rawline.core.studio.render.Tool
import app.rawline.core.ui.Lr
import app.rawline.core.ui.RawSlider
import kotlin.math.roundToInt

/**
 * Options of the current tool. Brush and Eraser: Size, Hardness, Opacity, Flow, each a RawSlider (its value is tappable for a typed number through SliderInput). The settings are
 * changed through `updateBrush`, so a value is always applied to what the tool holds now. Move and Scale: the active layer's scale, 25 to 400 percent.
 * [columns] 2 puts the four sliders in two rows of two (portrait tray), 1 stacks them (landscape panel).
 */
@Composable
fun ToolOptions(state: StudioState, session: StudioSession, columns: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Lr.Surface1).padding(vertical = 4.dp)) {
        when (state.tool) {
            Tool.BRUSH, Tool.ERASER -> {
                val b = if (state.tool == Tool.ERASER) state.eraser else state.brush
                val size = @Composable { m: Modifier -> RawSlider("Size", b.diameter.toFloat(), BrushSliders.size, default = 24f, unit = " px", onChange = { v -> session.updateBrush { BrushSliders.withSize(it, v) } }, onCommit = {}, modifier = m) }
                val hardness = @Composable { m: Modifier -> RawSlider("Hardness", (b.hardness * 100).toFloat(), BrushSliders.percent, default = 80f, unit = "%", onChange = { v -> session.updateBrush { BrushSliders.withHardness(it, v) } }, onCommit = {}, modifier = m) }
                val opacity = @Composable { m: Modifier -> RawSlider("Opacity", (b.opacity * 100).toFloat(), BrushSliders.percent, default = 100f, unit = "%", onChange = { v -> session.updateBrush { BrushSliders.withOpacity(it, v) } }, onCommit = {}, modifier = m) }
                val flow = @Composable { m: Modifier -> RawSlider("Flow", (b.flow * 100).toFloat(), BrushSliders.flow, default = 100f, unit = "%", onChange = { v -> session.updateBrush { BrushSliders.withFlow(it, v) } }, onCommit = {}, modifier = m) }
                if (columns >= 2) {
                    Row(Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { size(Modifier) }; Column(Modifier.weight(1f)) { hardness(Modifier) } }
                    Row(Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { opacity(Modifier) }; Column(Modifier.weight(1f)) { flow(Modifier) } }
                } else { size(Modifier); hardness(Modifier); opacity(Modifier); flow(Modifier) }
            }
            Tool.MOVE, Tool.SCALE -> {
                val l = state.document.layer(state.activeId) as? Layer.Pixel
                val pct = ((l?.common?.scale ?: 1f) * 100f)
                RawSlider(
                    "Scale", pct.roundToInt().toFloat(), 25f..400f, default = 100f, unit = "%",
                    onChange = { v -> session.previewScale(v) }, onCommit = { session.commitPreview() },
                )
                Text(
                    if (state.tool == Tool.MOVE) "Drag with one finger to move the layer. Two fingers pan and zoom the canvas." else "Pinch with two fingers to scale the layer, or set the number above.",
                    style = MaterialTheme.typography.bodySmall, color = Lr.TextMuted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }
        }
    }
}
