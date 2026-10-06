package app.rawline.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.rawline.core.ui.LrButton as Button
import androidx.compose.material3.MaterialTheme
import app.rawline.core.ui.LrSwitch as Switch
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rawline.core.nativelib.Native

@Composable
fun SettingsScreen(
    versionName: String,
    buildNumber: Int,
    buildDate: String,
    overlayOn: Boolean,
    onOverlayChange: (Boolean) -> Unit,
    onCopyReport: () -> Unit,
    lastCrash: String?,
    xmpOn: Boolean,
    onXmpChange: (Boolean) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    message: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Bytes the thumbnail cache uses on disk, and a way to empty it (thumbnails are made again as photos are shown). */
    cacheBytes: Long = 0L,
    onClearThumbnails: () -> Unit = {},
    onClearCrash: () -> Unit = {},
) {
    var confirmRestore by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val libraw = runCatching { Native.librawVersion() }.getOrElse { "failed: ${it.message}" }
    // the tab bar below already pads the navigation bar and the screen above the status bar, so only the sides are inset here
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))
        Item("Version", "$versionName (build $buildNumber)")
        Item("Built", buildDate)
        Item("LibRaw", libraw)
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Debug overlay", style = MaterialTheme.typography.bodyLarge)
                Text("Shows decode timings in the photo viewer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(overlayOn, onOverlayChange)
        }
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Write XMP sidecars", style = MaterialTheme.typography.bodyLarge)
                Text("Saves ratings and colour labels next to photos in folders you add. Camera roll photos and imported files are not covered.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(xmpOn, onXmpChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBackup) { Text("Back up edits") }
            Button(onClick = { confirmRestore = true }) { Text("Restore backup") }
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Item("Thumbnail cache", "${cacheBytes / 1024 / 1024} MB on this phone (capped at 300 MB)")
        Button(onClick = onClearThumbnails) { Text("Clear thumbnails") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCopyReport) { Text("Copy report") }
        Text("Copies timings, device info, errors and the last crash. Paste it into the chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        Text("Gestures", style = MaterialTheme.typography.titleSmall)
        Text(
            "Library: long press to select, pinch to change columns.\n" +
                "Viewer: swipe left or right to move, double tap to zoom, swipe up for info, swipe down to close it.\n" +
                "Editor: hold the photo to see the original, double tap a slider name to reset it, tap its number to type a value.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lastCrash != null) {
            Spacer(Modifier.height(16.dp))
            Text("Last crash", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(lastCrash.take(1500), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClearCrash) { Text("Clear crash reports") }
        }
    }
    if (confirmRestore) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("Restore a backup?") },
        text = { Text("Edits, ratings, presets and snapshots in the backup are added. Anything you changed more recently on this phone is kept.") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; onRestore() }) { Text("Choose a backup") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Item(label: String, value: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
