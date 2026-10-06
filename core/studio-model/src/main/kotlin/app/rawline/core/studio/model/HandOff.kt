package app.rawline.core.studio.model

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Open in Studio from Develop (spec 3.7, decision D9), the parts that need no Android. The hand off makes a NEW project from a photo and the recipe it has now; nothing flows
 * back and Develop's recipe file and catalogue are never written (the rule that Develop is untouched). Everything here writes only under the new project's own directory.
 */
class HandOffRequest(
    /** The new project, not on disk yet (the session saves it, like a project made from a photo). */
    val document: Document,
    /** Pixels of the layer, straight RGBA8, already scaled to the canvas. */
    val pixels: Map<String, RawPixels>,
    /** Runs once the project's first save worked, with the project directory: copies the source and the recipe next to project.json. A failure only means the copies are missing. */
    val afterFirstSave: (File) -> Unit,
)

object HandOffPlan {
    /** Written to the layer in project.json, so a later version can offer "Update from Develop" (smart objects arrive in S4). */
    const val ORIGIN = "develop"
    const val LAYER_ID = "l1"

    class Plan(val document: Document, val width: Int, val height: Int)

    /**
     * The document for a photo that Develop rendered at [renderedW] by [renderedH] pixels. The canvas is that picture fitted to the S1 limits (12 MP, 8192 on a side). A RAW file
     * that cannot be developed ([previewOnly]) hands off its embedded preview and the layer is named "Preview only" so nobody takes it for the full picture.
     */
    fun make(id: String, photoName: String, renderedW: Int, renderedH: Int, previewOnly: Boolean, nowMs: Long): Plan {
        val (w, h) = NewProject.fit(renderedW, renderedH)
        val name = photoName.substringBeforeLast('.').trim().take(40).ifEmpty { "Photo" }
        val layer = Layer.Pixel(LayerCommon(LAYER_ID, if (previewOnly) "Preview only" else "Photo", origin = ORIGIN), w, h)
        return Plan(Document(id, name, w, h, layers = listOf(layer), created = nowMs, modified = nowMs), w, h)
    }
}

object HandOffFiles {
    /**
     * Copies the original into `source/<hash>.<ext>` under [projectDir]: streamed (a RAW file is tens of megabytes) to a temporary file while a SHA-256 is taken, then renamed to the first 12 hex
     * characters of the hash, so the file is immutable and the same original is never stored twice. Returns the path relative to [projectDir].
     */
    fun copySource(input: InputStream, projectDir: File, ext: String): String {
        val dir = File(projectDir, "source"); dir.mkdirs()
        val tmp = File(dir, "incoming.tmp")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            input.use { ins -> tmp.outputStream().use { out -> val buf = ByteArray(64 * 1024); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }; out.flush(); out.fd.sync() } }
            val name = md.digest().joinToString("") { "%02x".format(it) }.take(12) + "." + safeExt(ext)
            val target = File(dir, name)
            if (target.exists()) tmp.delete() else Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return "source/$name"
        } finally { if (tmp.exists()) tmp.delete() }
    }

    /** `recipe.json`: the Develop recipe as it was at hand off (a copy, for "Update from Develop" later). */
    fun writeRecipe(projectDir: File, json: String) {
        projectDir.mkdirs()
        val tmp = File(projectDir, "recipe.json.tmp")
        tmp.outputStream().use { it.write(json.toByteArray(Charsets.UTF_8)); it.flush(); it.fd.sync() }
        Files.move(tmp.toPath(), File(projectDir, "recipe.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun safeExt(e: String): String = e.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(5).ifEmpty { "bin" }
}
