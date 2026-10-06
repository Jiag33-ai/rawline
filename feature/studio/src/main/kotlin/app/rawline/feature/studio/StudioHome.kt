package app.rawline.feature.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rawline.core.studio.model.ContinueRule
import app.rawline.core.studio.model.NewProject
import app.rawline.core.studio.model.RawPick
import app.rawline.core.studio.model.ProjectRow
import app.rawline.core.ui.EmptyState
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrDropdown
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconButton
import app.rawline.core.ui.LrIconView
import app.rawline.core.ui.LrMenuItem
import app.rawline.core.ui.LrOutlineButton
import app.rawline.core.ui.LrTextButton
import app.rawline.core.ui.PrimaryButton

/** What the home can ask of its host. */
class HomeActions(
    val onOpen: (String) -> Unit,
    val onNewBlank: (NewProject.Preset) -> Unit,
    val onNewFromPhoto: () -> Unit,
)

/**
 * The Studio home (spec 5): 48 dp top bar with the mode switch and New, a three column grid of projects (2 dp gaps), a 56 dp bottom bar with Projects only (Templates and Settings come later,
 * no empty tabs). Long press or the overflow button opens Open, Duplicate, Rename, Delete (Delete asks first).
 */
@Composable
fun StudioHome(vm: StudioHomeViewModel, modeSwitch: @Composable () -> Unit, busy: String?, continueId: String?, actions: HomeActions, modifier: Modifier = Modifier) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val damaged by vm.damaged.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    var newDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectRow?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, String>?>(null) }   // id and the name shown in the question

    Column(modifier.fillMaxSize().background(Lr.Canvas).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(48.dp).background(Lr.Surface1).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            modeSwitch()
            Spacer(Modifier.weight(1f))
            LrOutlineButton("New", { newDialog = true }, icon = LrIcon.ADD, small = true)
        }
        // BK-504: after a kill the project that was open comes first, one tap away
        ContinueRule.card(continueId, rows)?.let { r -> ContinueCard(vm, r) { actions.onOpen(r.id) } }
        // BK-505: until Studio can take a RAW file from Develop, say where RAW photos open
        Text(RawPick.HOME_NOTE, style = MaterialTheme.typography.labelSmall, color = Lr.TextMuted, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loaded && rows.isEmpty() && damaged.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(LrIcon.ADD, "No projects", "Make a canvas, or start from a photo.", action = { PrimaryButton("New", { newDialog = true }) })
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(rows, key = { it.id }) { r ->
                        ProjectCell(vm, r, actions.onOpen, { vm.duplicate(r.id) }, { renaming = r }, { deleting = r.id to r.name })
                    }
                    items(damaged, key = { "bad-$it" }) { id -> DamagedCell(id) { deleting = id to "this project" } }
                }
            }
            if (busy != null) Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).clip(RoundedCornerShape(6.dp)).background(Lr.Surface3).border(1.dp, Lr.BorderDefault, RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) { app.rawline.core.ui.LocalLoader(size = 18.dp); Text(busy, color = Lr.TextPrimary, style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth().background(Lr.Surface1).navigationBarsPadding().height(56.dp), horizontalArrangement = Arrangement.Center) {
            Column(Modifier.width(120.dp).fillMaxSize().semantics { contentDescription = "Projects, selected" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LrIconView(LrIcon.FOLDER, Lr.Accent, size = 22.dp)
                Text("Projects", style = MaterialTheme.typography.labelMedium, color = Lr.Accent)
            }
        }
    }

    if (newDialog) NewProjectSheet(onBlank = { newDialog = false; actions.onNewBlank(it) }, onPhoto = { newDialog = false; actions.onNewFromPhoto() }, onDismiss = { newDialog = false })
    renaming?.let { r -> RenameDialog(r.name, onDone = { vm.rename(r.id, it); renaming = null }, onDismiss = { renaming = null }) }
    deleting?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete project") },
            text = { Text("Delete $name? This cannot be undone.") },
            confirmButton = { LrTextButton(onClick = { vm.delete(id); deleting = null }) { Text("Delete", color = Lr.Error) } },
            dismissButton = { LrTextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    val message by vm.message.collectAsStateWithLifecycle()
    message?.let { m ->
        AlertDialog(onDismissRequest = { vm.consumeMessage() }, text = { Text(m) }, confirmButton = { LrTextButton(onClick = { vm.consumeMessage() }) { Text("OK") } })
    }
}

/** The "Continue <name>" card: a 64 dp row with the project's thumbnail, the name and the size. A tap opens the project like any other. */
@Composable
private fun ContinueCard(vm: StudioHomeViewModel, r: ProjectRow, onOpen: () -> Unit) {
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, r.id, r.modified, r.sizeBytes, r.hasThumb) { value = vm.thumbnail(r) }
    val label = ContinueRule.label(r)
    Row(
        Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Lr.Surface2).border(1.dp, Lr.BorderDefault, RoundedCornerShape(6.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen).semantics { contentDescription = label }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Lr.Black), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else LrIconView(LrIcon.PHOTOS, Lr.TextDisabled, size = 22.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Lr.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(StudioText.meta(r.width, r.height, r.sizeBytes), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Lr.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCell(vm: StudioHomeViewModel, r: ProjectRow, onOpen: (String) -> Unit, onDuplicate: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // keyed on what the file looks like, so a new thumbnail replaces the old one; decoded on a worker inside the view model
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, r.id, r.modified, r.sizeBytes, r.hasThumb) { value = vm.thumbnail(r) }
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClickLabel = "Open", onClick = { onOpen(r.id) }, onLongClickLabel = "Project menu", onLongClick = { menu = true })
            .semantics { contentDescription = "${r.name}, ${r.width} by ${r.height}" },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Lr.Black), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else LrIconView(LrIcon.PHOTOS, Lr.TextDisabled, size = 28.dp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Lr.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(StudioText.meta(r.width, r.height, r.sizeBytes), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Lr.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                LrIconButton(LrIcon.MORE, "Project menu", { menu = true }, size = 18.dp, hit = 48.dp)
                ProjectMenu(menu, { menu = false }, onOpen = { onOpen(r.id) }, onDuplicate = onDuplicate, onRename = onRename, onDelete = onDelete)
            }
        }
    }
}

