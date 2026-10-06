package app.rawline.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import app.rawline.core.ui.HelpSheet
import app.rawline.core.ui.HelpTopic
import app.rawline.core.ui.R
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
    backup: BackupUiState,
    backupActions: BackupActions,
    message: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Bytes the thumbnail cache uses on disk, and a way to empty it (thumbnails are made again as photos are shown). */
    cacheBytes: Long = 0L,
    onClearThumbnails: () -> Unit = {},
    onClearCrash: () -> Unit = {},
    /** Debug builds only: a long press on the Version row. Null (release) leaves the row exactly as it was. */
    onVersionLongPress: (() -> Unit)? = null,
    /** About text for Studio, shown only in builds that contain it. */
    studioNote: String? = null,
    /** "Show explanations" (the glossary on a long press of a slider name) and a way to see the welcome screens again (BK-392, BK-394). */
    showExplanations: Boolean = true,
    onShowExplanations: (Boolean) -> Unit = {},
    onShowWelcome: () -> Unit = {},
) {
    var helpTopic by remember { mutableStateOf<HelpTopic?>(null) }
    val libraw = runCatching { Native.librawVersion() }.getOrElse { "failed: ${it.message}" }
    // the tab bar below already pads the navigation bar and the screen above the status bar, so only the sides are inset here
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))
        Item("Version", "$versionName (build $buildNumber)", if (onVersionLongPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = onVersionLongPress) else Modifier)
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
        Spacer(Modifier.height(8.dp))
        Text("Backups", style = MaterialTheme.typography.titleSmall)
        Text("Backing up to ${backup.where}", style = MaterialTheme.typography.bodyMedium)
        Text(backup.last + (backup.lastWhere?.let { ", in $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
        if (backup.error != null) Text("Last backup failed: ${backup.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Back up automatically", style = MaterialTheme.typography.bodyLarge)
                Text("Once a day when something changed, and after about 25 changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(backup.auto, backupActions.onAutoChange)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = backupActions.onBackupNow, enabled = !backup.running) { Text(if (backup.running) "Backing up..." else "Back up now") }
            Button(onClick = backupActions.onOpenRestore) { Text("Restore from a backup...") }
            Button(onClick = backupActions.onSaveCopy) { Text("Save a copy to...") }
            if (backup.canChooseFolder) Button(onClick = backupActions.onChooseFolder) { Text("Choose backup folder...") }
        }
        Text("Keeps the last 7. Only your edits, ratings and presets are saved, not the photos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Item("Thumbnail cache", "${cacheBytes / 1024 / 1024} MB on this phone (capped at 300 MB)")
        Button(onClick = onClearThumbnails) { Text("Clear thumbnails") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCopyReport) { Text("Copy report") }
        Text("Copies timings, device info, errors and the last crash. Paste it into the chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (studioNote != null) {
            Spacer(Modifier.height(16.dp))
            Text("Studio", style = MaterialTheme.typography.titleSmall)
            Text(studioNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.title_help), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        for (topic in HelpTopic.values()) {
            val name = stringResource(topic.title)
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { helpTopic = topic }.padding(vertical = 12.dp))
        }
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.action_show_explanations), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.note_show_explanations), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(showExplanations, onShowExplanations)
        }
        TextButton(onClick = onShowWelcome) { Text(stringResource(R.string.action_show_welcome_again)) }
        TextButton(onClick = onCopyReport) { Text(stringResource(R.string.action_report_problem)) }
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
    helpTopic?.let { HelpSheet(it) { helpTopic = null } }
    val list = backup.list
    val offer = backup.offer
    if (list != null && offer == null) androidx.compose.material3.AlertDialog(
        onDismissRequest = backupActions.onCloseRestore,
        title = { Text("Restore from a backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("No backups were found in ${backup.where}.", style = MaterialTheme.typography.bodyMedium)
                list.forEach { b -> TextButton(onClick = { backupActions.onPickBackup(b.name) }) { Text(b.line) } }
            }
        },
        confirmButton = { TextButton(onClick = backupActions.onChooseFile) { Text("Choose a file...") } },
        dismissButton = { TextButton(onClick = backupActions.onCloseRestore) { Text("Cancel") } },
    )
    if (offer != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = backupActions.onCancelRestore,
        title = { Text(if (offer.canRestore) "Restore this backup?" else "Backup not restored") },
        text = { Text(offer.text) },
        confirmButton = { if (offer.canRestore) TextButton(onClick = backupActions.onConfirmRestore) { Text("Restore") } else TextButton(onClick = backupActions.onCancelRestore) { Text("OK") } },
        dismissButton = { if (offer.canRestore) TextButton(onClick = backupActions.onCancelRestore) { Text("Cancel") } },
    )
}

@Composable
private fun Item(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
