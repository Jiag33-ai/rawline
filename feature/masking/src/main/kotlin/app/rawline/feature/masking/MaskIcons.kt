package app.rawline.feature.masking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rawline.core.model.MaskComponent
import app.rawline.core.model.MaskType

/** Icons for the masking tray, in the same line style as core/ui LrIcons (those are not edited, so these live here). */
enum class MaskIcon { SUBJECT, SKY, BACKGROUND, PEOPLE, OBJECT, BRUSH, LINEAR, RADIAL, COLOUR, LUMINANCE, EYE, EYE_OFF, INVERT, OVERLAY, ERASE, PENCIL, CHEVRON_RIGHT, STROKE_UNDO }

/** The icon that best describes one part of a mask. */
fun iconFor(c: MaskComponent): MaskIcon = when (c.type) {
    MaskType.LINEAR -> MaskIcon.LINEAR
    MaskType.RADIAL -> MaskIcon.RADIAL
    MaskType.COLOR -> MaskIcon.COLOUR
    MaskType.LUMINANCE -> MaskIcon.LUMINANCE
    MaskType.BITMAP -> if (MaskRules.isBrush(c)) MaskIcon.BRUSH else {
        val l = c.label.lowercase()
        when {
            "sky" in l -> MaskIcon.SKY
            "background" in l -> MaskIcon.BACKGROUND
            "subject" in l -> MaskIcon.SUBJECT
            "object" in l -> MaskIcon.OBJECT
            "person" in l || "people" in l || "face" in l || "hair" in l || "skin" in l || "body" in l || "cloth" in l -> MaskIcon.PEOPLE
            else -> MaskIcon.OBJECT
        }
    }
}

@Composable
fun MaskIconView(icon: MaskIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawMaskIcon(icon, tint, 1.8.dp.toPx() * (size.toPx() / 24.dp.toPx()).coerceAtLeast(0.8f)) }
}

