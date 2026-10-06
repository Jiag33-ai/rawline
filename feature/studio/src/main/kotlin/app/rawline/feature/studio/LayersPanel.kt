package app.rawline.feature.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.rawline.core.studio.model.BlendMode
import app.rawline.core.studio.render.StudioSession
import app.rawline.core.studio.render.StudioState
import app.rawline.core.studio.render.Thumb
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.LrDropdown
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconButton
import app.rawline.core.ui.LrMenuItem
import app.rawline.core.ui.LrTextButton
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.TouchChip
import kotlin.math.roundToInt

/**
 * The layers tray (spec 5): top layer first, row 56 dp (thumbnail 40, name, eye, lock, blend chip, opacity chip), reorder with 48 dp up and down buttons that also exist as
 * TalkBack actions on each row, add, duplicate, delete (asks first for a layer that holds pixels). Messages from the session (the 10 layer cap, memory) show as a toast on the canvas.
 */
@Composable
fun LayersPanel(state: StudioState, session: StudioSession, onAddPhoto: () -> Unit, modifier: Modifier = Modifier) {
    val rows = remember(state.document.layers, state.activeId, state.nonBlank) { LayerRows.of(state.document.layers, state.activeId, state.nonBlank) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val active = rows.firstOrNull { it.active }
    Column(modifier.background(Lr.Surface1)) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Layers", style = MaterialTheme.typography.titleSmall, color = Lr.TextPrimary, modifier = Modifier.weight(1f))
            LrTextButton(onClick = onAddPhoto) { Text("Add photo") }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(rows, key = { it.id }) { row ->
                LayerRowView(row, state.thumbs[row.id], session)
            }
        }
        Row(Modifier.fillMaxWidth().height(LrDim.touch).border(1.dp, Lr.BorderSubtle), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            LrIconButton(LrIcon.ADD, "Add layer", { session.addLayer() }, hit = LrDim.touch)
            LrIconButton(LrIcon.COPY, "Duplicate layer", { active?.let { session.duplicateLayer(it.id) } }, hit = LrDim.touch, enabled = active != null)
            LrIconButton(LrIcon.TRASH, "Delete layer", { active?.let { if (it.hasPixels) confirmDelete = it.id else session.deleteLayer(it.id) } }, hit = LrDim.touch, enabled = active != null && rows.size > 1)
            Box(Modifier.size(LrDim.touch).clickable(enabled = active?.canMoveUp == true) { active?.let { session.moveLayer(it.id, up = true) } }.semantics { contentDescription = "Move layer up" }, contentAlignment = Alignment.Center) {
                StudioIconView(StudioIcon.UP, if (active?.canMoveUp == true) Lr.IconPrimary else Lr.TextDisabled)
            }
            Box(Modifier.size(LrDim.touch).clickable(enabled = active?.canMoveDown == true) { active?.let { session.moveLayer(it.id, up = false) } }.semantics { contentDescription = "Move layer down" }, contentAlignment = Alignment.Center) {
                StudioIconView(StudioIcon.DOWN, if (active?.canMoveDown == true) Lr.IconPrimary else Lr.TextDisabled)
            }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this layer?") },
            text = { Text("It has painting on it. Undo brings it back.") },
            confirmButton = { LrTextButton(onClick = { confirmDelete = null; session.deleteLayer(id) }) { Text("Delete") } },
            dismissButton = { LrTextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LayerRowView(row: LayerRow, thumb: Thumb?, session: StudioSession) {
    var blendMenu by remember { mutableStateOf(false) }
    var opacityOpen by remember(row.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(if (row.active) Lr.SurfaceSelected else Color.Transparent)
            .clickable { session.selectLayer(row.id) }
            .semantics(mergeDescendants = false) {
                contentDescription = "${row.name}${if (row.visible) "" else ", hidden"}${if (row.locked) ", locked" else ""}, ${LayerRows.blendName(row.blend)}, opacity ${row.opacity} percent"
                selected = row.active
                customActions = buildList {
                    if (row.canMoveUp) add(CustomAccessibilityAction("Move layer up") { session.moveLayer(row.id, up = true); true })
                    if (row.canMoveDown) add(CustomAccessibilityAction("Move layer down") { session.moveLayer(row.id, up = false); true })
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(40.dp).background(if (row.active) Lr.Accent else Color.Transparent))
            Spacer(Modifier.width(9.dp))
            ThumbView(thumb, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, color = if (row.visible) Lr.TextPrimary else Lr.TextMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        SmallChip(LayerRows.blendName(row.blend), "Blend mode ${LayerRows.blendName(row.blend)}", false) { blendMenu = true }
                        LrDropdown(blendMenu, { blendMenu = false }, width = 150.dp) {
                            for (m in BlendMode.entries) LrMenuItem(LayerRows.blendName(m), { blendMenu = false; session.setBlend(row.id, m) })
                        }
                    }
                    SmallChip("${row.opacity}%", "Opacity ${row.opacity} percent", opacityOpen) { opacityOpen = !opacityOpen }
                }
            }
            Box(Modifier.size(LrDim.touch).clickable { session.setVisible(row.id, !row.visible) }.semantics { contentDescription = if (row.visible) "Hide ${row.name}" else "Show ${row.name}" }, contentAlignment = Alignment.Center) {
                StudioIconView(if (row.visible) StudioIcon.EYE else StudioIcon.EYE_OFF, if (row.visible) Lr.IconPrimary else Lr.TextMuted, size = 22.dp)
            }
            LrIconButton(if (row.locked) LrIcon.LOCK else LrIcon.UNLOCK, if (row.locked) "Unlock ${row.name}" else "Lock ${row.name}", { session.setLocked(row.id, !row.locked) }, hit = LrDim.touch, tint = if (row.locked) Lr.Accent else Lr.IconSecondary)
        }
        if (opacityOpen) {
            // slider on tap: one history entry for the whole drag (preview while it moves, commit on release)
            RawSlider("Opacity", row.opacity.toFloat(), 0f..100f, default = 100f, unit = "%", onChange = { session.previewOpacity(row.id, it.roundToInt()) }, onCommit = { session.commitPreview() })
        }
    }
}

@Composable
private fun SmallChip(text: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.minimumInteractiveComponentSize().semantics { contentDescription = description }.clickable(onClick = onClick)   // 48 dp target, the visible chip below stays 24 dp
            .height(24.dp).clip(RoundedCornerShape(4.dp)).background(if (selected) Lr.Surface3 else Color.Transparent).border(1.dp, if (selected) Lr.BorderStrong else Lr.BorderDefault, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = Lr.TextSecondary) }
}

