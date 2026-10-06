package app.rawline.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    @Test fun aRecipeSavedBeforeTheFieldExistedReadsAsLookOne() {
        val old = """{"schemaVersion":1,"adjust":{"exposure":0.4}}"""
        val r = EditRecipe.fromJson(old)
        assertEquals(Look.V1, r.lookVersion); assertEquals(0.4f, r.adjust.exposure, 1e-6f)
    }

    @Test fun aNewRecipeIsTheCurrentLookAndRoundTrips() {
        assertEquals(Look.CURRENT, EditRecipe().lookVersion)
        val r = EditRecipe(lookVersion = Look.V1, adjust = Adjust(exposure = 0.5f))
        assertEquals(r, EditRecipe.fromJson(r.toJson()))
        assertEquals(Look.V1, EditRecipe.fromJson(r.toJson()).lookVersion)
        assertEquals(Look.V2, EditRecipe.fromJson(EditRecipe(adjust = Adjust(exposure = 0.5f)).toJson()).lookVersion)
    }

    @Test fun anOldEditThatIsOtherwiseDefaultIsNotADefaultRecipeSoItIsNeverDeleted() {
        assertTrue(EditRecipe().isDefault)
        assertFalse(EditRecipe(lookVersion = Look.V1).isDefault)
    }

    @Test fun aLookFromTheFutureIsRenderedWithTheNewestLookWeKnow() {
        val r = EditRecipe.fromJson("""{"schemaVersion":1,"lookVersion":9}""")
        assertEquals(Look.CURRENT, r.lookVersion)
        assertEquals(Look.V1, EditRecipe.fromJson("""{"lookVersion":0}""").lookVersion)
    }

    @Test fun updateLookChangesOnlyTheVersion() {
        val old = EditRecipe(lookVersion = Look.V1, adjust = Adjust(exposure = 0.3f, contrast = 12f))
        val up = old.withCurrentLook()
        assertEquals(Look.CURRENT, up.lookVersion); assertEquals(old.copy(lookVersion = Look.CURRENT), up)
        assertTrue(up.withCurrentLook() === up)
    }

    @Test fun resetBuildsANewEditUnderTheCurrentLook() { assertEquals(Look.CURRENT, EditRecipe().lookVersion) }

    @Test fun pasteSettingsKeepsTheTargetsLook() {
        val old = EditRecipe(lookVersion = Look.V1)
        val source = EditRecipe(adjust = Adjust(exposure = 0.8f, contrast = 20f))
        val out = RecipeMerge.paste(old, source, RecipeMerge.QUICK)
        assertEquals(Look.V1, out.lookVersion); assertEquals(0.8f, out.adjust.exposure, 1e-6f)
        // and the other way: a look 1 source pasted onto a new edit does not drag the target back to look 1
        assertEquals(Look.CURRENT, RecipeMerge.paste(EditRecipe(), EditRecipe(lookVersion = Look.V1, adjust = Adjust(exposure = 1f)), RecipeMerge.QUICK).lookVersion)
    }

    @Test fun twoRecipesThatDifferOnlyInLookHaveDifferentJsonSoAnythingKeyedOnTheJsonRendersAgain() {
        val a = EditRecipe(adjust = Adjust(exposure = 0.5f)); val b = a.copy(lookVersion = Look.V1)
        assertFalse(a.toJson() == b.toJson())
        assertFalse(a == b)
    }

    @Test fun savingNeverChangesTheLookOfAnOldEdit() {
        // read, write, read again: an old edit stays look 1 until someone moves it (nothing is rewritten on upgrade)
        val stored = "{\"schemaVersion\":1,\"adjust\":{\"exposure\":0.4}}"
        val again = EditRecipe.fromJson(EditRecipe.fromJson(stored).toJson())
        assertEquals(Look.V1, again.lookVersion)
    }
}
