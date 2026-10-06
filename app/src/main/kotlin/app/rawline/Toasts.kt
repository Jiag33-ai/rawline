package app.rawline

/** One toast. [seq] is unique per message, so showing the same text twice restarts the timer. */
data class ToastMsg(val text: String, val seq: Long, val isError: Boolean, val action: String? = null)

/** Toast rules without any UI types (testable on the host). */
object Toasts {
    const val SHORT_MS = 2500L
    const val ERROR_MS = 5000L

    fun isError(text: String): Boolean {
        val t = text.lowercase()
        return "failed" in t || "could not" in t || "couldn't" in t || "skipped" in t || "no longer" in t || "not written" in t
    }

    /** A toast with a button (Undo) stays as long as an error, so there is time to reach it. */
    fun durationMs(m: ToastMsg): Long = if (m.isError || m.action != null) ERROR_MS else SHORT_MS

    fun make(text: String, previous: ToastMsg?, action: String? = null): ToastMsg = ToastMsg(text, (previous?.seq ?: 0L) + 1, isError(text), action)

    /** Distance from the bottom edge so the toast clears the bar that each screen keeps there (dp). */
    fun bottomOffsetDp(route: String?): Int = when {
        route == null -> 16
        route.startsWith("loupe") -> 168   // stars row, exif line and the six button bar
        route.startsWith("edit") -> 112    // the editor dock
        route == "photos" -> 104           // the selection bar (two chip rows) when selecting
        else -> 16
    }
}
