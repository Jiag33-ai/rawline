package app.rawline

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rawline.core.cache.PerfLog
import app.rawline.core.data.IndexProgress
import app.rawline.core.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val app: Application) : AndroidViewModel(app) {
    private val graph = (app as RawlineApplication).graph

    val folder = MutableStateFlow(graph.prefs.getString("folder", null))
    val overlay = MutableStateFlow(graph.prefs.getBoolean("overlay", false))
    val progress: StateFlow<IndexProgress> = graph.indexer.progress

    val photos: StateFlow<List<Photo>> = folder.flatMapLatest { f ->
        if (f == null) emptyFlow() else graph.db.photos().observe(f)
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(Dispatchers.Default)
            .conflate()
            // At most one list update per 250 ms while the indexer is filling in rows.
            .transform { emit(it); delay(250) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        folder.value?.let { rescan(Uri.parse(it)) }
    }

    fun chooseFolder(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        graph.prefs.edit().putString("folder", uri.toString()).apply()
        folder.value = uri.toString()
        rescan(uri)
    }

    fun setOverlay(on: Boolean) {
        graph.prefs.edit().putBoolean("overlay", on).apply()
        overlay.value = on
    }

    private fun rescan(uri: Uri) {
        viewModelScope.launch {
            runCatching { graph.indexer.scan(uri) }
                .onFailure { PerfLog.error("scan: ${it.message}") }
        }
    }

    fun folderLabel(): String? = folder.value?.let {
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfter(':').ifEmpty { "Storage" } }.getOrNull()
    }
}
