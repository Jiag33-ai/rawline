package app.rawline.feature.editor

import app.rawline.core.model.Adjust
import app.rawline.core.model.Curves
import app.rawline.core.model.Detail
import app.rawline.core.model.Effects
import app.rawline.core.model.Geometry
import app.rawline.core.model.Grading
import app.rawline.core.model.Hsl
import app.rawline.core.model.Optics

/**
 * What each reset affordance puts back, as plain functions on the model so the targets are written down once and tested.
 * Every function returns a copy with only its own fields at their defaults (the defaults come from the model classes, so a
 * changed default can never leave a reset behind). The `isModified` helpers say whether the Reset button should look live.
 */
object Resets {
    private val A = Adjust()
    private val E = Effects()
    private val D = Detail()
    private val G = Geometry()

    // ---- Adjust (whole photo or one mask) ----
    fun light(a: Adjust) = a.copy(exposure = A.exposure, contrast = A.contrast, highlights = A.highlights, shadows = A.shadows, whites = A.whites, blacks = A.blacks)
    fun colourBasics(a: Adjust) = a.copy(temp = A.temp, tint = A.tint, vibrance = A.vibrance, saturation = A.saturation)
    fun mixer(a: Adjust) = a.copy(mixHue = A.mixHue, mixSat = A.mixSat, mixLum = A.mixLum)
    /** One colour of the mixer: its hue, saturation and luminance. */
    fun mixerBand(a: Adjust, band: Int) = a.copy(
        mixHue = a.mixHue.toMutableList().also { it[band] = 0f },
        mixSat = a.mixSat.toMutableList().also { it[band] = 0f },
        mixLum = a.mixLum.toMutableList().also { it[band] = 0f },
    )
    fun grading(a: Adjust) = a.copy(grading = Grading())
    /** One wheel: hue and saturation go to zero, its luminance slider is left (it has its own reset). */
    fun gradingWheel(g: Grading, which: String): Grading = when (which) {
        "shadows" -> g.copy(shadows = g.shadows.copy(hue = 0f, sat = 0f))
        "highlights" -> g.copy(highlights = g.highlights.copy(hue = 0f, sat = 0f))
        "global" -> g.copy(global = g.global.copy(hue = 0f, sat = 0f))
        else -> g.copy(mid = g.mid.copy(hue = 0f, sat = 0f))
    }
    fun gradingWheelOf(g: Grading, which: String): Hsl = when (which) { "shadows" -> g.shadows; "highlights" -> g.highlights; "global" -> g.global; else -> g.mid }
    fun presence(a: Adjust) = a.copy(texture = A.texture, clarity = A.clarity, dehaze = A.dehaze)
    /** Every curve (master, red, green, blue) and the parametric sliders. */
    fun curves(a: Adjust) = a.copy(curves = Curves())
    /** One curve channel (0 RGB, 1 red, 2 green, 3 blue): its points go, the parametric sliders stay. */
    fun curveChannel(c: Curves, channel: Int): Curves = when (channel) {
        0 -> c.copy(master = emptyList()); 1 -> c.copy(red = emptyList()); 2 -> c.copy(green = emptyList()); else -> c.copy(blue = emptyList())
    }

    // ---- Effects ----
    fun vignette(e: Effects) = e.copy(vignetteAmount = E.vignetteAmount, vignetteMidpoint = E.vignetteMidpoint, vignetteRoundness = E.vignetteRoundness, vignetteFeather = E.vignetteFeather)
    fun grain(e: Effects) = e.copy(grainAmount = E.grainAmount, grainSize = E.grainSize, grainRoughness = E.grainRoughness)

    // ---- Detail ----
    fun sharpen(d: Detail) = d.copy(sharpen = D.sharpen, radius = D.radius, detail = D.detail, masking = D.masking)
    fun noise(d: Detail) = d.copy(nrLuminance = D.nrLuminance, aiDenoise = D.aiDenoise, aiDenoiseAmount = D.aiDenoiseAmount)
    fun colourNoise(d: Detail) = d.copy(nrColor = D.nrColor)

    // ---- Optics, geometry ----
    fun optics(o: Optics) = Optics()
    fun perspective(g: Geometry) = g.copy(keystoneV = G.keystoneV, keystoneH = G.keystoneH)
    fun straighten(g: Geometry) = g.copy(angle = G.angle)

    // ---- is anything different from the default (drives how live a Reset button looks) ----
    fun lightModified(a: Adjust) = a != light(a)
    fun colourBasicsModified(a: Adjust) = a != colourBasics(a)
    fun mixerModified(a: Adjust) = a != mixer(a)
    fun gradingModified(a: Adjust) = a.grading != Grading()
    fun presenceModified(a: Adjust) = a != presence(a)
    fun curvesModified(a: Adjust) = !a.curves.isIdentity
    fun curveChannelModified(c: Curves, channel: Int) = c != curveChannel(c, channel)
    fun vignetteModified(e: Effects) = e != vignette(e)
    fun grainModified(e: Effects) = e != grain(e)
    fun sharpenModified(d: Detail) = d != sharpen(d)
    fun noiseModified(d: Detail) = d != noise(d)
    fun colourNoiseModified(d: Detail) = d != colourNoise(d)
    fun opticsModified(o: Optics) = o != Optics()
    fun perspectiveModified(g: Geometry) = g != perspective(g)
}
