package app.rawline

import android.content.Context
import android.content.Intent
import app.rawline.core.studio.render.StudioStats

/**
 * Debug builds only (this file is in src/debug; src/release has a version that returns null): a long press on the Version row in Settings opens the Studio canvas
 * (S1b has no home screen yet) and the Copy report gets a Studio section once a project has been opened.
 */
object DebugEntry {
    fun versionLongPress(context: Context): (() -> Unit)? = { context.startActivity(Intent(context, StudioDebugActivity::class.java)) }
    fun reportSection(): Pair<String, String>? = StudioStats.describe()?.let { "Studio" to it }
}
