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
import app.rawline.core.data.SidecarResult
import app.rawline.core.data.RecipeRead
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.LibraryFilter
import app.rawline.core.model.PasteScope
import app.rawline.core.model.Photo
import app.rawline.core.render.ExportSettings
import app.rawline.core.model.RecipeMerge
import app.rawline.feature.library.SourceItem
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.Plurals
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
    /** The look of the photo most recently edited and left, for "Paste from last". */
    val lastEdited = MutableStateFlow<EditRecipe?>(null)
    val message = MutableStateFlow<String?>(null)
    val recentFolders = MutableStateFlow(graph.prefs.getStringSet("folders", emptySet())!!.toList())
    /** Photo access works (Photos permission or All files access). Recomputed on every resume, so granting it in Settings is noticed. */
    val permissionGranted = MutableStateFlow(hasMediaPermission() || hasAllFiles())
    /** True when Android will no longer show the permission dialog: the library offers "Open settings" instead of "Allow access". */
    val permissionBlocked = MutableStateFlow(false)
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

    /** [rationale]: whether Android would still show the permission dialog (needs the Activity, so the screen supplies it). */
    fun onResume(rationale: Boolean) {
        val now = hasAllFiles()
        val allFilesChanged = now != allFilesGranted.value
        allFilesGranted.value = now
        refreshPermission(rationale)
        if (allFilesChanged && permissionGranted.value) rescanDevice()
    }

    fun allFilesIntent() = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${app.packageName}"))

    private fun hasMediaPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED
    }

    val mediaPermission: String get() = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /** True once the system dialog has been shown at least once (kept across launches). */
    val mediaAsked: Boolean get() = graph.prefs.getBoolean("mediaAsked", false)

    /** The result of the system dialog. */
    fun onPermissionResult(rationale: Boolean) {
        graph.prefs.edit().putBoolean("mediaAsked", true).apply()
        refreshPermission(rationale)
    }

    private fun refreshPermission(rationale: Boolean) {
        val p = MediaAccess.prompt(hasMediaPermission(), hasAllFiles(), mediaAsked, rationale)
        permissionGranted.value = p == MediaPrompt.GRANTED
        permissionBlocked.value = p == MediaPrompt.OPEN_SETTINGS
        if (p == MediaPrompt.GRANTED) startDeviceWatch()
    }

    fun appSettingsIntent() = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))

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
            message.value = if (n == 0) "Nothing new to import" else "Imported ${Plurals.photos(n)}"
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
    // Catalogue work never runs on the main dispatcher: the XMP step does SAF file I/O per photo.
    fun rate(list: List<Photo>, r: Int) = viewModelScope.launch(Dispatchers.IO) { reportSidecars(catalog.setRating(list, r)) }
    fun flag(list: List<Photo>, f: Int) = viewModelScope.launch(Dispatchers.IO) { catalog.setFlag(list, f) }
    fun label(list: List<Photo>, l: Int) = viewModelScope.launch(Dispatchers.IO) { reportSidecars(catalog.setLabel(list, l)) }

    private var told = false
    /** XMP is on but some photos cannot have a sidecar (camera roll, imported files) or it failed: say so once, never silently. */
    private fun reportSidecars(r: SidecarResult) {
        val text = SidecarNotice.text(r) ?: return
        if (told) return
        told = true
        message.value = text
    }

    // ---- copy, paste and sync of edits ----
    fun copyEdits(p: Photo) = viewModelScope.launch(Dispatchers.IO) {
        when (val r = catalog.readRecipe(p)) {
            is RecipeRead.Ok -> { copied.value = r.recipe; message.value = "Copied edits from ${p.name}" }
            RecipeRead.Missing -> { copied.value = EditRecipe(); message.value = "Copied edits from ${p.name}" }
            RecipeRead.Unreadable -> message.value = "The edit on ${p.name} could not be read, so nothing was copied"
        }
    }

    fun pasteEdits(targets: List<Photo>, scopes: Set<PasteScope>) = viewModelScope.launch(Dispatchers.IO) {
        val src = copied.value ?: return@launch
        message.value = applyToTargets(targets, "Pasted onto") { base -> RecipeMerge.paste(base, src, scopes) }
    }

    fun syncEdits(from: Photo, to: List<Photo>) = viewModelScope.launch(Dispatchers.IO) {
        val src = when (val r = catalog.readRecipe(from)) {
            is RecipeRead.Ok -> r.recipe
            RecipeRead.Missing -> EditRecipe()
            RecipeRead.Unreadable -> { message.value = "The edit on ${from.name} could not be read, so nothing was synced"; return@launch }
        }
        message.value = applyToTargets(to, "Synced") { base -> RecipeMerge.paste(base, src, RecipeMerge.QUICK) } + " from ${from.name}"
    }

    /** Merges into each target's saved edit. A target whose saved edit cannot be read is left alone, never overwritten with defaults. */
    private suspend fun applyToTargets(targets: List<Photo>, verb: String, merge: (EditRecipe) -> EditRecipe): String {
        var done = 0; var skipped = 0
        targets.forEach { t ->
            val base = when (val r = catalog.readRecipe(t)) { is RecipeRead.Ok -> r.recipe; RecipeRead.Missing -> EditRecipe(); RecipeRead.Unreadable -> { skipped++; return@forEach } }
            catalog.saveRecipe(t, merge(base)); done++
        }
        return "$verb ${Plurals.photos(done)}" + if (skipped > 0) ", skipped $skipped with an unreadable edit" else ""
    }

    // ---- export queue ----
    fun exportSettings() = ExportSettings.fromJson(graph.prefs.getString("export", null))

    fun enqueueExport(list: List<Photo>) = viewModelScope.launch(Dispatchers.IO) {
        graph.exportRunner.enqueue(list, exportSettings())
        message.value = if (list.size == 1) "Added to export queue" else "Added ${Plurals.photos(list.size)} to export queue"
    }

    // ---- backup ----
    fun backupTo(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { (app.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("could not open the file")).use { catalog.writeBackup(it) } }
            .onSuccess { message.value = "Backup saved" }.onFailure { message.value = "Backup failed: ${it.message}" }
    }

    fun restoreFrom(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { (app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("could not open the file")).use { catalog.readBackup(it) } }
            .onSuccess { message.value = "Restored ${Plurals.edits(it)}" }.onFailure { message.value = "Restore failed: ${it.message}" }
    }
}
