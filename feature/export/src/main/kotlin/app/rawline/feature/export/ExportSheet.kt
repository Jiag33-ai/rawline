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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
) {
    var customEdge by remember { mutableStateOf(if (settings.longEdge > 0 && settings.longEdge !in listOf(2048, 4096)) settings.longEdge.toString() else "3000") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Export photo" else "Export $count photos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("Format")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportFormat.entries.forEach { f -> ChipButton(f.name.replace("TIFF16", "TIFF 16-bit"), settings.format == f, { onChange(settings.copy(format = f)) }) }
                }
                if (settings.format == ExportFormat.JPEG) RawSlider("Quality", settings.quality.toFloat(), 40f..100f, 92f, onChange = { onChange(settings.copy(quality = it.toInt())) }, onCommit = {})
                SectionTitle("Size")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipButton("Full size", settings.longEdge == 0, { onChange(settings.copy(longEdge = 0)) })
                    ChipButton("4096 px", settings.longEdge == 4096, { onChange(settings.copy(longEdge = 4096)) })
                    ChipButton("2048 px", settings.longEdge == 2048, { onChange(settings.copy(longEdge = 2048)) })
                    ChipButton("Custom", settings.longEdge > 0 && settings.longEdge !in listOf(2048, 4096), { onChange(settings.copy(longEdge = customEdge.toIntOrNull() ?: 3000)) })
                }
                if (settings.longEdge > 0 && settings.longEdge !in listOf(2048, 4096)) {
                    OutlinedTextField(customEdge, { customEdge = it.filter { c -> c.isDigit() }.take(5); customEdge.toIntOrNull()?.let { v -> if (v >= 64) onChange(settings.copy(longEdge = v)) } },
                        label = { Text("Long edge (px)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                SectionTitle("Output sharpening")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SharpenFor.entries.forEach { f -> ChipButton(f.label, settings.sharpenFor == f, { onChange(settings.copy(sharpenFor = f)) }) }
                }
                if (settings.sharpenFor != SharpenFor.NONE) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SharpenAmount.entries.forEach { a -> ChipButton(a.label, settings.sharpenAmount == a, { onChange(settings.copy(sharpenAmount = a)) }) }
                }
                SectionTitle("Colour space")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                OutlinedButton(onClick = onPickFolder) { Text(destinationLabel ?: "Pictures/Rawline (tap to choose a folder)") }
            }
        },
        confirmButton = { Button(onClick = onExport) { Text("Export") } },
        dismissButton = {
            Row {
                if (onShare != null) TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
