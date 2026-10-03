package app.rawline.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
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
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView

/** A place photos come from: the camera roll, another album, imported files or a folder. */
class SourceItem(val key: String, val label: String, val count: Int, val icon: LrIcon)

/** Everything the library can ask the app to do. */
class LibraryActions(
    val onOpen: (Photo) -> Unit,
    val onFilter: (LibraryFilter) -> Unit,
    val onRate: (List<Photo>, Int) -> Unit,
    val onFlag: (List<Photo>, Int) -> Unit,
    val onLabel: (List<Photo>, Int) -> Unit,
    val onCopyEdits: (Photo) -> Unit,
    val onPasteEdits: (List<Photo>, Set<PasteScope>) -> Unit,
    val onSyncEdits: (Photo, List<Photo>) -> Unit,
    val onExport: (List<Photo>) -> Unit,
    val onSelectSource: (String) -> Unit,
    val onImportFiles: () -> Unit,
    val onAddFolder: () -> Unit,
    val onRequestPermission: () -> Unit,
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
    sources: List<SourceItem>,
    selectedSource: String,
    permissionGranted: Boolean,
    actions: LibraryActions,
) {
    var columns by remember { mutableIntStateOf(3) }
    val gridState = rememberLazyGridState()
    val selected = remember { mutableStateOf(setOf<Long>()) }
    var showFilters by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) FrameMonitor.start() else FrameMonitor.stop("grid")
    }
    val selecting = selected.value.isNotEmpty()
    val sel = photos.filter { it.id in selected.value }
    val current = sources.firstOrNull { it.key == selectedSource }

    Column(Modifier.fillMaxSize().background(Lr.Black)) {
        // ---- top bar: source picker on the left, tools on the right ----
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                IconTap(LrIcon.CLOSE, "Clear selection") { selected.value = emptySet() }
                Text("${sel.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconTap(LrIcon.SELECT, "Select all") { selected.value = photos.map { it.id }.toSet() }
            } else {
                Row(Modifier.weight(1f).clickable { sourceMenu = true }.padding(start = 12.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(current?.label ?: "Photos", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        val sub = when {
                            progress.running -> "Reading ${progress.done} of ${progress.total}"
                            filter.isActive -> "${photos.size} of $allCount (filtered)"
                            else -> "${photos.size} photos"
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = Lr.TextDim)
                    }
                    LrIconView(LrIcon.CHEVRON_DOWN, Lr.Text, Modifier.padding(start = 6.dp), 20.dp)
                    DropdownMenu(sourceMenu, { sourceMenu = false }) {
                        sources.forEach { s ->
                            DropdownMenuItem(
                                text = { Text("${s.label}   ${s.count}", color = if (s.key == selectedSource) Lr.Accent else Lr.Text) },
                                leadingIcon = { LrIconView(s.icon, if (s.key == selectedSource) Lr.Accent else Lr.TextDim, size = 22.dp) },
                                onClick = { sourceMenu = false; actions.onSelectSource(s.key) },
                            )
                        }
                    }
                }
                IconTap(LrIcon.FILTER, "Filter", tint = if (filter.isActive) Lr.Accent else Lr.Text) { showFilters = !showFilters }
                Box {
                    IconTap(LrIcon.MORE, "More") { moreMenu = true }
                    DropdownMenu(moreMenu, { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Newest first") }, onClick = { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.NEWEST)) })
                        DropdownMenuItem(text = { Text("Oldest first") }, onClick = { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.OLDEST)) })
                        DropdownMenuItem(text = { Text("By name") }, onClick = { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.NAME)) })
                        DropdownMenuItem(text = { Text("By rating") }, onClick = { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.RATING)) })
                    }
                }
            }
        }
        if (showFilters && !selecting) FilterBar(filter, cameras, actions.onFilter)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !permissionGranted && selectedSource.startsWith("device:") && photos.isEmpty() -> PermissionPrompt(actions.onRequestPermission, actions.onImportFiles)
                photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (filter.isActive) "No photos match this filter." else "Nothing here yet. Tap + to import photos.", color = Lr.TextDim, modifier = Modifier.padding(32.dp))
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns), state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
                    modifier = Modifier.fillMaxSize().pinchColumns(columns) { columns = it },
                ) {
                    itemsIndexed(photos, key = { _, p -> p.id }, contentType = { _, _ -> "photo" }) { _, p ->
                        val isSel = p.id in selected.value
                        Thumb(
                            p, thumbs, isSel, selecting,
                            Modifier.aspectRatio(1f).combinedClickable(
                                onClick = { if (selecting) selected.value = if (isSel) selected.value - p.id else selected.value + p.id else actions.onOpen(p) },
                                onLongClick = { selected.value = if (isSel) selected.value - p.id else selected.value + p.id },
                            ),
                        )
                    }
                }
            }
            if (!selecting) {
                Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Lr.Accent).clickable { addMenu = true }.semantics { contentDescription = "Add photos" }, contentAlignment = Alignment.Center) {
                        LrIconView(LrIcon.ADD, Color.White, size = 28.dp, strokeWidth = 2.4f)
                    }
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Import from files") }, leadingIcon = { LrIconView(LrIcon.IMPORT, Lr.Text, size = 22.dp) }, onClick = { addMenu = false; actions.onImportFiles() })
                        DropdownMenuItem(text = { Text("Add a folder") }, leadingIcon = { LrIconView(LrIcon.FOLDER, Lr.Text, size = 22.dp) }, onClick = { addMenu = false; actions.onAddFolder() })
                    }
                }
            }
        }
        if (selecting) SelectionBar(sel, actions, onPaste = { pasting = true })
    }
    if (pasting) PasteDialog(onDismiss = { pasting = false }, onPaste = { scopes -> actions.onPasteEdits(sel, scopes); pasting = false })
}

