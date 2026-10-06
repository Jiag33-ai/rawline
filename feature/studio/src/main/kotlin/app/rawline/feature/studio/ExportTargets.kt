package app.rawline.feature.studio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/** Somewhere an export is written. [discard] removes what a failed or cancelled run left; [publish] makes a finished file visible. All of it is file work: call from a worker. */
class ExportTarget(val out: OutputStream, val uri: Uri, private val onPublish: () -> Boolean, private val onDiscard: () -> Unit, val file: File? = null) {
    fun publish(): Boolean = onPublish()
    fun discard() { runCatching { out.close() }; onDiscard() }
}

/** The three ways out of Studio, the same as Develop's export: Pictures/Rawline (MediaStore, hidden until complete), a document the user picked, and a cache file for the share sheet. */
object ExportTargets {
    fun pictures(context: Context, name: String, mime: String): ExportTarget? {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Rawline")
            put(MediaStore.Images.Media.IS_PENDING, 1)   // hidden from the gallery until fully written, so a kill never leaves a truncated picture
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val out = cr.openOutputStream(uri, "wt") ?: run { runCatching { cr.delete(uri, null, null) }; return null }
        return ExportTarget(out, uri,
            onPublish = { runCatching { cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) }.getOrDefault(0) > 0 },
            onDiscard = { runCatching { cr.delete(uri, null, null) } })
    }

    /** A document the user chose with the system "create document" picker (it exists already, empty). */
    fun document(context: Context, uri: Uri): ExportTarget? {
        val cr = context.contentResolver
        val out = cr.openOutputStream(uri, "wt") ?: return null
        return ExportTarget(out, uri, onPublish = { true }, onDiscard = { runCatching { android.provider.DocumentsContract.deleteDocument(cr, uri) } })
    }

    /** A cache file under `share/`, one folder per share; folders older than an hour are cleared first (an app may still be reading a newer one). */
    fun shareFile(context: Context, name: String): ExportTarget? {
        val root = File(context.cacheDir, "share").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        val dir = File(root, "s" + System.currentTimeMillis() + "-" + System.nanoTime() % 100000).apply { mkdirs() }
        val f = File(dir, name)
        val out = runCatching { f.outputStream() }.getOrNull() ?: return null
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        return ExportTarget(out, uri, onPublish = { true }, onDiscard = { dir.deleteRecursively() }, file = f)
    }

    /** The system chooser for [uri]. False when no app can take it. */
    fun share(context: Context, uri: Uri, mime: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(Intent.createChooser(send, "Share picture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
