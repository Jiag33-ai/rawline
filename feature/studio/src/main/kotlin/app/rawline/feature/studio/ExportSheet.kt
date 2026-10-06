package app.rawline.feature.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.rawline.core.studio.render.FlattenFormat
import app.rawline.core.studio.render.StudioExporter
import app.rawline.core.studio.render.StudioPerf
import app.rawline.core.studio.render.StudioSession
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrTextButton
import app.rawline.core.ui.PrimaryButton
import app.rawline.core.ui.SecondaryButton
import app.rawline.core.ui.TouchChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private enum class Where { PICTURES, DOCUMENT, SHARE }

/**
 * Flatten export (spec 2.18 S1): format JPEG or PNG, JPEG quality 60 to 100 (default 92), the size, then Export (Pictures/Rawline), Save as (the system document picker) or Share. The render runs
 * on a worker in strips with progress and Cancel; closing the sheet or Back while it runs cancels it, and a cancelled or failed export deletes what it wrote. The canvas cannot be edited while
 * the sheet is open (it is modal), so what is exported is what was on screen.
 */
@Composable
fun ExportSheet(session: StudioSession, width: Int, height: Int, projectName: String, perf: StudioPerf, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(FlattenFormat.JPEG) }
    var quality by rememberSaveable { mutableIntStateOf(92) }
    var running by remember { mutableStateOf(false) }
    val progress = remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var doneTarget by remember { mutableStateOf<ExportTarget?>(null) }
    val cancel = remember { AtomicBoolean(false) }
    val exporter = remember(session) { StudioExporter(session, perf) }
    val name = StudioText.exportFileName(projectName, format.ext)

    fun startExport(where: Where, picked: android.net.Uri? = null) {
        if (running) return
        running = true; cancel.set(false); progress.floatValue = 0f; result = null; error = null; doneTarget = null
        val fmt = format; val q = quality; val fileName = name
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val snap = session.exportSnapshot() ?: return@withContext "Could not read the canvas. Try again." to null
                val target = when (where) {
                    Where.PICTURES -> ExportTargets.pictures(context, fileName, fmt.mime)
                    Where.DOCUMENT -> picked?.let { ExportTargets.document(context, it) }
                    Where.SHARE -> ExportTargets.shareFile(context, fileName)
                } ?: return@withContext "Could not open a place to save the picture." to null
                val r = try {
                    target.out.use { out ->
                        val buffered = java.io.BufferedOutputStream(out, 64 * 1024)
                        val res = exporter.flatten(snap, fmt, q, buffered, { cancel.get() }, { progress.floatValue = it })
                        buffered.flush()
                        res
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) { target.discard(); throw t }
                    perf.error("studio export: ${t.javaClass.simpleName}: ${t.message}")
                    StudioExporter.Result.FAILED
                }
                when (r) {
                    StudioExporter.Result.DONE -> if (target.publish()) null to target else { target.discard(); "The picture was written but could not be made visible." to null }
                    StudioExporter.Result.CANCELLED -> { target.discard(); "Export cancelled." to null }
                    StudioExporter.Result.FAILED -> { target.discard(); "Export failed. The canvas may be too large for the memory that is free." to null }
                }
            }
            running = false
            val (message, target) = outcome
            if (target == null) { if (message != "Export cancelled.") error = message else result = message; return@launch }
            if (where == Where.SHARE) {
                if (ExportTargets.share(context, target.uri, fmt.mime)) onDismiss() else { target.discard(); error = "No app is available to share this picture." }
            } else { doneTarget = target; result = if (where == Where.PICTURES) "Saved to Pictures/Rawline." else "Saved." }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri -> if (uri != null) startExport(Where.DOCUMENT, uri) }
    // leaving while it renders cancels the render (the worker deletes the partial file)
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }
    val close = { if (running) cancel.set(true) else onDismiss() }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !running)) {
        Box(Modifier.fillMaxSize().clickable(enabled = !running, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { close() }, contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clip4().background(Lr.Surface3).pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Export", style = MaterialTheme.typography.titleMedium, color = Lr.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TouchChip("JPEG", format == FlattenFormat.JPEG, { if (!running) format = FlattenFormat.JPEG })
                    TouchChip("PNG", format == FlattenFormat.PNG, { if (!running) format = FlattenFormat.PNG })
                }
                if (format == FlattenFormat.JPEG) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Quality", color = Lr.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Slider(
                            quality.toFloat(), { quality = it.toInt() }, valueRange = 60f..100f, steps = 39, enabled = !running,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "JPEG quality" },
                        )
                        Text("$quality", color = Lr.TextPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Transparent areas become white in a JPEG.", color = Lr.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text("$width x $height pixels, ${"%.1f".format(width.toLong() * height / 1e6)} megapixels. Layers are flattened.", color = Lr.TextMuted, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = Lr.Error, style = MaterialTheme.typography.bodySmall) }
                result?.let { Text(it, color = Lr.Success, style = MaterialTheme.typography.bodySmall) }
                if (running) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { progress.floatValue }, Modifier.fillMaxWidth(), color = Lr.Accent, trackColor = Lr.Surface1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rendering ${(progress.floatValue * 100).toInt()}%", color = Lr.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        LrTextButton(onClick = { cancel.set(true) }) { Text("Cancel") }
                    }
                } else {
                    PrimaryButton(if (doneTarget != null) "Export again" else "Export", { startExport(Where.PICTURES) }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Save as", { picker.launch(name) }, Modifier.weight(1f))
                        SecondaryButton("Share", { startExport(Where.SHARE) }, Modifier.weight(1f))
                    }
                    doneTarget?.let { t -> SecondaryButton("Share the saved picture", { if (!ExportTargets.share(context, t.uri, format.mime)) error = "No app is available to share this picture." }, Modifier.fillMaxWidth()) }
                    LrTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                }
            }
        }
    }
}

private fun Modifier.clip4() = this.then(Modifier.clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)))
