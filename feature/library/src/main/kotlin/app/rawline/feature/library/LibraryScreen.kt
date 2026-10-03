package app.rawline.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rawline.core.cache.FrameMonitor
import app.rawline.core.cache.ThumbStore
import app.rawline.core.data.IndexProgress
import app.rawline.core.model.EditedFilter
import app.rawline.core.model.FlagFilter
import app.rawline.core.model.LibraryFilter
import app.rawline.core.model.PasteScope
import app.rawline.core.model.Photo
import app.rawline.core.model.SortOrder
import app.rawline.core.ui.ChipButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the library can ask the app to do. */
class LibraryActions(
    val onPickFolder: () -> Unit,
    val onOpen: (Photo) -> Unit,
    val onSettings: () -> Unit,
    val onFilter: (LibraryFilter) -> Unit,
    val onRate: (List<Photo>, Int) -> Unit,
    val onFlag: (List<Photo>, Int) -> Unit,
    val onLabel: (List<Photo>, Int) -> Unit,
    val onCopyEdits: (Photo) -> Unit,
    val onPasteEdits: (List<Photo>, Set<PasteScope>) -> Unit,
    val onSyncEdits: (Photo, List<Photo>) -> Unit,
    val hasCopied: Boolean,
)

val LabelColors = listOf(Color.Transparent, Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA))

@Composable
fun LibraryScreen(
    photos: List<Photo>,
    allCount: Int,
    cameras: List<String>,
    filter: LibraryFilter,
    thumbs: ThumbStore,
    progress: IndexProgress,
    folderLabel: String?,
    actions: LibraryActions,
) {
    var columns by remember { mutableIntStateOf(4) }
    val gridState = rememberLazyGridState()
    val selected = remember { mutableStateOf(setOf<Long>()) }
    var showFilters by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) FrameMonitor.start() else FrameMonitor.stop("grid")
    }
    val selecting = selected.value.isNotEmpty()
    val sel = photos.filter { it.id in selected.value }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (selecting) "${sel.size} selected" else folderLabel ?: "No folder chosen", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val sub = when {
                    progress.running -> "Indexing ${progress.done} of ${progress.total}"
                    filter.isActive -> "${photos.size} of $allCount photos (filtered)"
                    else -> "${photos.size} photos"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selecting) TextButton(onClick = { selected.value = emptySet() }) { Text("Clear") }
            else {
                TextButton(onClick = { showFilters = !showFilters }) { Text(if (filter.isActive) "Filter *" else "Filter") }
                TextButton(onClick = actions.onPickFolder) { Text("Folder") }
                TextButton(onClick = actions.onSettings) { Text("Settings") }
            }
        }
        if (showFilters && !selecting) FilterBar(filter, cameras, actions.onFilter)
        if (selecting) SelectionBar(sel, actions, onPaste = { pasting = true }, onSelectAll = { selected.value = photos.map { it.id }.toSet() })

        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (folderLabel == null) "Tap Folder and choose where your photos are (for example DCIM)." else if (filter.isActive) "No photos match this filter." else "Nothing here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns), state = gridState,
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().pinchColumns(columns) { columns = it },
            ) {
                itemsIndexed(photos, key = { _, p -> p.id }, contentType = { _, _ -> "photo" }) { _, p ->
                    val isSel = p.id in selected.value
                    Thumb(
                        p, thumbs, isSel,
                        Modifier.aspectRatio(1f).combinedClickable(
                            onClick = { if (selecting) selected.value = if (isSel) selected.value - p.id else selected.value + p.id else actions.onOpen(p) },
                            onLongClick = { selected.value = if (isSel) selected.value - p.id else selected.value + p.id },
                        ),
                    )
                }
            }
        }
    }
    if (pasting) PasteDialog(onDismiss = { pasting = false }, onPaste = { scopes -> actions.onPasteEdits(sel, scopes); pasting = false })
}

