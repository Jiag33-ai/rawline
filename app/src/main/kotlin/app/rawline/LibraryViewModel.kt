package app.rawline

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rawline.core.cache.PerfLog
import app.rawline.core.data.IndexProgress
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.LibraryFilter
import app.rawline.core.model.PasteScope
import app.rawline.core.model.Photo
import app.rawline.core.model.RecipeMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val catalog = graph.catalog

    val folder = MutableStateFlow(graph.prefs.getString("folder", null))
    val recentFolders = MutableStateFlow(graph.prefs.getStringSet("folders", emptySet())!!.toList())
    val overlay = MutableStateFlow(graph.prefs.getBoolean("overlay", false))
    val xmp = MutableStateFlow(graph.prefs.getBoolean("xmp", false))
    val filter = MutableStateFlow(LibraryFilter())
    val progress: StateFlow<IndexProgress> = graph.indexer.progress
    val copied = MutableStateFlow<EditRecipe?>(null)
    val message = MutableStateFlow<String?>(null)

    /** Every photo in the folder, newest first. Updates are throttled while the indexer is filling in rows. */
    val allPhotos: StateFlow<List<Photo>> = folder.flatMapLatest { f ->
        if (f == null) emptyFlow() else graph.db.photos().observe(f)
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(Dispatchers.Default)
            .conflate()
            .transform { emit(it); delay(250) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val photos: StateFlow<List<Photo>> = combine(allPhotos, filter) { all, f -> f.apply(all) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cameras: StateFlow<List<String>> = allPhotos.map { l -> l.mapNotNull { it.camera }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        folder.value?.let { rescan(Uri.parse(it)) }
    }

    fun chooseFolder(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure {
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        remember(uri.toString())
        graph.prefs.edit().putString("folder", uri.toString()).apply()
        folder.value = uri.toString()
        rescan(uri)
    }

    private fun remember(uri: String) {
        val all = (listOf(uri) + recentFolders.value).distinct().take(8)
        graph.prefs.edit().putStringSet("folders", all.toSet()).apply()
        recentFolders.value = all
    }

    fun switchFolder(uri: String) {
        graph.prefs.edit().putString("folder", uri).apply()
        folder.value = uri
        remember(uri)
        rescan(Uri.parse(uri))
    }

    fun labelOf(uri: String): String = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifEmpty { "Storage" } }.getOrDefault(uri)

    fun setOverlay(on: Boolean) { graph.prefs.edit().putBoolean("overlay", on).apply(); overlay.value = on }
    fun setXmp(on: Boolean) { graph.prefs.edit().putBoolean("xmp", on).apply(); xmp.value = on }

    private fun rescan(uri: Uri) {
        viewModelScope.launch { runCatching { graph.indexer.scan(uri) }.onFailure { PerfLog.error("scan: ${it.message}") } }
    }

    fun folderLabel(): String? = folder.value?.let {
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfter(':').ifEmpty { "Storage" } }.getOrNull()
    }

    fun photoById(id: Long): Photo? = allPhotos.value.firstOrNull { it.id == id }

    // ---- ratings and organising ----
    fun rate(list: List<Photo>, r: Int) = viewModelScope.launch { catalog.setRating(list, r) }
    fun flag(list: List<Photo>, f: Int) = viewModelScope.launch { catalog.setFlag(list, f) }
    fun label(list: List<Photo>, l: Int) = viewModelScope.launch { catalog.setLabel(list, l) }

    // ---- copy, paste and sync of edits ----
    fun copyEdits(p: Photo) = viewModelScope.launch {
        copied.value = catalog.loadRecipe(p) ?: EditRecipe()
        message.value = "Copied edits from ${p.name}"
    }

    fun pasteEdits(targets: List<Photo>, scopes: Set<PasteScope>) = viewModelScope.launch {
        val src = copied.value ?: return@launch
        targets.forEach { t -> catalog.saveRecipe(t, RecipeMerge.paste(catalog.loadRecipe(t) ?: EditRecipe(), src, scopes)) }
        message.value = "Pasted onto ${targets.size} photos"
    }

    fun syncEdits(from: Photo, to: List<Photo>) = viewModelScope.launch {
        val src = catalog.loadRecipe(from) ?: EditRecipe()
        to.forEach { t -> catalog.saveRecipe(t, RecipeMerge.paste(catalog.loadRecipe(t) ?: EditRecipe(), src, PasteScope.entries.toSet() - PasteScope.GEOMETRY - PasteScope.MASKS - PasteScope.HEALS)) }
        message.value = "Synced ${to.size} photos from ${from.name}"
    }

    // ---- backup ----
    fun backupTo(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { app.contentResolver.openOutputStream(uri)?.use { catalog.writeBackup(it) } }
            .onSuccess { message.value = "Backup saved" }.onFailure { message.value = "Backup failed: ${it.message}" }
    }

    fun restoreFrom(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { app.contentResolver.openInputStream(uri)?.use { catalog.readBackup(it) } }
            .onSuccess { message.value = "Restored $it edits" }.onFailure { message.value = "Restore failed: ${it.message}" }
    }
}
