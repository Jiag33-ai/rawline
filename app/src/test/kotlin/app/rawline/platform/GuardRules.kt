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
        out += serviceProblems(xml)
        return out
    }

    private val SERVICE = Regex("""<service\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
    private fun attr(tag: String, name: String) = Regex("""android:$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)
    private fun permissionFor(type: String) = "android.permission.FOREGROUND_SERVICE_" + type.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

    /** Services: none exported, a job service is bound only by the system, and a foreground service type needs its own permission (Android 14 and later). */
    fun serviceProblems(xml: String): List<String> {
        val out = ArrayList<String>()
        val perms = Regex("""<uses-permission\s+android:name\s*=\s*"([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
        for (m in SERVICE.findAll(xml)) {
            val tag = m.value; val name = attr(tag, "name") ?: "?"
            if (attr(tag, "exported") != "false") out += "service $name must say android:exported=\"false\""
            if (name.endsWith("JobService") && attr(tag, "permission") != "android.permission.BIND_JOB_SERVICE") out += "job service $name must require android.permission.BIND_JOB_SERVICE"
            val types = attr(tag, "foregroundServiceType")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
            if (types.isNotEmpty() && "android.permission.FOREGROUND_SERVICE" !in perms) out += "service $name is a foreground service but android.permission.FOREGROUND_SERVICE is not declared"
            for (t in types) if (permissionFor(t) !in perms) out += "service $name has foregroundServiceType $t but ${permissionFor(t)} is not declared"
        }
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
        "OnboardingScreen.kt" to 1, // welcome screens (W27): one screen back; on the first screen it closes them like Skip. Always enabled while they show, so Back never reaches the screens under them.
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


