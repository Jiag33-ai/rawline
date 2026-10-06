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
import androidx.compose.runtime.rememberCoroutineScope
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
    // Share renders a full size copy, which takes seconds: the dialog stays open with a spinner, says why if it fails, and closing
    // it while it renders cancels the render. The scope belongs to the dialog, so nothing outlives it holding the Activity.
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val cancelShare = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val dismiss = { cancelShare.set(true); onDismiss() }
    ExportSheet(
        count = 1, settings = settings, destinationLabel = label, onChange = ::update, onPickFolder = { pick.launch(null) },
        onExport = dismiss,
        onShare = if (sharePhoto != null) ({
            if (!sharing) {
                sharing = true; shareError = null; cancelShare.set(false)
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { graph.exportRunner.exportForShare(sharePhoto, settings) { cancelShare.get() } } }
                    result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it; app.rawline.core.cache.PerfLog.error("share export: ${it.message}") }
                    val f = result.getOrNull()
                    if (f == null) {
                        sharing = false
                        if (!cancelShare.get()) shareError = result.exceptionOrNull()?.let { ExportErrors.plain(it) } ?: "Could not prepare the photo to share."
                    } else {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                        val send = Intent(Intent.ACTION_SEND).setType(settings.format.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val started = runCatching { context.startActivity(Intent.createChooser(send, "Share photo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        if (started.isSuccess) onDismiss() else { sharing = false; shareError = "No app is available to share this photo." }
                    }
                }
            }
        }) else null,
        onDismiss = dismiss, title = "Export settings", confirmLabel = "Done",
        sharing = sharing, shareError = shareError,
        onUseDefaultFolder = { update(settings.copy(destination = null)) },
    )
}
