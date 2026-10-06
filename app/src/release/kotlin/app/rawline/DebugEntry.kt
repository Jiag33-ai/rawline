package app.rawline

import android.content.Context

/** Release: there is no debug entry. The debug source set has the same object with the real entry (Studio canvas). */
object DebugEntry {
    fun versionLongPress(context: Context): (() -> Unit)? = null
    fun reportSection(): Pair<String, String>? = null
}
