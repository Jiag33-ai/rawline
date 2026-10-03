package app.rawline.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.rawline.core.model.Adjust
import app.rawline.core.model.Detail
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Effects
import app.rawline.core.model.Optics
import app.rawline.core.ui.ChipButton
import app.rawline.core.ui.RawSlider
import app.rawline.core.ui.SectionTitle
import kotlin.math.log2
import kotlin.math.pow

/** Which Adjust the Light, Colour and Effects panels edit: the whole photo, or one mask. */
class AdjustTarget(val get: (EditRecipe) -> Adjust, val set: (EditRecipe, Adjust) -> EditRecipe, val isMask: Boolean = false) {
    companion object {
        val Global = AdjustTarget({ it.adjust }, { r, a -> r.copy(adjust = a) })
    }
}

@Composable
fun AdjSlider(
    state: EditorState, target: AdjustTarget, label: String, range: ClosedFloatingPointRange<Float>,
    get: (Adjust) -> Float, set: (Adjust, Float) -> Adjust, decimals: Int = 0, default: Float = 0f,
    trackColors: List<Color>? = null, format: ((Float) -> String)? = null,
) {
    RawSlider(
        label, get(target.get(state.recipe)), range, default, decimals, trackColors = trackColors, format = format,
        onChange = { v -> state.live { r -> target.set(r, set(target.get(r), v)) } },
        onCommit = { state.commit(label) },
    )
}

@Composable
fun PanelColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) { content() }
}

// ---------------- Light ----------------

@Composable
fun LightPanel(state: EditorState, target: AdjustTarget = AdjustTarget.Global, onAuto: (() -> Unit)? = null) = PanelColumn {
    if (onAuto != null && !target.isMask) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAuto) { Text("Auto") }
            TextButton(onClick = {
                state.edit("Reset light") { r -> target.set(r, target.get(r).copy(exposure = 0f, contrast = 0f, highlights = 0f, shadows = 0f, whites = 0f, blacks = 0f)) }
            }) { Text("Reset light") }
        }
    }
    AdjSlider(state, target, "Exposure", -5f..5f, { it.exposure }, { a, v -> a.copy(exposure = v) }, decimals = 2)
    AdjSlider(state, target, "Contrast", -100f..100f, { it.contrast }, { a, v -> a.copy(contrast = v) })
    AdjSlider(state, target, "Highlights", -100f..100f, { it.highlights }, { a, v -> a.copy(highlights = v) })
    AdjSlider(state, target, "Shadows", -100f..100f, { it.shadows }, { a, v -> a.copy(shadows = v) })
    AdjSlider(state, target, "Whites", -100f..100f, { it.whites }, { a, v -> a.copy(whites = v) })
    AdjSlider(state, target, "Blacks", -100f..100f, { it.blacks }, { a, v -> a.copy(blacks = v) })
}

// ---------------- Effects ----------------

@Composable
fun EffectsPanel(state: EditorState, target: AdjustTarget = AdjustTarget.Global) = PanelColumn {
    SectionTitle("Presence")
    AdjSlider(state, target, "Texture", -100f..100f, { it.texture }, { a, v -> a.copy(texture = v) })
    AdjSlider(state, target, "Clarity", -100f..100f, { it.clarity }, { a, v -> a.copy(clarity = v) })
    AdjSlider(state, target, "Dehaze", -100f..100f, { it.dehaze }, { a, v -> a.copy(dehaze = v) })
    if (!target.isMask) {
        SectionTitle("Vignette")
        EffectSlider(state, "Amount", -100f..100f, 0f, { it.vignetteAmount }, { e, v -> e.copy(vignetteAmount = v) })
        EffectSlider(state, "Midpoint", 0f..100f, 50f, { it.vignetteMidpoint }, { e, v -> e.copy(vignetteMidpoint = v) })
        EffectSlider(state, "Roundness", -100f..100f, 0f, { it.vignetteRoundness }, { e, v -> e.copy(vignetteRoundness = v) })
        EffectSlider(state, "Feather", 0f..100f, 50f, { it.vignetteFeather }, { e, v -> e.copy(vignetteFeather = v) })
        SectionTitle("Grain")
        EffectSlider(state, "Amount ", 0f..100f, 0f, { it.grainAmount }, { e, v -> e.copy(grainAmount = v) })
        EffectSlider(state, "Size", 0f..100f, 25f, { it.grainSize }, { e, v -> e.copy(grainSize = v) })
        EffectSlider(state, "Roughness", 0f..100f, 50f, { it.grainRoughness }, { e, v -> e.copy(grainRoughness = v) })
    }
}

@Composable
private fun EffectSlider(state: EditorState, label: String, range: ClosedFloatingPointRange<Float>, default: Float, get: (Effects) -> Float, set: (Effects, Float) -> Effects) {
    RawSlider(label.trim(), get(state.recipe.effects), range, default,
        onChange = { v -> state.live { it.copy(effects = set(it.effects, v)) } }, onCommit = { state.commit("Effects ${label.trim()}") })
}

