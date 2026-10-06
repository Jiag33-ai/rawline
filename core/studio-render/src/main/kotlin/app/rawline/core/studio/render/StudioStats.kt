package app.rawline.core.studio.render

/** What the Copy report says about Studio (spec section 6 of the S1b task). Written by the session, read by the report builder from any thread. Null until a project was opened. */
object StudioStats {
    class Snapshot(val canvasW: Int, val canvasH: Int, val layers: Int, val textureMb: Long, val historyMb: Long, val guardEstimateMb: Long, val saveState: String, val project: String)

    @Volatile private var snap: Snapshot? = null
    fun update(s: Snapshot) { snap = s }
    fun clear() { snap = null }

    fun describe(): String? {
        val s = snap ?: return null
        val guard = if (s.guardEstimateMb * 1024 * 1024 <= MemoryGuard.LIMIT_BYTES) "ok" else "over"
        return "Project ${s.project}: canvas ${s.canvasW} x ${s.canvasH} (${"%.1f".format(s.canvasW.toLong() * s.canvasH / 1e6)} MP), ${s.layers} layers\n" +
            "GPU textures ${s.textureMb} MB, memory guard ${s.guardEstimateMb} of ${MemoryGuard.LIMIT_BYTES / 1024 / 1024} MB ($guard), undo history ${s.historyMb} MB\n" +
            "Autosave: ${s.saveState}"
    }
}
