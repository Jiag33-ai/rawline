package app.rawline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.rawline.core.data.ExportJobEntity
import app.rawline.core.data.forExport
import app.rawline.core.ml.Denoiser
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
    /** Set when the service is being stopped (time limit or destroyed): the running job goes back to waiting instead of being cancelled. */
    @Volatile var stopRequested = false

    private val exporter = Exporter(context, graph.maskStore, graph.patchStore) { h, a, p -> Denoiser(context, graph.modelStore).let { d -> try { d.run(h, a, p) } finally { d.release() } } }

    /** Adds photos to the export queue (kept in the database) and makes sure the service is working through it. */
    suspend fun enqueue(photos: List<Photo>, settings: ExportSettings) {
        val json = settings.toJson()
        graph.db.exports().add(photos.map { ExportJobEntity(photoKey = it.key, photoUri = it.uri, photoName = it.name, settingsJson = json, createdAt = System.currentTimeMillis()) })
        startService()
    }

    fun startService() {
        androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, ExportService::class.java))
    }

    fun cancelCurrent() { cancelled = true }

    /** Stops the queue cleanly: the current job is abandoned and returns to waiting, nothing is marked cancelled or failed. */
    fun stopForLater() { stopRequested = true; cancelled = true }

    /**
     * Call from the foreground at app start. A job left at "running" by a killed process would otherwise stay there forever,
     * so put it back to waiting (only when no service is alive to own it), then restart the service if anything is waiting.
     */
    suspend fun recoverAfterStart() {
        val dao = graph.db.exports()
        if (!ExportService.isRunning) dao.resetRunning()
        if (dao.nextWaiting() != null) startService()
    }

    /** Works through waiting jobs one at a time (blocking; runs on the service's thread). Returns how many files were written. */
    fun processQueue(): Int {
        val dao = graph.db.exports()
        File(context.cacheDir, "export-tmp").listFiles()?.forEach { it.delete() }   // leftovers from a killed process
        runBlocking { dao.resetRunning() }
        var written = 0
        var n = 0
        while (!stopRequested) {
            val job = runBlocking { dao.nextWaiting() } ?: break
            n++
            cancelled = false
            runBlocking { dao.start(job.id) }
            val remaining = runBlocking { dao.activeCount() }
            progress.value = ExportProgress(remaining + n - 1, n - 1, job.photoName, 0f, true)
            val entity = runBlocking { graph.db.photos().byUri(job.photoUri) }
            if (entity == null) { runBlocking { dao.finish(job.id, 3, "Photo no longer in the library", null, 0f) }; continue }
            val photo = entity.toModel()
            val settings = ExportSettings.fromJson(job.settingsJson)
            var lastWrite = 0L
            try {
                val uri = exportOne(photo, settings, n) { f ->
                    progress.value = ExportProgress(remaining + n - 1, n - 1, job.photoName, f, true)
                    val now = System.currentTimeMillis()
                    if (now - lastWrite > 400) { lastWrite = now; runBlocking { dao.progress(job.id, f) } }
                }
                if (stopRequested) { runBlocking { dao.finish(job.id, 0, null, null, 0f) }; break }
                else if (cancelled) runBlocking { dao.finish(job.id, 4, "Cancelled", null, 0f) }
                else if (uri != null) { written++; runBlocking { dao.finish(job.id, 2, null, uri.toString(), 1f) } }
                else runBlocking { dao.finish(job.id, 3, "Could not write the file", null, 0f) }
            } catch (e: Throwable) {
                app.rawline.core.cache.PerfLog.error("export ${photo.name}: ${e.message}")
                if (stopRequested) { runBlocking { dao.finish(job.id, 0, null, null, 0f) }; break }
                runBlocking { dao.finish(job.id, 3, e.message ?: e.javaClass.simpleName, null, 0f) }
            }
        }
        progress.value = ExportProgress(n, n, "", 1f, false, if (n == 0) null else "Exported $written of $n")
        return written
    }

    /** Renders to a cache file for the share sheet. */
    fun exportForShare(p: Photo, s: ExportSettings): File? {
        // Never delete a file another app may still be reading: only clear shares older than an hour, and give each share its own folder.
        val root = File(context.cacheDir, "share").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        val dir = File(root, "s" + System.currentTimeMillis() + "-" + System.nanoTime() % 100000).apply { mkdirs() }
        val f = File(dir, fileName(p, s, 1))
        cancelled = false  // a cancel aimed at an earlier queue job must not abort this share
        f.outputStream().use { out -> write(p, s.copy(destination = null), out, f.toURI().toString(), null) ?: return null }
        applyExif(f, p, s)
        return f
    }

    private fun exportOne(p: Photo, s: ExportSettings, n: Int, onFraction: (Float) -> Unit): Uri? {
        val name = fileName(p, s, n)
        if (s.format == ExportFormat.JPEG && s.metadata != MetadataMode.NONE) {
            // Render to a private file, write the EXIF there (ExifInterface needs a real, seekable file; many document
            // providers refuse an "rw" reopen), then copy the finished file to the destination.
            val tmp = File.createTempFile("export", ".jpg", File(context.cacheDir, "export-tmp").apply { mkdirs() })
            try {
                if (tmp.outputStream().use { write(p, s, it, name, onFraction) } == null) return null
                runCatching { writeExif(ExifInterface(tmp.path), p, s) }.onFailure { app.rawline.core.cache.PerfLog.error("export EXIF ${p.name}: ${it.message}") }
                val (out, uri) = openTarget(s, name, s.format.mime) ?: throw IllegalStateException("No place to save")
                try { out.use { o -> tmp.inputStream().use { it.copyTo(o) } } } catch (e: Throwable) { discard(uri); throw e }
                publish(uri)
                return uri
            } finally { tmp.delete() }
        }
        val (out, uri) = openTarget(s, name, s.format.mime) ?: throw IllegalStateException("No place to save")
        val ok = try { out.use { stream -> write(p, s, stream, name, onFraction) } } catch (e: Throwable) { discard(uri); throw e }
        if (ok == null) { discard(uri); return null }
        publish(uri)
        return uri
    }

    private fun publish(uri: Uri) {
        if (uri.authority == MediaStore.AUTHORITY) runCatching { context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) }
    }

    private fun discard(uri: Uri) {
        runCatching { if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri) else context.contentResolver.delete(uri, null, null) }
    }

    private fun write(p: Photo, s: ExportSettings, out: OutputStream, label: String, onFraction: ((Float) -> Unit)?): Unit? {
        // An edit that exists but cannot be read must fail the export, not produce an unedited photo.
        val recipe = runBlocking { graph.catalog.readRecipe(p) }.forExport(p.name)
        val res = exporter.render(p, recipe, s, if (s.format == ExportFormat.TIFF16) out else null, { onFraction?.invoke(it) }, { cancelled }) ?: return null
        val bmp = res.bitmap ?: return Unit
        when (s.format) {
            ExportFormat.JPEG -> if (!bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, s.quality, out)) throw java.io.IOException("Could not encode the JPEG")
            ExportFormat.PNG -> if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) throw java.io.IOException("Could not encode the PNG")
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

    fun fileName(p: Photo, s: ExportSettings, n: Int): String = ExportNaming.fileName(p, s, n)

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
            put(MediaStore.Images.Media.IS_PENDING, 1)  // hidden from the gallery until fully written, so a kill never leaves a truncated photo
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return (context.contentResolver.openOutputStream(uri, "wt") ?: return null) to uri
    }
}
