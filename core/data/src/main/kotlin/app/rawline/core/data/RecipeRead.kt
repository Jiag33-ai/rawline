package app.rawline.core.data

import app.rawline.core.model.EditRecipe
import java.io.IOException

/**
 * What the catalogue holds for one photo. "No edit" and "an edit we cannot read" (corrupt, or written by a newer build)
 * are different: treating the second as the first would export an unedited picture or paste over a saved edit.
 */
sealed interface RecipeRead {
    data object Missing : RecipeRead
    data class Ok(val recipe: EditRecipe) : RecipeRead
    data object Unreadable : RecipeRead

    companion object {
        fun parse(json: String?): RecipeRead =
            if (json == null) Missing else runCatching { EditRecipe.fromJson(json) }.fold({ Ok(it) }, { Unreadable })
    }
}

/** Thrown instead of silently using default adjustments. The message is shown to the user in the export queue. */
class UnreadableEditException(photoName: String) :
    IOException("The saved edit for $photoName could not be read (it may come from a newer version), so it was not exported unedited.")

/** The recipe to render, or [UnreadableEditException]. A photo with no edit renders with defaults, which is correct. */
fun RecipeRead.forExport(photoName: String): EditRecipe = when (this) {
    is RecipeRead.Ok -> recipe
    RecipeRead.Missing -> EditRecipe()
    RecipeRead.Unreadable -> throw UnreadableEditException(photoName)
}