@Composable
private fun ProjectMenu(open: Boolean, onDismiss: () -> Unit, onOpen: (() -> Unit)?, onDuplicate: (() -> Unit)?, onRename: (() -> Unit)?, onDelete: () -> Unit) {
    LrDropdown(open, onDismiss, width = 180.dp) {
        if (onOpen != null) LrMenuItem("Open", { onDismiss(); onOpen() })
        if (onDuplicate != null) LrMenuItem("Duplicate", { onDismiss(); onDuplicate() }, LrIcon.COPY)
        if (onRename != null) LrMenuItem("Rename", { onDismiss(); onRename() }, LrIcon.EDIT)
        LrMenuItem("Delete", { onDismiss(); onDelete() }, LrIcon.TRASH)
    }
}

/** A directory that does not open (damaged, or made by a newer Rawline): listed so it can be deleted, never opened or copied. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DamagedCell(id: String, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = { menu = true }, onLongClick = { menu = true }).semantics { contentDescription = "Project that cannot be opened. Tap for the menu." }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Lr.Surface1), contentAlignment = Alignment.Center) { LrIconView(LrIcon.ERROR, Lr.Warning, size = 28.dp) }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cannot open", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Lr.Warning, maxLines = 1)
                Text(id, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Lr.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                LrIconButton(LrIcon.MORE, "Menu for the project that cannot be opened", { menu = true }, size = 18.dp, hit = 48.dp)
                ProjectMenu(menu, { menu = false }, null, null, null, onDelete)
            }
        }
    }
}

/** New: the blank presets and one photo from the system picker. */
@Composable
private fun NewProjectSheet(onBlank: (NewProject.Preset) -> Unit, onPhoto: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("New project") },
        text = {
            Column {
                for (p in NewProject.presets) DialogRow(p.label) { onBlank(p) }
                DialogRow("From a photo") { onPhoto() }
                Text("Canvas is limited to 12 megapixels for now.", style = MaterialTheme.typography.bodySmall, color = Lr.TextMuted, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = {},
        dismissButton = { LrTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DialogRow(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Lr.TextPrimary, modifier = Modifier.padding(horizontal = 8.dp))
    }
}


@Composable
private fun RenameDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rename project") },
        text = {
            BasicTextField(
                text, { text = it.take(60) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Lr.TextPrimary, fontSize = 14.sp), cursorBrush = SolidColor(Lr.Focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(4.dp)).background(Lr.Input).border(1.dp, Lr.InputBorder, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp).semantics { contentDescription = "Project name" },
                decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() } },
            )
        },
        confirmButton = { LrTextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Rename") } },
        dismissButton = { LrTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
