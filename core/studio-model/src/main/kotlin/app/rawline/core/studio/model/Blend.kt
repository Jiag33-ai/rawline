package app.rawline.core.studio.model

/**
 * Blend modes of Studio S1 (spec 2.2). The enum order is the numbering used by the GPU shader and by project.json, so
 * never reorder; new modes are appended (S3 adds the rest of the 24).
 */
enum class BlendMode(val id: Int, val key: String) {
    NORMAL(0, "normal"),
    MULTIPLY(1, "multiply"),
    SCREEN(2, "screen");

    companion object {
        fun fromKey(key: String): BlendMode? = entries.firstOrNull { it.key == key }
    }
}

/** Straight (non-premultiplied) RGBA in 0..1. Used by the reference renderer and the tests; the GPU does the same maths in GLSL. */
class Rgba(val r: Float, val g: Float, val b: Float, val a: Float) {
    override fun toString() = "Rgba($r, $g, $b, $a)"
    companion object { val CLEAR = Rgba(0f, 0f, 0f, 0f) }
}

/**
 * Compositing as in W3C Compositing and Blending Level 1 (the general formula) on straight colour. Blend space is the space the
 * values are in: GAMMA (sRGB encoded, the default) or LINEAR, the caller decides what it passes in.
 *
 *   ar = as + ab (1 - as)
 *   Cr = ((1 - as) ab Cb + as ((1 - ab) Cs + ab B(Cb, Cs))) / ar      (everything 0 when ar = 0)
 *
 * Layer opacity and mask value multiply the source alpha before this step.
 */
object Blend {
    /** The blend function B(Cb, Cs) of one channel. */
    fun channel(mode: BlendMode, cb: Float, cs: Float): Float = when (mode) {
        BlendMode.NORMAL -> cs
        BlendMode.MULTIPLY -> cb * cs
        BlendMode.SCREEN -> cb + cs - cb * cs
    }

    fun over(backdrop: Rgba, source: Rgba, mode: BlendMode, opacity: Float = 1f, mask: Float = 1f): Rgba {
        val a = source.a * opacity * mask
        val ab = backdrop.a
        val ar = a + ab * (1f - a)
        if (ar <= 0f) return Rgba.CLEAR
        fun ch(cb: Float, cs: Float) = ((1f - a) * ab * cb + a * ((1f - ab) * cs + ab * channel(mode, cb, cs))) / ar
        return Rgba(ch(backdrop.r, source.r), ch(backdrop.g, source.g), ch(backdrop.b, source.b), ar)
    }
}
