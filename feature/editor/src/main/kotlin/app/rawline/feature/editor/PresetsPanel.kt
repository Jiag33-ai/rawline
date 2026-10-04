package app.rawline.feature.editor

import androidx.compose.foundation.clickable
import app.rawline.core.ui.LrOutlineButton
import app.rawline.core.ui.LrTabs
import app.rawline.core.ui.LrMenuItem
import app.rawline.core.ui.LrDropdown
import app.rawline.core.ui.LrIconButton
import app.rawline.core.ui.LrIconView
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.Lr
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import app.rawline.core.ui.LrButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton as TextButton
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
    var origin by remember { mutableStateOf<EditRecipe?>(null) }
    // The look is always blended onto the edit as it was before any preset was picked, so presets do not stack.
    fun selectPreset(p: Preset) {
        if (selected == null) origin = state.recipe
        base = origin ?: state.recipe; selected = p; strength = 100f
        state.edit("Preset ${p.name}") { Presets.apply(base, p.recipe, 1f) }
    }
    var tab by remember { mutableStateOf("looks") }
    Column(Modifier.fillMaxSize()) {
        LrTabs(listOf("looks" to "Looks", "yours" to "Yours"), tab, { tab = it })
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LrOutlineButton("Save current as preset", { naming = true }, small = true)
        }
        if (selected != null) {
            RawSlider("Strength: ${selected!!.name}", strength, 0f..100f, 100f,
                onChange = { v -> strength = v; state.live { Presets.apply(base, selected!!.recipe, v / 100f) } },
                onCommit = { state.commit("Preset ${selected!!.name}") })
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (tab == "yours") {
                if (userPresets.isEmpty()) item { Text("No saved presets yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), color = Lr.TextMuted) }
                items(userPresets) { p -> PresetRow(p, selected == p, { selectPreset(p) }, { onDeletePreset(p) }) }
            } else items(Presets.builtIn) { p -> PresetRow(p, selected == p, { selectPreset(p) }, null) }
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

/** 58 dp row: 48 dp square placeholder thumbnail (2 dp radius), name, overflow at the end. No card around it. */
@Composable
private fun PresetRow(p: Preset, selected: Boolean, onApply: () -> Unit, onDelete: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(LrDim.presetRow).clickable(onClick = onApply).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF151515)).border(1.dp, if (selected) Lr.Accent else Lr.BorderDefault, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) { LrIconView(LrIcon.PRESETS, Lr.IconSecondary, size = 22.dp) }
        Text(p.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp).weight(1f), color = if (selected) Lr.TextPrimary else Lr.TextSecondary)
        if (onDelete != null) Box {
            LrIconButton(LrIcon.MORE, "More", { menu = true })
            LrDropdown(menu, { menu = false }) { LrMenuItem("Delete", { menu = false; onDelete() }, LrIcon.TRASH) }
        }
    }
}

@Composable
fun HistoryPanel(state: EditorState, onSnapshot: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LrOutlineButton("New snapshot", { naming = true }, small = true)
            LrOutlineButton("Reset all", { state.reset() }, small = true)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (state.snapshots.isNotEmpty()) item { SectionTitle("Snapshots") }
            items(state.snapshots) { s ->
                Row(Modifier.fillMaxWidth().height(LrDim.menuRow).clickable { state.applySnapshot(s) }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) { Text(s.name, style = MaterialTheme.typography.bodySmall) }
            }
            item { SectionTitle("History") }
            items(state.history.size) { i0 ->
                val i = state.history.lastIndex - i0
                val e = state.history[i]
                Row(Modifier.fillMaxWidth().height(LrDim.menuRow).clickable { state.jump(i) }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.label, style = MaterialTheme.typography.bodySmall, color = if (i == state.historyIndex) MaterialTheme.colorScheme.primary else if (i > state.historyIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
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
