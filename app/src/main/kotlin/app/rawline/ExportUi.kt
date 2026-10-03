package app.rawline

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.rawline.core.model.Photo
import app.rawline.core.render.ExportSettings
import app.rawline.feature.export.ExportSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportDialog(photos: List<Photo>, graph: Graph, onDismiss: () -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(ExportSettings.fromJson(graph.prefs.getString("export", null))) }
    fun update(s: ExportSettings) { settings = s; graph.prefs.edit().putString("export", s.toJson()).apply() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            update(settings.copy(destination = uri.toString()))
        }
    }
    val label = settings.destination?.let { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfter(':').ifEmpty { "Storage" } }.getOrNull() }
    ExportSheet(
        count = photos.size, settings = settings, destinationLabel = label, onChange = ::update, onPickFolder = { pick.launch(null) },
        onExport = {
            onStart()
            ExportService.pending = ExportService.Job(photos, settings)
            ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
            onDismiss()
        },
        onShare = if (photos.size == 1) ({
            graph.appScope.launch {
                val f = withContext(Dispatchers.IO) { graph.exportRunner.exportForShare(photos[0], settings) }
                if (f != null) {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    val send = Intent(Intent.ACTION_SEND).setType(settings.format.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    withContext(Dispatchers.Main) { context.startActivity(Intent.createChooser(send, "Share photo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            onDismiss()
        }) else null,
        onDismiss = onDismiss,
    )
}

/** Thin inline progress strip while exporting. */
@Composable
fun ExportStatusBar(graph: Graph) {
    val p by graph.exportRunner.progress.collectAsState()
    if (!p.running && p.lastMessage == null) return
    var dismissed by remember(p.lastMessage, p.running) { mutableStateOf(false) }
    if (dismissed && !p.running) return
    Column(Modifier.fillMaxWidth().safeDrawingPadding().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(if (p.running) "Exporting ${p.done + 1} of ${p.total}  ${p.current}" else p.lastMessage ?: "", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (p.running) TextButton(onClick = { graph.exportRunner.cancelled = true }) { Text("Cancel") } else TextButton(onClick = { dismissed = true }) { Text("OK") }
        }
        if (p.running) LinearProgressIndicator(progress = { ((p.done + p.fraction) / p.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}
