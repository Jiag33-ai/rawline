package app.rawline

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.feature.onboarding.StudioText

/** Flag off (studio.enabled false and no -PstudioEnabled=true): Studio does not exist in this build. No Studio class is compiled in and the Studio modules are not on the classpath. */
object StudioEntry {
    const val available = false

    /** Develop, exactly as it was: no switch. */
    @Composable
    fun Root(openRoute: String?, develop: @Composable (modeSwitch: (@Composable () -> Unit)?) -> Unit) { develop(null) }

    fun reportSection(context: Context, prefs: SharedPreferences): Pair<String, String>? = null
    fun debugLongPress(context: Context): (() -> Unit)? = null
    fun settingsNote(): String? = null

    /** Open in Studio from the editor menu: none in this build. */
    fun openInStudio(context: Context, graph: Graph, photo: Photo, recipe: () -> EditRecipe, notify: (String) -> Unit): (() -> Unit)? = null

    /** The Studio screen of the welcome flow: none, so the flow has five screens. */
    @Composable
    fun onboardingText(): StudioText? = null
}
