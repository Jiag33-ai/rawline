package app.rawline

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import app.rawline.core.ui.LrDim
import app.rawline.core.ui.LrMotion
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.rawline.core.cache.CrashStore
import app.rawline.core.cache.PerfLog
import app.rawline.core.model.Photo
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView
import app.rawline.core.ui.RawlineTheme
import app.rawline.feature.export.QueueScreen
import app.rawline.feature.library.LibraryActions
import app.rawline.feature.library.LibraryScreen
import app.rawline.feature.loupe.LoupeScreen
import app.rawline.feature.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Set when a notification asks for a screen ("queue"); the root navigates there and clears it. */
    private val openRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The canvas is always black, so the clock, battery and navigation icons must always be light: the default style follows the
        // phone's light/dark setting and drew dark icons on black in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // only on a fresh start: after a restore the same intent is replayed and must not jump to the queue again
        if (savedInstanceState == null) openRoute.value = intent?.getStringExtra(EXTRA_OPEN)
        setContent {
            RawlineTheme {
                Surface(color = Lr.Canvas) {
                    // Develop | Studio when this build has Studio (StudioEntry in src/studioOn); otherwise Develop alone, exactly as before
                    StudioEntry.Root(openRoute.value) { modeSwitch -> RawlineRoot(openRoute.value, modeSwitch) { openRoute.value = null } }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRoute.value = intent.getStringExtra(EXTRA_OPEN)
    }

    /** Saves the timings and errors so they survive if Android kills the process while the app is in the background. */
    override fun onStop() {
        super.onStop()
        PerfLog.flushSoon()
    }

    companion object { const val EXTRA_OPEN = "open" }
}

private val TopLevel = listOf("photos", "queue", "settings")

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Opens a system settings page. Neither the page nor its fallback is guaranteed to exist on every build, so a miss tells the user the way by hand. */
private fun openSettings(context: Context, intent: android.content.Intent, byHand: String, fallback: android.content.Intent? = null, toast: (String) -> Unit) {
    val ok = runCatching { context.startActivity(intent) }.isSuccess || (fallback != null && runCatching { context.startActivity(fallback) }.isSuccess)
    if (!ok) toast(byHand)
}

@Composable
private fun RawlineRoot(openRoute: String?, modeSwitch: (@Composable () -> Unit)?, onOpened: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as RawlineApplication).graph
    val vm: LibraryViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val listing by vm.listing.collectAsStateWithLifecycle()
    val photos = listing.photos
    val allPhotos by vm.allPhotos.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()

    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val permission by vm.permissionGranted.collectAsStateWithLifecycle()
    val allFiles by vm.allFilesGranted.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val xmp by vm.xmp.collectAsStateWithLifecycle()
    val copied by vm.copied.collectAsStateWithLifecycle()
    val lastEdited by vm.lastEdited.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    // remembered: observe() returns a new Flow each call, and the root recomposes on every job progress write
    val jobsFlow = remember { graph.db.exports().observe() }
    val jobs by jobsFlow.collectAsStateWithLifecycle(emptyList())
    // only the running flag, not every progress fraction (those arrive several times a second and would recompose the whole root)
    val exportRunningFlow = remember { graph.exportRunner.progress.map { it.running }.distinctUntilChanged() }
    val exportRunning by exportRunningFlow.collectAsStateWithLifecycle(false)
    val workerAlive = exportRunning || ExportService.isRunning
    var toast by remember { mutableStateOf<ToastMsg?>(null) }
    fun showToast(text: String) { toast = Toasts.make(text, toast) }
    // keyed on the sequence number, so the same text twice restarts the timer; errors and Undo stay longer
    LaunchedEffect(toast?.seq) {
        toast?.let {
            kotlinx.coroutines.delay(Toasts.durationMs(it))
            if (it.action != null) vm.undo.value = null    // the chance to undo ends with the toast
            toast = null
        }
    }
    val undo by vm.undo.collectAsStateWithLifecycle()
    LaunchedEffect(undo) { undo?.let { toast = Toasts.make(it.message, toast, action = "Undo") } }
    val nav = rememberNavController()
    val route by nav.currentBackStackEntryAsState()
    var exportSettingsFor by remember { mutableStateOf<Pair<Boolean, Photo?>?>(null) }

    val permissionBlocked by vm.permissionBlocked.collectAsStateWithLifecycle()
    // Whether Android would still show the dialog. False before the first ask and after the second denial (see MediaAccess).
    fun rationale() = context.findActivity()?.let { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, vm.mediaPermission) } ?: false
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult(rationale()) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // The notification permission is asked once, when the first export is queued (with the reason obvious), not at launch alongside
    // the photo permission, where Android would drop one of the two dialogs.
    fun queueExport(list: List<Photo>) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !graph.prefs.getBoolean("notifAsked", false) &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            graph.prefs.edit().putBoolean("notifAsked", true).apply()
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.enqueueExport(list)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addFolder(uri) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importFiles(uris) }
    val backupOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.backupTo(uri) }
    val backupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restoreFrom(uri) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.onResume(rationale()); scope.launch(Dispatchers.IO) { runCatching { graph.exportRunner.recoverAfterStart() } } } }
        lifecycleOwner.lifecycle.addObserver(o)
        onDispose { lifecycleOwner.lifecycle.removeObserver(o) }
    }
    // The camera roll shows up by itself: ask for access on the very first launch only. The flag survives rotation, so the dialog
    // is not launched twice, and later launches use the library's own button (Allow access, or Open settings once Android stops asking).
    var autoAsked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prompt = if (permission) MediaPrompt.GRANTED else if (permissionBlocked) MediaPrompt.OPEN_SETTINGS else MediaPrompt.ASK
        if (MediaAccess.autoAsk(prompt, vm.mediaAsked, autoAsked)) { autoAsked = true; mediaPermission.launch(vm.mediaPermission) }
    }
    LaunchedEffect(message) {
        message?.let { if (it.isNotEmpty() && nav.currentDestination?.route != "settings") { showToast(it); vm.message.value = null } }
    }

    // The editor swaps photos within the list the viewer was opened from (see Browse), not the live filtered list: editing a photo
    // can make it leave "Unedited", and the next swipe must still go to its neighbour.
    fun browseList() = Browse.resolve(vm.browseIds.value, allPhotos, photos)
    fun openEditor(from: Photo, delta: Int) {
        val (index, next) = Browse.step(browseList(), from.id, delta) ?: return
        // Back from the editor should land on the photo edited last, so tell the viewer entry below where to be
        nav.previousBackStackEntry?.savedStateHandle?.set("jump", index)
        nav.navigate("edit/${next.id}") { popUpTo("edit/{id}") { inclusive = true } }
    }

    // Open from a notification ("queue")
    LaunchedEffect(openRoute) {
        if (openRoute == "queue") { nav.navigate("queue") { popUpTo("photos"); launchSingleTop = true }; onOpened() } else if (openRoute != null) onOpened()
    }

    val currentRoute = route?.destination?.route
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            // Library to editor and back is a horizontal move (320 ms, no bounce); the tabs and photo to photo swipes do not slide.
            val slide = tween<androidx.compose.ui.unit.IntOffset>(LrMotion.page, easing = LrMotion.standard)
            fun androidx.navigation.NavBackStackEntry.top() = destination.route in TopLevel
            androidx.compose.animation.SharedTransitionLayout {
            NavHost(
                nav, startDestination = "photos",
                enterTransition = {
                    if (initialState.destination.route == targetState.destination.route) androidx.compose.animation.EnterTransition.None
                    else if (initialState.top() && targetState.top()) androidx.compose.animation.fadeIn(tween(LrMotion.fast))
                    else androidx.compose.animation.slideInHorizontally(slide) { it }
                },
                exitTransition = {
                    if (initialState.destination.route == targetState.destination.route) androidx.compose.animation.ExitTransition.None
                    else if (initialState.top() && targetState.top()) androidx.compose.animation.fadeOut(tween(LrMotion.instant))
                    else androidx.compose.animation.slideOutHorizontally(slide) { -it * 8 / 100 }
                },
                popEnterTransition = {
                    if (initialState.top() && targetState.top()) androidx.compose.animation.fadeIn(tween(LrMotion.fast))
                    else androidx.compose.animation.slideInHorizontally(slide) { -it * 8 / 100 }
                },
                popExitTransition = {
                    if (initialState.top() && targetState.top()) androidx.compose.animation.fadeOut(tween(LrMotion.instant))
                    else androidx.compose.animation.slideOutHorizontally(slide) { it }
                },
            ) {
                composable("photos") {
                    // read here, not in the root: the viewer updates these as the user swipes and the whole root should not recompose for it
                    val scanning by vm.scanning.collectAsStateWithLifecycle()
                    val lastViewedId by vm.lastViewedId.collectAsStateWithLifecycle()
                    val sharedPhoto = photoShared(this@SharedTransitionLayout, this)
                    androidx.compose.runtime.CompositionLocalProvider(app.rawline.core.ui.LocalSharedPhoto provides sharedPhoto) {
                    Box(Modifier.statusBarsPadding()) {
                        LibraryScreen(
                            photos = photos, rows = listing.rows, scanning = scanning, scrollToId = lastViewedId, onScrolledTo = { vm.lastViewedId.value = null },
                            allCount = allPhotos.size, cameras = cameras, filter = filter, thumbs = graph.thumbs, progress = progress,
                            sources = sources, selectedSource = source, permissionGranted = permission, permissionBlocked = permissionBlocked, allFilesGranted = allFiles,
                            actions = LibraryActions(
                                onOpen = { p ->
                                    val i = photos.indexOfFirst { it.id == p.id }
                                    // start decoding the tapped photo and its neighbours before the viewer is even on screen
                                    graph.previews.prefetch((listOf(i) + (1..3).map { i + it } + (1..2).map { i - it }).mapNotNull { photos.getOrNull(it) })
                                    vm.browseIds.value = photos.map { it.id }     // the viewer browses this order even if a rating changes the filter
                                    // singleTop: a double tap must not stack two viewers
                                    nav.navigate("loupe/$i") { launchSingleTop = true }
                                },
                                onFilter = { vm.filter.value = it },
                                onRate = { l, r -> vm.rate(l, r) }, onFlag = { l, f -> vm.flag(l, f) }, onLabel = { l, c -> vm.label(l, c) },
                                onCopyEdits = { vm.copyEdits(it) }, onPasteEdits = { l, s -> vm.pasteEdits(l, s) }, onSyncEdits = { f, t -> vm.syncEdits(f, t) },
                                onExport = { queueExport(it) },
                                onSelectSource = { vm.selectSource(it) },
                                onImportFiles = { filePicker.launch(arrayOf("*/*")) },
                                onAddFolder = { folderPicker.launch(null) },
                                onRequestPermission = { mediaPermission.launch(vm.mediaPermission) },
                                onOpenSettings = { openSettings(context, vm.appSettingsIntent(), "Open Settings, Apps, Rawline, Permissions and allow Photos") { showToast(it) } },
                                onRequestAllFiles = { openSettings(context, vm.allFilesIntent(), "Open Settings, Apps, Special app access, All files access", fallback = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) { showToast(it) } },
                                hasCopied = copied != null,
                            ),
                            modeSwitch = modeSwitch,
                        )
                    }
                                    }
                }
                composable("queue") {
                    Box(Modifier.statusBarsPadding()) {
                        QueueScreen(
                            jobs = QueueOrder.sort(jobs), workerAlive = workerAlive,
                            onCancel = { j -> if (j.status == 1) graph.exportRunner.cancelCurrent() else scope.launch { graph.db.exports().cancel(j.id) } },
                            // a job marked running with no export alive is put back in line (retry), or removed
                            onRetry = { j -> scope.launch { if (j.status == 1) graph.db.exports().requeueRunning(j.id) else graph.db.exports().retry(j.id); graph.exportRunner.startService() } },
                            onRemove = { j -> scope.launch { graph.db.exports().delete(j.id) } },
                            onClearFinished = { scope.launch { graph.db.exports().clearFinished() } },
                            onCancelAll = { scope.launch { graph.db.exports().cancelWaiting() } },
                            onSettings = { exportSettingsFor = true to null },
                        )
                    }
                }
                composable("loupe/{index}", arguments = listOf(navArgument("index") { type = NavType.IntType })) { entry ->
                    val sharedPhoto = photoShared(this@SharedTransitionLayout, this)
                    androidx.compose.runtime.CompositionLocalProvider(app.rawline.core.ui.LocalSharedPhoto provides sharedPhoto) {
                    val browseIds by vm.browseIds.collectAsStateWithLifecycle()
                    val viewerPhotos = remember(browseIds, allPhotos, if (browseIds.isEmpty()) photos else null) { Browse.resolve(browseIds, allPhotos, photos) }
                    val jump by entry.savedStateHandle.getStateFlow<Int?>("jump", null).collectAsStateWithLifecycle()
                    LoupeScreen(
                        photos = viewerPhotos, startIndex = entry.arguments?.getInt("index") ?: 0,
                        previews = graph.previews, thumbs = graph.thumbs, showOverlay = overlay,
                        onBack = { nav.popBackStack() },
                        onEdit = { p -> nav.navigate("edit/${p.id}") { launchSingleTop = true } },
                        onRate = { p, r -> vm.rate(listOf(p), r) }, onFlag = { p, f -> vm.flag(listOf(p), f) }, onLabel = { p, l -> vm.label(listOf(p), l) },
                        onExport = { p -> queueExport(listOf(p)) },
                        onDwell = { p -> graph.rawPrefetch.prefetch(p) },
                        loaded = loaded, jumpTo = jump, onJumped = { entry.savedStateHandle["jump"] = null },
                        onPageSettled = { p -> vm.lastViewedId.value = p.id },
                    )
                                    }
                }
                composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val sharedPhoto = photoShared(this@SharedTransitionLayout, this)
                    androidx.compose.runtime.CompositionLocalProvider(app.rawline.core.ui.LocalSharedPhoto provides sharedPhoto) {
                    val id = entry.arguments?.getLong("id") ?: 0L
                    val photo = allPhotos.firstOrNull { it.id == id } ?: photos.firstOrNull { it.id == id }
                    if (photo != null) {
                        val browseIds by vm.browseIds.collectAsStateWithLifecycle()
                        val neighbours = remember(browseIds, allPhotos, if (browseIds.isEmpty()) photos else null, id) { Browse.neighbours(Browse.resolve(browseIds, allPhotos, photos), id) }
                        EditorHost(
                            photo, graph,
                            neighbors = neighbours,
                            copied = copied, lastEdited = lastEdited,
                            onCopied = { vm.copied.value = it },
                            onLeftEdited = { r -> if (r != app.rawline.core.model.EditRecipe()) vm.lastEdited.value = r },
                            onNotify = { vm.message.value = it },
                            onExport = { queueExport(listOf(it)) },
                            onExportSettings = { exportSettingsFor = true to it },
                            onSwipe = { delta -> openEditor(photo, delta) },
                            onBack = { nav.popBackStack() },
                        )
                    } else if (!loaded) {
                        // the library list is still being read (cold restore): show a spinner, not a black screen
                        Box(Modifier.fillMaxSize().background(Lr.Canvas), contentAlignment = Alignment.Center) { app.rawline.core.ui.LocalLoader(size = 28.dp) }
                    }
                                    }
                }
                composable("settings") {
                    // a "Backup saved" shown here must not still be there next time Settings opens
                    DisposableEffect(Unit) { onDispose { vm.message.value = null } }
                    var cacheBytes by remember { mutableStateOf(0L) }
                    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { graph.thumbs.diskBytes() } }
                    var lastCrash by remember { mutableStateOf(CrashStore.lastForBuild(context, ReportBuilder.buildLabel)) }
                    Box(Modifier.statusBarsPadding()) {
                        SettingsScreen(
                            versionName = BuildConfig.VERSION_NAME, buildNumber = BuildConfig.BUILD_NUMBER, buildDate = BuildConfig.BUILD_DATE,
                            overlayOn = overlay, onOverlayChange = vm::setOverlay,
                            onCopyReport = {
                                scope.launch {
                                    val label = when { source == "device:*" -> "all device photos"; source.startsWith("device:") -> "album ${source.removePrefix("device:")}"; else -> "a picked folder or imports" }
                                    val text = withContext(Dispatchers.IO) { ReportBuilder.build(context, graph, allPhotos.size, label) }
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rawline report", text))
                                    showToast("Report copied")
                                }
                            },
                            lastCrash = lastCrash,
                            onClearCrash = { CrashStore.clear(context); lastCrash = null },
                            cacheBytes = cacheBytes,
                            onClearThumbnails = { scope.launch { withContext(Dispatchers.IO) { graph.thumbs.clearDisk() }; cacheBytes = 0L; showToast("Thumbnails cleared") } },
                            xmpOn = xmp, onXmpChange = vm::setXmp,
                            onBackup = { backupOut.launch("rawline-backup.zip") }, onRestore = { backupIn.launch(arrayOf("application/zip", "application/octet-stream")) },
                            message = message,
                            onVersionLongPress = StudioEntry.debugLongPress(context),
                            studioNote = StudioEntry.settingsNote(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }            }
            // Drawn after the NavHost so it sits on top of every screen (the screens paint opaque backgrounds). Screens without the
            // bottom tab bar run under the system navigation bar, so the toast keeps clear of it as well.
            ToastHost(toast, onAction = { vm.undoLast() }, Modifier.align(Alignment.BottomCenter).then(if (currentRoute in TopLevel) Modifier else Modifier.navigationBarsPadding()).padding(bottom = Toasts.bottomOffsetDp(currentRoute).dp, start = 16.dp, end = 16.dp))
        }
        if (currentRoute in TopLevel) {
            Row(Modifier.fillMaxWidth().background(Lr.Surface1).navigationBarsPadding().height(LrDim.bottomNav)) {
                NavItem(LrIcon.PHOTOS, "Photos", currentRoute == "photos", 0, Modifier.weight(1f)) { nav.navigate("photos") { popUpTo("photos") { inclusive = false }; launchSingleTop = true } }
                NavItem(LrIcon.QUEUE, "Queue", currentRoute == "queue", jobs.count { it.status == 0 || it.status == 1 }, Modifier.weight(1f)) { nav.navigate("queue") { popUpTo("photos"); launchSingleTop = true } }
                NavItem(LrIcon.SETTINGS, "Settings", currentRoute == "settings", 0, Modifier.weight(1f)) { nav.navigate("settings") { popUpTo("photos"); launchSingleTop = true } }
            }
        }
    }
    exportSettingsFor?.let { (_, p) -> ExportSettingsDialog(graph, p, onDismiss = { exportSettingsFor = null }) }
}