@Composable
private fun FilterBar(f: LibraryFilter, cameras: List<String>, onChange: (LibraryFilter) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Rating", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium)
            (0..5).forEach { r -> ChipButton(if (r == 0) "Any" else "$r+", f.minRating == r, { onChange(f.copy(minRating = r)) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlagFilter.entries.forEach { ChipButton(it.label, f.flag == it, { onChange(f.copy(flag = it)) }) }
            EditedFilter.entries.forEach { ChipButton(it.label, f.edited == it, { onChange(f.copy(edited = it)) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SortOrder.entries.forEach { ChipButton(it.label, f.sort == it, { onChange(f.copy(sort = it)) }) }
            if (cameras.size > 1) {
                ChipButton("All cameras", f.camera == null, { onChange(f.copy(camera = null)) })
                cameras.forEach { c -> ChipButton(c, f.camera == c, { onChange(f.copy(camera = c)) }) }
            }
        }
    }
}

@Composable
private fun SelectionBar(sel: List<Photo>, a: LibraryActions, onPaste: () -> Unit, onSelectAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..5).forEach { r -> ChipButton(if (r == 0) "No stars" else "$r★", false, { a.onRate(sel, r) }) }
            ChipButton("Pick", false, { a.onFlag(sel, 1) }); ChipButton("Reject", false, { a.onFlag(sel, -1) }); ChipButton("Unflag", false, { a.onFlag(sel, 0) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("No label", "Red", "Yellow", "Green", "Blue", "Purple").forEachIndexed { i, n -> ChipButton(n, false, { a.onLabel(sel, i) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton("Select all", false, onSelectAll)
            if (sel.size == 1) ChipButton("Copy edits", false, { a.onCopyEdits(sel[0]) })
            if (a.hasCopied) ChipButton("Paste edits...", false, onPaste)
            if (sel.size > 1) ChipButton("Sync from first", false, { a.onSyncEdits(sel[0], sel.drop(1)) })
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onPaste: (Set<PasteScope>) -> Unit) {
    var chosen by remember { mutableStateOf(setOf(PasteScope.LIGHT, PasteScope.COLOUR, PasteScope.CURVE, PasteScope.MIXER, PasteScope.GRADING, PasteScope.EFFECTS, PasteScope.DETAIL)) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Paste which edits?") },
        text = {
            Column(Modifier.horizontalScrollNone()) {
                PasteScope.entries.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s in chosen, { on -> chosen = if (on) chosen + s else chosen - s })
                        Text(s.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPaste(chosen) }, enabled = chosen.isNotEmpty()) { Text("Paste") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Modifier.horizontalScrollNone() = this

@Composable
private fun Thumb(p: Photo, thumbs: ThumbStore, selected: Boolean, modifier: Modifier) {
    val bmp by produceState(thumbs.peek(p.id), p.id, p.indexed) {
        if (value == null && p.indexed) value = withContext(Dispatchers.IO) { thumbs.load(p.id) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = p.name + (if (p.rating > 0) ", ${p.rating} stars" else "") + (if (p.edited) ", edited" else "") }) {
        bmp?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (p.rating > 0) Text("★".repeat(p.rating), color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(3.dp))
        if (p.flag == 1) Text("⚑", color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(3.dp))
        if (p.flag == -1) Text("✕", color = Color(0xFFE57373), modifier = Modifier.align(Alignment.TopStart).padding(3.dp))
        if (p.label in 1..5) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(10.dp).background(LabelColors[p.label], CircleShape))
        if (p.edited) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(8.dp).background(Color(0xFF8AB4F8), CircleShape))
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x668AB4F8)))
    }
}

/** Two-finger pinch changes the column count (2 to 6) without blocking one-finger scrolling. */
private fun Modifier.pinchColumns(current: Int, set: (Int) -> Unit): Modifier = pointerInput(current) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var acc = 1f
        var cols = current
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.size >= 2 && ev.changes.all { it.pressed }) {
                acc *= ev.calculateZoom()
                ev.changes.forEach { it.consume() }
                if (acc > 1.3f && cols > 2) { cols--; acc = 1f; set(cols) }
                else if (acc < 0.77f && cols < 6) { cols++; acc = 1f; set(cols) }
            }
        } while (ev.changes.any { it.pressed })
    }
}
