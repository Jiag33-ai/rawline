package app.rawline.feature.editor

import app.rawline.core.model.Adjust
import app.rawline.core.model.CurvePoint
import app.rawline.core.model.Curves
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Effects
import app.rawline.core.model.Grading
import app.rawline.core.model.Hsl

data class Preset(val name: String, val recipe: EditRecipe, val builtIn: Boolean = true, val id: Long = 0)

object Presets {
    private fun adj(f: (Adjust) -> Adjust) = EditRecipe(adjust = f(Adjust()))

    val builtIn: List<Preset> = listOf(
        Preset("Vivid", adj { it.copy(contrast = 12f, vibrance = 30f, saturation = 12f, clarity = 10f) }),
        Preset("Matte", adj { it.copy(contrast = -10f, blacks = 35f, saturation = -8f, highlights = -10f, curves = Curves(master = listOf(CurvePoint(0f, 0.06f), CurvePoint(1f, 0.96f)))) }),
        Preset("Warm Glow", adj { it.copy(temp = 12f, tint = 3f, highlights = -20f, shadows = 15f, vibrance = 15f, grading = Grading(highlights = Hsl(35f, 25f, 0f), shadows = Hsl(20f, 15f, 0f))) }),
        Preset("Cool Fade", adj { it.copy(temp = -10f, blacks = 25f, saturation = -10f, grading = Grading(shadows = Hsl(215f, 25f, 0f))) }),
        Preset("Mono Punch", adj { it.copy(saturation = -100f, contrast = 28f, clarity = 18f, blacks = -10f, whites = 10f) }),
        Preset("Soft Portrait", adj { it.copy(clarity = -18f, texture = -15f, shadows = 20f, highlights = -15f, vibrance = 8f) }),
        Preset("Crisp Landscape", adj { it.copy(clarity = 25f, dehaze = 15f, vibrance = 22f, contrast = 8f, texture = 20f) }),
        Preset("Cinematic", adj { it.copy(contrast = 10f, saturation = -6f, grading = Grading(shadows = Hsl(195f, 35f, -5f), highlights = Hsl(35f, 30f, 3f))) }),
        Preset("Golden Hour", adj { it.copy(temp = 20f, tint = 5f, highlights = -25f, shadows = 20f, saturation = 6f, grading = Grading(highlights = Hsl(40f, 28f, 0f))) }),
        Preset("Night Blue", adj { it.copy(temp = -18f, exposure = -0.2f, shadows = 25f, grading = Grading(shadows = Hsl(225f, 30f, 0f))) }),
        Preset("High Key", adj { it.copy(exposure = 0.5f, highlights = -30f, whites = 20f, shadows = 35f, contrast = -8f) }),
        Preset("Deep Contrast", adj { it.copy(contrast = 30f, blacks = -20f, whites = 15f, clarity = 15f, saturation = 5f) }),
        Preset("Sharp Detail", EditRecipe(adjust = Adjust(texture = 30f, clarity = 15f), detail = app.rawline.core.model.Detail(sharpen = 40f, detail = 50f))),
        Preset("Film Grain", EditRecipe(adjust = Adjust(contrast = 8f, saturation = -6f), effects = Effects(grainAmount = 35f, grainSize = 30f, grainRoughness = 55f))),
    )

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun lerp(a: List<Float>, b: List<Float>, t: Float) = List(a.size) { lerp(a[it], b[it], t) }
    private fun lerp(a: Hsl, b: Hsl, t: Float) = Hsl(lerp(a.hue, b.hue, t), lerp(a.sat, b.sat, t), lerp(a.lum, b.lum, t))

    /** Blends a preset onto [base] by [t] (0 = base, 1 = full preset). Only the look settings move; crop and masks stay. */
    fun apply(base: EditRecipe, preset: EditRecipe, t: Float): EditRecipe {
        val a = base.adjust
        val p = preset.adjust
        val blended = Adjust(
            lerp(a.exposure, p.exposure, t), lerp(a.contrast, p.contrast, t), lerp(a.highlights, p.highlights, t), lerp(a.shadows, p.shadows, t),
            lerp(a.whites, p.whites, t), lerp(a.blacks, p.blacks, t), lerp(a.temp, p.temp, t), lerp(a.tint, p.tint, t),
            lerp(a.vibrance, p.vibrance, t), lerp(a.saturation, p.saturation, t), lerp(a.texture, p.texture, t), lerp(a.clarity, p.clarity, t),
            lerp(a.dehaze, p.dehaze, t),
            lerp(a.mixHue, p.mixHue, t), lerp(a.mixSat, p.mixSat, t), lerp(a.mixLum, p.mixLum, t),
            Grading(
                lerp(a.grading.shadows, p.grading.shadows, t), lerp(a.grading.mid, p.grading.mid, t),
                lerp(a.grading.highlights, p.grading.highlights, t), lerp(a.grading.global, p.grading.global, t),
                lerp(a.grading.blending, p.grading.blending, t), lerp(a.grading.balance, p.grading.balance, t),
            ),
            if (t >= 0.5f) p.curves else a.curves,
        )
        val d = base.detail; val pd = preset.detail
        val e = base.effects; val pe = preset.effects
        return base.copy(
            adjust = blended,
            detail = d.copy(sharpen = lerp(d.sharpen, pd.sharpen, t), detail = if (pd.sharpen > 0f) lerp(d.detail, pd.detail, t) else d.detail),
            effects = e.copy(grainAmount = lerp(e.grainAmount, pe.grainAmount, t), grainSize = if (pe.grainAmount > 0f) pe.grainSize else e.grainSize,
                grainRoughness = if (pe.grainAmount > 0f) pe.grainRoughness else e.grainRoughness),
        )
    }
}