/** The layer's thumbnail over a small checkerboard (so transparent areas read as transparent). The bitmap is made once per thumbnail object (96 px at most). */
@Composable
private fun ThumbView(thumb: Thumb?, modifier: Modifier) {
    val image: ImageBitmap? = remember(thumb) { thumb?.let { Bitmap.createBitmap(it.argb, it.w, it.h, Bitmap.Config.ARGB_8888).asImageBitmap() } }
    Canvas(modifier.clip(RoundedCornerShape(2.dp)).border(1.dp, Lr.BorderDefault, RoundedCornerShape(2.dp))) {
        val cell = 5.dp.toPx()
        var y = 0f; var j = 0
        while (y < size.height) {
            var x = 0f; var i = 0
            while (x < size.width) { drawRect(if ((i + j) % 2 == 0) Color(0xFF4A4A4A) else Color(0xFF353535), Offset(x, y), Size(cell, cell)); x += cell; i++ }
            y += cell; j++
        }
        if (image != null) {
            // letterbox: the thumbnail keeps the layer's aspect
            val s = minOf(size.width / image.width, size.height / image.height)
            val dw = (image.width * s).roundToInt(); val dh = (image.height * s).roundToInt()
            drawImage(image, dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()), dstSize = IntSize(dw, dh), filterQuality = FilterQuality.Low)
        }
    }
}
