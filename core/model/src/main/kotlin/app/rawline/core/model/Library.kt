package app.rawline.core.model

enum class SortOrder(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), NAME("Name"), RATING("Rating") }
enum class FlagFilter(val label: String) { ANY("Any flag"), PICK("Picks"), REJECT("Rejects"), NONE("Unflagged") }
enum class EditedFilter(val label: String) { ANY("All"), EDITED("Edited"), UNEDITED("Unedited") }

data class LibraryFilter(
    val minRating: Int = 0,
    val flag: FlagFilter = FlagFilter.ANY,
    val edited: EditedFilter = EditedFilter.ANY,
    val camera: String? = null,
    val sort: SortOrder = SortOrder.NEWEST,
    /** BK-498: show RAW photos only (the first run default when the phone has any). A view choice, not a filter the user built, so it does not count in [isActive]. */
    val rawOnly: Boolean = false,
) {
    val isActive get() = minRating > 0 || flag != FlagFilter.ANY || edited != EditedFilter.ANY || camera != null

    fun apply(all: List<Photo>): List<Photo> {
        val f = all.filter {
            it.rating >= minRating &&
                when (flag) { FlagFilter.ANY -> true; FlagFilter.PICK -> it.flag == 1; FlagFilter.REJECT -> it.flag == -1; FlagFilter.NONE -> it.flag == 0 } &&
                when (edited) { EditedFilter.ANY -> true; EditedFilter.EDITED -> it.edited; EditedFilter.UNEDITED -> !it.edited } &&
                (camera == null || it.camera == camera) &&
                (!rawOnly || it.kind == Kind.RAW)
        }
        return when (sort) {
            SortOrder.NEWEST -> f.sortedWith(compareByDescending<Photo> { if (it.takenAt > 0) it.takenAt else it.modified }.thenByDescending { it.id })
            SortOrder.OLDEST -> f.sortedWith(compareBy<Photo> { if (it.takenAt > 0) it.takenAt else it.modified }.thenBy { it.id })
            SortOrder.NAME -> f.sortedBy { it.name.lowercase() }
            SortOrder.RATING -> f.sortedWith(compareByDescending<Photo> { it.rating }.thenByDescending { it.takenAt })
        }
    }
}

/** Panels that can be pasted from one photo to others. */
enum class PasteScope(val label: String) {
    LIGHT("Light"), COLOUR("Colour (white balance, vibrance, saturation)"), CURVE("Tone curve"), MIXER("Colour mixer"),
    GRADING("Colour grading"), EFFECTS("Effects (texture, clarity, dehaze, vignette, grain)"), DETAIL("Detail"), OPTICS("Optics"),
    GEOMETRY("Crop and geometry"), MASKS("Masks"), HEALS("Healing and removal");
}

object RecipeMerge {
    /** What a one tap paste carries: every look setting, but not crop, masks or repairs (those belong to one photo's content). */
    val QUICK: Set<PasteScope> = PasteScope.entries.toSet() - PasteScope.GEOMETRY - PasteScope.MASKS - PasteScope.HEALS

    fun paste(target: EditRecipe, source: EditRecipe, scopes: Set<PasteScope>): EditRecipe {
        var t = target
        val sa = source.adjust
        var a = t.adjust
        if (PasteScope.LIGHT in scopes) a = a.copy(exposure = sa.exposure, contrast = sa.contrast, highlights = sa.highlights, shadows = sa.shadows, whites = sa.whites, blacks = sa.blacks)
        if (PasteScope.COLOUR in scopes) a = a.copy(temp = sa.temp, tint = sa.tint, vibrance = sa.vibrance, saturation = sa.saturation)
        if (PasteScope.CURVE in scopes) a = a.copy(curves = sa.curves)
        if (PasteScope.MIXER in scopes) a = a.copy(mixHue = sa.mixHue, mixSat = sa.mixSat, mixLum = sa.mixLum)
        if (PasteScope.GRADING in scopes) a = a.copy(grading = sa.grading)
        if (PasteScope.EFFECTS in scopes) {
            a = a.copy(texture = sa.texture, clarity = sa.clarity, dehaze = sa.dehaze)
            t = t.copy(effects = source.effects)
        }
        t = t.copy(adjust = a)
        if (PasteScope.DETAIL in scopes) t = t.copy(detail = source.detail)
        if (PasteScope.OPTICS in scopes) t = t.copy(optics = source.optics)
        if (PasteScope.GEOMETRY in scopes) t = t.copy(geometry = source.geometry)
        if (PasteScope.MASKS in scopes) {
            // brush layers are edited, so each pasted brush gets its own key (rebuilt from its strokes); AI layers are read only and shared
            var n = 0
            val stamp = System.nanoTime().toString(36)
            t = t.copy(masks = source.masks.map { m ->
                m.copy(id = m.id + "p$stamp", components = m.components.map { c ->
                    if (c.type == MaskType.BITMAP && c.layerKey?.startsWith("brush_") == true) c.copy(layerKey = "brush_p${stamp}_${n++}") else c
                })
            })
        }
        if (PasteScope.HEALS in scopes) t = t.copy(heals = source.heals)
        return t
    }
}
