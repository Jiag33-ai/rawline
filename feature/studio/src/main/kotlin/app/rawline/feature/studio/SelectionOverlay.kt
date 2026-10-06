package app.rawline.feature.studio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.rawline.core.studio.model.CanvasView
import app.rawline.core.studio.render.StudioState
import app.rawline.core.studio.render.Tool

/**
 * The marching ants (spec 2.2, first cut) and the shape being dragged. The outline is a black line with a white dashed line walking over it, so it reads on any picture.
 * The path is built once per selection edit and view change; only the dash offset animates. It draws nothing and takes no touches.
 */
@Composable
fun SelectionOverlay(state: StudioState, modifier: Modifier = Modifier) {
    val sel = state.selection
    val drag = state.selectionDrag
    if (sel == null && drag == null) return
    val view = state.view
    val phase by rememberInfiniteTransition(label = "ants").animateFloat(0f, 16f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart), label = "antsPhase")
    val path = remember(sel?.version, view) { sel?.let { contourPath(it.contour, view) } }
    Canvas(modifier) {
        val w = 1.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), phase.dp.toPx() / 2f)
        path?.let { ants(it, w, dash) }
        if (drag != null) dragShape(state, view, w, dash)
    }
}

private fun contourPath(c: FloatArray, v: CanvasView): Path {
    val p = Path()
    var i = 0
    while (i + 3 < c.size) {
        p.moveTo(v.toScreenX(c[i]), v.toScreenY(c[i + 1])); p.lineTo(v.toScreenX(c[i + 2]), v.toScreenY(c[i + 3]))
        i += 4
    }
    return p
}

private fun DrawScope.ants(p: Path, w: Float, dash: PathEffect) {
    drawPath(p, Color.Black, style = Stroke(w))
    drawPath(p, Color.White, style = Stroke(w, pathEffect = dash))
}

private fun DrawScope.dragShape(state: StudioState, v: CanvasView, w: Float, dash: PathEffect) {
    val d = state.selectionDrag ?: return
    val shape = Path()
    when (d.tool) {
        Tool.RECT_SELECT -> shape.addRect(androidx.compose.ui.geometry.Rect(v.toScreenX(minOf(d.x0, d.x1)), v.toScreenY(minOf(d.y0, d.y1)), v.toScreenX(maxOf(d.x0, d.x1)), v.toScreenY(maxOf(d.y0, d.y1))))
        Tool.ELLIPSE_SELECT -> shape.addOval(androidx.compose.ui.geometry.Rect(v.toScreenX(minOf(d.x0, d.x1)), v.toScreenY(minOf(d.y0, d.y1)), v.toScreenX(maxOf(d.x0, d.x1)), v.toScreenY(maxOf(d.y0, d.y1))))
        else -> {
            if (d.xs.isEmpty()) return
            shape.moveTo(v.toScreenX(d.xs[0]), v.toScreenY(d.ys[0]))
            for (i in 1 until d.xs.size) shape.lineTo(v.toScreenX(d.xs[i]), v.toScreenY(d.ys[i]))
            shape.close()
        }
    }
    ants(shape, w, dash)
}
