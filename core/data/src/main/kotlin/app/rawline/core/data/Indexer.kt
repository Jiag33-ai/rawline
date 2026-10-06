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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** [listing]: still listing a folder, [total] is how many photos have been found so far and nothing is being read yet. */
data class IndexProgress(val total: Int = 0, val done: Int = 0, val running: Boolean = false, val listing: Boolean = false)

/**
 * Lists a SAF folder, adds rows as each directory is read (so the grid fills while a big tree is still being listed) and then fills in
 * thumbnails and EXIF newest first. Headers only: no raw data is ever decoded here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Indexer(private val context: Context, private val dao: PhotoDao, private val thumbs: ThumbStore, private val catalog: Catalog? = null) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress

    suspend fun scan(folder: Uri) = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        val folderKey = folder.toString()
        val docs = ArrayList<ScanDoc>()
        val xmp = HashMap<String, String>()
        val known = dao.known(folderKey)
        val byUri = known.associateBy { it.uri }
        val seen = HashSet<String>()
        val stale = ArrayList<Long>()
        val freshUris = HashSet<String>()
        _progress.value = IndexProgress(0, 0, true, listing = true)
        // Each directory's photos are stored as soon as it is read, so the grid fills while the rest is still being listed.
        val complete = try {
            walk(folder, DocumentsContract.getTreeDocumentId(folder), xmp) { batch ->
                docs.addAll(batch)
                batch.forEach { seen.add(it.uri) }
                val plan = FolderScan.plan(folderKey, batch, byUri)
                plan.stale.chunked(500).forEach { dao.delete(it) }
                plan.fresh.chunked(200).forEach { dao.insertAll(it) }
                plan.fresh.forEach { freshUris.add(it.uri) }
                _progress.value = IndexProgress(docs.size, 0, true, listing = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { _progress.value = IndexProgress(); throw e }
        // Never prune when the listing may be partial (unmounted card, revoked permission): that would wipe ratings and edit marks.
        if (complete && docs.isNotEmpty()) known.filter { it.uri !in seen }.forEach { stale.add(it.id) }
        stale.chunked(500).forEach { dao.delete(it) }
        catalog?.reapply(folderKey)
        // Ratings and labels from existing XMP sidecars (only for photos without saved ratings)
        if (xmp.isNotEmpty()) {
            val docByUri = docs.associateBy { it.uri }
            dao.known(folderKey).forEach { k ->
                val d = docByUri[k.uri] ?: return@forEach
                val sidecar = xmp[d.dir + "/" + d.name.substringBeforeLast('.').lowercase()] ?: return@forEach
                if (k.uri in freshUris) {
                    runCatching {
                        context.contentResolver.openInputStream(Uri.parse(sidecar))?.use { st ->
                            Xmp.parse(st.readBytes().toString(Charsets.UTF_8))?.let { (r, l) -> if (r > 0 || l > 0) dao.setRatingLabelIfUnset(k.uri, r, l) }
                        }
                    }
                }
            }
        }
        PerfLog.record("scan_list_ms", (System.nanoTime() - t0) / 1_000_000)
        indexPending(folderKey)
    }

    private suspend fun indexPending(folderKey: String) = indexTodo(dao.pending(folderKey))

    /** Fills in EXIF (and RAW previews) for device and imported photos, e.g. prefix "device:%" or "imported". */
    suspend fun indexPendingLike(prefix: String) = indexTodo(dao.pendingLike(prefix))

    private suspend fun indexTodo(todo: List<PhotoEntity>) {
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
                        try { indexOne(row) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { failures.incrementAndGet(); PerfLog.error("index ${row.name}: ${e.message}"); dao.markFailed(row.id) }
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
        if (!row.isRaw && (row.folderUri.startsWith("device:") || row.folderUri == "imported")) {
            // Plain pictures from the camera roll: read EXIF only; their thumbnails come from the system when a tile is shown.
            val e = PreviewDecoder.readExifOnly(context, Uri.parse(row.uri))
            dao.markIndexed(row.id, e?.takenAt?.takeIf { it > 0 } ?: row.takenAt.takeIf { it > 0 } ?: row.modified, e?.camera, e?.lens, e?.iso ?: 0, e?.shutter ?: 0.0, e?.aperture ?: 0.0,
                e?.focal ?: 0.0, 1, 0, 0, row.width, row.height)
            return
        }
        val r = PreviewDecoder.decode(context, photo, 320, software = true, wantExif = true)
        if (r == null) { dao.markFailed(row.id); PerfLog.error("no preview: ${row.name}"); return }
        thumbs.save(row.id, r.bitmap)
        val e = r.exif
        dao.markIndexed(
            row.id, e?.takenAt?.takeIf { it > 0 } ?: row.modified, e?.camera, e?.lens, e?.iso ?: 0, e?.shutter ?: 0.0,
            e?.aperture ?: 0.0, e?.focal ?: 0.0, r.ifdOrientation, r.previewOffset, r.previewLength, r.width, r.height,
        )
    }

    /** Lists [tree] depth first and hands each directory's photos to [onDirectory]. @return false if any folder could not be listed */
    private suspend fun walk(tree: Uri, docId: String, xmp: MutableMap<String, String>, onDirectory: suspend (List<ScanDoc>) -> Unit): Boolean {
        kotlin.coroutines.coroutineContext.ensureActive()    // a cancelled scan stops at the next directory
        var ok = true
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = ArrayList<String>()
        val here = ArrayList<ScanDoc>()
        val cursor = context.contentResolver.query(children, cols, null, null, null)
        if (cursor == null) ok = false
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { dirs.add(id); continue }
                if (name.endsWith(".xmp", true)) { xmp[docId + "/" + name.substringBeforeLast('.').lowercase()] = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(); continue }
                val kind = FileTypes.kindOf(name) ?: continue
                here.add(ScanDoc(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, c.getLong(3), c.getLong(4), kind == Kind.RAW, docId))
            }
        }
        if (here.isNotEmpty()) onDirectory(here)
        dirs.forEach { if (!walk(tree, it, xmp, onDirectory)) ok = false }
        return ok
    }

}
