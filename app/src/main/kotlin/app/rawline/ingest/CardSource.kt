package app.rawline.ingest

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.rawline.core.data.ingest.CardFile
import app.rawline.core.data.ingest.ImportNaming
import java.io.IOException

/**
 * Lists the RAW files of a card (or USB-C reader) the person picked with the system folder picker. When the tree has a DCIM folder at its top only that
 * is walked, else the whole tree; hidden folders (a leading dot) are skipped. The card is only ever read.
 */
class SafCardSource(private val cr: ContentResolver, private val tree: Uri, private val maxFiles: Int = 50_000) {
    private val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)

    private class Child(val id: String, val name: String, val size: Long, val modified: Long, val dir: Boolean)

    private fun children(parentId: String): List<Child> {
        val out = ArrayList<Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        (cr.query(uri, cols, null, null, null) ?: throw IOException("could not read the card")).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += Child(c.getString(0), name, if (c.isNull(2)) -1L else c.getLong(2), if (c.isNull(3)) 0L else c.getLong(3), c.getString(4) == Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    /** Throws [IOException] when the card cannot be read at all (pulled, locked). */
    fun list(): List<CardFile> {
        val out = ArrayList<CardFile>()
        fun walk(id: String, depth: Int) {
            if (depth > 8) return
            for (k in children(id)) {
                if (out.size >= maxFiles) return
                if (k.dir) { if (!k.name.startsWith(".")) walk(k.id, depth + 1) }
                else if (ImportNaming.isRaw(k.name)) {
                    val u = DocumentsContract.buildDocumentUriUsingTree(tree, k.id)
                    out += CardFile(k.name, k.size, k.modified) { cr.openInputStream(u) ?: throw IOException("could not open ${k.name}") }
                }
            }
        }
        val root = DocumentsContract.getTreeDocumentId(tree)
        val dcim = children(root).firstOrNull { it.dir && it.name.equals("DCIM", ignoreCase = true) }
        walk(dcim?.id ?: root, 0)
        return out
    }
}
