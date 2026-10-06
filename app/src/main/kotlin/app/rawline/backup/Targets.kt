package app.rawline.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import app.rawline.core.data.BackupName
import app.rawline.core.data.BackupTarget
import app.rawline.core.data.FileBackupTarget
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A folder the person chose with the system folder picker. The permission is persisted, so the folder survives an uninstall and can be picked again. */
class SafBackupTarget(private val cr: ContentResolver, private val tree: Uri) : BackupTarget {
    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    private val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
    private val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)

    private fun find(name: String): Uri? {
        cr.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    override fun list(): List<BackupTarget.Entry> {
        val out = ArrayList<BackupTarget.Entry>()
        cr.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            while (c.moveToNext()) out += BackupTarget.Entry(c.getString(0), if (c.isNull(1)) 0L else c.getLong(1), if (c.isNull(2)) 0L else c.getLong(2))
        } ?: throw IOException("could not read the backup folder")
        return out
    }

    // octet-stream: providers add no extension to a ".part" name
    override fun create(name: String): OutputStream =
        cr.openOutputStream(DocumentsContract.createDocument(cr, treeDoc, "application/octet-stream", name) ?: throw IOException("could not create $name"))
            ?: throw IOException("could not write $name")
    override fun open(name: String): InputStream = cr.openInputStream(find(name) ?: throw IOException("missing $name")) ?: throw IOException("could not read $name")
    override fun rename(from: String, to: String) {
        if (find(to) != null) throw IOException("$to exists")
        DocumentsContract.renameDocument(cr, find(from) ?: throw IOException("missing $from"), to) ?: throw IOException("could not rename $from")
    }
    override fun delete(name: String) { find(name)?.let { DocumentsContract.deleteDocument(cr, it) } }
    override fun freeBytes(): Long? = null
}

/**
 * Documents/Rawline/backups through MediaStore.Files: needs no permission. On Android 11 and later the app sees only the rows it created itself,
 * and whether a reinstall sees them again is not something that can be proved offline, so this is the last resort and Settings says so.
 * A `.part` row is pending (IS_PENDING = 1, invisible to other apps) until the publishing update.
 */
class MediaStoreBackupTarget(private val cr: ContentResolver) : BackupTarget {
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val rel = "Documents/Rawline/backups/"

    private fun query(projection: Array<String>, name: String?): Cursor? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, if (name == null) "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" else "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, if (name == null) arrayOf(rel) else arrayOf(rel, name))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return cr.query(collection, projection, args, null)
    }

    private fun find(name: String): Uri? = query(arrayOf(MediaStore.MediaColumns._ID), name)?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

    override fun list(): List<BackupTarget.Entry> {
        val out = ArrayList<BackupTarget.Entry>()
        (query(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED), null) ?: throw IOException("could not read the backups"))
            .use { c -> while (c.moveToNext()) out += BackupTarget.Entry(c.getString(0), c.getLong(1), c.getLong(2) * 1000L) }
        return out
    }

    override fun create(name: String): OutputStream {
        find(name)?.let { cr.delete(it, null, null) }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(BackupName.PART)) "application/octet-stream" else "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, v) ?: throw IOException("could not create $name")
        return cr.openOutputStream(uri, "w") ?: run { cr.delete(uri, null, null); throw IOException("could not write $name") }
    }

    override fun open(name: String): InputStream = cr.openInputStream(find(name) ?: throw IOException("missing $name")) ?: throw IOException("could not read $name")

    override fun rename(from: String, to: String) {
        if (find(to) != null) throw IOException("$to exists")
        val uri = find(from) ?: throw IOException("missing $from")
        val v = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, to); put(MediaStore.MediaColumns.MIME_TYPE, "application/zip"); put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (cr.update(uri, v, null, null) < 1) throw IOException("could not rename $from")
    }

    override fun delete(name: String) { find(name)?.let { cr.delete(it, null, null) } }
    override fun freeBytes(): Long? = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()
}

object BackupTargets {
    const val PREF_TREE = "backup_tree"

    class Chosen(val kind: TargetKind, val target: BackupTarget, val label: String)

    /** The folder the person picked, if the permission to write to it is still held (it is dropped when the folder is deleted or access is reset). */
    fun usableTree(c: Context, prefs: SharedPreferences): Uri? {
        val s = prefs.getString(PREF_TREE, null) ?: return null
        val uri = Uri.parse(s)
        return uri.takeIf { u -> c.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission && it.isWritePermission } }
    }

    fun choose(c: Context, prefs: SharedPreferences): Chosen {
        val tree = usableTree(c, prefs)
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Rawline/backups")
        // All files access may be on while the folder still cannot be made (a read only card): then fall through to the next target
        val files = if (Environment.isExternalStorageManager()) runCatching { FileBackupTarget(dir).takeIf { dir.isDirectory && dir.canWrite() } }.getOrNull() else null
        val kind = TargetChoice.pick(files != null, tree != null)
        val target: BackupTarget = when (kind) { TargetKind.FILES -> files!!; TargetKind.FOLDER -> SafBackupTarget(c.contentResolver, tree!!); TargetKind.MEDIASTORE -> MediaStoreBackupTarget(c.contentResolver) }
        return Chosen(kind, target, TargetChoice.label(kind))
    }
}
