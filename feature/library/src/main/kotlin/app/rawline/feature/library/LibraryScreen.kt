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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import app.rawline.core.ui.LrCheckbox as Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton as TextButton
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.LrDropdown
import app.rawline.core.ui.LrMenuItem
import app.rawline.core.ui.LrIconButton
import app.rawline.core.ui.EmptyState
import app.rawline.core.ui.PrimaryButton
import app.rawline.core.ui.SecondaryButton
import app.rawline.core.model.EditedFilter
import app.rawline.core.model.FlagFilter
import app.rawline.core.model.Kind
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
    val onRequestAllFiles: () -> Unit,
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
    allFilesGranted: Boolean,
    actions: LibraryActions,
) {
    var columns by remember { mutableIntStateOf(5) }
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
    androidx.activity.compose.BackHandler(enabled = selecting) { selected.value = emptySet() }
    val sel = photos.filter { it.id in selected.value }
    val current = sources.firstOrNull { it.key == selectedSource }

    Column(Modifier.fillMaxSize().background(Lr.Black)) {
        // ---- top bar: source picker on the left, tools on the right ----
        Row(Modifier.fillMaxWidth().height(LrDim.libraryHeader).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                IconTap(LrIcon.CLOSE, "Clear selection") { selected.value = emptySet() }
                Text("${sel.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconTap(LrIcon.SELECT, "Select all") { selected.value = photos.map { it.id }.toSet() }
            } else {
                Row(Modifier.weight(1f).clickable { sourceMenu = true }.padding(start = 10.dp).height(LrDim.libraryHeader), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(current?.label ?: "Photos", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        val sub = when {
                            progress.running -> "Reading ${progress.done} of ${progress.total}"
                            filter.isActive -> "${photos.size} of $allCount (filtered)"
                            else -> "${photos.size} photos"
                        }
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = Lr.TextMuted)
                    }
                    LrIconView(LrIcon.CHEVRON_DOWN, Lr.IconSecondary, Modifier.padding(start = 6.dp), 18.dp)
                    LrDropdown(sourceMenu, { sourceMenu = false }, width = 200.dp) {
                        sources.forEach { s ->
                            LrMenuItem("${s.label}   ${s.count}", { sourceMenu = false; actions.onSelectSource(s.key) }, s.icon)
                        }
                    }
                }
                Box {
                    IconTap(LrIcon.ADD, "Add photos") { addMenu = true }
                    LrDropdown(addMenu, { addMenu = false }) {
                        LrMenuItem("Import from files", { addMenu = false; actions.onImportFiles() }, LrIcon.IMPORT)
                        LrMenuItem("Add a folder", { addMenu = false; actions.onAddFolder() }, LrIcon.FOLDER)
                    }
                }
                IconTap(LrIcon.FILTER, "Filter", tint = if (filter.isActive) Lr.Accent else Lr.IconPrimary) { showFilters = !showFilters }
                Box {
                    IconTap(LrIcon.SORT, "Sort") { moreMenu = true }
                    LrDropdown(moreMenu, { moreMenu = false }) {
                        LrMenuItem("Newest first", { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.NEWEST)) })
                        LrMenuItem("Oldest first", { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.OLDEST)) })
                        LrMenuItem("By name", { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.NAME)) })
                        LrMenuItem("By rating", { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.RATING)) })
                    }
                }
            }
        }
        if (showFilters && !selecting) FilterBar(filter, cameras, actions.onFilter)

        if (!allFilesGranted && permissionGranted && !selecting) {
            Row(Modifier.fillMaxWidth().background(Lr.AccentSoft).clickable { actions.onRequestAllFiles() }.padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(44.dp).background(Lr.Accent))
                Text("RAW files such as RW2 are hidden by Android until you allow all files access.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
                Text("Allow", color = Lr.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 14.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !permissionGranted && selectedSource.startsWith("device:") && photos.isEmpty() -> PermissionPrompt(actions.onRequestPermission, actions.onImportFiles)
                photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(LrIcon.PHOTOS, if (filter.isActive) "No photos match" else "Nothing here yet", if (filter.isActive) "Change or clear the filter to see more." else "Tap + to import photos or a folder.")
                }
                else -> {
                val rows = remember(photos, filter.sort) { gridRows(photos, filter.sort) }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns), state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize().pinchColumns(columns) { columns = it },
                ) {
                    items(rows, key = { r -> if (r is GridRow.Head) "h${r.label}" else (r as GridRow.Pic).p.id }, span = { r -> if (r is GridRow.Head) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        contentType = { r -> if (r is GridRow.Head) "head" else "photo" }) { r ->
                        if (r is GridRow.Head) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Lr.TextSecondary, modifier = Modifier.weight(1f))
                                Text(r.count.toString(), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Lr.TextMuted)
                            }
                        } else {
                            val p = (r as GridRow.Pic).p
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
                }
            }
        }
        if (selecting) SelectionBar(sel, actions, onPaste = { pasting = true })
    }
    if (pasting) PasteDialog(onDismiss = { pasting = false }, onPaste = { scopes -> actions.onPasteEdits(sel, scopes); pasting = false })
}

