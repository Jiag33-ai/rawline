package app.rawline.core.render

import app.rawline.core.model.Adjust
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskType

/** Mirrors core/native/src/main/cpp/engine/params.h. Keep in sync. */
object P {
    const val BLOCK_TEXELS = 20
    const val BLOCK_FLOATS = BLOCK_TEXELS * 4
    const val MAX_MASKS = 8
    const val MAX_BLOCKS = 1 + MAX_MASKS
    const val MASK_TEXELS = 32
    const val MASK_FLOATS = MASK_TEXELS * 4
    const val MAX_LAYERS = 16
    const val CURVE_SIZE = 256
    const val CURVE_ROWS = MAX_BLOCKS * 4

    const val G_CROP = 0
    const val G_GEO = 4
    const val G_GEO2 = 8
    const val G_DETAIL = 12
    const val G_NR = 16
    const val G_FX = 20
    const val G_FX2 = 24
    const val G_NUM_MASKS = 28
    const val G_OVERLAY = 29
    const val G_SHOWMASK = 30
    const val G_LDIST = 32
    const val G_LDIST_ON = 37
    const val G_LTCA = 38
    const val G_LTCA_ON = 44
    const val G_LVIG = 45
    const val G_LVIG_ON = 48
    const val G_COUNT = 56

    const val OFF_BLOCKS = G_COUNT
    const val OFF_MASKS = OFF_BLOCKS + MAX_BLOCKS * BLOCK_FLOATS
    const val OFF_CURVES = OFF_MASKS + MAX_MASKS * MASK_FLOATS
    const val TOTAL = OFF_CURVES + CURVE_ROWS * CURVE_SIZE
}

/** The look every raw gets before the user touches a slider (like a camera profile). Invisible in the sliders. */
object Baseline {
    const val SATURATION = 10f
    const val TEXTURE = 25f
    const val CLARITY = 10f
    const val SHARPEN = 45f
}

data class LayerBinding(val key: String, val index: Int)

object RenderParams {

    /**
     * Parameters for rendering a source region that becomes a heal or remove patch. The patch is laid over the raw before the
     * main adjustment pass, which applies the baseline look itself, so the patch must be rendered without it (otherwise the
     * patched area gets the baseline saturation, texture, clarity and sharpening twice).
     */
    fun patchSource(): FloatArray = build(EditRecipe(), 1, emptyMap(), overlayOn = false, useBaseline = false)

    /** The lens distortion polynomial the shader will apply for this recipe, or null when lens correction is off or the profile has none. */
    fun lensDistFor(recipe: EditRecipe, lens: LensCorrection?): FloatArray? = if (recipe.optics.lensCorrection) lens?.dist else null

    /** Maps TIFF orientation to (rot90, flipH). */
    private fun orientationToRotFlip(o: Int): Pair<Int, Boolean> = when (o) {
        2 -> 0 to true
        3 -> 2 to false
        4 -> 2 to true
        5 -> 1 to true
        6 -> 1 to false
        7 -> 3 to true
        8 -> 3 to false
        else -> 0 to false
    }

