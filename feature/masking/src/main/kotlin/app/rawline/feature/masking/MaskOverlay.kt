package app.rawline.feature.masking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.rawline.core.model.MaskType
import app.rawline.core.ui.Lr
import app.rawline.feature.editor.PhotoMapper
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val Shadow = Color(0x99000000)

/** On-photo drawing for the active mask part: gradient handles, the brush ring, and a short hint. No pointer input here: touches go to MaskingFeature.gestures. */
@Composable
internal fun BoxScope.MaskOverlay(f: MaskingFeature, mapper: PhotoMapper) {
    val ui = f.ui
    val density = LocalDensity.current.density
    SideEffect { ui.density = density }
    val c = if (ui.page == MaskPage.EDIT) f.selComp else null

    var previewOn by remember { mutableStateOf(false) }
    LaunchedEffect(ui.brushPreviewTick) { if (ui.brushPreviewTick > 0) { previewOn = true; delay(900); previewOn = false } }

    val hintKey = "${ui.selectedId}:${ui.selectedComp}:${c?.type}"
    var hintVisible by remember(hintKey) { mutableStateOf(true) }
    LaunchedEffect(hintKey) { hintVisible = true; delay(3500); hintVisible = false }

    val hint = when {
        ui.pickingObject -> "Tap the object in the photo"
        ui.pickingColour -> "Tap a colour in the photo"
        c == null || !hintVisible -> null
        c.type == MaskType.LINEAR -> "Drag the handles. Drag the line to move it."
        c.type == MaskType.RADIAL -> "Drag a handle to resize, the top grip to rotate, inside to move."
        MaskRules.isBrush(c) -> "Paint on the photo. Size and feather are in the tray."
        else -> null
    }

    if (c != null) {
        Canvas(Modifier.fillMaxSize()) {
            val asp = f.aspect()
            when (c.type) {
                MaskType.LINEAR -> drawLinear(f, mapper, c.params, ui.dragHandle)
                MaskType.RADIAL -> drawRadial(f, mapper, c.params, asp, ui.dragHandle)
                MaskType.BITMAP -> if (MaskRules.isBrush(c)) drawBrushRing(f, mapper, ui.brushCursor, previewOn)
                else -> {}
            }
        }
    }
    if (hint != null) {
        Text(
            hint, color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 16.dp, end = 16.dp).background(Lr.ValuePill, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// ---- drawing helpers: every line is drawn twice, a dark shadow first, so it reads on both bright and dark photos ----

private fun DrawScope.contrastLine(a: Offset, b: Offset, width: Float, color: Color = Color.White, dashed: Boolean = false) {
    val pe = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())) else null
    drawLine(Shadow, a, b, width + 2.dp.toPx(), StrokeCap.Round, pe)
    drawLine(color, a, b, width, StrokeCap.Round, pe)
}

private fun DrawScope.handle(p: Offset, active: Boolean, filled: Boolean = true) {
    if (active) drawCircle(Lr.AccentSoft, 26.dp.toPx(), p)
    val r = 10.dp.toPx()
    drawCircle(Shadow, r + 2.dp.toPx(), p)
    val col = if (active) Lr.Focus else Color.White
    if (filled) drawCircle(col, r, p) else { drawCircle(Color(0x66000000), r, p); drawCircle(col, r - 1.dp.toPx(), p, style = Stroke(2.5.dp.toPx())) }
}

private fun V2.o() = Offset(x, y)

private fun DrawScope.drawLinear(f: MaskingFeature, mapper: PhotoMapper, params: List<Float>, drag: Int) {
    val q = LinearGeom.padded(params)
    val a = f.viewOf(mapper, V2(q[0], q[1])); val b = f.viewOf(mapper, V2(q[2], q[3]))
    var u = b - a
    val l = u.len()
    u = if (l < 1f) V2(0f, 1f) else u * (1f / l)
    val perp = V2(-u.y, u.x)
    val far = max(size.width, size.height)
    val mid = V2((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    val line = if (drag == 2) Lr.Focus else Color.White
    // start line (dashed, effect is 0 here) and end line (solid, effect is full here)
    contrastLine((a + perp * far).o(), (a + perp * -far).o(), 1.5.dp.toPx(), line, dashed = true)
    contrastLine((b + perp * far).o(), (b + perp * -far).o(), 1.5.dp.toPx(), line)
    contrastLine(a.o(), b.o(), 1.5.dp.toPx(), line)
    // arrow head at the end handle, pointing along the effect
    val ah = 12.dp.toPx()
    val tip = b + u * (14.dp.toPx())
    val back = tip - u * ah
    contrastLine(tip.o(), (back + perp * (ah * 0.6f)).o(), 1.5.dp.toPx(), line)
    contrastLine(tip.o(), (back - perp * (ah * 0.6f)).o(), 1.5.dp.toPx(), line)
    handle(a.o(), drag == 0, filled = false)
    handle(b.o(), drag == 1, filled = true)
    handle(mid.o(), drag == 2, filled = false)
}

private fun DrawScope.drawRadial(f: MaskingFeature, mapper: PhotoMapper, params: List<Float>, asp: Float, drag: Int) {
    val q = RadialGeom.padded(params)
    val v = RadialGeom.viewHandles(q, asp, 40.dp.toPx()) { f.viewOf(mapper, it) }
    fun ellipse(scale: Float): Path {
        val p = Path()
        val n = 72
        for (i in 0..n) {
            val t = 2.0 * PI * i / n
            val pt = f.viewOf(mapper, RadialGeom.framePoint(params, asp, (q[2] * scale * cos(t)).toFloat(), (q[3] * scale * sin(t)).toFloat()))
            if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        p.close()
        return p
    }
    val body = if (drag == RadialPart.BODY.ordinal) Lr.Focus else Color.White
    val outer = ellipse(1f)
    drawPath(outer, Shadow, style = Stroke(3.5.dp.toPx()))
    drawPath(outer, body, style = Stroke(1.5.dp.toPx()))
    // the soft edge begins at (1 - feather) of the radius
    val inner = 1f - q[5].coerceIn(0.01f, 1f)
    if (inner > 0.05f) drawPath(ellipse(inner), Color(0xCCFFFFFF), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))))
    // stem and rotate grip
    contrastLine(v.yNeg.o(), v.rotate.o(), 1.5.dp.toPx(), Color.White)
    handle(v.xPos.o(), drag == RadialPart.X_POS.ordinal); handle(v.xNeg.o(), drag == RadialPart.X_NEG.ordinal)
    handle(v.yPos.o(), drag == RadialPart.Y_POS.ordinal); handle(v.yNeg.o(), drag == RadialPart.Y_NEG.ordinal)
    handle(v.rotate.o(), drag == RadialPart.ROTATE.ordinal, filled = false)
    val ctr = v.center.o()
    val arm = 9.dp.toPx()
    val cc = if (drag == RadialPart.CENTER.ordinal) Lr.Focus else Color.White
    contrastLine(ctr - Offset(arm, 0f), ctr + Offset(arm, 0f), 1.5.dp.toPx(), cc)
    contrastLine(ctr - Offset(0f, arm), ctr + Offset(0f, arm), 1.5.dp.toPx(), cc)
}

private fun DrawScope.drawBrushRing(f: MaskingFeature, mapper: PhotoMapper, cursor: Offset?, preview: Boolean) {
    if (cursor == null && !preview) return
    val ui = f.ui
    val g = f.cropRect()
    val p0 = f.viewOf(mapper, V2(g.cropX, g.cropY)); val p1 = f.viewOf(mapper, V2(g.cropX, g.cropY + g.cropH))
    val r = BrushMath.ringRadiusPx(ui.brushSize, (p1 - p0).len(), g.cropH).coerceAtLeast(2f)
    val centre = cursor ?: Offset(size.width / 2f, size.height / 2f)
    val col = if (ui.brushErase) Lr.Error else Color.White
    drawCircle(col.copy(alpha = 0.12f), r, centre)
    drawCircle(Shadow, r, centre, style = Stroke(3.5.dp.toPx()))
    drawCircle(col, r, centre, style = Stroke(1.5.dp.toPx()))
    // the soft edge reaches from (1 - feather) of the radius inwards
    val fe = ui.brushFeather
    if (fe > 0.05f) drawCircle(col.copy(alpha = 0.85f), max(r * (1f - fe), 2f), centre, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))))
}