@Composable
private fun NavItem(icon: LrIcon, label: String, selected: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val col = if (selected) Lr.Accent else Color(0xFFBDBDBD)
    Column(modifier.fillMaxSize().clickable(onClick = onClick).semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Box {
            LrIconView(icon, col, size = 22.dp)
            if (badge > 0) Box(Modifier.align(Alignment.TopEnd).padding(start = 14.dp).background(Lr.Accent, CircleShape).padding(horizontal = 4.dp)) { Text("$badge", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp), color = Color.White) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = col)
    }
}

/** Quiet toast above the navigation: #292929, 6 dp, 13 sp, 160 ms in and 120 ms out. */
@Composable
private fun ToastHost(toast: ToastMsg?, onAction: () -> Unit, modifier: Modifier) {
    // keep the last text while the fade out runs
    var last by remember { mutableStateOf("") }
    var lastAction by remember { mutableStateOf<String?>(null) }
    if (toast != null) { last = toast.text; lastAction = toast.action }
    androidx.compose.animation.AnimatedVisibility(
        toast != null, modifier,
        enter = androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.slideInVertically(tween(160)) { 6 },
        exit = androidx.compose.animation.fadeOut(tween(120)),
    ) {
        val shown = last
        Box(Modifier.widthIn(max = 320.dp).defaultMinSize(minHeight = 36.dp).background(Color(0xFF292929), RoundedCornerShape(6.dp)).border(1.dp, Lr.BorderSubtle, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shown, style = MaterialTheme.typography.bodySmall, color = Lr.TextPrimary, modifier = Modifier.weight(1f, fill = false))
                lastAction?.let { a -> Text(a, style = MaterialTheme.typography.labelLarge, color = Lr.Accent, modifier = Modifier.padding(start = 16.dp).clickable(onClick = onAction).padding(vertical = 8.dp, horizontal = 4.dp)) }
            }
        }
    }
}

/** The shared-element modifier for a photo's picture inside one navigation destination (420 ms, same curve as the spec). */
@androidx.compose.runtime.Composable
private fun photoShared(
    transition: androidx.compose.animation.SharedTransitionScope,
    visibility: androidx.compose.animation.AnimatedVisibilityScope,
): @androidx.compose.runtime.Composable (Long) -> androidx.compose.ui.Modifier = { id ->
    with(transition) {
        androidx.compose.ui.Modifier.sharedElement(
            rememberSharedContentState("photo-$id"), visibility,
            boundsTransform = { _, _ -> tween(LrMotion.shared, easing = LrMotion.enter) },
        )
    }
}
