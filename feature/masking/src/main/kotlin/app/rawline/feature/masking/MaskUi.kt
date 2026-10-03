package app.rawline.feature.masking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.rawline.core.model.Adjust
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskComponent
import app.rawline.core.model.MaskOp
import app.rawline.core.model.MaskType

enum class MaskKind(val label: String, val type: MaskType?) {
    BRUSH("Brush", MaskType.BITMAP), LINEAR("Linear", MaskType.LINEAR), RADIAL("Radial", MaskType.RADIAL),
    COLOR("Colour range", MaskType.COLOR), LUMINANCE("Luminance range", MaskType.LUMINANCE),
}

/** UI state shared between the masking tab and the on-photo overlay. */
class MaskUi {
    var selected by mutableIntStateOf(-1)
    var selectedComp by mutableIntStateOf(0)
    var showOverlay by mutableStateOf(true)
    var brushSize by mutableFloatStateOf(0.06f)     // fraction of frame height
    var brushFeather by mutableFloatStateOf(0.5f)
    var brushFlow by mutableFloatStateOf(1f)
    var brushErase by mutableStateOf(false)
    var brushAuto by mutableStateOf(false)
    var pickingColour by mutableStateOf(false)
    var sub by mutableStateOf("mask")
    var busy by mutableStateOf<String?>(null)
    var pickingObject by mutableStateOf(false)
}

object MaskFactory {
    private var counter = 0
    fun newId() = "m${System.currentTimeMillis().toString(36)}${counter++}"

    fun component(kind: MaskKind, op: MaskOp = MaskOp.ADD, layerKey: String? = null): MaskComponent = when (kind) {
        MaskKind.LINEAR -> MaskComponent(MaskType.LINEAR, op, params = listOf(0.5f, 0.25f, 0.5f, 0.6f), label = "Linear gradient")
        MaskKind.RADIAL -> MaskComponent(MaskType.RADIAL, op, params = listOf(0.5f, 0.5f, 0.3f, 0.3f, 0f, 0.5f), label = "Radial gradient")
        MaskKind.COLOR -> MaskComponent(MaskType.COLOR, op, params = listOf(0.5f, 0.5f, 0.5f, 0.2f, 0.5f), label = "Colour range")
        MaskKind.LUMINANCE -> MaskComponent(MaskType.LUMINANCE, op, params = listOf(0.4f, 0.9f, 0.15f), label = "Luminance range")
        MaskKind.BRUSH -> MaskComponent(MaskType.BITMAP, op, layerKey = layerKey ?: "brush_${newId()}", label = "Brush")
    }

    fun mask(kind: MaskKind, n: Int): Mask = Mask(newId(), "${kind.label} $n", listOf(component(kind)), Adjust())

    fun duplicate(m: Mask): Mask = m.copy(
        id = newId(), name = m.name + " copy",
        // a brush gets its own layer so painting on the copy never edits the original; AI layers are never changed, so they can be shared
        components = m.components.map { c -> if (c.type == MaskType.BITMAP && c.layerKey?.startsWith("brush_") == true) c.copy(layerKey = "brush_${newId()}") else c },
    )
}

/** Small editing helpers so the UI code stays readable. */
fun EditRecipe.withMask(index: Int, f: (Mask) -> Mask): EditRecipe =
    copy(masks = masks.mapIndexed { i, m -> if (i == index) f(m) else m })

fun EditRecipe.withComponent(mask: Int, comp: Int, f: (MaskComponent) -> MaskComponent): EditRecipe =
    withMask(mask) { m -> m.copy(components = m.components.mapIndexed { i, c -> if (i == comp) f(c) else c }) }
