package app.rawline.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.rawline.core.cache.FrameMonitor
import app.rawline.core.cache.ThumbStore
import app.rawline.core.data.IndexProgress
import app.rawline.core.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    photos: List<Photo>,
    thumbs: ThumbStore,
    progress: IndexProgress,
    folderLabel: String?,
    onPickFolder: () -> Unit,
    onOpen: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    var columns by remember { mutableIntStateOf(4) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) FrameMonitor.start() else FrameMonitor.stop("grid")
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(folderLabel ?: "No folder chosen", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val sub = when {
                    progress.running -> "Indexing ${progress.done} of ${progress.total}"
                    else -> "${photos.size} photos"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onPickFolder) { Text("Folder") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (folderLabel == null) "Tap Folder and choose where your photos are (for example DCIM)." else "Nothing here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().pinchColumns(columns) { columns = it },
            ) {
                itemsIndexed(photos, key = { _, p -> p.id }, contentType = { _, _ -> "photo" }) { i, p ->
                    Thumb(p, thumbs, Modifier.aspectRatio(1f).clickable { onOpen(i) })
                }
            }
        }
    }
}

@Composable
private fun Thumb(p: Photo, thumbs: ThumbStore, modifier: Modifier) {
    val bmp by produceState(thumbs.peek(p.id), p.id, p.indexed) {
        if (value == null && p.indexed) value = withContext(Dispatchers.IO) { thumbs.load(p.id) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(img, contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
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
