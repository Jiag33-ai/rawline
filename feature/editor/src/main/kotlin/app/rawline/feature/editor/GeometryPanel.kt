package app.rawline.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrOutlineButton
import app.rawline.core.model.Geometry
import app.rawline.core.ui.ChipButton
import app.rawline.core.ui.LrTabs
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.SectionTitle
import kotlin.math.abs
import kotlin.math.hypot

/** Aspect presets as width / height. null = free, 0 = original. */
private val Aspects = listOf(
    "Original" to 0f, "Free" to -1f, "1:1" to 1f, "4:5" to 0.8f, "5:4" to 1.25f, "3:2" to 1.5f, "2:3" to 2f / 3f, "16:9" to 16f / 9f, "9:16" to 9f / 16f, "3:1" to 3f,
    "10:16" to 10f / 16f, "8.5:11" to 8.5f / 11f, "5:7" to 5f / 7f, "3:4" to 0.75f, "1:2" to 0.5f,
)

/** Crop options tray: Aspect | Geometry, open on the tray surface. */
@Composable
fun GeometryPanel(state: EditorState, imageAspect: Float, onAutoLevel: (() -> Unit)?, onAutoPerspective: (() -> Unit)?) {
    val g = state.recipe.geometry
    var sub by remember { mutableStateOf("aspect") }
    var ratios by remember { mutableStateOf(false) }
    fun upd(label: String, f: (Geometry) -> Geometry) = state.edit(label) { it.copy(geometry = f(it.geometry)) }
    fun pick(name: String) { val a = Aspects.firstOrNull { it.first.equals(name, true) }?.second ?: -1f; upd("Crop $name") { gg -> gg.copy(aspect = name).let { fitAspect(it, a, imageAspect) } } }
    Column {
        LrTabs(listOf("aspect" to "Aspect", "geometry" to "Geometry"), sub, { sub = it })
        if (sub == "aspect") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AspectOptionTile(LrIcon.ORIGINAL, "Original", g.aspect.equals("original", true), { pick("Original") })
                AspectOptionTile(LrIcon.FREE_CROP, "Free", g.aspect.equals("free", true), { pick("Free") })
                Box {
                    AspectOptionTile(LrIcon.RATIOS, "Ratios", g.aspect in listOf("10:16", "9:16", "8.5:11", "5:7", "4:5", "3:4", "2:3", "1:2", "1:1"), { ratios = true })
                    RatioPopover(ratios, { ratios = false }, g.aspect, { pick(it) })
                }
                AspectOptionTile(LrIcon.ORIGINAL, "16:9", g.aspect == "16:9", { pick("16:9") })
                AspectOptionTile(LrIcon.ORIGINAL, "3:2", g.aspect == "3:2", { pick("3:2") })
                AspectOptionTile(LrIcon.RESET, "Reset", false, { upd("Reset crop") { it.copy(cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f, aspect = "original") } })
            }
        } else {
            PanelColumn {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onAutoLevel != null) LrOutlineButton("Auto level", onAutoLevel, small = true)
                    if (onAutoPerspective != null) LrOutlineButton("Auto perspective", onAutoPerspective, small = true)
                }
                Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LrOutlineButton("Flip H", { upd("Flip horizontal") { it.copy(flipH = !it.flipH) } }, small = true, active = g.flipH)
                    LrOutlineButton("Flip V", { upd("Flip vertical") { it.copy(flipV = !it.flipV) } }, small = true, active = g.flipV)
                }
                RawSlider("Vertical", g.keystoneV, -100f..100f, 0f,
                    onChange = { v -> state.live { it.copy(geometry = it.geometry.copy(keystoneV = v)) } }, onCommit = { state.commit("Vertical perspective") })
                RawSlider("Horizontal", g.keystoneH, -100f..100f, 0f,
                    onChange = { v -> state.live { it.copy(geometry = it.geometry.copy(keystoneH = v)) } }, onCommit = { state.commit("Horizontal perspective") })
            }
        }
    }
}

/** Centre-crops the current crop to a target aspect (width / height in image pixels). */
fun fitAspect(g: Geometry, aspect: Float, imageAspect: Float): Geometry {
    if (aspect < 0f) return g
    val target = if (aspect == 0f) imageAspect else aspect
    // crop in normalised units: (w * imageAspect) / h = target  (imageAspect = base width / base height)
    val cx = g.cropX + g.cropW / 2f; val cy = g.cropY + g.cropH / 2f
    var w = g.cropW; var h = g.cropH
    val cur = w * imageAspect / h
    if (cur > target) w = h * target / imageAspect else h = w * imageAspect / target
    val s = minOf(1f / w, 1f / h, 1f)
    w *= s.coerceAtMost(1f); h *= s.coerceAtMost(1f)
    val x = (cx - w / 2f).coerceIn(0f, 1f - w); val y = (cy - h / 2f).coerceIn(0f, 1f - h)
    return g.copy(cropX = x, cropY = y, cropW = w, cropH = h)
}

