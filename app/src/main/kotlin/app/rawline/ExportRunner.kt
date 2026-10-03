package app.rawline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.rawline.core.ml.Denoiser
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.core.render.ExportFormat
import app.rawline.core.render.ExportSettings
import app.rawline.core.render.Exporter
import app.rawline.core.render.MetadataMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportProgress(val total: Int = 0, val done: Int = 0, val current: String = "", val fraction: Float = 0f, val running: Boolean = false, val lastMessage: String? = null)

/** Writes finished files. One instance in the graph; the foreground service and the share button both use it. */
class ExportRunner(private val context: Context, private val graph: Graph) {
    val progress = MutableStateFlow(ExportProgress())
    @Volatile var cancelled = false

    private val exporter = Exporter(context, graph.maskStore, graph.patchStore) { h, a, p -> Denoiser(context, graph.modelStore).run(h, a, p) }

    /** Exports to the chosen folder (or Pictures/Rawline). Returns the number of files written. */
    fun exportAll(photos: List<Photo>, s: ExportSettings): Int {
        cancelled = false
        var ok = 0
        progress.value = ExportProgress(photos.size, 0, photos.firstOrNull()?.name ?: "", 0f, true)
        photos.forEachIndexed { i, p ->
            if (cancelled) return@forEachIndexed
            try {
                val uri = exportOne(p, s, i + 1) { f -> progress.value = ExportProgress(photos.size, i, p.name, f, true) }
                if (uri != null) ok++
            } catch (e: Throwable) {
                app.rawline.core.cache.PerfLog.error("export ${p.name}: ${e.message}")
                progress.value = progress.value.copy(lastMessage = "Failed: ${p.name}: ${e.message}")
            }
            progress.value = ExportProgress(photos.size, i + 1, p.name, 1f, true, progress.value.lastMessage)
        }
        progress.value = ExportProgress(photos.size, photos.size, "", 1f, false, if (cancelled) "Cancelled after $ok" else "Exported $ok of ${photos.size}")
        return ok
    }

    /** Renders to a cache file for the share sheet. */
    fun exportForShare(p: Photo, s: ExportSettings): File? {
        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, fileName(p, s, 1))
        f.outputStream().use { out -> write(p, s.copy(destination = null), out, f.toURI().toString(), null) ?: return null }
        applyExif(f, p, s)
        return f
    }

    private fun exportOne(p: Photo, s: ExportSettings, n: Int, onFraction: (Float) -> Unit): Uri? {
        val name = fileName(p, s, n)
        val (out, uri) = openTarget(s, name, s.format.mime) ?: throw IllegalStateException("No place to save")
        out.use { stream -> write(p, s, stream, name, onFraction) ?: run { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }; return null } }
        if (s.format == ExportFormat.JPEG && s.metadata != MetadataMode.NONE) runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd -> writeExif(ExifInterface(pfd.fileDescriptor), p, s) }
        }
        return uri
    }

    private fun write(p: Photo, s: ExportSettings, out: OutputStream, label: String, onFraction: ((Float) -> Unit)?): Unit? {
        val recipe = runBlocking { graph.catalog.loadRecipe(p) } ?: EditRecipe()
        val res = exporter.render(p, recipe, s, if (s.format == ExportFormat.TIFF16) out else null, { onFraction?.invoke(it) }, { cancelled }) ?: return null
        val bmp = res.bitmap ?: return Unit
        when (s.format) {
            ExportFormat.JPEG -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, s.quality, out)
            ExportFormat.PNG -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            ExportFormat.TIFF16 -> {}
        }
        bmp.recycle()
        return Unit
    }

    private fun applyExif(f: File, p: Photo, s: ExportSettings) {
        if (s.format != ExportFormat.JPEG || s.metadata == MetadataMode.NONE) return
        runCatching { writeExif(ExifInterface(f.path), p, s) }
    }

    private fun writeExif(e: ExifInterface, p: Photo, s: ExportSettings) {
        if (s.metadata == MetadataMode.ALL) {
            p.camera?.let { cam ->
                val parts = cam.split(" ", limit = 2)
                if (parts.size == 2) { e.setAttribute(ExifInterface.TAG_MAKE, parts[0]); e.setAttribute(ExifInterface.TAG_MODEL, parts[1]) } else e.setAttribute(ExifInterface.TAG_MODEL, cam)
            }
            p.lens?.let { e.setAttribute(ExifInterface.TAG_LENS_MODEL, it) }
            if (p.iso > 0) e.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, p.iso.toString())
            if (p.shutter > 0) e.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, p.shutter.toString())
            if (p.aperture > 0) e.setAttribute(ExifInterface.TAG_F_NUMBER, p.aperture.toString())
            if (p.focal > 0) e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(p.focal * 100).toInt()}/100")
            if (p.takenAt > 0) e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(p.takenAt)))
            e.setAttribute(ExifInterface.TAG_SOFTWARE, "Rawline")
        }
        if (s.copyright.isNotBlank()) e.setAttribute(ExifInterface.TAG_COPYRIGHT, s.copyright)
        e.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        e.saveAttributes()
    }

    fun fileName(p: Photo, s: ExportSettings, n: Int): String {
        val base = p.name.substringBeforeLast('.')
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(if (p.takenAt > 0) p.takenAt else p.modified))
        var name = s.pattern.ifBlank { "{name}" }
            .replace("{name}", base).replace("{date}", date).replace("{n}", n.toString().padStart(3, '0'))
            .replace("{rating}", p.rating.toString()).replace("{camera}", (p.camera ?: "camera").replace(Regex("[^A-Za-z0-9]+"), "-"))
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "$name.${s.format.ext}"
    }

    private fun openTarget(s: ExportSettings, name: String, mime: String): Pair<OutputStream, Uri>? {
        val dest = s.destination
        if (dest != null) {
            val tree = Uri.parse(dest)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: return null
            return (context.contentResolver.openOutputStream(doc, "wt") ?: return null) to doc
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Rawline")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return (context.contentResolver.openOutputStream(uri, "wt") ?: return null) to uri
    }
}
