package app.rawline.feature.masking

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rawline.core.model.Adjust
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskComponent
import app.rawline.core.model.MaskOp
import app.rawline.core.model.MaskType
import app.rawline.core.ui.LocalLoader
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconButton
import app.rawline.core.ui.LrOutlineButton
import app.rawline.core.ui.LrTextButton
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.SectionTitle
import app.rawline.core.ui.ToggleRow
import app.rawline.feature.editor.AdjustTarget
import app.rawline.feature.editor.ColourBasicsPanel
import app.rawline.feature.editor.CurvePanel
import app.rawline.feature.editor.EffectsPanel
import app.rawline.feature.editor.GradingPanel
import app.rawline.feature.editor.LightPanel
import app.rawline.feature.editor.MixerPanel
import app.rawline.feature.editor.PanelColumn
import kotlinx.coroutines.delay

private val R4 = RoundedCornerShape(4.dp)
private val R6 = RoundedCornerShape(6.dp)

/** The whole Masking tray. The tray area is a fixed height, so every page here scrolls inside it. */
@Composable
internal fun MaskTray(f: MaskingFeature) {
    val ui = f.ui
    val density = LocalDensity.current.density
    SideEffect { ui.density = density }
    val ids = f.masks.map { it.id }
    val sel = f.selMask

    LaunchedEffect(Unit) { f.onEnter() }
    // any change to the mask list (undo, redo, reset, delete) keeps the selection pointing at a mask that exists
    LaunchedEffect(ids, sel?.components?.size) { f.reconcile() }
    // the red tint follows the selected mask, the page and the Mask / adjustment tab
    LaunchedEffect(ids, ui.selectedId, ui.page, ui.sub, ui.overlayPref, sel?.visible, ui.active) { f.syncOverlay() }
    val toast = ui.toast
    LaunchedEffect(toast) { if (toast != null) { delay(4500); if (ui.toast === toast) ui.toast = null } }

    val canStep = ui.busy != null || ui.pickingObject || ui.pickingColour || ui.renaming || ui.page == MaskPage.EDIT || (ui.page == MaskPage.PICK && ids.isNotEmpty())
    BackHandler(enabled = canStep) { f.back() }

    Column(Modifier.fillMaxSize()) {
        StatusStrip(f)
        when {
            ui.page == MaskPage.EDIT && sel != null -> EditPage(f, sel)
            ui.page == MaskPage.PICK || ids.isEmpty() -> PickPage(f, closable = ids.isNotEmpty())
            else -> ListPage(f)
        }
    }
}

// ------------------------------------------------------------------------------------------------ shared pieces

@Composable
private fun StatusStrip(f: MaskingFeature) {
    val ui = f.ui
    val busy = ui.busy
    val pick = when { ui.pickingObject -> "Tap the object in the photo"; ui.pickingColour -> "Tap a colour in the photo"; else -> null }
    val toast = ui.toast
    val text = busy?.let { "$it..." } ?: pick ?: toast?.text ?: return
    Row(Modifier.fillMaxWidth().height(40.dp).background(Lr.Surface3).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy != null) { LocalLoader(size = 18.dp); Spacer(Modifier.width(10.dp)) }
        Text(text, Modifier.weight(1f), color = Lr.TextPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (busy != null || pick != null) LrTextButton({ f.cancelWork() }) { Text("Cancel", color = Lr.Focus) }
        else if (toast?.actionLabel != null) LrTextButton({ toast.action?.invoke() }) { Text(toast.actionLabel, color = Lr.Focus) }
        else LrIconButton(LrIcon.CLOSE, "Dismiss", { ui.toast = null }, size = 16.dp)
    }
}

@Composable
private fun TrayHeader(title: String, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) LrIconButton(LrIcon.BACK, "Back", onBack, Modifier.padding(start = 4.dp)) else Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp), color = Lr.TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        trailing()
    }
}

