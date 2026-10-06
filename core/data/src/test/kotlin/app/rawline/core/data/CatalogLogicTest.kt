package app.rawline.core.data

import app.rawline.core.model.Adjust
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLogicTest {
    // ---- saveRecipe decision (T1) ----
    @Test fun defaultSaveOverAnUnreadableStoredEditKeepsIt() =
        assertEquals(SaveAction.KEEP_UNREADABLE, saveAction(true, RecipeRead.Unreadable))

    @Test fun defaultSaveDeletesAReadableOrMissingEdit() {
        assertEquals(SaveAction.DELETE, saveAction(true, RecipeRead.Ok(EditRecipe(adjust = Adjust(exposure = 1f)))))
        assertEquals(SaveAction.DELETE, saveAction(true, RecipeRead.Missing))
    }

    @Test fun anEditedSaveAlwaysWrites() {
        listOf(RecipeRead.Missing, RecipeRead.Unreadable, RecipeRead.Ok(EditRecipe())).forEach { assertEquals(SaveAction.PUT, saveAction(false, it)) }
    }

    @Test fun defaultRecipeIsDefaultAndAnEditIsNot() {
        assertTrue(EditRecipe().isDefault)
        assertTrue(!EditRecipe(adjust = Adjust(exposure = 0.1f)).isDefault)
    }

    // ---- device scan pruning (T4, AQ-007) ----
    private fun known(n: Int) = (1..n).map { KnownRow(it.toLong(), "u$it", 0, 0) }

    @Test fun emptyListingNeverPrunes() {
        val r = ScanPrune.decide(known(100), emptySet(), null)
        assertTrue(r.gone.isEmpty()); assertNull(r.suspicious)
    }

    @Test fun smallLibraryPrunesNormally() {
        val r = ScanPrune.decide(known(10), setOf("u1", "u2"), null)
        assertEquals(8, r.gone.size)
    }

    @Test fun normalDeletionsArePrunedAtOnce() {
        val seen = (1..95).map { "u$it" }.toSet()
        val r = ScanPrune.decide(known(100), seen, null)
        assertEquals(listOf(96L, 97L, 98L, 99L, 100L), r.gone.map { it.id })
    }

    @Test fun bigDropIsNotBelievedOnTheFirstScan() {
        val seen = (1..30).map { "u$it" }.toSet()
        val r = ScanPrune.decide(known(100), seen, null)
        assertTrue(r.gone.isEmpty()); assertEquals(30, r.suspicious)
    }

    @Test fun bigDropIsAcceptedWhenASecondScanAgrees() {
        val seen = (1..30).map { "u$it" }.toSet()
        val r = ScanPrune.decide(known(100), seen, 30)
        assertEquals(70, r.gone.size); assertNull(r.suspicious)
    }

    @Test fun aDifferentBigDropRestartsTheWait() {
        val r = ScanPrune.decide(known(100), (1..40).map { "u$it" }.toSet(), 30)
        assertTrue(r.gone.isEmpty()); assertEquals(40, r.suspicious)
    }

    // ---- reapply after a re-scan (T4) ----
    private fun row(name: String, size: Long, mod: Long, rating: Int = 0, flag: Int = 0, label: Int = 0, edited: Boolean = false, uri: String = "u/$name") =
        PhotoEntity(folderUri = "device:x", uri = uri, name = name, size = size, modified = mod, isRaw = true, rating = rating, flag = flag, label = label, edited = edited)

    @Test fun reappliedRatingAndEditMarkReturnToAReindexedPhoto() {
        val key = Photo.keyOf("A.RW2", 10, 5)
        val plan = Reapply.plan(listOf(row("A.RW2", 10, 5)), mapOf(key to MetaEntity(key, 4, 1, 2, 7)), setOf(key))
        assertEquals(1, plan.size)
        with(plan[0]) { assertEquals(listOf(4, 1, 2), listOf(rating, flag, label)); assertTrue(edited); assertEquals("u/A.RW2", uri) }
    }

    @Test fun nothingIsWrittenWhenRowsAlreadyMatch() {
        val key = Photo.keyOf("A.RW2", 10, 5)
        assertTrue(Reapply.plan(listOf(row("A.RW2", 10, 5, 4, 1, 2, true)), mapOf(key to MetaEntity(key, 4, 1, 2)), setOf(key)).isEmpty())
    }

    @Test fun aModifiedFileLosesItsSavedStateBecauseTheKeyChanged() {
        // documented in docs/DECISIONS.md: the key is name|size|modified, so a changed time is a different photo
        val old = Photo.keyOf("A.RW2", 10, 5)
        assertTrue(Reapply.plan(listOf(row("A.RW2", 10, 6)), mapOf(old to MetaEntity(old, 4, 0, 0)), setOf(old)).isEmpty())
    }

    @Test fun editMarkIsClearedWhenTheEditIsGone() {
        val plan = Reapply.plan(listOf(row("A.RW2", 10, 5, edited = true)), emptyMap(), emptySet())
        assertEquals(1, plan.size); assertTrue(!plan[0].edited)
    }

    @Test fun identicalNameSizeAndTimeShareAKeyByDesign() {
        val a = row("IMG_1.RW2", 10, 5, uri = "folderA/IMG_1.RW2"); val b = row("IMG_1.RW2", 10, 5, uri = "folderB/IMG_1.RW2")
        val key = Photo.keyOf("IMG_1.RW2", 10, 5)
        val plan = Reapply.plan(listOf(a, b), mapOf(key to MetaEntity(key, 3, 0, 0)), emptySet())
        assertEquals(listOf("folderA/IMG_1.RW2", "folderB/IMG_1.RW2"), plan.map { it.uri })   // both get the one rating
    }

    // ---- XMP sidecar (T1/T3 area) ----
    @Test fun xmpRatingAndLabelRoundTrip() {
        for (rating in 0..5) for (label in 0..5) {
            assertEquals(rating to label, Xmp.parse(Xmp.build(rating, label, null)))
        }
    }

    @Test fun xmpCarriesDevelopSettingsOnlyForAReadableRecipe() {
        val withEdit = Xmp.build(3, 0, EditRecipe(adjust = Adjust(exposure = 0.5f)).toJson())
        assertTrue("rawline:Exposure=\"0.5\"" in withEdit)
        assertTrue("rawline:" !in Xmp.build(3, 0, "{broken").substringAfter("xmlns:rawline"))
    }

    @Test fun xmpFromAnotherEditorWithoutRatingIsNull() = assertNull(Xmp.parse("<x:xmpmeta/>"))

    @Test fun xmpRatingIsClamped() = assertEquals(0 to 0, Xmp.parse("xmp:Rating=\"-1\""))

    @Test fun sidecarNameReplacesTheExtension() {
        assertEquals("P1055415.xmp", Xmp.sidecarName("P1055415.RW2"))
        assertEquals("a.b.xmp", Xmp.sidecarName("a.b.RW2"))
    }
}
