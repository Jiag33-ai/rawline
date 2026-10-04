package app.rawline

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rawline.core.cache.PerfLog
import app.rawline.core.data.IndexProgress
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.LibraryFilter
import app.rawline.core.model.PasteScope
import app.rawline.core.model.Photo
import app.rawline.core.render.ExportSettings
import app.rawline.core.model.RecipeMerge
import app.rawline.feature.library.SourceItem
import app.rawline.core.ui.LrIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library: where photos come from (camera roll, other device albums, imported files, folders), the current filter and
 * everything done to selections. The camera roll is listed straight from MediaStore as soon as access is granted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val app: Application) : AndroidViewModel(app) {
    private val graph = (app as RawlineApplication).graph
    private val catalog = graph.catalog

    val overlay = MutableStateFlow(graph.prefs.getBoolean("overlay", false))
    val xmp = MutableStateFlow(graph.prefs.getBoolean("xmp", false))
    val filter = MutableStateFlow(LibraryFilter())
    val progress: StateFlow<IndexProgress> = graph.indexer.progress
    val copied = MutableStateFlow<EditRecipe?>(null)
    val message = MutableStateFlow<String?>(null)
    val recentFolders = MutableStateFlow(graph.prefs.getStringSet("folders", emptySet())!!.toList())
    val permissionGranted = MutableStateFlow(hasMediaPermission())
    /** All files access lets the list include RAW files (RW2) that the phone does not classify as images. */
    val allFilesGranted = MutableStateFlow(hasAllFiles())

    /** Key of the shown source: "device:*" (all device photos), "device:<album>", "imported" or a folder uri. */
    val source = MutableStateFlow(graph.prefs.getString("source", null) ?: "device:*")

    val sources: StateFlow<List<SourceItem>> = graph.db.photos().sources().map { rows ->
        val counts = rows.associate { it.source to it.n }
        val deviceTotal = counts.filterKeys { it.startsWith("device:") }.values.sum()
        val out = ArrayList<SourceItem>()
        val camera = counts["device:Camera"]
        if (camera != null) out.add(SourceItem("device:Camera", "Camera roll", camera, LrIcon.CAMERA))
        out.add(SourceItem("device:*", "All device photos", deviceTotal, LrIcon.PHOTOS))
        counts.filterKeys { it.startsWith("device:") && it != "device:Camera" }.entries.sortedByDescending { it.value }
            .forEach { out.add(SourceItem(it.key, it.key.removePrefix("device:"), it.value, LrIcon.FOLDER)) }
        out.add(SourceItem("imported", "Imported files", counts["imported"] ?: 0, LrIcon.IMPORT))
        counts.filterKeys { !it.startsWith("device:") && it != "imported" }.forEach { out.add(SourceItem(it.key, labelOf(it.key), it.value, LrIcon.FOLDER)) }
        out
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every photo of the shown source, newest first. Updates are throttled while the indexer is filling in rows. */
    val allPhotos: StateFlow<List<Photo>> = source.flatMapLatest { key ->
        val rows = when {
            key == "device:*" -> graph.db.photos().observeLike("device:%")
            else -> graph.db.photos().observe(key)
        }
        rows.map { l -> l.map { it.toModel() } }.flowOn(Dispatchers.Default).conflate().transform { emit(it); delay(250) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val photos: StateFlow<List<Photo>> = combine(allPhotos, filter) { all, f -> f.apply(all) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cameras: StateFlow<List<String>> = allPhotos.map { l -> l.mapNotNull { it.camera }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var observer: ContentObserver? = null
    private var scanJob: Job? = null

    init {
        // First run: no saved choice. Show the camera roll (falls back to everything on the phone if there is no "Camera" album).
        viewModelScope.launch {
            if (graph.prefs.getString("source", null) == null) {
                sources.first { it.isNotEmpty() }; source.value = "device:*"
            }
        }
        if (permissionGranted.value) startDeviceWatch()
        graph.prefs.getString("folder", null)?.let { rescanFolder(Uri.parse(it)) }
        viewModelScope.launch { graph.indexer.indexPendingLike("imported") }
    }

    private fun hasAllFiles() = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    fun onResume() {
        val now = hasAllFiles()
        if (now != allFilesGranted.value) { allFilesGranted.value = now; if (permissionGranted.value) rescanDevice() }
    }

    fun allFilesIntent() = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${app.packageName}"))

    private fun hasMediaPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED
    }

    val mediaPermission: String get() = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun onPermission(granted: Boolean) {
        permissionGranted.value = granted || hasMediaPermission()
        if (permissionGranted.value) startDeviceWatch()
    }

    /** Lists the camera roll now and again whenever the phone's media database changes (new photo taken, file deleted). */
    private fun startDeviceWatch() {
        rescanDevice()
        if (observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { rescanDevice(delayMs = 1500) }
        }
        app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
        observer = o
    }

    fun rescanDevice(delayMs: Long = 0) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            runCatching { graph.deviceScanner.scanDevice() }.onFailure { PerfLog.error("device scan: ${it.message}") }
            runCatching { graph.indexer.indexPendingLike("device:%") }
        }
    }

    override fun onCleared() { observer?.let { app.contentResolver.unregisterContentObserver(it) } }

    fun selectSource(key: String) { graph.prefs.edit().putString("source", key).apply(); source.value = key; filter.value = LibraryFilter(sort = filter.value.sort) }

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = graph.deviceScanner.importFiles(uris)
            message.value = if (n == 0) "Nothing new to import" else "Imported $n photos"
            withContext(Dispatchers.Main) { selectSource("imported") }
            graph.indexer.indexPendingLike("imported")
        }
    }

    /** A folder chosen with the system picker: its photos are read in place (nothing is copied). */
    fun addFolder(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        graph.prefs.edit().putString("folder", uri.toString()).apply()
        val all = (listOf(uri.toString()) + recentFolders.value).distinct().take(8)
        graph.prefs.edit().putStringSet("folders", all.toSet()).apply()
        recentFolders.value = all
        selectSource(uri.toString())
        rescanFolder(uri)
    }

    private fun rescanFolder(uri: Uri) {
        viewModelScope.launch { runCatching { graph.indexer.scan(uri) }.onFailure { PerfLog.error("scan: ${it.message}") } }
    }

    fun setOverlay(on: Boolean) { graph.prefs.edit().putBoolean("overlay", on).apply(); overlay.value = on }
    fun setXmp(on: Boolean) { graph.prefs.edit().putBoolean("xmp", on).apply(); xmp.value = on }

    fun labelOf(uri: String): String = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Storage" } }.getOrDefault(uri)

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

    // ---- export queue ----
    fun exportSettings() = ExportSettings.fromJson(graph.prefs.getString("export", null))

    fun enqueueExport(list: List<Photo>) = viewModelScope.launch {
        graph.exportRunner.enqueue(list, exportSettings())
        message.value = if (list.size == 1) "Added to export queue" else "Added ${list.size} photos to export queue"
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