@Composable
private fun MaskIconButton(icon: MaskIcon, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, enabled: Boolean = true, tint: Color? = null) {
    Box(
        modifier.size(44.dp).clip(R6).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { MaskIconView(icon, tint ?: if (!enabled) Lr.TextDisabled else if (on) Lr.Accent else Lr.IconPrimary, size = 22.dp) }
}

/** Joined option buttons, one selected. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(38.dp).clip(R4).border(1.dp, Lr.BorderDefault, R4)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (on) Lr.SurfaceSelected else Color.Transparent).clickable { onSelect(i) }
                    .semantics { contentDescription = if (on) "$o, selected" else o },
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) Lr.TextPrimary else Lr.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            if (i < options.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(Lr.BorderSubtle))
        }
    }
}

private val OP_LABELS = listOf("Add", "Subtract", "Intersect")

// ------------------------------------------------------------------------------------------------ add mask picker

@Composable
private fun PickPage(f: MaskingFeature, closable: Boolean) {
    val atLimit = f.masks.size >= app.rawline.core.render.P.MAX_MASKS
    Column(Modifier.fillMaxSize()) {
        TrayHeader("Add mask", if (closable) ({ f.closePicker() }) else null)
        Box(Modifier.weight(1f)) {
            PanelColumn {
                if (atLimit) Text("A photo can have ${app.rawline.core.render.P.MAX_MASKS} masks. Delete one to add another.", color = Lr.Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                Text("Select with AI", color = Lr.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 4.dp))
                ToolRow(listOf(MaskTool.SUBJECT, MaskTool.SKY, MaskTool.BACKGROUND, MaskTool.PEOPLE, MaskTool.OBJECT), f, !atLimit)
                Text("Draw or select by range", color = Lr.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp))
                ToolRow(listOf(MaskTool.BRUSH, MaskTool.LINEAR, MaskTool.RADIAL, MaskTool.COLOUR, MaskTool.LUMINANCE), f, !atLimit)
            }
        }
    }
}

@Composable
private fun ToolRow(tools: List<MaskTool>, f: MaskingFeature, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tools.forEach { t ->
            val ok = enabled && (!t.ai || f.ai != null)
            Column(
                Modifier.weight(1f).height(78.dp).clip(R6).background(Lr.Surface3).border(1.dp, Lr.BorderSubtle, R6)
                    .clickable(enabled = enabled) { f.create(t) }.alpha(if (ok) 1f else 0.4f).semantics { contentDescription = "Add ${t.label} mask" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                MaskIconView(t.icon, Lr.IconPrimary, size = 30.dp)
                Spacer(Modifier.height(6.dp))
                Text(t.label, color = Lr.TextPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ mask list

@Composable
private fun ListPage(f: MaskingFeature) {
    Column(Modifier.fillMaxSize()) {
        TrayHeader("Masks", null) { LrOutlineButton("Add mask", { f.openPicker() }, icon = LrIcon.ADD, small = true) }
        Box(Modifier.weight(1f)) {
            PanelColumn {
                f.masks.forEach { m -> MaskRow(f, m) }
            }
        }
    }
}

private fun summary(m: Mask): String {
    val first = if (m.components.size == 1) m.components[0].label.ifEmpty { m.components[0].type.name.lowercase() } else "${m.components.size} parts"
    return buildString {
        append(first)
        if (m.invert) append(", inverted")
        if (m.amount < 0.995f) append(", ${(m.amount * 100).toInt()}%")
        if (!m.visible) append(", hidden")
    }
}

@Composable
private fun MaskRow(f: MaskingFeature, m: Mask) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).clickable { f.select(m.id) }.padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(R6).background(Lr.Surface3), contentAlignment = Alignment.Center) {
            MaskIconView(m.components.firstOrNull()?.let { iconFor(it) } ?: MaskIcon.OBJECT, if (m.visible) Lr.IconPrimary else Lr.TextDisabled, size = 22.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(m.name, color = if (m.visible) Lr.TextPrimary else Lr.TextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary(m), color = Lr.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MaskIconButton(if (m.visible) MaskIcon.EYE else MaskIcon.EYE_OFF, if (m.visible) "Hide ${m.name}" else "Show ${m.name}", { f.toggleVisible(m.id) }, tint = if (m.visible) Lr.IconPrimary else Lr.TextMuted)
        MaskIconView(MaskIcon.CHEVRON_RIGHT, Lr.IconSecondary, Modifier.padding(end = 10.dp), size = 18.dp)
    }
}

// ------------------------------------------------------------------------------------------------ active mask

private val SUBS = listOf("mask" to "Mask", "light" to "Light", "colour" to "Colour", "effects" to "Effects", "curve" to "Curve", "mixer" to "Mixer", "grade" to "Grade")

@Composable
private fun EditPage(f: MaskingFeature, m: Mask) {
    val ui = f.ui
    var mixBand by remember { mutableIntStateOf(0) }
    var mixMode by remember { mutableIntStateOf(0) }
    val id = m.id
    val target = remember(id) {
        AdjustTarget({ r -> r.masks.firstOrNull { it.id == id }?.adjust ?: Adjust() }, { r, a -> MaskRules.mapMask(r, id) { it.copy(adjust = a) } }, isMask = true)
    }
    Column(Modifier.fillMaxSize()) {
        EditHeader(f, m)
        SubTabs(ui.sub) { ui.sub = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.sub) {
                "light" -> LightPanel(f.state, target)
                "colour" -> PanelColumn { ColourBasicsPanel(f.state, target, null, null) }
                "effects" -> EffectsPanel(f.state, target)
                "curve" -> CurvePanel(f.state, target, null)
                "mixer" -> MixerPanel(f.state, target, mixBand, { mixBand = it }, mixMode, { mixMode = it }, null)
                "grade" -> GradingPanel(f.state, target)
                else -> MaskTab(f, m)
            }
        }
    }
}

@Composable
private fun EditHeader(f: MaskingFeature, m: Mask) {
    val ui = f.ui
    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        LrIconButton(LrIcon.BACK, "Back to all masks", { f.toList() }, Modifier.padding(start = 4.dp))
        Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            if (ui.renaming) {
                NameField(m.name, { ui.renaming }, { text -> ui.renaming = false; f.rename(m.id, text) })
            } else {
                Row(
                    Modifier.height(44.dp).clip(R6).clickable { ui.renaming = true }.padding(horizontal = 6.dp).semantics { contentDescription = "Rename ${m.name}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m.name, Modifier.weight(1f, fill = false), color = Lr.TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    MaskIconView(MaskIcon.PENCIL, Lr.IconSecondary, size = 16.dp)
                }
            }
        }
        MaskIconButton(MaskIcon.OVERLAY, if (f.effectiveOverlay()) "Hide mask overlay" else "Show mask overlay", { f.toggleOverlay() }, on = f.effectiveOverlay())
        MaskIconButton(if (m.visible) MaskIcon.EYE else MaskIcon.EYE_OFF, if (m.visible) "Hide mask" else "Show mask", { f.toggleVisible(m.id) }, tint = if (m.visible) Lr.IconPrimary else Lr.Warning)
        LrIconButton(LrIcon.TRASH, "Delete mask", { f.deleteMask(m.id) })
    }
}

/** Inline name editor. Commits on Done or when focus leaves; Back cancels (the tray clears [active] first, so a late focus loss does nothing). */
@Composable
private fun NameField(initial: String, active: () -> Boolean, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val isActive by rememberUpdatedState(active)
    val done by rememberUpdatedState(onDone)
    fun finish() { if (!finished && isActive()) { finished = true; done(text.text) } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        text, { if (it.text.length <= MaskRules.MAX_NAME) text = it }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Lr.TextPrimary),
        cursorBrush = SolidColor(Lr.Focus),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish() }),
        modifier = Modifier.fillMaxWidth().height(40.dp).clip(R4).background(Lr.Input).border(1.dp, Lr.InputBorder, R4)
            .focusRequester(focus).onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) finish() }.semantics { contentDescription = "Mask name" },
        decorationBox = { inner -> Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) { inner() } },
    )
}