@Composable
private fun IconTap(icon: LrIcon, description: String, tint: Color = Lr.Text, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) { LrIconView(icon, tint, size = 24.dp) }
}

@Composable
private fun PermissionPrompt(onAllow: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LrIconView(LrIcon.CAMERA, Lr.TextDim, size = 56.dp)
        Spacer(Modifier.height(16.dp))
        Text("Show your camera roll", style = MaterialTheme.typography.titleMedium)
        Text("Allow access to photos so Rawline can list what is on your phone.", color = Lr.TextDim, modifier = Modifier.padding(vertical = 8.dp))
        ChipButton("Allow access", true, onAllow)
        Spacer(Modifier.height(8.dp))
        ChipButton("Import files instead", false, onImport)
    }
}

@Composable
private fun FilterBar(f: LibraryFilter, cameras: List<String>, onChange: (LibraryFilter) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lr.Panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rating", style = MaterialTheme.typography.labelMedium, color = Lr.TextDim)
            (0..5).forEach { r -> ChipButton(if (r == 0) "Any" else "$r+", f.minRating == r, { onChange(f.copy(minRating = r)) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlagFilter.entries.forEach { ChipButton(it.label, f.flag == it, { onChange(f.copy(flag = it)) }) }
            EditedFilter.entries.forEach { ChipButton(it.label, f.edited == it, { onChange(f.copy(edited = it)) }) }
        }
        if (cameras.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton("All cameras", f.camera == null, { onChange(f.copy(camera = null)) })
            cameras.forEach { c -> ChipButton(c, f.camera == c, { onChange(f.copy(camera = c)) }) }
        }
    }
}

@Composable
private fun SelectionBar(sel: List<Photo>, a: LibraryActions, onPaste: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lr.Panel).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton("Add to export queue", true, { a.onExport(sel) })
            (1..5).forEach { r -> ChipButton("$r ★", false, { a.onRate(sel, r) }) }
            ChipButton("No stars", false, { a.onRate(sel, 0) })
            ChipButton("Pick", false, { a.onFlag(sel, 1) }); ChipButton("Reject", false, { a.onFlag(sel, -1) }); ChipButton("Unflag", false, { a.onFlag(sel, 0) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("No label", "Red", "Yellow", "Green", "Blue", "Purple").forEachIndexed { i, n -> ChipButton(n, false, { a.onLabel(sel, i) }) }
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
            Column {
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

@Composable
private fun Thumb(p: Photo, thumbs: ThumbStore, selected: Boolean, selecting: Boolean, modifier: Modifier) {
    val bmp by produceState(thumbs.peek(p.id), p.id) { if (value == null) value = thumbs.obtain(p) }
    Box(modifier.background(Lr.Surface).semantics { contentDescription = p.name + (if (p.rating > 0) ", ${p.rating} stars" else "") + (if (p.edited) ", edited" else "") }) {
        bmp?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (p.rating > 0) Text("★".repeat(p.rating), color = Lr.Star, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(3.dp))
        if (p.flag == 1) Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { LrIconView(LrIcon.FLAG_FILLED, Color.White, size = 14.dp) }
        if (p.flag == -1) Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { LrIconView(LrIcon.REJECT, Color(0xFFE57373), size = 14.dp) }
        if (p.label in 1..5) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(9.dp).background(LabelColors[p.label], CircleShape))
        if (p.edited) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { LrIconView(LrIcon.EDIT, Color.White, size = 14.dp) }
        if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(if (selected) Lr.Accent else Color(0x66000000))) {
            if (selected) LrIconView(LrIcon.CHECK, Color.White, size = 20.dp)
        }
        if (selected) Box(Modifier.fillMaxSize().background(Lr.AccentDim))
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
