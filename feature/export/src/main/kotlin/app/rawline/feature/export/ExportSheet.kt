package app.rawline.feature.export

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import app.rawline.core.ui.LrButton as Button
import androidx.compose.material3.MaterialTheme
import app.rawline.core.ui.LrOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rawline.core.render.ColorSpaceOut
import app.rawline.core.render.ExportFormat
import app.rawline.core.render.ExportSettings
import app.rawline.core.render.MetadataMode
import app.rawline.core.render.SharpenAmount
import app.rawline.core.render.SharpenFor
import app.rawline.core.ui.ChipButton
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.SectionTitle

/** Export options. [count] is how many photos will be written; [destinationLabel] names the chosen folder. */
@Composable
fun ExportSheet(
    count: Int,
    settings: ExportSettings,
    destinationLabel: String?,
    onChange: (ExportSettings) -> Unit,
    onPickFolder: () -> Unit,
    onExport: () -> Unit,
    onShare: (() -> Unit)?,
    onDismiss: () -> Unit,
    title: String? = null,
    confirmLabel: String = "Export",
    /** True while the shared copy renders: the dialog stays open with a spinner instead of closing and leaving the user guessing. */
    sharing: Boolean = false,
    /** Why the last Share failed, shown in the dialog. */
    shareError: String? = null,
    /** Back to Pictures/Rawline; offered only while a custom folder is set. */
    onUseDefaultFolder: (() -> Unit)? = null,
) {
    var customMode by remember { mutableStateOf(settings.longEdge > 0 && settings.longEdge !in listOf(2048, 4096)) }
    var customEdge by remember { mutableStateOf(if (settings.longEdge > 0 && settings.longEdge !in listOf(2048, 4096)) settings.longEdge.toString() else "3000") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: if (count == 1) "Export photo" else "Export $count photos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (shareError != null) Text(shareError, color = app.rawline.core.ui.Lr.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                SectionTitle("Format")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportFormat.entries.forEach { f -> ChipButton(f.name.replace("TIFF16", "TIFF 16-bit"), settings.format == f, { onChange(settings.copy(format = f)) }) }
                }
                // its own feedback holder: this dialog has no value pill, and the shared default would keep "Quality: 92" for later screens
                if (settings.format == ExportFormat.JPEG) androidx.compose.runtime.CompositionLocalProvider(app.rawline.core.ui.LocalValueFeedback provides remember { app.rawline.core.ui.ValueFeedback() }) {
                    RawSlider("Quality", settings.quality.toFloat(), 40f..100f, 92f, onChange = { onChange(settings.copy(quality = it.toInt())) }, onCommit = {})
                }
                SectionTitle("Size")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipButton("Full size", settings.longEdge == 0 && !customMode, { customMode = false; onChange(settings.copy(longEdge = 0)) })
                    ChipButton("4096 px", settings.longEdge == 4096 && !customMode, { customMode = false; onChange(settings.copy(longEdge = 4096)) })
                    ChipButton("2048 px", settings.longEdge == 2048 && !customMode, { customMode = false; onChange(settings.copy(longEdge = 2048)) })
                    ChipButton("Custom", customMode, { customMode = true; onChange(settings.copy(longEdge = customEdge.toIntOrNull() ?: 3000)) })
                }
                if (customMode) {
                    val tooSmall = customEdge.toIntOrNull()?.let { it < 64 } ?: true
                    OutlinedTextField(customEdge, { customEdge = it.filter { c -> c.isDigit() }.take(5); customEdge.toIntOrNull()?.let { v -> if (v >= 64) onChange(settings.copy(longEdge = v)) } },
                        label = { Text("Long edge (px)") }, singleLine = true, isError = tooSmall, modifier = Modifier.fillMaxWidth(),
                        supportingText = if (tooSmall) ({ Text("Enter 64 or more. Until then the last valid size is used.") }) else null)
                }
                SectionTitle("Output sharpening")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SharpenFor.entries.forEach { f -> ChipButton(f.label, settings.sharpenFor == f, { onChange(settings.copy(sharpenFor = f)) }) }
                }
                if (settings.sharpenFor != SharpenFor.NONE) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SharpenAmount.entries.forEach { a -> ChipButton(a.label, settings.sharpenAmount == a, { onChange(settings.copy(sharpenAmount = a)) }) }
                }
                SectionTitle("Colour space")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorSpaceOut.entries.forEach { c -> ChipButton(c.label, settings.colorSpace == c, { onChange(settings.copy(colorSpace = c)) }) }
                }
                SectionTitle("Metadata")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetadataMode.entries.forEach { m -> ChipButton(m.label, settings.metadata == m, { onChange(settings.copy(metadata = m)) }) }
                }
                if (settings.metadata != MetadataMode.NONE) OutlinedTextField(settings.copyright, { onChange(settings.copy(copyright = it)) }, label = { Text("Copyright text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SectionTitle("File name")
                OutlinedTextField(settings.pattern, { onChange(settings.copy(pattern = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("{name} {date} {n} {rating} {camera}") })
                SectionTitle("Save to")
                OutlinedButton(onClick = onPickFolder) { Text(destinationLabel ?: "Pictures/Rawline", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                Text(if (destinationLabel == null) "Tap to choose another folder." else "A folder you chose.", style = MaterialTheme.typography.bodySmall, color = app.rawline.core.ui.Lr.TextMuted, modifier = Modifier.padding(top = 4.dp))
                if (destinationLabel != null && onUseDefaultFolder != null) TextButton(onClick = onUseDefaultFolder) { Text("Use Pictures/Rawline") }
            }
        },
        confirmButton = { Button(onClick = onExport) { Text(confirmLabel) } },
        dismissButton = {
            Row {
                if (onShare != null) {
                    if (sharing) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        app.rawline.core.ui.LocalLoader(size = 18.dp)
                        Text("Preparing...", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                    } else TextButton(onClick = onShare) { Text("Share") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