@Composable
private fun IconTap(icon: LrIcon, description: String, tint: Color = Lr.IconPrimary, onClick: () -> Unit) = LrIconButton(icon, description, onClick, tint = tint)

@Composable
private fun PermissionPrompt(onAllow: () -> Unit, onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(LrIcon.CAMERA, "Show your camera roll", "Allow access to photos so Rawline can list what is on your phone.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PrimaryButton("Allow access", onAllow); SecondaryButton("Import files", onImport) }
        }
    }
}

@Composable
private fun FilterBar(f: LibraryFilter, cameras: List<String>, onChange: (LibraryFilter) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lr.Surface1).padding(horizontal = 8.dp, vertical = 4.dp)) {
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
    Box(modifier.background(Lr.Surface2).semantics { contentDescription = p.name + (if (p.rating > 0) ", ${p.rating} stars" else "") + (if (p.edited) ", edited" else "") }) {
        bmp?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (p.rating > 0) Text("★".repeat(p.rating), color = Lr.Star, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(3.dp))
        if (p.flag == 1) Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { LrIconView(LrIcon.FLAG_FILLED, Color.White, size = 14.dp) }
        if (p.flag == -1) Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { LrIconView(LrIcon.REJECT, Color(0xFFE57373), size = 14.dp) }
        if (p.label in 1..5) Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 7.dp).size(9.dp).background(LabelColors[p.label], CircleShape))
        if (p.kind == Kind.RAW && !selecting) Text("RAW", color = Lr.TextPrimary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp))
        if (p.edited) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { LrIconView(LrIcon.EDIT, Color.White, size = 14.dp) }
        if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(if (selected) Lr.Accent else Color(0x66000000))) {
            if (selected) LrIconView(LrIcon.CHECK, Color.White, size = 20.dp)
        }
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, Lr.Accent))
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

private sealed interface GridRow {
    class Head(val label: String, val count: Int) : GridRow
    class Pic(val p: Photo) : GridRow
}

/** Date headers like Lightroom ("October 3, 2026" and a count) when sorted by date; a plain grid otherwise. */
private fun gridRows(photos: List<Photo>, sort: SortOrder): List<GridRow> {
    if (sort != SortOrder.NEWEST && sort != SortOrder.OLDEST) return photos.map { GridRow.Pic(it) }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH)
    val zone = java.time.ZoneId.systemDefault()
    fun day(p: Photo) = java.time.Instant.ofEpochMilli(if (p.takenAt > 0) p.takenAt else p.modified).atZone(zone).toLocalDate()
    val out = ArrayList<GridRow>(photos.size + 32)
    var i = 0
    while (i < photos.size) {
        val d = day(photos[i]); var j = i
        while (j < photos.size && day(photos[j]) == d) j++
        out.add(GridRow.Head(d.format(fmt), j - i))
        for (k in i until j) out.add(GridRow.Pic(photos[k]))
        i = j
    }
    return out
}
