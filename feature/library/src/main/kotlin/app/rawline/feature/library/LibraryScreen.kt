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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
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
import app.rawline.core.ui.TouchChip
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
    val onOpenSettings: () -> Unit,
    val onRequestAllFiles: () -> Unit,
    val hasCopied: Boolean,
    /** BK-498: the RAW photos / All photos chips. true = RAW photos. An explicit choice, stored and never overridden. */
    val onViewChoice: (raw: Boolean) -> Unit = {},
    val onDismissWhatsNew: () -> Unit = {},
    /** BK-497: true while a finger is down on the grid, it is scrolling or photos are selected (the order is held while it is). */
    val onGridBusy: (Boolean) -> Unit = {},
    /** W15: copy the RAW photos of a card or USB-C reader into DCIM/Rawline. */
    val onImportCard: () -> Unit = {},
)

/** Saves a selection across recreation. Beyond 5000 ids it saves nothing (a huge Bundle can crash the save), so the selection resets. */
private val SelectionSaver = listSaver<androidx.compose.runtime.MutableState<Set<Long>>, Long>(
    save = { st -> if (st.value.size <= 5000) st.value.toList() else emptyList() },
    restore = { androidx.compose.runtime.mutableStateOf(it.toSet()) },
)

val LabelColors = listOf(Color.Transparent, Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA))