    /**
     * @param layers alpha layer index per layerKey (brush, AI masks). Components without a bound layer are skipped.
     * @param showMask index into recipe.masks to tint red while editing it, -1 for none.
     * @param srcW source size in pixels (only the aspect matters). When both are above zero the crop is constrained to valid
     * source with [Geo.fitCrop], so no outside-image pixel is ever shown or exported. Leave at 0 to use the recipe crop as is
     * (crop editing, whole-frame renders).
     */
    fun build(
        recipe: EditRecipe, orientation: Int, layers: Map<String, Int> = emptyMap(), showMask: Int = -1,
        useBaseline: Boolean = true, overlayOn: Boolean = false, lens: LensCorrection? = null, out: FloatArray = FloatArray(P.TOTAL),
        srcW: Int = 0, srcH: Int = 0,
    ): FloatArray {
        out.fill(0f)
        val written = BooleanArray(P.CURVE_ROWS)
        val g = recipe.geometry
        val (rot0, flip0) = orientationToRotFlip(orientation)
        val crop = if (srcW > 0 && srcH > 0) Geo.fitCrop(g, recipe.optics, orientation, srcW, srcH, lensDistFor(recipe, lens))
        else floatArrayOf(g.cropX, g.cropY, g.cropW, g.cropH)
        out[P.G_CROP] = crop[0]; out[P.G_CROP + 1] = crop[1]; out[P.G_CROP + 2] = crop[2]; out[P.G_CROP + 3] = crop[3]
        out[P.G_GEO] = Math.toRadians(g.angle.toDouble()).toFloat()
        // Orientation flip and user flips combine by XOR; user rotation adds to the base rotation.
        out[P.G_GEO + 1] = if (flip0 xor g.flipH) 1f else 0f
        out[P.G_GEO + 2] = if (g.flipV) 1f else 0f
        out[P.G_GEO + 3] = ((rot0 + g.rotate90) % 4 + 4) % 4f
        out[P.G_GEO2] = g.keystoneV / 100f * 0.5f
        out[P.G_GEO2 + 1] = g.keystoneH / 100f * 0.5f
        out[P.G_GEO2 + 2] = recipe.optics.distortion / 100f * 0.5f
        out[P.G_GEO2 + 3] = recipe.optics.vignetting / 100f * 0.6f

        if (lens != null) {
            if (recipe.optics.lensCorrection) {
                lens.dist?.let { for (i in 0 until 5) out[P.G_LDIST + i] = it[i]; out[P.G_LDIST_ON] = 1f }
                lens.vig?.let { for (i in 0 until 3) out[P.G_LVIG + i] = it[i]; out[P.G_LVIG_ON] = 1f }
            }
            if (recipe.optics.removeCa) lens.tca?.let { for (i in 0 until 6) out[P.G_LTCA + i] = it[i]; out[P.G_LTCA_ON] = 1f }
        }

        val d = recipe.detail
        out[P.G_DETAIL] = d.sharpen + if (useBaseline) Baseline.SHARPEN else 0f
        out[P.G_DETAIL + 1] = d.radius; out[P.G_DETAIL + 2] = d.detail; out[P.G_DETAIL + 3] = d.masking
        out[P.G_NR] = d.nrLuminance; out[P.G_NR + 1] = d.nrColor
        val e = recipe.effects
        out[P.G_FX] = e.vignetteAmount; out[P.G_FX + 1] = e.vignetteMidpoint; out[P.G_FX + 2] = e.vignetteRoundness; out[P.G_FX + 3] = e.vignetteFeather
        out[P.G_FX2] = e.grainAmount; out[P.G_FX2 + 1] = e.grainSize; out[P.G_FX2 + 2] = e.grainRoughness
        out[P.G_OVERLAY] = if (overlayOn) 1f else 0f
        // the shader indexes the visible masks only, so map the selected mask to its place in that list (-1 when hidden)
        out[P.G_SHOWMASK] = (if (showMask in recipe.masks.indices && recipe.masks[showMask].visible) recipe.masks.take(showMask).count { it.visible } else -1).toFloat()

        val base = if (useBaseline) recipe.adjust.copy(
            saturation = recipe.adjust.saturation + Baseline.SATURATION,
            texture = recipe.adjust.texture + Baseline.TEXTURE,
            clarity = recipe.adjust.clarity + Baseline.CLARITY,
        ) else recipe.adjust
        writeBlock(out, 0, base, written, useBaseline, recipe.lookVersion)
        val masks = recipe.masks.filter { it.visible }.take(P.MAX_MASKS)
        out[P.G_NUM_MASKS] = masks.size.toFloat()
        masks.forEachIndexed { i, m ->
            writeBlock(out, 1 + i, m.adjust, written, useBaseline, recipe.lookVersion)
            writeMask(out, i, m, layers)
        }
        // identity curves for rows nobody wrote (a real inverted curve ends at 0, so test what was written, not the values)
        for (r in 0 until P.CURVE_ROWS) if (!written[r]) for (k in 0 until 256) out[P.OFF_CURVES + r * 256 + k] = k / 255f
        return out
    }