/** Draws and edits the crop rectangle over the displayed (uncropped) image. fit = left, top, width, height in px. */
@Composable
fun CropOverlay(state: EditorState, fit: FloatArray, imageAspect: Float, modifier: Modifier = Modifier) {
    val g = state.recipe.geometry
    var active by remember { mutableStateOf(-1) }   // 0..3 corners (tl, tr, bl, br), 4 move
    Canvas(
        modifier.fillMaxSize().pointerInput(fit.toList(), g.aspect) {
            detectDragGestures(
                onDragStart = { p ->
                    val geo = state.recipe.geometry
                    val l = fit[0] + geo.cropX * fit[2]; val t = fit[1] + geo.cropY * fit[3]
                    val r = l + geo.cropW * fit[2]; val b = t + geo.cropH * fit[3]
                    val corners = listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))
                    val near = corners.indexOfFirst { hypot(it.x - p.x, it.y - p.y) < 64f }
                    active = if (near >= 0) near else if (p.x in l..r && p.y in t..b) 4 else -1
                },
                onDragEnd = { active = -1; state.commit("Crop") },
                onDragCancel = { active = -1; state.commit("Crop") },
            ) { change, drag ->
                if (active < 0) return@detectDragGestures
                change.consume()
                val dx = drag.x / fit[2]; val dy = drag.y / fit[3]
                state.live { r ->
                    val geo = r.geometry
                    var x0 = geo.cropX; var y0 = geo.cropY; var x1 = geo.cropX + geo.cropW; var y1 = geo.cropY + geo.cropH
                    when (active) {
                        0 -> { x0 += dx; y0 += dy }
                        1 -> { x1 += dx; y0 += dy }
                        2 -> { x0 += dx; y1 += dy }
                        3 -> { x1 += dx; y1 += dy }
                        4 -> {
                            val w = x1 - x0; val h = y1 - y0
                            x0 = (x0 + dx).coerceIn(0f, 1f - w); y0 = (y0 + dy).coerceIn(0f, 1f - h); x1 = x0 + w; y1 = y0 + h
                        }
                    }
                    x0 = x0.coerceIn(0f, 0.95f); y0 = y0.coerceIn(0f, 0.95f); x1 = x1.coerceIn(0.05f, 1f); y1 = y1.coerceIn(0.05f, 1f)
                    var w = (x1 - x0).coerceAtLeast(0.05f); var h = (y1 - y0).coerceAtLeast(0.05f)
                    val a = Aspects.firstOrNull { it.first.equals(geo.aspect, ignoreCase = true) }?.second ?: -1f
                    if (a != -1f && active != 4) {
                        val target = if (a == 0f) imageAspect else a
                        h = w * imageAspect / target
                        if (h > 1f) { h = 1f; w = h * target / imageAspect }
                        // anchor opposite corner
                        if (active == 0 || active == 2) x0 = x1 - w else x1 = x0 + w
                        if (active == 0 || active == 1) y0 = y1 - h else y1 = y0 + h
                        x0 = x0.coerceIn(0f, 1f - w); y0 = y0.coerceIn(0f, 1f - h)
                        x1 = x0 + w; y1 = y0 + h
                    }
                    r.copy(geometry = geo.copy(cropX = x0, cropY = y0, cropW = x1 - x0, cropH = y1 - y0))
                }
            }
        },
    ) {
        val l = fit[0] + g.cropX * fit[2]; val t = fit[1] + g.cropY * fit[3]
        val w = g.cropW * fit[2]; val h = g.cropH * fit[3]
        val shade = Color(0x99000000)
        drawRect(shade, Offset(fit[0], fit[1]), Size(fit[2], t - fit[1]))
        drawRect(shade, Offset(fit[0], t + h), Size(fit[2], fit[1] + fit[3] - t - h))
        drawRect(shade, Offset(fit[0], t), Size(l - fit[0], h))
        drawRect(shade, Offset(l + w, t), Size(fit[0] + fit[2] - l - w, h))
        drawRect(Color.White, Offset(l, t), Size(w, h), style = Stroke(1.dp.toPx()))
        for (i in 1..2) {
            drawLine(Color(0x33FFFFFF), Offset(l + w * i / 3, t), Offset(l + w * i / 3, t + h), 1f)
            drawLine(Color(0x33FFFFFF), Offset(l, t + h * i / 3), Offset(l + w, t + h * i / 3), 1f)
        }
        // clean white handles: 16 dp corners, 24 dp side middles, 2 dp
        val sw = 2.dp.toPx(); val cl = 16.dp.toPx(); val sl = 24.dp.toPx()
        fun seg(a: Offset, b: Offset) = drawLine(Color.White, a, b, sw, androidx.compose.ui.graphics.StrokeCap.Square)
        seg(Offset(l, t), Offset(l + cl, t)); seg(Offset(l, t), Offset(l, t + cl))
        seg(Offset(l + w, t), Offset(l + w - cl, t)); seg(Offset(l + w, t), Offset(l + w, t + cl))
        seg(Offset(l, t + h), Offset(l + cl, t + h)); seg(Offset(l, t + h), Offset(l, t + h - cl))
        seg(Offset(l + w, t + h), Offset(l + w - cl, t + h)); seg(Offset(l + w, t + h), Offset(l + w, t + h - cl))
        seg(Offset(l + w / 2 - sl / 2, t), Offset(l + w / 2 + sl / 2, t)); seg(Offset(l + w / 2 - sl / 2, t + h), Offset(l + w / 2 + sl / 2, t + h))
        seg(Offset(l, t + h / 2 - sl / 2), Offset(l, t + h / 2 + sl / 2)); seg(Offset(l + w, t + h / 2 - sl / 2), Offset(l + w, t + h / 2 + sl / 2))
    }
}
