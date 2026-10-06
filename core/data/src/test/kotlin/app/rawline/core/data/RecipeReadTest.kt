package app.rawline.core.data

import app.rawline.core.model.Adjust
import app.rawline.core.model.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecipeReadTest {
    private val edited = EditRecipe(adjust = Adjust(exposure = 1.25f))

    @Test fun noRowIsMissingNotUnreadable() {
        assertSame(RecipeRead.Missing, RecipeRead.parse(null))
    }

    @Test fun validJsonIsOk() {
        val r = RecipeRead.parse(edited.toJson())
        assertTrue(r is RecipeRead.Ok)
        assertEquals(1.25f, (r as RecipeRead.Ok).recipe.adjust.exposure, 0f)
    }

    @Test fun corruptJsonIsUnreadable() {
        assertSame(RecipeRead.Unreadable, RecipeRead.parse("{not json"))
        assertSame(RecipeRead.Unreadable, RecipeRead.parse(""))
    }

    @Test fun exportUsesTheEditWhenThereIsOne() {
        assertEquals(edited, RecipeRead.Ok(edited).forExport("a.RW2"))
    }

    @Test fun exportOfAPhotoWithNoEditUsesDefaults() {
        assertEquals(EditRecipe(), RecipeRead.Missing.forExport("a.RW2"))
    }

    @Test fun exportOfAnUnreadableEditFailsInsteadOfExportingUnedited() {
        try {
            RecipeRead.Unreadable.forExport("P1055415.RW2")
            fail("an unreadable edit must not export as an unedited photo")
        } catch (e: UnreadableEditException) {
            assertTrue(e.message!!, "P1055415.RW2" in e.message!!)
            assertTrue(e.message!!, "could not be read" in e.message!!)
        }
    }
}
