package app.rawline.feature.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line icons Studio needs that the shared set (core:ui LrIcon) does not have, drawn the same way: 1.8 stroke on a 24 unit grid, round caps. */
enum class StudioIcon { EYE, EYE_OFF, LAYERS, BRUSH, ERASER, MOVE, SCALE, UP, DOWN }

@Composable
fun StudioIconView(icon: StudioIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { draw(icon, tint, 1.8f * density * (this.size.width / (24f * density))) }
}

private fun DrawScope.draw(icon: StudioIcon, c: Color, sw: Float) {
    val w = size.width; val h = size.height
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) = drawCircle(c, r * w, Offset(cx * w, cy * h), style = if (fill) Fill else st)
    fun poly(vararg p: Float, close: Boolean = false, fill: Boolean = false) {
        val path = Path()
        path.moveTo(p[0] * w, p[1] * h)
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i] * w, p[i + 1] * h); i += 2 }
        if (close) path.close()
        drawPath(path, c, style = if (fill) Fill else st)
    }
    when (icon) {
        StudioIcon.EYE -> { poly(0.08f, 0.5f, 0.3f, 0.28f, 0.5f, 0.22f, 0.7f, 0.28f, 0.92f, 0.5f, 0.7f, 0.72f, 0.5f, 0.78f, 0.3f, 0.72f, close = true); circle(0.5f, 0.5f, 0.12f, fill = true) }
        StudioIcon.EYE_OFF -> { poly(0.08f, 0.5f, 0.3f, 0.28f, 0.5f, 0.22f, 0.7f, 0.28f, 0.92f, 0.5f, 0.7f, 0.72f, 0.5f, 0.78f, 0.3f, 0.72f, close = true); line(0.15f, 0.88f, 0.85f, 0.12f) }
        StudioIcon.LAYERS -> { poly(0.5f, 0.12f, 0.9f, 0.34f, 0.5f, 0.56f, 0.1f, 0.34f, close = true); poly(0.1f, 0.5f, 0.5f, 0.72f, 0.9f, 0.5f); poly(0.1f, 0.66f, 0.5f, 0.88f, 0.9f, 0.66f) }
        StudioIcon.BRUSH -> { poly(0.8f, 0.12f, 0.9f, 0.22f, 0.5f, 0.62f, 0.4f, 0.52f, close = true); poly(0.4f, 0.58f, 0.2f, 0.62f, 0.12f, 0.88f, 0.4f, 0.8f, 0.46f, 0.6f) }
        StudioIcon.ERASER -> { poly(0.55f, 0.15f, 0.88f, 0.48f, 0.5f, 0.86f, 0.12f, 0.86f, 0.12f, 0.58f, close = true); line(0.34f, 0.36f, 0.66f, 0.68f); line(0.5f, 0.86f, 0.9f, 0.86f) }
        StudioIcon.MOVE -> { line(0.5f, 0.12f, 0.5f, 0.88f); line(0.12f, 0.5f, 0.88f, 0.5f); poly(0.38f, 0.24f, 0.5f, 0.12f, 0.62f, 0.24f); poly(0.38f, 0.76f, 0.5f, 0.88f, 0.62f, 0.76f); poly(0.24f, 0.38f, 0.12f, 0.5f, 0.24f, 0.62f); poly(0.76f, 0.38f, 0.88f, 0.5f, 0.76f, 0.62f) }
        StudioIcon.SCALE -> { drawRect(c, Offset(0.1f * w, 0.4f * h), Size(0.5f * w, 0.5f * h), style = st); poly(0.5f, 0.1f, 0.9f, 0.1f, 0.9f, 0.5f); line(0.9f, 0.1f, 0.5f, 0.5f) }
        StudioIcon.UP -> { line(0.5f, 0.85f, 0.5f, 0.2f); poly(0.25f, 0.42f, 0.5f, 0.17f, 0.75f, 0.42f) }
        StudioIcon.DOWN -> { line(0.5f, 0.15f, 0.5f, 0.8f); poly(0.25f, 0.58f, 0.5f, 0.83f, 0.75f, 0.58f) }
    }
}
