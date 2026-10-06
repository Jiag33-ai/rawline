package app.rawline.feature.loupe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import app.rawline.core.ui.LrDim
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.geometry.isSpecified
import app.rawline.core.ui.LrMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LoupeScreen(
    photos: List<Photo>,
    startIndex: Int,
    previews: PreviewCache,
    thumbs: ThumbStore,
    showOverlay: Boolean,
    onBack: () -> Unit,
    onEdit: (Photo) -> Unit,
    onRate: (Photo, Int) -> Unit,
    onFlag: (Photo, Int) -> Unit,
    onLabel: (Photo, Int) -> Unit,
    onExport: (Photo) -> Unit,
    onDwell: (Photo) -> Unit = {},
    /** False until the library list has been read once: an empty list before that is "not loaded yet", not "nothing to show". */
    loaded: Boolean = true,
    /** Set by the editor when it moved to another photo, so Back lands on the photo that was edited last. Cleared with [onJumped]. */
    jumpTo: Int? = null,
    onJumped: () -> Unit = {},
    /** The photo the pager rests on, so the library can scroll back to it. */
    onPageSettled: (Photo) -> Unit = {},
) {
    if (photos.isEmpty()) {
        if (loaded) LaunchedEffect(Unit) { onBack() }
        else Box(Modifier.fillMaxSize().background(Lr.Canvas), contentAlignment = Alignment.Center) { app.rawline.core.ui.LocalLoader(size = 28.dp) }
        return
    }
    app.rawline.core.ui.KeepScreenOn(true)   // culling a card of photos should not be cut off by the screen timeout
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    LaunchedEffect(jumpTo, photos.size) {
        if (jumpTo != null) { if (jumpTo in photos.indices) pager.scrollToPage(jumpTo); onJumped() }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            photos.getOrNull(page)?.let(onPageSettled)
            // Current first, then four ahead and three behind. Wider than that costs memory for little gain.
            val want = (listOf(page) + (1..4).map { page + it } + (1..3).map { page - it }).mapNotNull { photos.getOrNull(it) }
            previews.prefetch(want)
        }
    }
    var info by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var stars by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = info || stars) { info = false; stars = false }
    // hiding the bars also closes the sheet, so Back is never swallowed by something that is not on screen
    LaunchedEffect(chrome) { if (!chrome) { info = false; stars = false } }
    LaunchedEffect(pager.currentPage) {
        kotlinx.coroutines.delay(1500)
        photos.getOrNull(pager.currentPage)?.let(onDwell)
    }
    val p = photos.getOrNull(pager.currentPage)
    Box(Modifier.fillMaxSize().background(Lr.Canvas).pointerInput(Unit) {
        // Swipe up opens the info panel, swipe down closes it
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total < -120f) info = true else if (total > 120f) info = false },
        ) { _, dy -> total += dy }
    }) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 2, key = { photos[it].id }) { page ->
            LoupePage(photos[page], previews, thumbs, isCurrent = page == pager.currentPage, onTap = { chrome = !chrome; stars = false })
        }
        if (chrome && p != null) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                app.rawline.core.ui.LrIconButton(LrIcon.BACK, "Back", onBack)
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Lr.TextPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text("${pager.currentPage + 1} of ${photos.size}", color = Lr.TextDim, style = MaterialTheme.typography.labelSmall)
                }
                if (showOverlay) {
                    var line by remember { mutableStateOf("") }
                    LaunchedEffect(pager.currentPage) { kotlinx.coroutines.delay(300); line = PerfLog.lastOpen }
                    Text(line, color = Lr.DebugText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(2f))
                }
            }
        }
        if (chrome && p != null) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Lr.Surface1).navigationBarsPadding()) {
                if (stars) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    for (i in 1..5) Box(Modifier.size(44.dp).clickable { onRate(p, if (p.rating == i) 0 else i) }.semantics { contentDescription = app.rawline.core.ui.Plurals.count(i, "star"); role = Role.RadioButton; selected = (i == p.rating) }, contentAlignment = Alignment.Center) {
                        LrIconView(if (i <= p.rating) LrIcon.STAR_FILLED else LrIcon.STAR, Lr.IconPrimary, size = 24.dp)
                    }
                }
                Text(exifLine(p), color = Lr.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                Row(Modifier.fillMaxWidth().height(LrDim.confirmBar), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    BarIcon(LrIcon.INFO, "Info", info, toggle = true) { info = !info }
                    BarIcon(if (p.flag == 1) LrIcon.FLAG_FILLED else LrIcon.FLAG, "Pick", p.flag == 1, toggle = true) { onFlag(p, if (p.flag == 1) 0 else 1) }
                    BarIcon(if (p.rating > 0) LrIcon.STAR_FILLED else LrIcon.STAR, "Rating", stars, toggle = true) { stars = !stars }
                    BarIcon(LrIcon.REJECT, "Reject", p.flag == -1, toggle = true) { onFlag(p, if (p.flag == -1) 0 else -1) }
                    BarIcon(LrIcon.QUEUE, "Add to export queue", false) { onExport(p) }
                    Box(Modifier.height(40.dp).clip(RoundedCornerShape(4.dp)).background(Lr.Accent).clickable { onEdit(p) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LrIconView(LrIcon.EDIT, Color.White, size = 18.dp)
                            Spacer(Modifier.width(7.dp))
                            Text("Edit", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (info && p != null) InfoSheet(p, previews, onClose = { info = false }, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun BarIcon(icon: LrIcon, description: String, active: Boolean, toggle: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick).semantics {
        contentDescription = description
        if (toggle) stateDescription = if (active) "On" else "Off"   // "Pick" reads the same whether it is on or off otherwise
    }, contentAlignment = Alignment.Center) {
        LrIconView(icon, if (active) Lr.Accent else Lr.IconPrimary, size = 22.dp)
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
private fun LoupePage(p: Photo, previews: PreviewCache, thumbs: ThumbStore, isCurrent: Boolean, onTap: () -> Unit) {
    val thumb by produceState(thumbs.peek(p.id), p.id) { if (value == null) value = thumbs.obtain(p) }
    var retry by remember(p.id) { mutableStateOf(0) }
    var failed by remember(p.id) { mutableStateOf(false) }
    val preview by produceState(previews.peek(p.id), p.id, retry) {
        if (value == null) {
            failed = false
            val t0 = System.nanoTime()
            // a decode cancelled by a quick swipe comes back empty: look once more before calling it a failure
            value = previews.load(p) ?: previews.load(p)
            failed = value == null
            if (isCurrent && value != null) PerfLog.record("swipe_cold_ms", (System.nanoTime() - t0) / 1_000_000)
        } else if (isCurrent) PerfLog.record("swipe_cached_ms", 0)
    }

    var scale by remember(p.id) { mutableFloatStateOf(1f) }
    var offset by remember(p.id) { mutableStateOf(Offset.Zero) }
    val shown = preview ?: thumb
    val zoomScope = rememberCoroutineScope()
    var zoomJob by remember(p.id) { mutableStateOf<Job?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(p.id) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { tap ->
                    zoomJob?.cancel()
                    val s0 = scale; val o0 = offset
                    val s1 = if (s0 > 1f) 1f else 3f
                    // zoom about the tapped point; going back to fit centres the photo again
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val o1 = if (s1 <= 1f) Offset.Zero else {
                        val raw = (tap - c) * (1f - s1 / s0) + o0 * (s1 / s0)
                        Offset(raw.x.coerceIn(-size.width * (s1 - 1f) / 2f, size.width * (s1 - 1f) / 2f), raw.y.coerceIn(-size.height * (s1 - 1f) / 2f, size.height * (s1 - 1f) / 2f))
                    }
                    zoomJob = zoomScope.launch {
                        androidx.compose.animation.core.Animatable(0f).animateTo(1f, tween(LrMotion.panel, easing = LrMotion.standard)) {
                            scale = s0 + (s1 - s0) * value; offset = o0 + (o1 - o0) * value
                        }
                    }
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
                            zoomJob?.cancel()
                            val oldScale = scale
                            scale = (scale * ev.calculateZoom()).coerceIn(1f, 8f)
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            // keep the point under the finger centroid fixed while scaling (layer scales about the view centre)
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val ratio = scale / oldScale
                            val o = (ev.calculateCentroid(useCurrent = false).takeIf { it.isSpecified }?.let { cen -> (cen - centre) * (1f - ratio) } ?: Offset.Zero) + offset * ratio + ev.calculatePan()
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
                modifier = Modifier.fillMaxSize().then(app.rawline.core.ui.LocalSharedPhoto.current(p.id)).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
        // the sharp preview is still coming (spinner) or could not be read (message and a way to try again)
        if (preview == null) {
            if (failed) Column(Modifier.align(Alignment.Center).background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not read this photo", color = Lr.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { retry++ }) { Text("Try again", color = Lr.Accent) }
            } else app.rawline.core.ui.LocalLoader(Modifier.align(Alignment.Center), size = 28.dp)
        }
    }
}


@Composable
private fun InfoSheet(p: Photo, previews: PreviewCache, onClose: () -> Unit, modifier: Modifier) {
    // the histogram reads pixels back from the preview, so it is built off the main thread
    val preview by produceState(previews.peek(p.id), p.id) { if (value == null) value = previews.load(p) }
    val hist by produceState<IntArray?>(null, preview) { value = preview?.let { b -> withContext(Dispatchers.Default) { runCatching { histogram(b) }.getOrNull() } } }
    Column(modifier.padding(24.dp).fillMaxWidth().background(Lr.Modal, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).border(1.dp, Lr.BorderSubtle, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(16.dp)) {
        Text(p.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
        val facts = InfoText.facts(p.width, p.height, p.size)
        if (facts.isNotEmpty()) Text(facts, color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodySmall)
        p.camera?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
        p.lens?.let { Text(it, color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodySmall) }
        exifLine(p).let { if (it.isNotEmpty()) Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
        if (p.takenAt > 0) Text(app.rawline.core.ui.DateText.dateTime(p.takenAt), color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodySmall)
        app.rawline.core.ui.Histogram(hist, Modifier.fillMaxWidth().height(80.dp).padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Swipe down to close", color = Color(0xFF888888), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

private fun histogram(b: android.graphics.Bitmap): IntArray {
    val src = if (b.config == android.graphics.Bitmap.Config.HARDWARE) b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else b
    val s = android.graphics.Bitmap.createScaledBitmap(src, 128, (128f * src.height / src.width).toInt().coerceAtLeast(8), true)
    val px = IntArray(s.width * s.height)
    s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
    val h = IntArray(768)
    for (c in px) { h[c shr 16 and 255]++; h[256 + (c shr 8 and 255)]++; h[512 + (c and 255)]++ }
    return h
}