@Composable
fun LibraryScreen(
    photos: List<Photo>,
    rows: List<GridRow>,
    scanning: Boolean,
    scrollToId: Long?,
    onScrolledTo: () -> Unit,
    allCount: Int,
    cameras: List<String>,
    filter: LibraryFilter,
    thumbs: ThumbStore,
    progress: IndexProgress,
    sources: List<SourceItem>,
    selectedSource: String,
    permissionGranted: Boolean,
    permissionBlocked: Boolean,
    allFilesGranted: Boolean,
    actions: LibraryActions,
    /** The Develop | Studio switch, drawn at the start of the top bar. Null (Studio not in this build) leaves the bar exactly as it was. */
    modeSwitch: (@Composable () -> Unit)? = null,
    /** BK-498: the shown source holds at least one RAW photo (the chips are drawn only then, or while the RAW view is on). */
    rawInSource: Boolean = false,
    /** BK-498: show the one time "What's new" note. */
    whatsNew: Boolean = false,
    /** BK-120: the column count stored in the preferences (the saveable state below does not survive the user swiping the app away). */
    startColumns: Int = 5,
    onColumnsChanged: (Int) -> Unit = {},
    /** BK-120: the photo that was at the top when the app last paused; the grid scrolls to it once, then [onTopRestored] is called. Null: nothing to restore. */
    restoreTopId: Long? = null,
    onTopRestored: () -> Unit = {},
    /** BK-120: called when the app pauses, with the photo at the top of the grid, so a cold start can return to it. */
    onTopPhoto: (Long?) -> Unit = {},
) {
    // Saved, so a rotation, a visit to the viewer or another tab keeps the density, the selection and the open filter bar.
    var columns by rememberSaveable { mutableStateOf(startColumns) }
    val gridState = rememberLazyGridState()
    val selected = rememberSaveable(saver = SelectionSaver) { mutableStateOf(setOf<Long>()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) FrameMonitor.start() else FrameMonitor.stop("grid")
    }
    // Leaving the screen mid fling cancels the effect above without a "stopped scrolling" step, so stop the frame callbacks here.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { FrameMonitor.stop("grid") } }
    // The selection is whatever of it is still in the list: a photo that leaves the filter (rated to 0 under "Rating 3+") or is
    // removed by a scan cannot leave a ghost "0 selected" bar behind.
    val sel = remember(photos, selected.value) { if (selected.value.isEmpty()) emptyList() else photos.filter { it.id in selected.value } }
    val selecting = sel.isNotEmpty()
    // BK-497: the app holds the grid's order while this is true and for a moment after
    var touching by remember { mutableStateOf(false) }
    val busy = touching || gridState.isScrollInProgress || selecting
    val busyNow = androidx.compose.runtime.rememberUpdatedState(actions.onGridBusy)
    LaunchedEffect(busy) { busyNow.value(busy) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { busyNow.value(false) } }
    LaunchedEffect(sel) { if (selected.value.size != sel.size) selected.value = sel.mapTo(HashSet()) { it.id } }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected.value = emptySet() }
    app.rawline.core.ui.KeepScreenOn(progress.running)   // a first index of a big library takes minutes: do not let the screen sleep on it
    // BK-120: remember the photo at the top whenever the app pauses (a swipe away kills the process after this)
    val rowsNow = androidx.compose.runtime.rememberUpdatedState(rows)
    val topPhotoNow = androidx.compose.runtime.rememberUpdatedState(onTopPhoto)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && rowsNow.value.isNotEmpty()) topPhotoNow.value(GridRows.topPhotoId(rowsNow.value, gridState.firstVisibleItemIndex))
        }
        lifecycleOwner.lifecycle.addObserver(o)
        onDispose { lifecycleOwner.lifecycle.removeObserver(o) }
    }
    // BK-120: after a cold start, go back to that photo once the list is there (not when the viewer is sending the grid to a photo itself)
    LaunchedEffect(restoreTopId, rows.isNotEmpty(), scrollToId) {
        if (restoreTopId != null && rows.isNotEmpty()) {
            if (scrollToId == null) gridState.scrollToItem(GridRows.restoreIndex(rows, restoreTopId))
            onTopRestored()
        }
    }
    // coming back from the viewer: scroll to the photo it ended on when that tile is off screen
    LaunchedEffect(scrollToId, rows) {
        if (scrollToId != null && rows.isNotEmpty()) {
            val i = GridRows.indexOfPhoto(rows, scrollToId)
            if (i >= 0) {
                androidx.compose.runtime.snapshotFlow { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
                if (gridState.layoutInfo.visibleItemsInfo.none { it.index == i }) gridState.scrollToItem(i)
            }
            onScrolledTo()
        }
    }
    val current = sources.firstOrNull { it.key == selectedSource }

    Column(Modifier.fillMaxSize().background(Lr.Black)) {
        // ---- top bar: source picker on the left, tools on the right ----
        Row(Modifier.fillMaxWidth().heightIn(min = LrDim.libraryHeader).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                IconTap(LrIcon.CLOSE, "Clear selection") { selected.value = emptySet() }
                Text("${sel.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconTap(LrIcon.SELECT, "Select all") { selected.value = photos.map { it.id }.toSet() }
            } else {
                if (modeSwitch != null) { modeSwitch(); Spacer(Modifier.width(4.dp)) }
                Row(Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.DropdownList) { sourceMenu = true }.semantics { stateDescription = "Photo source" }.padding(start = 10.dp).heightIn(min = LrDim.libraryHeader), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(current?.label ?: "Photos", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = if (modeSwitch != null) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip)
                        val sub = when {
                            progress.listing -> "Listing photos, ${progress.total} found"
                            progress.running -> "Reading ${progress.done} of ${progress.total}"
                            filter.isActive -> "${photos.size} of $allCount (filtered)"
                            else -> app.rawline.core.ui.Plurals.photos(photos.size)
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
                        LrMenuItem("Import from a card", { addMenu = false; actions.onImportCard() }, LrIcon.CAMERA)
                        LrMenuItem("Add a folder", { addMenu = false; actions.onAddFolder() }, LrIcon.FOLDER)
                    }
                }
                IconTap(LrIcon.FILTER, "Filter", tint = if (filter.isActive) Lr.Accent else Lr.IconPrimary) { showFilters = !showFilters }
                Box {
                    IconTap(LrIcon.SORT, "Sort") { moreMenu = true }
                    LrDropdown(moreMenu, { moreMenu = false }) {
                        LrMenuItem("Newest first", { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.NEWEST)) }, if (filter.sort == SortOrder.NEWEST) LrIcon.CHECK else null)
                        LrMenuItem("Oldest first", { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.OLDEST)) }, if (filter.sort == SortOrder.OLDEST) LrIcon.CHECK else null)
                        LrMenuItem("By name", { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.NAME)) }, if (filter.sort == SortOrder.NAME) LrIcon.CHECK else null)
                        LrMenuItem("By rating", { moreMenu = false; actions.onFilter(filter.copy(sort = SortOrder.RATING)) }, if (filter.sort == SortOrder.RATING) LrIcon.CHECK else null)
                    }
                }
            }
        }
        if (showFilters && !selecting) FilterBar(filter, cameras, actions.onFilter)
        if (whatsNew && !selecting) WhatsNewBanner(actions.onDismissWhatsNew)
        if ((rawInSource || filter.rawOnly) && !selecting) ViewChips(filter.rawOnly, actions.onViewChoice)

        if (!allFilesGranted && permissionGranted && !selecting) {
            Row(Modifier.fillMaxWidth().background(Lr.AccentSoft).clickable { actions.onRequestAllFiles() }.padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(44.dp).background(Lr.Accent))
                Text("RAW files such as RW2 are hidden by Android until you allow all files access.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
                Text("Allow", color = Lr.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 14.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !permissionGranted && selectedSource.startsWith("device:") && photos.isEmpty() -> PermissionPrompt(permissionBlocked, actions.onRequestPermission, actions.onOpenSettings, actions.onImportFiles)
                photos.isEmpty() && (scanning || progress.listing) && !filter.isActive -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        app.rawline.core.ui.LocalLoader(size = 28.dp, color = Lr.IconSecondary)
                        Text("Reading your photos", style = MaterialTheme.typography.bodyMedium, color = Lr.TextSecondary, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                photos.isEmpty() && filter.rawOnly && !filter.isActive && allCount > 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(LrIcon.PHOTOS, "No RAW photos here", "Tap All photos to see everything in this place.")
                }
                photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(LrIcon.PHOTOS, if (filter.isActive) "No photos match" else "Nothing here yet", if (filter.isActive) "Change or clear the filter to see more." else "Tap + to import photos or a folder.",
                        action = if (filter.isActive) ({ SecondaryButton("Clear filters", { actions.onFilter(LibraryFilter(sort = filter.sort, rawOnly = filter.rawOnly)) }) }) else null)
                }
                else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns), state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        // watches without consuming: a finger on the grid (before it scrolls) already counts as busy
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            touching = true
                            do { val ev = awaitPointerEvent(PointerEventPass.Final) } while (ev.changes.any { it.pressed })
                            touching = false
                        }
                    }.pinchColumns(columns) { columns = it; onColumnsChanged(it) },
                ) {
                    items(rows, key = { r -> if (r is GridRow.Head) "h${r.key}" else (r as GridRow.Pic).p.id }, span = { r -> if (r is GridRow.Head) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
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
                                    onClickLabel = if (selecting) "Toggle selection" else "Open",
                                    onClick = { if (selecting) selected.value = if (isSel) selected.value - p.id else selected.value + p.id else actions.onOpen(p) },
                                    onLongClickLabel = "Select",
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
private fun PermissionPrompt(blocked: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit, onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (blocked) EmptyState(LrIcon.CAMERA, "Photo access is off", "Android will not ask again. Open Settings and allow Photos for Rawline, or import files instead.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PrimaryButton("Open settings", onOpenSettings); SecondaryButton("Import files", onImport) }
        } else EmptyState(LrIcon.CAMERA, "Show your camera roll", "Allow access to photos so Rawline can list what is on your phone.") {
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
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Flag", style = MaterialTheme.typography.labelMedium, color = Lr.TextDim)
            FlagFilter.entries.forEach { ChipButton(it.label, f.flag == it, { onChange(f.copy(flag = it)) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Edited", style = MaterialTheme.typography.labelMedium, color = Lr.TextDim)
            EditedFilter.entries.forEach { ChipButton(it.label, f.edited == it, { onChange(f.copy(edited = it)) }) }
            if (f.isActive) ChipButton("Clear filters", false, { onChange(LibraryFilter(sort = f.sort, rawOnly = f.rawOnly)) })
        }
        if (cameras.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton("All cameras", f.camera == null, { onChange(f.copy(camera = null)) })
            cameras.forEach { c -> ChipButton(c, f.camera == c, { onChange(f.copy(camera = c)) }) }
        }
    }
}

/** BK-498: "RAW photos" and "All photos". Two options of one choice; 48 dp targets through [TouchChip]. */
@Composable
private fun ViewChips(rawOnly: Boolean, onChoice: (raw: Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Lr.Surface1).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TouchChip("RAW photos", rawOnly, { onChoice(true) })
        TouchChip("All photos", !rawOnly, { onChoice(false) })
    }
}

/** BK-498: said once, after the update that makes the library open on RAW photos. */
@Composable
private fun WhatsNewBanner(onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Lr.AccentSoft).padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(60.dp).background(Lr.Accent))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("What's new", style = MaterialTheme.typography.labelLarge)
            Text("Rawline now opens on your RAW photos. Tap All photos to see everything on your phone.", style = MaterialTheme.typography.bodySmall, color = Lr.TextSecondary)
        }
        TextButton(onClose, Modifier.height(LrDim.touch)) { Text("Close") }
    }
}

