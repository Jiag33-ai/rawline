package app.rawline.core.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.rawline.core.cache.PerfLog
import app.rawline.core.cache.PreviewDecoder
import app.rawline.core.cache.ThumbStore
import app.rawline.core.model.FileTypes
import app.rawline.core.model.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class IndexProgress(val total: Int = 0, val done: Int = 0, val running: Boolean = false)

/**
 * Lists a SAF folder, adds rows straight away (so the grid fills at once) and then fills in
 * thumbnails and EXIF newest first. Headers only: no raw data is ever decoded here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Indexer(private val context: Context, private val dao: PhotoDao, private val thumbs: ThumbStore) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress

    private class Doc(val uri: String, val name: String, val size: Long, val modified: Long, val raw: Boolean)

    suspend fun scan(folder: Uri) = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        val folderKey = folder.toString()
        val docs = ArrayList<Doc>()
        walk(folder, DocumentsContract.getTreeDocumentId(folder), docs)
        val known = dao.known(folderKey)
        val byUri = known.associateBy { it.uri }
        val seen = HashSet<String>(docs.size)
        val fresh = ArrayList<PhotoEntity>()
        val stale = ArrayList<Long>()
        for (d in docs) {
            seen.add(d.uri)
            val k = byUri[d.uri]
            if (k == null) fresh.add(PhotoEntity(folderUri = folderKey, uri = d.uri, name = d.name, size = d.size, modified = d.modified, isRaw = d.raw))
            else if (k.modified != d.modified || k.size != d.size) { stale.add(k.id); fresh.add(PhotoEntity(folderUri = folderKey, uri = d.uri, name = d.name, size = d.size, modified = d.modified, isRaw = d.raw)) }
        }
        known.filter { it.uri !in seen }.forEach { stale.add(it.id) }
        stale.chunked(500).forEach { dao.delete(it) }
        fresh.chunked(200).forEach { dao.insertAll(it) }
        PerfLog.record("scan_list_ms", (System.nanoTime() - t0) / 1_000_000)
        indexPending(folderKey)
    }

    private suspend fun indexPending(folderKey: String) {
        val todo = dao.pending(folderKey)
        if (todo.isEmpty()) { _progress.value = IndexProgress(); return }
        val t0 = System.nanoTime()
        val done = AtomicInteger()
        _progress.value = IndexProgress(todo.size, 0, true)
        val gate = Semaphore(4)
        val failures = AtomicInteger()
        kotlinx.coroutines.coroutineScope {
            todo.map { row ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try { indexOne(row) } catch (e: Throwable) { failures.incrementAndGet(); PerfLog.error("index ${row.name}: ${e.message}"); dao.markFailed(row.id) }
                    }
                    val n = done.incrementAndGet()
                    if (n % 10 == 0 || n == todo.size) _progress.value = IndexProgress(todo.size, n, n < todo.size)
                }
            }.awaitAll()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        PerfLog.record("index_total_ms (n=${todo.size})", ms)
        PerfLog.record("index_per_file_ms", ms / todo.size)
        _progress.value = IndexProgress(todo.size, todo.size, false)
    }

    private suspend fun indexOne(row: PhotoEntity) {
        val photo = row.toModel()
        val r = PreviewDecoder.decode(context, photo, 320, software = true, wantExif = true)
        if (r == null) { dao.markFailed(row.id); PerfLog.error("no preview: ${row.name}"); return }
        thumbs.save(row.id, r.bitmap)
        val e = r.exif
        dao.markIndexed(
            row.id, e?.takenAt?.takeIf { it > 0 } ?: row.modified, e?.camera, e?.lens, e?.iso ?: 0, e?.shutter ?: 0.0,
            e?.aperture ?: 0.0, e?.focal ?: 0.0, r.ifdOrientation, r.previewOffset, r.previewLength, r.width, r.height,
        )
    }

    private fun walk(tree: Uri, docId: String, out: MutableList<Doc>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = ArrayList<String>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { dirs.add(id); continue }
                val kind = FileTypes.kindOf(name) ?: continue
                out.add(Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, c.getLong(3), c.getLong(4), kind == Kind.RAW))
            }
        }
        dirs.forEach { walk(tree, it, out) }
    }

}
