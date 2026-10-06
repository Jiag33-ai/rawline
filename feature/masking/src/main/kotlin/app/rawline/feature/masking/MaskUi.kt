package app.rawline.feature.masking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.rawline.core.model.Adjust
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Geometry
import app.rawline.core.model.Mask
import app.rawline.core.model.MaskComponent
import app.rawline.core.model.MaskOp
import app.rawline.core.model.MaskType

/** The kinds of mask that are drawn or computed from the pixels (no AI model). */
enum class MaskKind(val label: String, val type: MaskType?) {
    BRUSH("Brush", MaskType.BITMAP), LINEAR("Linear gradient", MaskType.LINEAR), RADIAL("Radial gradient", MaskType.RADIAL),
    COLOR("Colour range", MaskType.COLOR), LUMINANCE("Luminance range", MaskType.LUMINANCE),
}

/** Everything the Add mask picker offers. [ai] tools need the on-device models. Depth range is not offered: no depth model is bundled. */
enum class MaskTool(val label: String, val icon: MaskIcon, val kind: MaskKind?, val ai: Boolean) {
    SUBJECT("Subject", MaskIcon.SUBJECT, null, true),
    SKY("Sky", MaskIcon.SKY, null, true),
    BACKGROUND("Background", MaskIcon.BACKGROUND, null, true),
    PEOPLE("People", MaskIcon.PEOPLE, null, true),
    OBJECT("Object", MaskIcon.OBJECT, null, true),
    BRUSH("Brush", MaskIcon.BRUSH, MaskKind.BRUSH, false),
    LINEAR("Linear", MaskIcon.LINEAR, MaskKind.LINEAR, false),
    RADIAL("Radial", MaskIcon.RADIAL, MaskKind.RADIAL, false),
    COLOUR("Colour range", MaskIcon.COLOUR, MaskKind.COLOR, false),
    LUMINANCE("Luminance range", MaskIcon.LUMINANCE, MaskKind.LUMINANCE, false),
    ;

    /** Name base for masks made with this tool ("Linear 1"). */
    val nameBase: String get() = when (this) { LINEAR -> "Linear"; RADIAL -> "Radial"; COLOUR -> "Colour"; LUMINANCE -> "Luminance"; else -> label }
}

enum class MaskPage { LIST, PICK, EDIT }

/** One line of feedback in the tray, with an optional action (Undo after a delete). */
class MaskToast(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Where an AI result goes: a new mask, or an extra part of an existing one. */
sealed interface AiTarget {
    data object NewMask : AiTarget
    data class Part(val maskId: String, val op: MaskOp) : AiTarget
}

/** UI state shared between the masking tab and the on-photo overlay. Cleared by [MaskingFeature.onExit]. */
class MaskUi {
    var page by mutableStateOf(MaskPage.LIST)
    /** Where the picker was opened from, so Back returns there. */
    var pickFrom by mutableStateOf(MaskPage.LIST)
    /** Selection is by id so it stays right after undo, redo and deletes. */
    var selectedId by mutableStateOf<String?>(null)
    var selectedComp by mutableIntStateOf(0)
    var sub by mutableStateOf("mask")
    /** Null = automatic (tint while shaping the mask, off while adjusting it); true or false once the person has chosen. */
    var overlayPref by mutableStateOf<Boolean?>(null)
    /** Set while the tray is on screen; the red tint is only ever on while true. */
    var active by mutableStateOf(false)

    var brushSize by mutableFloatStateOf(0.06f)     // fraction of frame height
    var brushFeather by mutableFloatStateOf(0.5f)
    var brushFlow by mutableFloatStateOf(1f)
    var brushErase by mutableStateOf(false)
    var brushAuto by mutableStateOf(false)
    /** Finger position (view px) while painting, for the live ring. */
    var brushCursor by mutableStateOf<Offset?>(null)
    /** Bumped when a brush slider moves so the ring shows at the middle of the photo for a moment. */
    var brushPreviewTick by mutableIntStateOf(0)

    var pickingColour by mutableStateOf(false)
    var pickingObject by mutableStateOf(false)
    var aiTarget by mutableStateOf<AiTarget>(AiTarget.NewMask)
    var busy by mutableStateOf<String?>(null)
    var toast by mutableStateOf<MaskToast?>(null)
    /** Combine mode for the next part added to a mask. */
    var nextOp by mutableStateOf(MaskOp.ADD)
    /** Which on-photo handle is being dragged (highlight), -1 for none. */
    var dragHandle by mutableIntStateOf(-1)
    var renaming by mutableStateOf(false)
    var density by mutableFloatStateOf(2.75f)

    fun clearTransient() {
        pickingColour = false; pickingObject = false; busy = null; toast = null
        brushCursor = null; dragHandle = -1; renaming = false; aiTarget = AiTarget.NewMask
    }
}

object MaskFactory {
    private var counter = 0
    @Synchronized fun newId() = "m${System.currentTimeMillis().toString(36)}${counter++}"

    /**
     * A new part. [crop] places gradients inside the visible (cropped) area so the handles start where the person can see them;
     * masks live in the uncropped frame.
     */
    fun component(kind: MaskKind, op: MaskOp = MaskOp.ADD, layerKey: String? = null, crop: Geometry = Geometry()): MaskComponent {
        val cx = crop.cropX + crop.cropW * 0.5f
        return when (kind) {
            MaskKind.LINEAR -> MaskComponent(MaskType.LINEAR, op, params = listOf(cx, crop.cropY + crop.cropH * 0.25f, cx, crop.cropY + crop.cropH * 0.6f), label = "Linear gradient")
            MaskKind.RADIAL -> {
                val r = 0.3f * crop.cropH
                MaskComponent(MaskType.RADIAL, op, params = listOf(cx, crop.cropY + crop.cropH * 0.5f, r, r, 0f, 0.5f), label = "Radial gradient")
            }
            MaskKind.COLOR -> MaskComponent(MaskType.COLOR, op, params = listOf(0.5f, 0.5f, 0.5f, 0.2f, 0.5f), label = "Colour range")
            MaskKind.LUMINANCE -> MaskComponent(MaskType.LUMINANCE, op, params = listOf(0.4f, 0.9f, 0.15f), label = "Luminance range")
            MaskKind.BRUSH -> MaskComponent(MaskType.BITMAP, op, layerKey = layerKey ?: "brush_${newId()}", label = "Brush")
        }
    }

    fun mask(kind: MaskKind, name: String, crop: Geometry = Geometry()): Mask = Mask(newId(), name, listOf(component(kind, crop = crop)), Adjust())

    fun aiPart(label: String, key: String, op: MaskOp = MaskOp.ADD) = MaskComponent(MaskType.BITMAP, op, layerKey = key, label = label)

    fun duplicate(m: Mask, name: String): Mask = m.copy(
        id = newId(), name = name,
        // a brush gets its own layer so painting on the copy never edits the original; AI layers are never changed, so they can be shared
        components = m.components.map { c -> if (MaskRules.isBrush(c)) c.copy(layerKey = "brush_${newId()}") else c },
    )
}

/** Small editing helpers kept for callers that address masks by index. */
fun EditRecipe.withMask(index: Int, f: (Mask) -> Mask): EditRecipe =
    copy(masks = masks.mapIndexed { i, m -> if (i == index) f(m) else m })

fun EditRecipe.withComponent(mask: Int, comp: Int, f: (MaskComponent) -> MaskComponent): EditRecipe =
    withMask(mask) { m -> m.copy(components = m.components.mapIndexed { i, c -> if (i == comp) f(c) else c }) }
