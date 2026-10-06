package app.rawline.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rawline.core.data.ExportJobEntity
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView

/** The export queue: what is waiting, running, finished or failed. Jobs survive closing the app. */
@Composable
fun QueueScreen(
    jobs: List<ExportJobEntity>,
    onCancel: (ExportJobEntity) -> Unit,
    onRetry: (ExportJobEntity) -> Unit,
    onRemove: (ExportJobEntity) -> Unit,
    onClearFinished: () -> Unit,
    onCancelAll: () -> Unit,
    onSettings: () -> Unit,
    /** False when no export is actually running: a job still marked "running" then has nothing behind it and can be retried or removed. */
    workerAlive: Boolean = true,
) {
    val active = jobs.count { it.status == 0 || it.status == 1 }
    app.rawline.core.ui.KeepScreenOn(workerAlive && jobs.any { it.status == 1 })   // an export in view should not be cut short by the screen timeout
    Column(Modifier.fillMaxSize().background(Lr.Black)) {
        Row(Modifier.fillMaxWidth().heightIn(min = app.rawline.core.ui.LrDim.libraryHeader).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Export queue", style = MaterialTheme.typography.titleMedium)
                Text(if (active > 0) "$active waiting or running" else "Nothing waiting", style = MaterialTheme.typography.labelSmall, color = Lr.TextMuted)
            }
            TextButton(onClick = onSettings) { Text("Settings", color = Lr.Accent) }
        }
        Text("Export settings apply to photos you add next.", style = MaterialTheme.typography.labelSmall, color = Lr.TextMuted, modifier = Modifier.padding(horizontal = 14.dp))
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (active > 0) TextButton(onClick = onCancelAll) { Text("Cancel waiting", color = Lr.TextDim) }
            if (jobs.any { it.status >= 2 }) TextButton(onClick = onClearFinished) { Text("Clear finished", color = Lr.TextDim) }
        }
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                app.rawline.core.ui.EmptyState(LrIcon.QUEUE, "Nothing in the queue", "Photos you export wait here and are saved one by one in the background.")
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(jobs, key = { it.id }) { j ->
                val stuck = j.status == 1 && !workerAlive   // marked running, but no export is alive behind it
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (j.status) {
                        1 -> LrIcon.REFRESH to Lr.Accent
                        2 -> LrIcon.CHECK to Lr.Success
                        3 -> LrIcon.ERROR to Lr.Error
                        4 -> LrIcon.CLOSE to Lr.TextDim
                        else -> LrIcon.PAUSE to Lr.TextDim
                    }
                    LrIconView(icon, tint, size = 24.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(j.photoName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        val line = when (j.status) { 0 -> "Waiting"; 1 -> if (stuck) "Stopped before it finished" else "Saving ${(j.progress * 100).toInt()}%"; 2 -> "Saved"; 3 -> j.message ?: "Failed"; else -> "Cancelled" }
                        Text(line, style = MaterialTheme.typography.bodySmall, color = if (j.status == 3) Lr.Error else Lr.TextMuted)
                        if (j.status == 1 && !stuck) LinearProgressIndicator(progress = { j.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Lr.Accent, trackColor = Lr.SurfaceSelected, gapSize = 0.dp, drawStopIndicator = {})
                    }
                    when (j.status) {
                        0 -> TextButton(onClick = { onCancel(j) }) { Text("Cancel", color = Lr.TextDim) }
                        1 -> if (stuck) { TextButton(onClick = { onRetry(j) }) { Text("Retry", color = Lr.Accent) }; TextButton(onClick = { onRemove(j) }) { Text("Remove", color = Lr.TextDim) } }
                            else TextButton(onClick = { onCancel(j) }) { Text("Stop", color = Lr.TextDim) }
                        3, 4 -> { TextButton(onClick = { onRetry(j) }) { Text("Retry", color = Lr.Accent) }; TextButton(onClick = { onRemove(j) }) { Text("Remove", color = Lr.TextDim) } }
                        else -> TextButton(onClick = { onRemove(j) }) { Text("Remove", color = Lr.TextDim) }
                    }
                }
            }
        }
    }
}
