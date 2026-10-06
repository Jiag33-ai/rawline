package app.rawline.feature.studio

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.BlendMode
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.render.Rgb
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
data class LayerRow(val id: String, val name: String, val visible: Boolean, val locked: Boolean, val opacity: Int, val blend: BlendMode, val active: Boolean, val hasPixels: Boolean, val canMoveUp: Boolean, val canMoveDown: Boolean)

object LayerRows {
    /** The stack is stored bottom to top; the panel shows the top layer first. */
    fun of(layers: List<Layer>, activeId: String, nonBlank: Set<String>): List<LayerRow> =
        layers.mapIndexed { i, l ->
            val c = l.common
            LayerRow(c.id, c.name, c.visible, c.locked, c.opacity, c.blend, c.id == activeId, c.id in nonBlank, canMoveUp = i < layers.size - 1, canMoveDown = i > 0)
        }.reversed()

    fun blendName(m: BlendMode) = when (m) { BlendMode.NORMAL -> "Normal"; BlendMode.MULTIPLY -> "Multiply"; BlendMode.SCREEN -> "Screen" }
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
