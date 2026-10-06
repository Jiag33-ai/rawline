package app.rawline.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Line icons in the style of Lightroom mobile, drawn in code so no icon font or large icon library is needed. */
enum class LrIcon {
    PHOTOS, QUEUE, SETTINGS, ADD, FILTER, SORT, CHECK, BACK, UNDO, REDO, SHARE, MORE, INFO, STAR, STAR_FILLED, FLAG, FLAG_FILLED, REJECT,
    EDIT, PRESETS, CROP, LIGHT, COLOR, EFFECTS, DETAIL, OPTICS, GEOMETRY, MASKING, HEALING, AUTO, CURVE, CLOSE, CHEVRON_DOWN, HISTOGRAM,
    VERSIONS, RESET, FOLDER, IMPORT, CAMERA, SELECT, TRASH, DOWNLOAD, ERROR, PAUSE, REFRESH,
    LOCK, UNLOCK, ROTATE, HELP, ORIGINAL, RATIOS, FREE_CROP, EYEDROPPER, COPY, SEARCH,
    ROTATE_LEFT, FLIP_H, FLIP_V, SWAP_ORIENTATION, LEVEL,
}

@Composable
fun LrIconView(icon: LrIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, strokeWidth: Float = 1.8f) {
    Canvas(modifier.size(size)) { draw(icon, tint, strokeWidth * this.size.width / (24f * density) * density) }
}

