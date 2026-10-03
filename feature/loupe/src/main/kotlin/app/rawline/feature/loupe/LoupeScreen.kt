package app.rawline.feature.loupe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.rawline.core.cache.PerfLog
import app.rawline.core.cache.PreviewCache
import app.rawline.core.cache.ThumbStore
import app.rawline.core.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LoupeScreen(
    photos: List<Photo>,
    startIndex: Int,
    previews: PreviewCache,
    thumbs: ThumbStore,
    showOverlay: Boolean,
    onBack: () -> Unit,
) {
    if (photos.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            // Next 3 and previous 2 in swipe direction, current first.
            val want = (listOf(page) + (1..3).map { page + it } + (1..2).map { page - it }).mapNotNull { photos.getOrNull(it) }
            previews.prefetch(want)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { photos[it].id }) { page ->
            LoupePage(photos[page], previews, thumbs, isCurrent = page == pager.currentPage)
        }
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)) {
            TextButton(onClick = onBack) { Text("Back", color = Color.White) }
            if (showOverlay) {
                var line by remember { mutableStateOf("") }
                LaunchedEffect(pager.currentPage) { kotlinx.coroutines.delay(300); line = PerfLog.lastOpen }
                Text(line, color = Color(0xFF9EE493), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        val p = photos.getOrNull(pager.currentPage)
        if (p != null) {
            Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp)) {
                Text(p.name + "   ${pager.currentPage + 1} / ${photos.size}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(exifLine(p), color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun exifLine(p: Photo): String {
    if (!p.indexed) return ""
    val parts = ArrayList<String>()
    if (p.iso > 0) parts += "ISO ${p.iso}"
    if (p.shutter > 0) parts += if (p.shutter < 1) "1/${Math.round(1 / p.shutter)}s" else "${p.shutter}s"
    if (p.aperture > 0) parts += "f/${"%.1f".format(p.aperture)}"
    if (p.focal > 0) parts += "${p.focal.toInt()}mm"
    p.camera?.let { parts += it }
    return parts.joinToString("  ·  ")
}

@Composable
private fun LoupePage(p: Photo, previews: PreviewCache, thumbs: ThumbStore, isCurrent: Boolean) {
    val thumb by produceState(thumbs.peek(p.id), p.id, p.indexed) {
        if (value == null && p.indexed) value = withContext(Dispatchers.IO) { thumbs.load(p.id) }
    }
    val preview by produceState(previews.peek(p.id), p.id) {
        if (value == null) {
            val t0 = System.nanoTime()
            value = previews.load(p)
            if (isCurrent) PerfLog.record("swipe_cold_ms", (System.nanoTime() - t0) / 1_000_000)
        } else if (isCurrent) PerfLog.record("swipe_cached_ms", 0)
    }

    var scale by remember(p.id) { mutableFloatStateOf(1f) }
    var offset by remember(p.id) { mutableStateOf(Offset.Zero) }
    val shown = preview ?: thumb
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(p.id) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f
                })
            }
            .pointerInput(p.id) {
                // Pinch always zooms; one finger pans only while zoomed, otherwise the pager gets the swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val multi = ev.changes.size >= 2
                        if (multi || scale > 1f) {
                            scale = (scale * ev.calculateZoom()).coerceIn(1f, 8f)
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            val o = offset + ev.calculatePan()
                            offset = if (scale <= 1f) Offset.Zero else Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        shown?.let { b ->
            val img = remember(b) { b.asImageBitmap() }
            Image(
                img, contentDescription = p.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
    }
}
