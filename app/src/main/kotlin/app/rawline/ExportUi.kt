package app.rawline

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.rawline.core.model.Photo
import app.rawline.core.render.ExportSettings
import app.rawline.feature.export.ExportSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The options every queued export uses. If [sharePhoto] is given a Share button renders that photo straight to the share sheet. */
@Composable
fun ExportSettingsDialog(graph: Graph, sharePhoto: Photo?, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
        count = 1, settings = settings, destinationLabel = label, onChange = ::update, onPickFolder = { pick.launch(null) },
        onExport = onDismiss,
        onShare = if (sharePhoto != null) ({
            graph.appScope.launch {
                var failure: String? = null
                val f = runCatching { withContext(Dispatchers.IO) { graph.exportRunner.exportForShare(sharePhoto, settings) } }.onFailure { failure = it.message; app.rawline.core.cache.PerfLog.error("share export: ${it.message}") }.getOrNull()
                if (f == null) withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, failure ?: "Could not prepare the photo to share", android.widget.Toast.LENGTH_LONG).show() }
                if (f != null) {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    val send = Intent(Intent.ACTION_SEND).setType(settings.format.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    withContext(Dispatchers.Main) { context.startActivity(Intent.createChooser(send, "Share photo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            onDismiss()
        }) else null,
        onDismiss = onDismiss, title = "Export settings", confirmLabel = "Done",
    )
}