// ---------------- Detail ----------------

@Composable
fun DetailPanel(state: EditorState, onAiDenoiseChanged: (Boolean) -> Unit = {}) = PanelColumn {
    SectionTitle("Sharpening")
    DetailSlider(state, "Amount", 0f..150f, 0f, { it.sharpen }, { d, v -> d.copy(sharpen = v) })
    DetailSlider(state, "Radius", 0.5f..3f, 1f, { it.radius }, { d, v -> d.copy(radius = v) }, decimals = 1)
    DetailSlider(state, "Detail", 0f..100f, 25f, { it.detail }, { d, v -> d.copy(detail = v) })
    DetailSlider(state, "Masking", 0f..100f, 0f, { it.masking }, { d, v -> d.copy(masking = v) })
    SectionTitle("Noise reduction")
    DetailSlider(state, "Luminance", 0f..100f, 0f, { it.nrLuminance }, { d, v -> d.copy(nrLuminance = v) })
    DetailSlider(state, "Colour", 0f..100f, 0f, { it.nrColor }, { d, v -> d.copy(nrColor = v) })
    SectionTitle("AI denoise")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Denoise with on-device AI", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(state.recipe.detail.aiDenoise, { on ->
            state.edit(if (on) "AI denoise on" else "AI denoise off") { it.copy(detail = it.detail.copy(aiDenoise = on)) }
            onAiDenoiseChanged(on)
        })
    }
    if (state.recipe.detail.aiDenoise)
        DetailSlider(state, "AI amount", 0f..100f, 50f, { it.aiDenoiseAmount }, { d, v -> d.copy(aiDenoiseAmount = v) })
}

@Composable
private fun DetailSlider(state: EditorState, label: String, range: ClosedFloatingPointRange<Float>, default: Float, get: (Detail) -> Float, set: (Detail, Float) -> Detail, decimals: Int = 0) {
    RawSlider(label, get(state.recipe.detail), range, default, decimals,
        onChange = { v -> state.live { it.copy(detail = set(it.detail, v)) } }, onCommit = { state.commit("Detail $label") })
}

// ---------------- Optics ----------------

@Composable
fun OpticsPanel(state: EditorState) = PanelColumn {
    Text(
        "Manual lens fixes. Automatic lens profiles are not available in this build.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
    )
    OpticSlider(state, "Distortion", -100f..100f, { it.distortion }, { o, v -> o.copy(distortion = v) })
    OpticSlider(state, "Vignetting", -100f..100f, { it.vignetting }, { o, v -> o.copy(vignetting = v) })
}

@Composable
private fun OpticSlider(state: EditorState, label: String, range: ClosedFloatingPointRange<Float>, get: (Optics) -> Float, set: (Optics, Float) -> Optics) {
    RawSlider(label, get(state.recipe.optics), range, 0f,
        onChange = { v -> state.live { it.copy(optics = set(it.optics, v)) } }, onCommit = { state.commit(label) })
}

// ---------------- Colour: white balance, saturation ----------------

/** Temperature slider values are shown as kelvin around a nominal 5500 K as-shot reference. */
fun tempToKelvin(temp: Float): Float = 5500f * 2f.pow(temp / 50f)
fun kelvinToTemp(k: Float): Float = 50f * log2(k / 5500f)

@Composable
fun ColourBasicsPanel(state: EditorState, target: AdjustTarget, onAutoWb: (() -> Unit)?, onPickWb: (() -> Unit)?) {
    if (!target.isMask) {
        SectionTitle("White balance")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val presets = listOf("As shot" to 5500f, "Daylight" to 5500f, "Cloudy" to 6500f, "Shade" to 7500f, "Tungsten" to 3200f, "Fluorescent" to 4000f, "Flash" to 5500f)
            presets.forEach { (name, k) ->
                ChipButton(name, false, { state.edit("WB $name") { r -> target.set(r, target.get(r).copy(temp = kelvinToTemp(k), tint = if (name == "Fluorescent") 8f else 0f)) } })
            }
            if (onAutoWb != null) ChipButton("Auto", false, onAutoWb)
            if (onPickWb != null) ChipButton("Pick grey", false, onPickWb)
        }
    }
    AdjSlider(state, target, "Temperature", -100f..100f, { it.temp }, { a, v -> a.copy(temp = v) }, trackColors = listOf(Color(0xFF3B7DDD), Color(0xFFDDDDDD), Color(0xFFE8A33D)), format = { "${(tempToKelvin(it) / 10).toInt() * 10} K" })
    AdjSlider(state, target, "Tint", -100f..100f, { it.tint }, { a, v -> a.copy(tint = v) }, trackColors = listOf(Color(0xFF3DB06B), Color(0xFFDDDDDD), Color(0xFFC95CC9)))
    AdjSlider(state, target, "Vibrance", -100f..100f, { it.vibrance }, { a, v -> a.copy(vibrance = v) })
    AdjSlider(state, target, "Saturation", -100f..100f, { it.saturation }, { a, v -> a.copy(saturation = v) })
}