@Composable
private fun SelectionBar(sel: List<Photo>, a: LibraryActions, onPaste: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lr.Panel).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton("Add to export queue", false, { a.onExport(sel) })   // an action, not a selected option
            (1..5).forEach { r -> ChipButton("$r ★", false, { a.onRate(sel, r) }) }
            ChipButton("No stars", false, { a.onRate(sel, 0) })
            ChipButton("Pick", false, { a.onFlag(sel, 1) }); ChipButton("Reject", false, { a.onFlag(sel, -1) }); ChipButton("Unflag", false, { a.onFlag(sel, 0) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("No label", "Red", "Yellow", "Green", "Blue", "Purple").forEachIndexed { i, n -> ChipButton(n, false, { a.onLabel(sel, i) }) }
            if (sel.size == 1) ChipButton("Copy edits", false, { a.onCopyEdits(sel[0]) })
            if (a.hasCopied) ChipButton("Paste edits...", false, onPaste)
            if (sel.size > 1) ChipButton("Sync from top photo", false, { a.onSyncEdits(sel[0], sel.drop(1)) })   // the first in grid order, not the first tapped
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onPaste: (Set<PasteScope>) -> Unit) {
    var chosen by remember { mutableStateOf(setOf(PasteScope.LIGHT, PasteScope.COLOUR, PasteScope.CURVE, PasteScope.MIXER, PasteScope.GRADING, PasteScope.EFFECTS, PasteScope.DETAIL)) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Paste which edits?") },
        text = {
            // eleven rows are taller than a landscape phone: scroll, so Paste and Cancel never fall off the bottom
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PasteScope.entries.forEach { s ->
                    val on = s in chosen
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - s else chosen + s }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { v -> chosen = if (v) chosen + s else chosen - s })
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
    Box(modifier.background(Lr.Surface2).semantics {
        contentDescription = p.name + (if (p.rating > 0) ", ${app.rawline.core.ui.Plurals.count(p.rating, "star")}" else "") + (if (p.edited) ", edited" else "")
        if (selecting) this.selected = selected
    }) {
        bmp?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(app.rawline.core.ui.LocalSharedPhoto.current(p.id)))
        }
        // badges sit on a dark translucent chip (like the RAW badge) so they stay readable on a bright sky or snow
        if (p.rating > 0) Text("★".repeat(p.rating), color = Lr.Star, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).background(Color(0xB3000000), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp))
        if (p.flag == 1) Box(Modifier.align(Alignment.TopStart).padding(3.dp).background(Color(0xB3000000), RoundedCornerShape(2.dp)).padding(2.dp)) { LrIconView(LrIcon.FLAG_FILLED, Color.White, size = 14.dp) }
        if (p.flag == -1) Box(Modifier.align(Alignment.TopStart).padding(3.dp).background(Color(0xB3000000), RoundedCornerShape(2.dp)).padding(2.dp)) { LrIconView(LrIcon.REJECT, Lr.Error, size = 14.dp) }
        if (p.label in 1..5) Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 7.dp).size(9.dp).background(LabelColors[p.label], CircleShape))
        if (p.kind == Kind.RAW && !selecting) Text("RAW", color = Lr.TextPrimary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp))
        if (p.edited) Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0xB3000000), RoundedCornerShape(2.dp)).padding(2.dp)) { LrIconView(LrIcon.EDIT, Color.White, size = 14.dp) }
        if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(if (selected) Lr.Accent else Color(0x66000000))) {
            if (selected) LrIconView(LrIcon.CHECK, Color.White, size = 12.dp)
        }
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, Lr.Accent))
    }
}

/** Two-finger pinch changes the column count (4 to 6) without blocking one-finger scrolling. */
private fun Modifier.pinchColumns(current: Int, set: (Int) -> Unit): Modifier = composed {
  // Keyed on Unit so changing the column count does not cancel the pinch that caused it.
  val now by androidx.compose.runtime.rememberUpdatedState(current)
  val setNow by androidx.compose.runtime.rememberUpdatedState(set)
  pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var acc = 1f
        var cols = now
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.size >= 2 && ev.changes.all { it.pressed }) {
                acc *= ev.calculateZoom()
                ev.changes.forEach { it.consume() }
                if (acc > 1.3f && cols > 4) { cols--; acc = 1f; setNow(cols) }
                else if (acc < 0.77f && cols < 6) { cols++; acc = 1f; setNow(cols) }
            }
        } while (ev.changes.any { it.pressed })
    }
  }
}
