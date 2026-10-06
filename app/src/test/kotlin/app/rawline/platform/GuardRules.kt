package app.rawline.platform

/** BK-246 and BK-426: what the manifest must and must not say. Returns the problems found (empty is good). */
object ManifestRules {
    fun problems(xml: String): List<String> {
        val out = ArrayList<String>()
        if (Regex("""android:screenOrientation\s*=""").containsMatchIn(xml)) out += "screenOrientation is set: no orientation lock (Android 17 ignores it on large screens, and a phone user rotates)"
        if (Regex("""android:resizeableActivity\s*=\s*"false"""").containsMatchIn(xml)) out += "resizeableActivity=false: not allowed"
        if (Regex("""android:(minAspectRatio|maxAspectRatio)\s*=""").containsMatchIn(xml)) out += "an aspect ratio limit is set: not allowed"
        if (!Regex("""android:enableOnBackInvokedCallback\s*=\s*"true"""").containsMatchIn(xml)) out += "android:enableOnBackInvokedCallback=\"true\" is missing: say it explicitly so predictive back never depends on a default"
        if (Regex("""android:enableOnBackInvokedCallback\s*=\s*"false"""").containsMatchIn(xml)) out += "predictive back is switched off"
        return out
    }
}

/**
 * BK-246: every BackHandler in the app is listed here with where it is and what it closes, in the order they take effect (the one registered last, which is the innermost
 * and the newest composition, wins). A new BackHandler fails `BackInventoryTest` until it is added to this table and to docs/PLATFORM.md.
 */
object BackInventory {
    /** file name (no path) to the number of BackHandler registrations it holds. */
    val EXPECTED: Map<String, Int> = mapOf(
        "EditorHost.kt" to 1,      // leave the editor: save the recipe, then Back to the viewer. Always enabled; the outermost, so everything below wins first.
        "EditorScreen.kt" to 1,    // close the open panel (a crop is cancelled first, the curve page steps back to Basic, then the panel closes)
        "MaskTray.kt" to 1,        // step back inside the masking tray (busy, picking, renaming, edit page, pick page)
        "LoupeScreen.kt" to 1,     // close the info panel or the star picker
        "LibraryScreen.kt" to 1,   // clear the selection
        "CanvasScreen.kt" to 1,    // Studio: close export, then the layers panel, then save and leave
        "StudioRoot.kt" to 1,      // Studio home (S1c): Back on the Studio home goes back to Develop; the canvas has its own handler above
    )

    private val CALL = Regex("""\bBackHandler\s*[({]""")
    private val IMPORT = Regex("""^\s*import\s""")

    /** Counts registrations per file in the given sources (file name to text). Comments and imports do not count. */
    fun count(sources: Map<String, String>): Map<String, Int> {
        val out = sortedMapOf<String, Int>()
        for ((name, text) in sources) {
            var n = 0
            for (line in text.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || IMPORT.containsMatchIn(line)) continue
                n += CALL.findAll(line).count()
            }
            if (n > 0) out[name] = n
        }
        return out
    }
}


