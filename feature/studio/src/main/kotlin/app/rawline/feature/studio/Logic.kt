package app.rawline.feature.studio

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.BlendMode
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.SelOp
import app.rawline.core.studio.render.Rgb
import app.rawline.core.studio.render.Tool
import java.util.Locale

/** Plain logic of the Studio screens, kept out of the composables so it can be tested on the host. */
object ColourHex {
    fun format(c: Rgb): String = String.format(Locale.US, "#%02X%02X%02X", q(c.r), q(c.g), q(c.b))

    /** "#RRGGBB", "RRGGBB", "#RGB" or "RGB" (any case). Null for anything else. */
    fun parse(text: String): Rgb? {
        val t = text.trim().removePrefix("#")
        val full = when (t.length) { 3 -> t.map { "$it$it" }.joinToString(""); 6 -> t; else -> return null }
        if (!full.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val v = full.toInt(16)
        return Rgb(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f)
    }

    private fun q(v: Float) = Math.round(v.coerceIn(0f, 1f) * 255f)
}

/** What the layers panel shows for one layer. */
data class LayerRow(
    val id: String, val name: String, val visible: Boolean, val locked: Boolean, val opacity: Int, val blend: BlendMode, val active: Boolean, val hasPixels: Boolean, val canMoveUp: Boolean, val canMoveDown: Boolean,
    /** S2: the layer has a mask, whether it is on, and whether it is shown inverted. */
    val hasMask: Boolean = false, val maskEnabled: Boolean = true, val maskInverted: Boolean = false,
)

object LayerRows {
    /** The stack is stored bottom to top; the panel shows the top layer first. */
    fun of(layers: List<Layer>, activeId: String, nonBlank: Set<String>): List<LayerRow> =
        layers.mapIndexed { i, l ->
            val c = l.common
            LayerRow(c.id, c.name, c.visible, c.locked, c.opacity, c.blend, c.id == activeId, c.id in nonBlank, canMoveUp = i < layers.size - 1, canMoveDown = i > 0, hasMask = c.mask != null, maskEnabled = c.mask?.enabled ?: true, maskInverted = c.mask?.inverted ?: false)
        }.reversed()

    fun blendName(m: BlendMode) = when (m) { BlendMode.NORMAL -> "Normal"; BlendMode.MULTIPLY -> "Multiply"; BlendMode.SCREEN -> "Screen" }
}

/** Words and groupings of the selection tools (S2). Australian English. */
object SelectionText {
    val ops = listOf(SelOp.REPLACE, SelOp.ADD, SelOp.SUBTRACT, SelOp.INTERSECT)
    val tools = listOf(Tool.RECT_SELECT, Tool.ELLIPSE_SELECT, Tool.LASSO_SELECT)
    fun label(op: SelOp) = when (op) { SelOp.REPLACE -> "Replace"; SelOp.ADD -> "Add"; SelOp.SUBTRACT -> "Subtract"; SelOp.INTERSECT -> "Intersect" }
    fun toolName(t: Tool) = when (t) { Tool.RECT_SELECT -> "Rectangle"; Tool.ELLIPSE_SELECT -> "Ellipse"; Tool.LASSO_SELECT -> "Lasso"; else -> t.name.lowercase().replaceFirstChar { it.uppercase() } }
    fun isSelect(t: Tool) = t in tools
    fun hint(t: Tool) = when (t) {
        Tool.RECT_SELECT -> "Drag to select a rectangle. A tap clears the selection."
        Tool.ELLIPSE_SELECT -> "Drag to select an ellipse. A tap clears the selection."
        else -> "Draw around what you want. The last point joins the first."
    }
}

enum class MaskAction { ADD_WHITE, ADD_BLACK, ADD_FROM_SELECTION, TURN_ON, TURN_OFF, INVERT, DELETE, PAINT_MASK, PAINT_PIXELS }
data class MaskEntry(val label: String, val action: MaskAction, val enabled: Boolean = true)

/** What the long press menu of a layer row offers (S2): a layer without a mask can get one; a layer with one can paint it, turn it off, invert it or lose it. */
object MaskMenu {
    fun entries(row: LayerRow, paintingMask: Boolean, hasSelection: Boolean): List<MaskEntry> =
        if (!row.hasMask) listOf(
            MaskEntry("Add mask, all visible", MaskAction.ADD_WHITE),
            MaskEntry("Add mask, all hidden", MaskAction.ADD_BLACK),
            MaskEntry("Add mask from selection", MaskAction.ADD_FROM_SELECTION, enabled = hasSelection),
        ) else listOf(
            if (paintingMask && row.active) MaskEntry("Paint the layer", MaskAction.PAINT_PIXELS) else MaskEntry("Paint the mask", MaskAction.PAINT_MASK),
            if (row.maskEnabled) MaskEntry("Turn mask off", MaskAction.TURN_OFF) else MaskEntry("Turn mask on", MaskAction.TURN_ON),
            MaskEntry(if (row.maskInverted) "Show mask the normal way" else "Invert mask", MaskAction.INVERT),
            MaskEntry("Delete mask", MaskAction.DELETE),
        )
}

/** Slider ranges and conversions of the brush options (values are shown as the user thinks of them; the Brush holds 0..1 fractions). */
object BrushSliders {
    val size = 1f..500f
    val percent = 0f..100f
    val flow = 1f..100f

    fun withSize(b: Brush, v: Float) = b.copy(diameter = v.toDouble().coerceIn(1.0, 2000.0))
    fun withHardness(b: Brush, v: Float) = b.copy(hardness = (v / 100.0).coerceIn(0.0, 1.0))
    fun withOpacity(b: Brush, v: Float) = b.copy(opacity = (v / 100.0).coerceIn(0.0, 1.0))
    fun withFlow(b: Brush, v: Float) = b.copy(flow = (v / 100.0).coerceIn(0.01, 1.0))
}

/** Names and sizes shown by the Studio home and export. */
object StudioText {
    private const val MAX_BASE = 120

    /** File name for an export: the project name with characters file systems refuse replaced, never empty, at most 120 characters before the extension. */
    fun exportFileName(projectName: String, ext: String): String {
        var base = projectName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        if (base.length > MAX_BASE) base = base.take(MAX_BASE).trimEnd()
        if (base.isEmpty()) base = "studio"
        return "$base.$ext"
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1L shl 10 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    fun meta(w: Int, h: Int, bytes: Long) = "$w x $h, ${size(bytes)}"
}
