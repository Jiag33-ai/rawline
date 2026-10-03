package app.rawline.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rawline.core.model.EditRecipe
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.SectionTitle

/** Presets with a strength slider. The edit before choosing a preset is remembered so the slider can re-blend it. */
@Composable
fun PresetsPanel(state: EditorState, userPresets: List<Preset>, onSavePreset: (String, EditRecipe) -> Unit, onDeletePreset: (Preset) -> Unit) {
    var selected by remember { mutableStateOf<Preset?>(null) }
    var base by remember { mutableStateOf(EditRecipe()) }
    var strength by remember { mutableFloatStateOf(100f) }
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { naming = true }) { Text("Save current as preset") }
        }
        if (selected != null) {
            RawSlider("Strength: ${selected!!.name}", strength, 0f..100f, 100f,
                onChange = { v -> strength = v; state.live { Presets.apply(base, selected!!.recipe, v / 100f) } },
                onCommit = { state.commit("Preset ${selected!!.name}") })
        }
        LazyColumn(Modifier.fillMaxWidth().size(width = 400.dp, height = 190.dp)) {
            item { SectionTitle("Mine") }
            if (userPresets.isEmpty()) item { Text("None yet", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(userPresets) { p -> PresetRow(p, selected == p, { base = state.recipe; selected = p; strength = 100f; state.edit("Preset ${p.name}") { Presets.apply(base, p.recipe, 1f) } }, { onDeletePreset(p) }) }
            item { SectionTitle("Looks") }
            items(Presets.builtIn) { p -> PresetRow(p, selected == p, { base = state.recipe; selected = p; strength = 100f; state.edit("Preset ${p.name}") { Presets.apply(base, p.recipe, 1f) } }, null) }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false }, title = { Text("Preset name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSavePreset(name.trim(), state.recipe); naming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PresetRow(p: Preset, selected: Boolean, onApply: () -> Unit, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onApply).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, modifier = Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
fun HistoryPanel(state: EditorState, onSnapshot: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { naming = true }) { Text("New snapshot") }
            TextButton(onClick = { state.reset() }) { Text("Reset all") }
        }
        LazyColumn(Modifier.size(width = 400.dp, height = 220.dp)) {
            if (state.snapshots.isNotEmpty()) item { SectionTitle("Snapshots") }
            items(state.snapshots) { s ->
                Row(Modifier.fillMaxWidth().clickable { state.applySnapshot(s) }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(s.name) }
            }
            item { SectionTitle("History") }
            items(state.history.size) { i0 ->
                val i = state.history.lastIndex - i0
                val e = state.history[i]
                Row(Modifier.fillMaxWidth().clickable { state.jump(i) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(e.label, color = if (i == state.historyIndex) MaterialTheme.colorScheme.primary else if (i > state.historyIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false }, title = { Text("Snapshot name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onSnapshot(name.ifBlank { "Snapshot" }); naming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}
