package app.rawline.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun busyRecipe() = EditRecipe(
        adjust = Adjust(exposure = 0.7f, contrast = 12f, temp = -8f, mixHue = List(8) { it * 2f }, curves = Curves(master = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f)), parametric = listOf(5f, 0f, -5f, 0f)),
            grading = Grading(shadows = Hsl(210f, 30f, -5f))),
        detail = Detail(sharpen = 40f, aiDenoise = true, aiDenoiseAmount = 60f),
        effects = Effects(vignetteAmount = -20f, grainAmount = 10f),
        geometry = Geometry(cropX = 0.1f, cropY = 0.2f, cropW = 0.6f, cropH = 0.5f, angle = 1.5f, rotate90 = 1, flipH = true, aspect = "3:2"),
        masks = listOf(
            Mask("m1", "Sky", listOf(MaskComponent(MaskType.BITMAP, MaskOp.ADD, layerKey = "ai_1"), MaskComponent(MaskType.LINEAR, MaskOp.SUBTRACT, params = listOf(0.1f, 0.2f, 0.3f, 0.4f))), Adjust(exposure = -0.5f), 0.8f, true, true),
            Mask("m2", "Brush", listOf(MaskComponent(MaskType.BITMAP, layerKey = "brush_x", strokes = listOf(BrushStroke(listOf(0.1f, 0.1f, 0.2f, 0.2f), 0.05f, 0.5f, 1f, false, true))))),
        ),
        heals = listOf(HealOp("remove", BrushStroke(listOf(0.5f, 0.5f), 0.04f, 0.4f, 1f, false), 0f, 0f, "heal_a", listOf(0.1f, 0.1f, 0.2f, 0.2f))),
    )

    @Test fun recipeJsonRoundTrips() {
        val r = busyRecipe()
        assertEquals(r, EditRecipe.fromJson(r.toJson()))
    }

    @Test fun defaultRecipeIsDefault() {
        assertTrue(EditRecipe().isDefault)
        assertFalse(busyRecipe().isDefault)
        assertEquals(EditRecipe(), EditRecipe.fromJson(EditRecipe().toJson()))
    }

    @Test fun oldRecipeWithMissingFieldsStillLoads() {
        val r = EditRecipe.fromJson("""{"schemaVersion":1,"adjust":{"exposure":1.0}}""")
        assertEquals(1f, r.adjust.exposure)
        assertEquals(8, r.adjust.mixHue.size)
    }

    @Test fun quickPasteCarriesLookButNotGeometryMasksOrHeals() {
        val src = busyRecipe()
        val target = EditRecipe()
        val out = RecipeMerge.paste(target, src, RecipeMerge.QUICK)
        assertEquals(src.adjust.exposure, out.adjust.exposure)
        assertEquals(target.geometry, out.geometry)
        assertEquals(target.masks, out.masks)
        assertEquals(target.heals, out.heals)
    }

    @Test fun pasteOnlyChosenPanels() {
        val src = busyRecipe()
        val out = RecipeMerge.paste(EditRecipe(), src, setOf(PasteScope.LIGHT))
        assertEquals(0.7f, out.adjust.exposure)
        assertEquals(0f, out.adjust.temp)
        assertTrue(out.masks.isEmpty())
        val withMasks = RecipeMerge.paste(EditRecipe(), src, setOf(PasteScope.MASKS, PasteScope.GEOMETRY))
        assertEquals(2, withMasks.masks.size)
        assertEquals(0.6f, withMasks.geometry.cropW)
    }

    private fun photo(id: Long, rating: Int, flag: Int, edited: Boolean, cam: String, t: Long) =
        Photo(id, "f", "u$id", "P$id.RW2", 1, t, Kind.RAW, true, takenAt = t, camera = cam, rating = rating, flag = flag, edited = edited)

    @Test fun filtersAndSorts() {
        val l = listOf(photo(1, 5, 1, true, "A", 100), photo(2, 2, 0, false, "B", 300), photo(3, 4, -1, false, "A", 200))
        assertEquals(listOf(2L, 3L, 1L), LibraryFilter().apply(l).map { it.id })
        assertEquals(listOf(1L, 3L), LibraryFilter(minRating = 4, sort = SortOrder.RATING).apply(l).map { it.id })
        assertEquals(listOf(1L), LibraryFilter(flag = FlagFilter.PICK).apply(l).map { it.id })
        assertEquals(listOf(2L, 3L), LibraryFilter(edited = EditedFilter.UNEDITED).apply(l).map { it.id })
        assertEquals(listOf(3L, 1L), LibraryFilter(camera = "A").apply(l).map { it.id })
        assertTrue(LibraryFilter(camera = "A").isActive)
        assertFalse(LibraryFilter().isActive)
    }

    @Test fun fileTypes() {
        assertEquals(Kind.RAW, FileTypes.kindOf("P1055415.RW2"))
        assertEquals(Kind.IMAGE, FileTypes.kindOf("a.JPG"))
        assertEquals(null, FileTypes.kindOf("notes.txt"))
    }
}