    private fun writeBlock(out: FloatArray, block: Int, a: Adjust, written: BooleanArray, useBaseline: Boolean, look: Int) {
        val o = P.OFF_BLOCKS + block * P.BLOCK_FLOATS
        out[o] = a.exposure; out[o + 1] = a.contrast; out[o + 2] = a.highlights; out[o + 3] = a.shadows
        out[o + 4] = a.whites; out[o + 5] = a.blacks; out[o + 6] = a.temp; out[o + 7] = a.tint
        out[o + 8] = a.vibrance; out[o + 9] = a.saturation; out[o + 10] = a.texture; out[o + 11] = a.clarity
        out[o + 12] = a.dehaze
        for (i in 0 until 8) { out[o + 16 + i] = a.mixHue[i]; out[o + 24 + i] = a.mixSat[i]; out[o + 32 + i] = a.mixLum[i] }
        fun hsl(off: Int, h: app.rawline.core.model.Hsl) { out[o + off] = h.hue / 360f; out[o + off + 1] = h.sat / 100f; out[o + off + 2] = h.lum / 100f }
        hsl(40, a.grading.shadows); hsl(44, a.grading.mid); hsl(48, a.grading.highlights); hsl(52, a.grading.global)
        out[o + 56] = a.grading.blending / 100f
        out[o + 57] = a.grading.balance / 100f
        val c = a.curves
        val master = c.master.size >= 2 || c.parametric.any { it != 0f }
        out[o + 60] = if (master) 1f else 0f
        out[o + 61] = if (c.red.size >= 2) 1f else 0f
        out[o + 62] = if (c.green.size >= 2) 1f else 0f
        out[o + 63] = if (c.blue.size >= 2) 1f else 0f
        fun put(row: Int, lut: FloatArray) { written[block * 4 + row] = true; System.arraycopy(lut, 0, out, P.OFF_CURVES + (block * 4 + row) * 256, 256) }
        if (master) put(0, BaseCurve.toWorking(CurveMath.lut(c.master, c.parametric), useBaseline, look))
        if (c.red.size >= 2) put(1, BaseCurve.toWorking(CurveMath.lut(c.red), useBaseline, look))
        if (c.green.size >= 2) put(2, BaseCurve.toWorking(CurveMath.lut(c.green), useBaseline, look))
        if (c.blue.size >= 2) put(3, BaseCurve.toWorking(CurveMath.lut(c.blue), useBaseline, look))
    }

    private fun writeMask(out: FloatArray, index: Int, m: Mask, layers: Map<String, Int>) {
        val o = P.OFF_MASKS + index * P.MASK_FLOATS
        val comps = m.components.filter { it.type != MaskType.BITMAP || layers.containsKey(it.layerKey) }.take(6)
        out[o] = comps.size.toFloat(); out[o + 1] = m.amount; out[o + 2] = if (m.invert) 1f else 0f
        comps.forEachIndexed { i, c ->
            val t = o + 4 + i * 12
            out[t] = c.type.code.toFloat(); out[t + 1] = c.op.code.toFloat(); out[t + 2] = if (c.invert) 1f else 0f
            out[t + 3] = (layers[c.layerKey] ?: 0).toFloat()
            val p = c.params
            when (c.type) {
                MaskType.LINEAR -> for (k in 0 until 4) out[t + 4 + k] = p.getOrElse(k) { 0f }
                MaskType.RADIAL -> { for (k in 0 until 4) out[t + 4 + k] = p.getOrElse(k) { 0.2f }; out[t + 8] = p.getOrElse(4) { 0f }; out[t + 9] = p.getOrElse(5) { 0.5f } }
                MaskType.COLOR -> { for (k in 0 until 4) out[t + 4 + k] = p.getOrElse(k) { 0.5f }; out[t + 8] = p.getOrElse(4) { 0.5f } }
                MaskType.LUMINANCE -> { for (k in 0 until 3) out[t + 4 + k] = p.getOrElse(k) { 0f } }
                MaskType.BITMAP -> {}
            }
        }
    }
}
