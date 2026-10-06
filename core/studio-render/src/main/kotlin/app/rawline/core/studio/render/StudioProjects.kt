package app.rawline.core.studio.render

import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.JavaFs
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.OpenResult
import app.rawline.core.studio.model.ProjectStore
import app.rawline.core.studio.model.RawPixels
import java.io.File
import java.util.UUID

/** A project that does not exist on disk yet: its document and the pixels of its layers. */
class NewProject(val document: Document, val pixels: Map<String, RawPixels>)

/** Project directories under `files/studio/{id}` (spec 3.6, the S1 form: no index yet, S1c adds `studio.db`). Every function except [sizeProblem] reads the disk: call them off the main thread. */
class StudioProjects(private val filesDir: File, private val appVersion: String = "0", private val clock: () -> Long = System::currentTimeMillis) {
    private val fs = JavaFs(filesDir)

    fun rootOf(id: String) = "studio/$id"
    fun fs() = fs

    /** Blank canvas with one opaque white Background layer. */
    fun newBlank(width: Int, height: Int, name: String = "Untitled"): NewProject {
        val id = newId(); val now = clock()
        val layer = Layer.Pixel(LayerCommon("bg", "Background"), width, height)
        return NewProject(Document(id, name, width, height, layers = listOf(layer), created = now, modified = now), mapOf("bg" to RawPixels(width, height, ByteArray(width * height * 4) { 255.toByte() })))
    }

    /** The picture is the first layer and sets the canvas size (already scaled to the 12 MP cap by [PhotoImport]). */
    fun newFromPhoto(photo: RawPixels, name: String = "Photo"): NewProject {
        val id = newId(); val now = clock()
        val layer = Layer.Pixel(LayerCommon("bg", name.take(40).ifEmpty { "Photo" }), photo.w, photo.h)
        return NewProject(Document(id, name.take(40).ifEmpty { "Photo" }, photo.w, photo.h, layers = listOf(layer), created = now, modified = now), mapOf("bg" to photo))
    }

    /** The project directory that was written most recently, or null. */
    fun latestId(): String? {
        val dirs = File(filesDir, "studio").listFiles { f -> f.isDirectory } ?: return null
        return dirs.mapNotNull { d ->
            val stamp = listOf("project.json", "project.json.new", "project.json.bak").map { File(d, it) }.filter { it.isFile }.maxOfOrNull { it.lastModified() }
            stamp?.let { d.name to it }
        }.maxByOrNull { it.second }?.first
    }

    /** Writes the home thumbnail (`thumb.jpg`, a JPEG made by [StudioExporter.thumbnailJpeg]) next to the project: a temporary file first, then an atomic rename, so the home never reads half a file. Worker thread. */
    fun writeThumbnail(id: String, jpeg: ByteArray) {
        val path = "${rootOf(id)}/thumb.jpg"
        fs.write("$path.tmp", jpeg)
        fs.rename("$path.tmp", path)
    }

    /** Throws ProjectFormatException (with a message for the user) or NewerSchemaException. */
    fun open(id: String): OpenResult = ProjectStore(fs, rootOf(id), appVersion).open()

    companion object {
        /** A message for a canvas size that is not allowed, or null. The same limits as [Document]. */
        fun sizeProblem(w: Int?, h: Int?): String? = when {
            w == null || h == null -> "Enter a width and a height in pixels."
            w < 1 || h < 1 -> "The canvas needs at least 1 pixel each way."
            w > Document.MAX_EDGE || h > Document.MAX_EDGE -> "The longest side can be ${Document.MAX_EDGE} pixels."
            w.toLong() * h > Document.MAX_PIXELS_S1 -> "A canvas can have ${Document.MAX_PIXELS_S1 / 1_000_000} megapixels for now (this one has ${"%.1f".format(w.toLong() * h / 1e6)})."
            else -> null
        }

        private fun newId() = "p" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