@Composable
private fun SubTabs(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
        SUBS.forEach { (id, label) ->
            val on = id == selected
            Column(
                Modifier.height(40.dp).clickable { onSelect(id) }.padding(horizontal = 10.dp).semantics { contentDescription = if (on) "$label, selected" else label },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(label, color = if (on) Lr.TextPrimary else Lr.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.padding(top = 3.dp).height(2.dp).fillMaxWidth().background(if (on) Lr.TextPrimary else Color.Transparent))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Mask tab: amount, parts

@Composable
private fun MaskTab(f: MaskingFeature, m: Mask) = PanelColumn {
    val ui = f.ui
    val id = m.id
    RawSlider("Amount", m.amount * 100f, 0f..100f, 100f, unit = "%",
        onChange = { v -> f.state.live { r -> MaskRules.mapMask(r, id) { it.copy(amount = v / 100f) } } }, onCommit = { f.state.commit("Mask amount") })
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LrOutlineButton(if (m.invert) "Inverted" else "Invert", { f.toggleInvert(id) }, Modifier.weight(1f), active = m.invert)
        LrOutlineButton("Duplicate", { f.duplicateMask(id) }, Modifier.weight(1f), icon = LrIcon.COPY)
    }
    SectionTitle("Parts of this mask")
    m.components.forEachIndexed { ci, c ->
        PartCard(f, m, ci, c, ui.selectedComp == ci)
        if (ui.selectedComp == ci) PartSettings(f, id, ci, c)
    }
    AddPartSection(f, m)
}

private fun partTitle(c: MaskComponent) = c.label.ifEmpty { c.type.name.lowercase().replaceFirstChar { it.uppercase() } }

@Composable
private fun PartCard(f: MaskingFeature, m: Mask, ci: Int, c: MaskComponent, selected: Boolean) {
    val shape = R6
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).clip(shape).background(if (selected) Lr.Surface3 else Lr.Surface2)
            .border(BorderStroke(1.dp, if (selected) Lr.Accent else Lr.BorderSubtle), shape),
    ) {
        Row(Modifier.fillMaxWidth().clickable { f.selectPart(ci) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MaskIconView(iconFor(c), Lr.IconPrimary, size = 22.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                Text(partTitle(c), color = Lr.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (ci == 0) "Base shape" else OP_LABELS[c.op.ordinal]) + if (c.invert) ", inverted" else "",
                    color = Lr.TextMuted, style = MaterialTheme.typography.labelSmall,
                )
            }
            MaskIconButton(MaskIcon.INVERT, "Invert this part", { f.togglePartInvert(m.id, ci) }, on = c.invert)
            if (m.components.size > 1) LrIconButton(LrIcon.TRASH, "Remove this part", { f.removePart(m.id, ci) })
            else Spacer(Modifier.width(6.dp))
        }
        if (selected && ci > 0) Segmented(OP_LABELS, c.op.ordinal, { f.setPartOp(m.id, ci, MaskOp.entries[it]) }, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
    }
}

@Composable
private fun AddPartSection(f: MaskingFeature, m: Mask) {
    val ui = f.ui
    SectionTitle("Add a part")
    Segmented(OP_LABELS, ui.nextOp.ordinal, { ui.nextOp = MaskOp.entries[it] }, Modifier.padding(horizontal = 14.dp))
    Text("The new part is combined with the mask this way. Subtract takes it away, Intersect keeps only the overlap.", color = Lr.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    val full = m.components.size >= MaskRules.MAX_PARTS
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(MaskTool.BRUSH, MaskTool.LINEAR, MaskTool.RADIAL, MaskTool.COLOUR, MaskTool.LUMINANCE, MaskTool.SUBJECT, MaskTool.SKY, MaskTool.OBJECT, MaskTool.BACKGROUND).forEach { t ->
            val ok = !full && (!t.ai || f.ai != null)
            Row(
                Modifier.height(42.dp).clip(R6).background(Lr.Surface3).border(1.dp, Lr.BorderSubtle, R6).clickable { f.addPart(t) }.alpha(if (ok) 1f else 0.4f).padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Add ${t.label} part" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaskIconView(t.icon, Lr.IconPrimary, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(t.label, color = Lr.TextPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ settings of the selected part

@Composable
private fun PartSettings(f: MaskingFeature, id: String, ci: Int, c: MaskComponent) {
    val ui = f.ui
    fun param(i: Int, default: Float) = MaskRules.partParam(c, i, default)
    fun setParam(i: Int, v: Float) = f.state.live { r -> MaskRules.mapPart(r, id, ci) { cc -> MaskRules.withParam(cc, i, v) } }
    fun commit(label: String) = f.state.commit(label)
    when (c.type) {
        MaskType.BITMAP -> if (MaskRules.isBrush(c)) {
            SectionTitle("Brush")
            Segmented(listOf("Paint", "Erase"), if (ui.brushErase) 1 else 0, { ui.brushErase = it == 1 }, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            RawSlider("Size", ui.brushSize * 100f, 1f..30f, 6f, decimals = 1, onChange = { ui.brushSize = it / 100f; f.brushPreview() }, onCommit = {})
            RawSlider("Feather", ui.brushFeather * 100f, 0f..100f, 50f, onChange = { ui.brushFeather = it / 100f; f.brushPreview() }, onCommit = {})
            RawSlider("Flow", ui.brushFlow * 100f, 5f..100f, 100f, onChange = { ui.brushFlow = it / 100f; f.brushPreview() }, onCommit = {})
            ToggleRow("Auto mask (follow edges)", ui.brushAuto, { ui.brushAuto = it })
            val n = c.strokes.size
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LrOutlineButton("Undo stroke", { f.undoStroke() }, Modifier.weight(1f).alpha(if (n > 0) 1f else 0.4f))
                LrOutlineButton("Clear brush", { if (n > 0) f.clearBrush() }, Modifier.weight(1f).alpha(if (n > 0) 1f else 0.4f))
            }
            Text(if (n == 0) "Paint on the photo to build the mask." else "$n stroke${if (n == 1) "" else "s"}", color = Lr.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        } else {
            SectionTitle("Selection")
            Text("Made from the photo. Add a Brush part set to Add or Subtract to refine the edges.", color = Lr.TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        MaskType.RADIAL -> {
            SectionTitle("Radial gradient")
            RawSlider("Feather", param(5, 0.5f) * 100f, 1f..100f, 50f, onChange = { setParam(5, it / 100f) }, onCommit = { commit("Radial feather") })
            RawSlider("Rotate", Math.toDegrees(param(4, 0f).toDouble()).toFloat(), -90f..90f, 0f, unit = "°", onChange = { setParam(4, Math.toRadians(it.toDouble()).toFloat()) }, onCommit = { commit("Radial rotate") })
            RawSlider("Width", param(2, 0.3f) * 100f, 2f..100f, 30f, onChange = { setParam(2, it / 100f) }, onCommit = { commit("Radial width") })
            RawSlider("Height", param(3, 0.3f) * 100f, 2f..100f, 30f, onChange = { setParam(3, it / 100f) }, onCommit = { commit("Radial height") })
        }
        MaskType.LINEAR -> {
            SectionTitle("Linear gradient")
            val asp = f.aspect()
            val angle = LinearGeom.angleDeg(c.params, asp)
            val len = LinearGeom.length(c.params, asp) * 100f
            fun setShape(a: Float, l: Float) = f.state.live { r -> MaskRules.mapPart(r, id, ci) { cc -> cc.copy(params = LinearGeom.withAngleLength(cc.params, a, l / 100f, asp)) } }
            RawSlider("Angle", angle, -180f..180f, 90f, unit = "°", onChange = { setShape(it, len) }, onCommit = { commit("Gradient angle") })
            RawSlider("Length", len, 2f..150f, 35f, unit = "%", onChange = { setShape(angle, it) }, onCommit = { commit("Gradient length") })
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                LrOutlineButton("Flip direction", { f.state.edit("Flip gradient") { r -> MaskRules.mapPart(r, id, ci) { cc -> cc.copy(params = LinearGeom.flip(cc.params)) } } }, Modifier.fillMaxWidth())
            }
            Text("The effect fades in from the dashed line to the solid line. Drag the handles or the line on the photo.", color = Lr.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        MaskType.COLOR -> {
            SectionTitle("Colour range")
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).clip(R4).background(Color(param(0, 0.5f).coerceIn(0f, 1f), param(1, 0.5f).coerceIn(0f, 1f), param(2, 0.5f).coerceIn(0f, 1f))).border(1.dp, Lr.BorderStrong, R4).semantics { contentDescription = "Picked colour" })
                LrOutlineButton(if (ui.pickingColour) "Tap the photo" else "Pick colour", { if (ui.pickingColour) f.cancelWork() else f.startColourPick() }, Modifier.weight(1f), icon = LrIcon.EYEDROPPER, active = ui.pickingColour)
            }
            RawSlider("Range", param(3, 0.2f) * 100f, 1f..100f, 20f, onChange = { setParam(3, it / 100f) }, onCommit = { commit("Colour range") })
            RawSlider("Softness", param(4, 0.5f) * 100f, 0f..100f, 50f, onChange = { setParam(4, it / 100f) }, onCommit = { commit("Colour softness") })
        }
        MaskType.LUMINANCE -> {
            SectionTitle("Luminance range")
            val bw = listOf(Color.Black, Color.White)
            RawSlider("From", param(0, 0.4f) * 100f, 0f..100f, 40f, trackColors = bw, onChange = { setParam(0, minOf(it / 100f, param(1, 0.9f))) }, onCommit = { commit("Luminance from") })
            RawSlider("To", param(1, 0.9f) * 100f, 0f..100f, 90f, trackColors = bw, onChange = { setParam(1, maxOf(it / 100f, param(0, 0.4f))) }, onCommit = { commit("Luminance to") })
            RawSlider("Falloff", param(2, 0.15f) * 100f, 1f..50f, 15f, onChange = { setParam(2, it / 100f) }, onCommit = { commit("Luminance falloff") })
        }
    }
}