private fun DrawScope.draw(icon: LrIcon, c: Color, sw: Float) {
    val w = size.width; val h = size.height
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) = drawCircle(c, r * w, Offset(cx * w, cy * h), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st)
    fun rrect(x: Float, y: Float, rw: Float, rh: Float, r: Float = 0.08f, fill: Boolean = false) =
        drawRoundRect(c, Offset(x * w, y * h), Size(rw * w, rh * h), CornerRadius(r * w), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st)
    fun poly(vararg p: Float, close: Boolean = false, fill: Boolean = false) {
        val path = Path()
        path.moveTo(p[0] * w, p[1] * h)
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i] * w, p[i + 1] * h); i += 2 }
        if (close) path.close()
        drawPath(path, c, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st)
    }
    when (icon) {
        LrIcon.PHOTOS -> { rrect(0.12f, 0.2f, 0.76f, 0.6f); circle(0.32f, 0.38f, 0.07f); poly(0.15f, 0.72f, 0.4f, 0.5f, 0.55f, 0.65f, 0.7f, 0.5f, 0.86f, 0.7f) }
        LrIcon.QUEUE -> { line(0.15f, 0.25f, 0.85f, 0.25f); line(0.15f, 0.5f, 0.6f, 0.5f); line(0.15f, 0.75f, 0.45f, 0.75f); poly(0.7f, 0.62f, 0.8f, 0.78f, 0.9f, 0.62f) }
        LrIcon.SETTINGS -> { for (k in 0 until 3) { val y = 0.25f + k * 0.25f; line(0.12f, y, 0.88f, y); circle(0.3f + k * 0.2f, y, 0.07f, fill = true) } }
        LrIcon.ADD -> { line(0.5f, 0.15f, 0.5f, 0.85f); line(0.15f, 0.5f, 0.85f, 0.5f) }
        LrIcon.FILTER -> poly(0.12f, 0.2f, 0.88f, 0.2f, 0.58f, 0.55f, 0.58f, 0.82f, 0.42f, 0.74f, 0.42f, 0.55f, close = true)
        LrIcon.SORT -> { line(0.3f, 0.2f, 0.3f, 0.8f); poly(0.15f, 0.65f, 0.3f, 0.8f, 0.45f, 0.65f); line(0.7f, 0.8f, 0.7f, 0.2f); poly(0.55f, 0.35f, 0.7f, 0.2f, 0.85f, 0.35f) }
        LrIcon.CHECK -> poly(0.18f, 0.52f, 0.42f, 0.75f, 0.84f, 0.28f)
        LrIcon.BACK -> { line(0.82f, 0.5f, 0.2f, 0.5f); poly(0.42f, 0.28f, 0.2f, 0.5f, 0.42f, 0.72f) }
        LrIcon.UNDO -> { poly(0.25f, 0.35f, 0.12f, 0.5f, 0.25f, 0.65f); drawArc(c, -180f, 200f, false, Offset(0.15f * w, 0.28f * h), Size(0.72f * w, 0.44f * h), style = st) }
        LrIcon.REDO -> { poly(0.75f, 0.35f, 0.88f, 0.5f, 0.75f, 0.65f); drawArc(c, -20f, 200f, false, Offset(0.13f * w, 0.28f * h), Size(0.72f * w, 0.44f * h), style = st) }
        LrIcon.SHARE -> { line(0.5f, 0.12f, 0.5f, 0.62f); poly(0.3f, 0.3f, 0.5f, 0.1f, 0.7f, 0.3f); poly(0.25f, 0.45f, 0.18f, 0.45f, 0.18f, 0.88f, 0.82f, 0.88f, 0.82f, 0.45f, 0.75f, 0.45f) }
        LrIcon.MORE -> { circle(0.5f, 0.2f, 0.06f, true); circle(0.5f, 0.5f, 0.06f, true); circle(0.5f, 0.8f, 0.06f, true) }
        LrIcon.INFO -> { circle(0.5f, 0.5f, 0.38f); line(0.5f, 0.45f, 0.5f, 0.72f); circle(0.5f, 0.3f, 0.035f, true) }
        LrIcon.STAR, LrIcon.STAR_FILLED -> {
            val p = Path()
            for (i in 0 until 10) { val r = if (i % 2 == 0) 0.4f else 0.17f; val a = -PI / 2 + i * PI / 5; val x = (0.5f + r * cos(a).toFloat()) * w; val y = (0.52f + r * sin(a).toFloat()) * h; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            p.close()
            drawPath(p, c, style = if (icon == LrIcon.STAR_FILLED) androidx.compose.ui.graphics.drawscope.Fill else st)
        }
        LrIcon.FLAG, LrIcon.FLAG_FILLED -> { line(0.25f, 0.12f, 0.25f, 0.9f); poly(0.25f, 0.15f, 0.8f, 0.15f, 0.65f, 0.38f, 0.8f, 0.6f, 0.25f, 0.6f, close = true, fill = icon == LrIcon.FLAG_FILLED) }
        LrIcon.REJECT -> { line(0.25f, 0.25f, 0.75f, 0.75f); line(0.75f, 0.25f, 0.25f, 0.75f) }
        LrIcon.PRESETS -> { circle(0.4f, 0.45f, 0.26f); circle(0.6f, 0.55f, 0.26f) }
        LrIcon.EDIT -> { for (k in 0 until 3) { val y = 0.25f + k * 0.25f; line(0.12f, y, 0.88f, y); circle(0.3f + ((k * 3) % 5) * 0.1f, y, 0.075f, fill = true) } }
        LrIcon.CROP -> { poly(0.3f, 0.1f, 0.3f, 0.7f, 0.9f, 0.7f); poly(0.1f, 0.3f, 0.7f, 0.3f, 0.7f, 0.9f) }
        LrIcon.LIGHT -> { circle(0.5f, 0.5f, 0.17f); for (i in 0 until 8) { val a = i * PI / 4; line(0.5f + 0.27f * cos(a).toFloat(), 0.5f + 0.27f * sin(a).toFloat(), 0.5f + 0.4f * cos(a).toFloat(), 0.5f + 0.4f * sin(a).toFloat()) } }
        LrIcon.COLOR -> { poly(0.5f, 0.1f, 0.78f, 0.52f); drawArc(c, -30f, 240f, false, Offset(0.22f * w, 0.32f * h), Size(0.56f * w, 0.56f * h), style = st); poly(0.22f, 0.52f, 0.5f, 0.1f) }
        LrIcon.EFFECTS -> { poly(0.42f, 0.12f, 0.5f, 0.38f, 0.76f, 0.46f, 0.5f, 0.54f, 0.42f, 0.8f, 0.34f, 0.54f, 0.08f, 0.46f, 0.34f, 0.38f, close = true); line(0.78f, 0.7f, 0.78f, 0.9f); line(0.68f, 0.8f, 0.88f, 0.8f) }
        LrIcon.DETAIL -> poly(0.5f, 0.15f, 0.88f, 0.82f, 0.12f, 0.82f, close = true)
        LrIcon.OPTICS -> { circle(0.5f, 0.5f, 0.38f); circle(0.5f, 0.5f, 0.2f); circle(0.5f, 0.5f, 0.05f, true) }
        LrIcon.GEOMETRY -> { poly(0.3f, 0.2f, 0.7f, 0.2f, 0.88f, 0.8f, 0.12f, 0.8f, close = true); line(0.5f, 0.2f, 0.5f, 0.8f) }
        LrIcon.MASKING -> { drawCircle(c, 0.38f * w, Offset(0.5f * w, 0.5f * h), style = Stroke(sw, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(sw * 2.2f, sw * 2f)))); circle(0.5f, 0.5f, 0.15f, true) }
        LrIcon.HEALING -> { rrect(0.1f, 0.38f, 0.8f, 0.24f, 0.12f); line(0.4f, 0.42f, 0.4f, 0.58f); line(0.6f, 0.42f, 0.6f, 0.58f) }
        LrIcon.AUTO -> { line(0.15f, 0.85f, 0.65f, 0.35f); poly(0.62f, 0.15f, 0.68f, 0.3f, 0.84f, 0.36f, 0.68f, 0.42f, 0.62f, 0.58f, 0.56f, 0.42f, 0.4f, 0.36f, 0.56f, 0.3f, close = true) }
        LrIcon.CURVE -> { rrect(0.12f, 0.12f, 0.76f, 0.76f, 0.04f); val p = Path(); p.moveTo(0.15f * w, 0.85f * h); p.cubicTo(0.45f * w, 0.8f * h, 0.55f * w, 0.2f * h, 0.85f * w, 0.15f * h); drawPath(p, c, style = st) }
        LrIcon.CLOSE -> { line(0.22f, 0.22f, 0.78f, 0.78f); line(0.78f, 0.22f, 0.22f, 0.78f) }
        LrIcon.CHEVRON_DOWN -> poly(0.22f, 0.38f, 0.5f, 0.66f, 0.78f, 0.38f)
        LrIcon.HISTOGRAM -> { poly(0.1f, 0.85f, 0.1f, 0.6f, 0.3f, 0.3f, 0.45f, 0.65f, 0.6f, 0.15f, 0.75f, 0.55f, 0.9f, 0.45f, 0.9f, 0.85f, close = true) }
        LrIcon.VERSIONS -> { rrect(0.2f, 0.3f, 0.6f, 0.55f); line(0.3f, 0.2f, 0.7f, 0.2f); line(0.4f, 0.1f, 0.6f, 0.1f) }
        LrIcon.RESET -> { drawArc(c, -60f, 300f, false, Offset(0.15f * w, 0.15f * h), Size(0.7f * w, 0.7f * h), style = st); poly(0.62f, 0.08f, 0.72f, 0.2f, 0.58f, 0.26f) }
        LrIcon.FOLDER -> poly(0.1f, 0.25f, 0.4f, 0.25f, 0.5f, 0.38f, 0.9f, 0.38f, 0.9f, 0.8f, 0.1f, 0.8f, close = true)
        LrIcon.IMPORT -> { line(0.5f, 0.12f, 0.5f, 0.62f); poly(0.3f, 0.45f, 0.5f, 0.65f, 0.7f, 0.45f); poly(0.15f, 0.7f, 0.15f, 0.88f, 0.85f, 0.88f, 0.85f, 0.7f) }
        LrIcon.DOWNLOAD -> { line(0.5f, 0.12f, 0.5f, 0.62f); poly(0.3f, 0.45f, 0.5f, 0.65f, 0.7f, 0.45f); line(0.2f, 0.85f, 0.8f, 0.85f) }
        LrIcon.CAMERA -> { rrect(0.1f, 0.3f, 0.8f, 0.52f); circle(0.5f, 0.56f, 0.15f); poly(0.35f, 0.3f, 0.42f, 0.18f, 0.58f, 0.18f, 0.65f, 0.3f) }
        LrIcon.SELECT -> { circle(0.5f, 0.5f, 0.38f); poly(0.32f, 0.5f, 0.45f, 0.64f, 0.7f, 0.36f) }
        LrIcon.TRASH -> { line(0.15f, 0.25f, 0.85f, 0.25f); poly(0.3f, 0.25f, 0.35f, 0.88f, 0.65f, 0.88f, 0.7f, 0.25f); poly(0.4f, 0.25f, 0.4f, 0.12f, 0.6f, 0.12f, 0.6f, 0.25f) }
        LrIcon.ERROR -> { circle(0.5f, 0.5f, 0.38f); line(0.5f, 0.28f, 0.5f, 0.56f); circle(0.5f, 0.72f, 0.035f, true) }
        LrIcon.PAUSE -> { line(0.35f, 0.2f, 0.35f, 0.8f); line(0.65f, 0.2f, 0.65f, 0.8f) }
        LrIcon.REFRESH -> { drawArc(c, -60f, 300f, false, Offset(0.15f * w, 0.15f * h), Size(0.7f * w, 0.7f * h), style = st); poly(0.62f, 0.08f, 0.72f, 0.2f, 0.58f, 0.26f) }
        LrIcon.LOCK, LrIcon.UNLOCK -> { rrect(0.2f, 0.45f, 0.6f, 0.4f, 0.06f); if (icon == LrIcon.LOCK) poly(0.32f, 0.45f, 0.32f, 0.3f, 0.4f, 0.15f, 0.6f, 0.15f, 0.68f, 0.3f, 0.68f, 0.45f) else poly(0.32f, 0.45f, 0.32f, 0.3f, 0.4f, 0.15f, 0.6f, 0.15f, 0.68f, 0.28f) }
        LrIcon.ROTATE -> { drawArc(c, -50f, 280f, false, Offset(0.15f * w, 0.15f * h), Size(0.7f * w, 0.7f * h), style = st); poly(0.7f, 0.1f, 0.82f, 0.25f, 0.65f, 0.3f) }
        LrIcon.HELP -> { circle(0.5f, 0.5f, 0.4f); drawArc(c, 200f, 220f, false, Offset(0.36f * w, 0.28f * h), Size(0.28f * w, 0.26f * h), style = st); line(0.5f, 0.52f, 0.5f, 0.62f); circle(0.5f, 0.74f, 0.035f, true) }
        LrIcon.ORIGINAL -> { rrect(0.12f, 0.2f, 0.76f, 0.6f); circle(0.34f, 0.4f, 0.07f); poly(0.15f, 0.76f, 0.4f, 0.52f, 0.58f, 0.68f, 0.7f, 0.55f, 0.85f, 0.72f) }
        LrIcon.RATIOS -> { rrect(0.22f, 0.3f, 0.6f, 0.5f, 0.04f); line(0.12f, 0.2f, 0.62f, 0.2f); line(0.12f, 0.2f, 0.12f, 0.62f) }
        LrIcon.FREE_CROP -> { poly(0.2f, 0.4f, 0.2f, 0.2f, 0.4f, 0.2f); poly(0.6f, 0.2f, 0.8f, 0.2f, 0.8f, 0.4f); poly(0.8f, 0.6f, 0.8f, 0.8f, 0.6f, 0.8f); poly(0.4f, 0.8f, 0.2f, 0.8f, 0.2f, 0.6f) }
        LrIcon.EYEDROPPER -> { line(0.2f, 0.8f, 0.6f, 0.4f); rrect(0.55f, 0.15f, 0.3f, 0.3f, 0.08f); line(0.2f, 0.8f, 0.12f, 0.88f) }
        LrIcon.COPY -> { rrect(0.3f, 0.3f, 0.55f, 0.58f, 0.05f); poly(0.2f, 0.7f, 0.15f, 0.7f, 0.15f, 0.12f, 0.58f, 0.12f, 0.58f, 0.2f) }
        LrIcon.SEARCH -> { circle(0.43f, 0.43f, 0.28f); line(0.65f, 0.65f, 0.85f, 0.85f) }
        LrIcon.ROTATE_LEFT -> { drawArc(c, 110f, 280f, false, Offset(0.15f * w, 0.15f * h), Size(0.7f * w, 0.7f * h), style = st); poly(0.3f, 0.1f, 0.18f, 0.25f, 0.35f, 0.3f) }
        LrIcon.FLIP_H -> { line(0.5f, 0.1f, 0.5f, 0.9f); poly(0.4f, 0.25f, 0.12f, 0.75f, 0.4f, 0.75f, close = true); poly(0.6f, 0.25f, 0.88f, 0.75f, 0.6f, 0.75f, close = true) }
        LrIcon.FLIP_V -> { line(0.1f, 0.5f, 0.9f, 0.5f); poly(0.25f, 0.4f, 0.75f, 0.12f, 0.75f, 0.4f, close = true); poly(0.25f, 0.6f, 0.75f, 0.88f, 0.75f, 0.6f, close = true) }
        LrIcon.SWAP_ORIENTATION -> { rrect(0.1f, 0.34f, 0.5f, 0.34f, 0.04f); rrect(0.56f, 0.18f, 0.34f, 0.5f, 0.04f); poly(0.2f, 0.84f, 0.5f, 0.84f); poly(0.42f, 0.76f, 0.5f, 0.84f, 0.42f, 0.92f) }
        LrIcon.LEVEL -> { line(0.1f, 0.62f, 0.9f, 0.38f); line(0.1f, 0.5f, 0.28f, 0.5f); line(0.72f, 0.5f, 0.9f, 0.5f); circle(0.5f, 0.5f, 0.06f, true) }
    }
}
