package app.rawline.feature.studio

import android.app.Application
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rawline.core.studio.model.SpaceCheck
import app.rawline.core.studio.model.IndexDiff
import app.rawline.core.studio.model.JavaFs
import app.rawline.core.studio.model.ProjectCatalog
import app.rawline.core.studio.model.ProjectRow
import app.rawline.core.studio.render.StudioPerf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Studio home's data (spec 5, decision D4). The first paint is the indexed rows from studio.db; then the project directories are scanned on a worker and the index is brought into line
 * (the directories win). Every file and database step runs on Dispatchers.IO and one at a time ([work]), so a Delete cannot interleave with the scan that follows it.
 * Damaged or newer-format projects are listed apart ([damaged]) and never crash the home.
 */
class StudioHomeViewModel(private val app: Application, private val perf: StudioPerf = StudioPerf.None) : AndroidViewModel(app) {
    private val fs = JavaFs(app.filesDir)
    private val root = "studio"
    private val work = Mutex()
    private val _rows = MutableStateFlow<List<ProjectRow>>(emptyList())
    val rows: StateFlow<List<ProjectRow>> = _rows
    private val _damaged = MutableStateFlow<List<String>>(emptyList())
    val damaged: StateFlow<List<String>> = _damaged
    /** False until the first rows are known (index or scan), so the empty state is not flashed while loading. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val thumbs = LruCache<String, ImageBitmap>(40)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.nanoTime()
            // first paint from the index; a database that will not open is not an error here, the scan below rebuilds it
            val indexed = dbOr(emptyList()) { dao().all().map { it.toRow() } }
            if (indexed.isNotEmpty()) { _rows.value = indexed; _loaded.value = true }
            refresh()
            perf.record("studio_home_first_paint_ms", (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun dao() = StudioDb.get(app).projects()

    private suspend fun <T> dbOr(fallback: T, block: suspend () -> T): T = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { perf.error("studio.db: ${t.javaClass.simpleName}: ${t.message}"); fallback }

    /** Scans the directories and applies the difference to the index. Safe to call at any time. */
    suspend fun refresh() = withContext(Dispatchers.IO) { work.withLock { scan() } }

    private suspend fun scan() {
        val result = try { ProjectCatalog.scan(fs, root) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { perf.error("studio scan: ${t.javaClass.simpleName}: ${t.message}"); return }
        dbOr(Unit) {
            val diff = IndexDiff.compute(dao().all().map { it.toRow() }, result.rows)
            if (!diff.isEmpty) { dao().upsert(diff.upserts.map { it.toEntity() }); dao().delete(diff.deletes) }
        }
        _rows.value = result.rows
        _damaged.value = result.damaged
        _loaded.value = true
    }

    fun reload() { viewModelScope.launch { refresh() } }

    fun duplicate(id: String) = op("Could not duplicate the project.") {
        val now = System.currentTimeMillis()
        val size = java.io.File(app.filesDir, "$root/$id").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        SpaceCheck.problem(app.filesDir.usableSpace, size)?.let { _message.value = it; return@op }
        ProjectCatalog.duplicate(fs, root, id, ProjectCatalog.newId(now), now)
    }

    fun rename(id: String, name: String) = op("Could not rename the project.") { ProjectCatalog.rename(fs, root, id, name, System.currentTimeMillis()) }

    fun delete(id: String) = op("Could not delete the project.") {
        ProjectCatalog.delete(fs, root, id)
        dbOr(Unit) { dao().delete(listOf(id)) }
    }

    private fun op(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            work.withLock {
                try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { perf.error("studio home: ${t.javaClass.simpleName}: ${t.message}"); _message.value = failure }
                scan()
            }
        }
    }

    fun consumeMessage() { _message.value = null }

    /** The project's thumbnail (thumb.jpg, 512 px), decoded off the main thread and kept in a 40 entry cache. Null when there is none. */
    suspend fun thumbnail(row: ProjectRow): ImageBitmap? {
        if (!row.hasThumb) return null
        val key = "${row.id}@${row.modified}:${row.sizeBytes}"
        thumbs.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bmp = BitmapFactory.decodeFile(File(app.filesDir, "$root/${row.id}/thumb.jpg").path)?.asImageBitmap()
            if (bmp != null) thumbs.put(key, bmp)
            bmp
        }
    }
}