private fun DrawScope.drawMaskIcon(icon: MaskIcon, c: Color, sw: Float) {
    val w = size.width; val h = size.height
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, col: Color = c) = drawLine(col, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false, col: Color = c) = drawCircle(col, r * w, Offset(cx * w, cy * h), style = if (fill) Fill else st)
    fun rrect(x: Float, y: Float, rw: Float, rh: Float, r: Float = 0.08f, dashed: Boolean = false) =
        drawRoundRect(c, Offset(x * w, y * h), Size(rw * w, rh * h), CornerRadius(r * w),
            style = if (dashed) Stroke(sw, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 1.6f, sw * 1.8f))) else st)
    fun poly(vararg p: Float, close: Boolean = false) {
        val path = Path(); path.moveTo(p[0] * w, p[1] * h)
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i] * w, p[i + 1] * h); i += 2 }
        if (close) path.close()
        drawPath(path, c, style = st)
    }
    when (icon) {
        MaskIcon.SUBJECT -> {
            rrect(0.1f, 0.1f, 0.8f, 0.8f, 0.1f, dashed = true)
            circle(0.5f, 0.38f, 0.1f); poly(0.3f, 0.78f, 0.32f, 0.62f, 0.5f, 0.54f, 0.68f, 0.62f, 0.7f, 0.78f)
        }
        MaskIcon.SKY -> {
            val p = Path()
            p.moveTo(0.25f * w, 0.72f * h)
            p.cubicTo(0.06f * w, 0.72f * h, 0.06f * w, 0.46f * h, 0.28f * w, 0.46f * h)
            p.cubicTo(0.3f * w, 0.24f * h, 0.64f * w, 0.2f * h, 0.7f * w, 0.44f * h)
            p.cubicTo(0.94f * w, 0.42f * h, 0.94f * w, 0.72f * h, 0.74f * w, 0.72f * h)
            p.close()
            drawPath(p, c, style = st)
        }
        MaskIcon.BACKGROUND -> {
            rrect(0.1f, 0.14f, 0.8f, 0.72f, 0.06f)
            line(0.18f, 0.5f, 0.42f, 0.22f); line(0.18f, 0.74f, 0.66f, 0.2f); line(0.4f, 0.8f, 0.82f, 0.32f); line(0.66f, 0.8f, 0.84f, 0.6f)
        }
        MaskIcon.PEOPLE -> {
            circle(0.36f, 0.34f, 0.1f); poly(0.14f, 0.78f, 0.16f, 0.62f, 0.36f, 0.54f, 0.56f, 0.62f, 0.58f, 0.78f)
            circle(0.7f, 0.38f, 0.08f); poly(0.64f, 0.56f, 0.8f, 0.6f, 0.86f, 0.72f)
        }
        MaskIcon.OBJECT -> {
            circle(0.5f, 0.5f, 0.2f); circle(0.5f, 0.5f, 0.04f, fill = true)
            line(0.5f, 0.1f, 0.5f, 0.22f); line(0.5f, 0.78f, 0.5f, 0.9f); line(0.1f, 0.5f, 0.22f, 0.5f); line(0.78f, 0.5f, 0.9f, 0.5f)
        }
        MaskIcon.BRUSH -> {
            line(0.84f, 0.16f, 0.46f, 0.54f)
            val p = Path()
            p.moveTo(0.44f * w, 0.52f * h); p.cubicTo(0.3f * w, 0.5f * h, 0.2f * w, 0.62f * h, 0.2f * w, 0.74f * h)
            p.cubicTo(0.2f * w, 0.84f * h, 0.14f * w, 0.86f * h, 0.12f * w, 0.88f * h)
            p.cubicTo(0.3f * w, 0.9f * h, 0.5f * w, 0.84f * h, 0.54f * w, 0.62f * h)
            p.close()
            drawPath(p, c, style = st)
        }
        MaskIcon.LINEAR -> {
            rrect(0.1f, 0.14f, 0.8f, 0.72f, 0.06f)
            line(0.18f, 0.3f, 0.82f, 0.3f, c.copy(alpha = c.alpha * 0.3f)); line(0.18f, 0.5f, 0.82f, 0.5f, c.copy(alpha = c.alpha * 0.62f)); line(0.18f, 0.7f, 0.82f, 0.7f)
        }
        MaskIcon.RADIAL -> {
            circle(0.5f, 0.5f, 0.38f, col = c.copy(alpha = c.alpha * 0.45f)); circle(0.5f, 0.5f, 0.24f, col = c.copy(alpha = c.alpha * 0.75f)); circle(0.5f, 0.5f, 0.09f, fill = true)
        }
        MaskIcon.COLOUR -> {
            val p = Path()
            p.moveTo(0.5f * w, 0.1f * h)
            p.cubicTo(0.5f * w, 0.1f * h, 0.2f * w, 0.46f * h, 0.2f * w, 0.62f * h)
            p.cubicTo(0.2f * w, 0.9f * h, 0.8f * w, 0.9f * h, 0.8f * w, 0.62f * h)
            p.cubicTo(0.8f * w, 0.46f * h, 0.5f * w, 0.1f * h, 0.5f * w, 0.1f * h)
            drawPath(p, c, style = st)
        }
        MaskIcon.LUMINANCE -> {
            circle(0.5f, 0.5f, 0.36f)
            drawArc(c, 90f, 180f, true, Offset(0.14f * w, 0.14f * h), Size(0.72f * w, 0.72f * h), style = Fill)
        }
        MaskIcon.EYE, MaskIcon.EYE_OFF -> {
            val p = Path()
            p.moveTo(0.08f * w, 0.5f * h); p.cubicTo(0.28f * w, 0.2f * h, 0.72f * w, 0.2f * h, 0.92f * w, 0.5f * h)
            p.cubicTo(0.72f * w, 0.8f * h, 0.28f * w, 0.8f * h, 0.08f * w, 0.5f * h)
            drawPath(p, c, style = st)
            circle(0.5f, 0.5f, 0.12f, fill = true)
            if (icon == MaskIcon.EYE_OFF) line(0.18f, 0.84f, 0.82f, 0.16f)
        }
        MaskIcon.INVERT -> {
            circle(0.5f, 0.5f, 0.36f)
            drawArc(c, -90f, 180f, true, Offset(0.14f * w, 0.14f * h), Size(0.72f * w, 0.72f * h), style = Fill)
        }
        MaskIcon.OVERLAY -> {
            rrect(0.12f, 0.18f, 0.76f, 0.64f, 0.06f)
            drawRect(c.copy(alpha = c.alpha * 0.45f), Offset(0.18f * w, 0.24f * h), Size(0.64f * w, 0.52f * h))
        }
        MaskIcon.ERASE -> {
            poly(0.14f, 0.58f, 0.46f, 0.22f, 0.82f, 0.46f, 0.54f, 0.78f, close = true)
            line(0.3f, 0.4f, 0.62f, 0.64f); line(0.5f, 0.86f, 0.88f, 0.86f)
        }
        MaskIcon.PENCIL -> poly(0.18f, 0.82f, 0.22f, 0.64f, 0.66f, 0.2f, 0.8f, 0.34f, 0.36f, 0.78f, close = true)
        MaskIcon.CHEVRON_RIGHT -> poly(0.38f, 0.22f, 0.66f, 0.5f, 0.38f, 0.78f)
        MaskIcon.STROKE_UNDO -> {
            poly(0.28f, 0.3f, 0.14f, 0.46f, 0.3f, 0.6f)
            drawArc(c, -150f, 210f, false, Offset(0.16f * w, 0.26f * h), Size(0.7f * w, 0.44f * h), style = st)
            line(0.3f, 0.84f, 0.74f, 0.84f)
        }
    }
}
