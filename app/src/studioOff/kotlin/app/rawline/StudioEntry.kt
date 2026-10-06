package app.rawline

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable

/** Flag off (studio.enabled false and no -PstudioEnabled=true): Studio does not exist in this build. No Studio class is compiled in and the Studio modules are not on the classpath. */
object StudioEntry {
    const val available = false

    /** Develop, exactly as it was: no switch. */
    @Composable
    fun Root(openRoute: String?, develop: @Composable (modeSwitch: (@Composable () -> Unit)?) -> Unit) { develop(null) }

    fun reportSection(context: Context, prefs: SharedPreferences): Pair<String, String>? = null
    fun debugLongPress(context: Context): (() -> Unit)? = null
    fun settingsNote(): String? = null
}
