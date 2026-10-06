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
import app.rawline.backup.BackupCoordinator
import app.rawline.backup.BackupPrefs
import app.rawline.backup.BackupScheduler
import app.rawline.backup.BackupTargets
import app.rawline.backup.TargetKind
import app.rawline.core.data.RestoreCheck
import app.rawline.core.data.RestoreStaging
import app.rawline.core.data.RestoreText
import app.rawline.feature.settings.BackupItem
import app.rawline.feature.settings.BackupUiState
import app.rawline.feature.settings.RestoreOffer
import app.rawline.core.data.IndexProgress
import app.rawline.core.data.SidecarResult
import app.rawline.core.data.RecipeRead
import app.rawline.core.model.EditRecipe
import app.rawline.platform.LibraryFilterJson
import app.rawline.core.model.DefaultView
import app.rawline.core.model.HeldOrder
import app.rawline.core.model.Kind
import app.rawline.core.model.LibraryFilter
import app.rawline.core.model.OrderGate
import app.rawline.core.model.ViewChoice
import app.rawline.core.model.WhatsNew
import app.rawline.core.model.PasteScope
import app.rawline.core.model.Photo
import app.rawline.core.render.ExportSettings
import app.rawline.core.model.RecipeMerge
import app.rawline.feature.library.GridRows
import app.rawline.feature.library.GridRow
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
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
    /** BK-120: the filter and sort come back after a cold start (JSON in the preferences, damaged or unknown values fall back to the defaults). */
    /** BK-498: "raw" or "all" once the user has tapped a chip; null until then (the default view applies). Never overridden after a choice. */
    private val viewChoice = MutableStateFlow(ViewChoice.parse(graph.prefs.getString("viewChoice", null)))
    private val whatsNewSeen = MutableStateFlow(graph.prefs.getInt("whatsNewSeen", 0))
    /** True once the default view has been decided from the content, so the grid never shows everything for a moment and then flips to RAW photos. */
    private val viewReady = MutableStateFlow(false)
    /** The saved filter does not hold the RAW view (D8 of W29): it comes from [viewChoice] and the content, so a stored filter cannot contradict the chip. */
    val filter = MutableStateFlow(LibraryFilterJson.read(graph.prefs.getString("libraryFilter", null)).copy(rawOnly = viewChoice.value == ViewChoice.RAW))
    val rawCount: StateFlow<Int> = graph.db.photos().rawCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
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

    private val _loaded = MutableStateFlow(false)
    /** True once the list has emitted at least once, so an empty list means "nothing here" and not "not read yet". */
    val loaded: StateFlow<Boolean> = combine(_loaded, viewReady) { a, b -> a && b }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Every photo of the shown source, newest first. Updates are throttled, more so while the indexer is filling in rows. */
    val allPhotos: StateFlow<List<Photo>> = source.flatMapLatest { key ->
        val rows = when {
            key == "device:*" -> graph.db.photos().observeLike("device:%")
            else -> graph.db.photos().observe(key)
        }
        rows.map { l -> l.map { it.toModel() } }.flowOn(Dispatchers.Default).conflate()
            .transform { _loaded.value = true; emit(it); delay(ListThrottle.delayMs(graph.indexer.progress.value.running)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The filtered list and its grid rows (date headings), both built off the main thread. */
    class Listing(val photos: List<Photo>, val rows: List<GridRow>, val rawInSource: Boolean = false) { companion object { val EMPTY = Listing(emptyList(), emptyList()) } }

    /** The list as the database and the filter give it, before the order is held (BK-497). [ids] is made once per list. */
    private class Sorted(val photos: List<Photo>, val filter: LibraryFilter, val rawInSource: Boolean, val source: String) { val ids: List<Long> by lazy { photos.map { it.id } } }

    private val sorted: kotlinx.coroutines.flow.Flow<Sorted> = combine(allPhotos, filter, viewChoice, viewReady) { all, f, choice, ready ->
        if (!ready) return@combine null
        val rawIn = all.any { it.kind == Kind.RAW }
        // the default (no choice made) only applies where the shown source has RAW photos, so a folder of JPEG files is never an empty grid
        val eff = if (f.rawOnly && choice == null && !rawIn) f.copy(rawOnly = false) else f
        Sorted(eff.apply(all), eff, rawIn, source.value)
    }.filterNotNull()

    /** BK-497: true while the grid is on screen (false while a photo or the editor is open, when a new order can apply at once). */
    val gridVisible = MutableStateFlow(true)
    /** BK-497: a finger is down on the grid, it is scrolling or photos are selected. */
    val gridBusy = MutableStateFlow(false)
    private val orderGate = OrderGate()
    private val tick = MutableStateFlow(0)
    private val tickPending = java.util.concurrent.atomic.AtomicBoolean(false)
    /** How many times the order of photos already on screen changed this session. The Copy report prints it as grid_resort_count. */
    val gridResorts: Int get() = orderGate.resorts

    /**
     * The grid's list. While the user is busy on the grid and for 1.5 s after, photos keep their places: deleted ones leave at once, new ones wait, and
     * the sorted order is applied once afterwards. A change of source or filter, and a grid that is not on screen, apply at once. The data of each tile
     * (rating, flag, edited badge) is always the latest; only the order is held.
     */
    val listing: StateFlow<Listing> = flow {
        val held = HeldOrder(orderGate)
        combine(sorted, gridBusy, gridVisible, tick) { s, busy, visible, _ -> Triple(s, busy, visible) }.collect { (s, busy, visible) ->
            val step = held.step(s, s.source to s.filter, s.ids, busy, visible, android.os.SystemClock.elapsedRealtime())
            if (step.waiting && tickPending.compareAndSet(false, true)) viewModelScope.launch { delay(300); tickPending.set(false); tick.value++ }
            if (!step.changed) return@collect
            val byId = HashMap<Long, Photo>(s.photos.size * 2); s.photos.forEach { byId[it.id] = it }
            val shown = step.ids.mapNotNull { byId[it] }
            emit(Listing(shown, GridRows.build(shown, s.filter.sort), s.rawInSource))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Listing.EMPTY)

    /** BK-498: the one time note. Only where the new default is really in effect and the user has made no choice. */
    val whatsNew: StateFlow<Boolean> = combine(listing, filter, viewChoice, whatsNewSeen) { l, f, c, seen -> WhatsNew.shouldShow(seen, f.rawOnly && l.rawInSource, c) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val cameras: StateFlow<List<String>> = allPhotos.map { l -> l.mapNotNull { it.camera }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The ids that were on screen when the viewer opened, in order (see [Browse]). Not saved: after a restart the live list is used. */
    val browseIds = MutableStateFlow<List<Long>>(emptyList())
    /** The photo the viewer or editor last showed, so the grid can scroll back to it. */
    val lastViewedId = MutableStateFlow<Long?>(null)
    /** True while a device scan runs, so an empty grid says "Reading your photos" instead of "Nothing here yet". */
    val scanning = MutableStateFlow(false)

    private var observer: ContentObserver? = null
    private var scanJob: Job? = null
    private var told = false
    // These must be declared above init: init starts a device scan, and a property declared below it would still be null then.
    private val gate = RescanGate()
    private val scanCount = java.util.concurrent.atomic.AtomicInteger()

    /** BK-120: the grid column count and the photo that was at the top when the app last paused. Read once; [topRestored] drops the top so the grid scrolls to it once per process. */
    val columns: Int = graph.prefs.getInt("columns", 5).coerceIn(2, 8)
    fun setColumns(n: Int) { graph.prefs.edit().putInt("columns", n.coerceIn(2, 8)).apply() }
    val restoreTopId = MutableStateFlow<Long?>(graph.prefs.getLong("libraryTop", -1L).takeIf { it >= 0 })
    fun saveTopPhoto(id: Long?) { graph.prefs.edit().apply { if (id == null) remove("libraryTop") else putLong("libraryTop", id) }.apply() }
    fun topRestored() { restoreTopId.value = null }

    init {
        // decide the default view before the first list is shown (one COUNT query), so the grid does not open on everything and then flip
        viewModelScope.launch(Dispatchers.IO) { evaluateView(runCatching { graph.db.photos().rawCountNow() }.getOrDefault(0)); viewReady.value = true }
        viewModelScope.launch { filter.collect { graph.prefs.edit().putString("libraryFilter", LibraryFilterJson.write(it)).apply() } }
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
        val wasGranted = permissionGranted.value
        permissionGranted.value = p == MediaPrompt.GRANTED
        permissionBlocked.value = p == MediaPrompt.OPEN_SETTINGS
        // scan when access has just arrived (a grant in Settings, or the dialog), not on every resume
        if (p == MediaPrompt.GRANTED && (!wasGranted || observer == null)) startDeviceWatch()
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


    /** Keeps [scanning] true while any listing step runs (several can overlap). */
    private suspend fun <T> scanTracked(block: suspend () -> T): T {
        scanning.value = scanCount.incrementAndGet() > 0
        try { return block() } finally { scanning.value = scanCount.decrementAndGet() > 0 }
    }

    fun rescanDevice(delayMs: Long = 0) {
        if (!gate.request(delayMs)) return    // a pass is running: it will go round again
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val d = gate.next() ?: break
                    if (d > 0) {
                        delay(d)
                        // a running export writes into the library and would fire this again and again: wait for it to finish
                        var waited = 0
                        while (ExportService.isRunning && waited < 300) { delay(2000); waited++ }
                    }
                    scanTracked { runCatching { graph.deviceScanner.scanDevice() }.onFailure { PerfLog.error("device scan: ${it.message}") } }
                    // BK-498: a scan may have found the first RAW files; decide again unless the user is busy on the grid (the view never flips under a thumb)
                    if (viewChoice.value == null && !gridBusy.value) runCatching { evaluateView(graph.db.photos().rawCountNow()) }
                    runCatching { graph.indexer.indexPendingLike("device:%") }
                }
            } finally { gate.release() }
        }
    }

    override fun onCleared() { staged?.discard(); observer?.let { app.contentResolver.unregisterContentObserver(it) } }

    fun selectSource(key: String) {
        graph.prefs.edit().putString("source", key).apply(); source.value = key
        // a new source starts without filters; the RAW view follows the user's choice, or the default for what the phone holds
        filter.value = LibraryFilter(sort = filter.value.sort, rawOnly = DefaultView.rawOnly(viewChoice.value, rawCount.value))
    }

    /** BK-498: decide the RAW view from the choice, or from the content when there is none. Called after a scan and when the source changes, not while the user scrolls. */
    fun evaluateView(rawCount: Int) {
        val raw = DefaultView.rawOnly(viewChoice.value, rawCount)
        if (filter.value.rawOnly != raw) filter.value = filter.value.copy(rawOnly = raw)
    }

    /** The user tapped "RAW photos" or "All photos": stored, never overridden again, and the What's New note is done with. */
    fun setViewChoice(raw: Boolean) {
        val c = if (raw) ViewChoice.RAW else ViewChoice.ALL
        graph.prefs.edit().putString("viewChoice", c.key).putInt("whatsNewSeen", WhatsNew.NOTE_VERSION).apply()
        viewChoice.value = c; whatsNewSeen.value = WhatsNew.NOTE_VERSION
        filter.value = filter.value.copy(rawOnly = raw)
    }

    fun dismissWhatsNew() { graph.prefs.edit().putInt("whatsNewSeen", WhatsNew.NOTE_VERSION).apply(); whatsNewSeen.value = WhatsNew.NOTE_VERSION }

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = scanTracked { graph.deviceScanner.importFiles(uris) }
            val kept = runCatching { app.contentResolver.persistedUriPermissions.size }.getOrDefault(0)
            message.value = ImportNotice.text(n, kept)
            // only jump to the Imported source when something was added, never to an empty list
            if (n > 0) withContext(Dispatchers.Main) { selectSource("imported") }
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
    /** The last rating, flag or label change, offered as Undo for a few seconds. */
    val undo = MutableStateFlow<UndoEntry?>(null)

    fun rate(list: List<Photo>, r: Int) = change(list, UndoField.RATING, r)
    fun flag(list: List<Photo>, f: Int) = change(list, UndoField.FLAG, f)
    fun label(list: List<Photo>, l: Int) = change(list, UndoField.LABEL, l)

    private fun change(list: List<Photo>, field: UndoField, value: Int) = viewModelScope.launch(Dispatchers.IO) {
        val changed = UndoRules.changed(list, field, value)
        if (changed.isNotEmpty()) undo.value = UndoEntry(field, value, changed)
        write(list, field, value)
    }

    private suspend fun write(list: List<Photo>, field: UndoField, value: Int) {
        when (field) {
            UndoField.RATING -> reportSidecars(catalog.setRating(list, value))
            UndoField.FLAG -> catalog.setFlag(list, value)
            UndoField.LABEL -> reportSidecars(catalog.setLabel(list, value))
        }
    }

    /** Puts the photos of the last change back as they were. */
    fun undoLast() {
        val e = undo.value ?: return
        undo.value = null
        viewModelScope.launch(Dispatchers.IO) {
            UndoRules.restoreGroups(e).forEach { (old, group) -> write(group, e.field, old) }
            message.value = "Undone"
        }
    }

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
    /** What the Backups section of Settings shows; rebuilt from the preferences by [refreshBackup]. */
    val backupUi = MutableStateFlow(BackupUiState())
    private var staged: RestoreStaging.Staged? = null

    private fun buildBackupUi(prev: BackupUiState): BackupUiState {
        val p = graph.prefs; val chosen = BackupTargets.choose(app, p)
        return BackupUiState(
            where = chosen.label,
            last = RestoreText.lastLine(p.getLong(BackupPrefs.LAST, 0), p.getLong(BackupPrefs.LAST_BYTES, 0), System.currentTimeMillis()),
            lastWhere = p.getString(BackupPrefs.LAST_WHERE, null),
            error = p.getString(BackupPrefs.ERROR, null),
            auto = p.getBoolean(BackupPrefs.AUTO, true),
            canChooseFolder = chosen.kind != TargetKind.FILES,
            running = prev.running, list = prev.list, offer = prev.offer,
        )
    }

    fun refreshBackup() = viewModelScope.launch(Dispatchers.IO) { backupUi.value = buildBackupUi(backupUi.value) }

    /** Back up now: the same runner as the scheduled backups, whatever the policy says. */
    fun backupNow() = viewModelScope.launch(Dispatchers.IO) {
        backupUi.value = buildBackupUi(backupUi.value).copy(running = true)
        val run = BackupCoordinator.run(app, graph, manual = true)
        message.value = if (run?.ok == true) "Backup saved (${RestoreText.size(run.bytes)})" else "Backup failed: ${run?.message ?: "nothing was written"}"
        backupUi.value = buildBackupUi(backupUi.value).copy(running = false)
    }

    fun setAutoBackup(on: Boolean) {
        graph.prefs.edit().putBoolean(BackupPrefs.AUTO, on).apply()
        BackupScheduler.ensure(app, on)
        refreshBackup()
    }

    /** The folder picker's answer: keep the permission across restarts and an uninstall, and remember the folder. */
    fun setBackupFolder(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onSuccess { graph.prefs.edit().putString(BackupTargets.PREF_TREE, uri.toString()).apply(); message.value = "Backups will go to the folder you chose" }
            .onFailure { message.value = "That folder cannot be used: ${it.message}" }
        refreshBackup()
    }

    /** "Save a copy to...": the old save-as path, for moving a backup somewhere else. */
    fun backupTo(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { (app.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("could not open the file")).use { catalog.writeBackup(it, BuildConfig.VERSION_NAME) } }
            .onSuccess { message.value = "Backup saved" }.onFailure { message.value = "Backup failed: ${it.message}" }
    }

    fun openRestoreList() = viewModelScope.launch(Dispatchers.IO) {
        BackupCoordinator.cleanStaging(app)
        val items = runCatching { BackupCoordinator.list(app, graph).map { BackupItem(it.name, it.line) } }
            .getOrElse { message.value = "Could not read the backups: ${it.message}"; emptyList() }
        backupUi.value = buildBackupUi(backupUi.value).copy(list = items, offer = null)
    }

    fun closeRestoreList() { backupUi.value = buildBackupUi(backupUi.value).copy(list = null, offer = null) }

    /** Copy the chosen backup to the cache, check it, and show what is in it. Nothing is restored until [confirmRestore]. */
    fun prepareRestoreFromTarget(name: String) = prepare { BackupCoordinator.stage(app, graph, name) }

    fun prepareRestoreFromFile(uri: Uri) = prepare {
        withContext(Dispatchers.IO) { RestoreStaging.stage(app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("could not open the file"), java.io.File(app.cacheDir, "restore")) }
    }

    private fun prepare(stage: suspend () -> RestoreStaging.Staged) = viewModelScope.launch(Dispatchers.IO) {
        staged?.discard(); staged = null
        runCatching { stage() }.onSuccess { s ->
            staged = s
            val offer = RestoreOffer(RestoreText.preview(s.check), s.check !is RestoreCheck.Damaged)
            if (!offer.canRestore) { s.discard(); staged = null }
            backupUi.value = buildBackupUi(backupUi.value).let { it.copy(list = it.list ?: emptyList(), offer = offer) }
        }.onFailure { message.value = "Could not read the backup: ${it.message}" }
    }

    /** The existing hardened, newer wins restore ([BackupReader] through Catalog.readBackup), on the file that was checked. */
    fun confirmRestore() = viewModelScope.launch(Dispatchers.IO) {
        val s = staged ?: return@launch
        staged = null
        runCatching { s.file.inputStream().use { catalog.readBackup(it) } }
            .onSuccess { message.value = "Restored ${Plurals.edits(it)}" }.onFailure { message.value = "Restore failed: ${it.message}" }
        s.discard()
        closeRestoreList()
    }

    fun cancelRestore() { staged?.discard(); staged = null; closeRestoreList() }
}
