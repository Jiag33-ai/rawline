package app.rawline

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RawlineTheme { Surface(color = Lr.Canvas) { RawlineRoot() } } }
    }
}

private val TopLevel = listOf("photos", "queue", "settings")

@Composable
private fun RawlineRoot() {
    val context = LocalContext.current
    val graph = (context.applicationContext as RawlineApplication).graph
    val vm: LibraryViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val allPhotos by vm.allPhotos.collectAsStateWithLifecycle()
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
    val message by vm.message.collectAsStateWithLifecycle()
    val jobs by graph.db.exports().observe().collectAsStateWithLifecycle(emptyList())
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2500); toast = null } }
    val nav = rememberNavController()
    val route by nav.currentBackStackEntryAsState()
    var exportSettingsFor by remember { mutableStateOf<Pair<Boolean, Photo?>?>(null) }

    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermission(it) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addFolder(uri) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importFiles(uris) }
    val backupOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.backupTo(uri) }
    val backupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restoreFrom(uri) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.onResume() }
        lifecycleOwner.lifecycle.addObserver(o)
        onDispose { lifecycleOwner.lifecycle.removeObserver(o) }
    }
    // The camera roll shows up by itself: ask for access on first launch.
    LaunchedEffect(Unit) {
        if (!permission) mediaPermission.launch(vm.mediaPermission)
        if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(message) {
        message?.let { if (it.isNotEmpty() && nav.currentDestination?.route != "settings") { toast = it; vm.message.value = null } }
    }

    fun openEditor(from: Photo, delta: Int) {
        val i = photos.indexOfFirst { it.id == from.id }
        val next = photos.getOrNull(i + delta) ?: return
        nav.navigate("edit/${next.id}") { popUpTo("edit/{id}") { inclusive = true } }
    }

    val currentRoute = route?.destination?.route
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            ToastHost(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            // Library to editor and back is a horizontal move (320 ms, no bounce); the tabs and photo to photo swipes do not slide.
            val slide = tween<androidx.compose.ui.unit.IntOffset>(LrMotion.page, easing = LrMotion.standard)
            fun androidx.navigation.NavBackStackEntry.top() = destination.route in TopLevel
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
                    Box(Modifier.statusBarsPadding()) {
                        LibraryScreen(
                            photos = photos, allCount = allPhotos.size, cameras = cameras, filter = filter, thumbs = graph.thumbs, progress = progress,
                            sources = sources, selectedSource = source, permissionGranted = permission, allFilesGranted = allFiles,
                            actions = LibraryActions(
                                onOpen = { p ->
                                    val i = photos.indexOfFirst { it.id == p.id }
                                    // start decoding the tapped photo and its neighbours before the viewer is even on screen
                                    graph.previews.prefetch((listOf(i) + (1..3).map { i + it } + (1..2).map { i - it }).mapNotNull { photos.getOrNull(it) })
                                    nav.navigate("loupe/$i")
                                },
                                onFilter = { vm.filter.value = it },
                                onRate = { l, r -> vm.rate(l, r) }, onFlag = { l, f -> vm.flag(l, f) }, onLabel = { l, c -> vm.label(l, c) },
                                onCopyEdits = { vm.copyEdits(it) }, onPasteEdits = { l, s -> vm.pasteEdits(l, s) }, onSyncEdits = { f, t -> vm.syncEdits(f, t) },
                                onExport = { vm.enqueueExport(it) },
                                onSelectSource = { vm.selectSource(it) },
                                onImportFiles = { filePicker.launch(arrayOf("*/*")) },
                                onAddFolder = { folderPicker.launch(null) },
                                onRequestPermission = { mediaPermission.launch(vm.mediaPermission) },
                                onRequestAllFiles = { runCatching { context.startActivity(vm.allFilesIntent()) }.onFailure { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } },
                                hasCopied = copied != null,
                            ),
                        )
                    }
                }
                composable("queue") {
                    Box(Modifier.statusBarsPadding()) {
                        QueueScreen(
                            jobs = jobs,
                            onCancel = { j -> if (j.status == 1) graph.exportRunner.cancelCurrent() else scope.launch { graph.db.exports().cancel(j.id) } },
                            onRetry = { j -> scope.launch { graph.db.exports().retry(j.id); graph.exportRunner.startService() } },
                            onRemove = { j -> scope.launch { graph.db.exports().delete(j.id) } },
                            onClearFinished = { scope.launch { graph.db.exports().clearFinished() } },
                            onCancelAll = { scope.launch { graph.db.exports().cancelWaiting() } },
                            onSettings = { exportSettingsFor = true to null },
                        )
                    }
                }
                composable("loupe/{index}", arguments = listOf(navArgument("index") { type = NavType.IntType })) { entry ->
                    LoupeScreen(
                        photos = photos, startIndex = entry.arguments?.getInt("index") ?: 0,
                        previews = graph.previews, thumbs = graph.thumbs, showOverlay = overlay,
                        onBack = { nav.popBackStack() },
                        onEdit = { p -> nav.navigate("edit/${p.id}") },
                        onRate = { p, r -> vm.rate(listOf(p), r) }, onFlag = { p, f -> vm.flag(listOf(p), f) }, onLabel = { p, l -> vm.label(listOf(p), l) },
                        onExport = { p -> vm.enqueueExport(listOf(p)) },
                        onDwell = { p -> graph.rawPrefetch.prefetch(p) },
                    )
                }
                composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    val photo = allPhotos.firstOrNull { it.id == id } ?: photos.firstOrNull { it.id == id }
                    if (photo != null) {
                        val i = photos.indexOfFirst { it.id == id }
                        EditorHost(
                            photo, graph,
                            neighbors = listOfNotNull(photos.getOrNull(i + 1), photos.getOrNull(i - 1)),
                            onExport = { vm.enqueueExport(listOf(it)) },
                            onExportSettings = { exportSettingsFor = true to it },
                            onSwipe = { delta -> openEditor(photo, delta) },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
                composable("settings") {
                    Box(Modifier.statusBarsPadding()) {
                        SettingsScreen(
                            versionName = BuildConfig.VERSION_NAME, buildNumber = BuildConfig.BUILD_NUMBER, buildDate = BuildConfig.BUILD_DATE,
                            overlayOn = overlay, onOverlayChange = vm::setOverlay,
                            onCopyReport = {
                                val v = "Rawline ${BuildConfig.VERSION_NAME} build ${BuildConfig.BUILD_NUMBER} (${BuildConfig.BUILD_DATE})"
                                val text = PerfLog.report(context, v, "Photos in this source: ${allPhotos.size}")
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rawline report", text))
                                toast = "Report copied"
                            },
                            lastCrash = remember { CrashStore.last(context) },
                            xmpOn = xmp, onXmpChange = vm::setXmp,
                            onBackup = { backupOut.launch("rawline-backup.zip") }, onRestore = { backupIn.launch(arrayOf("application/zip", "application/octet-stream")) },
                            message = message,
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
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
private fun ToastHost(text: String?, modifier: Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        text != null, modifier,
        enter = androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.slideInVertically(tween(160)) { 6 },
        exit = androidx.compose.animation.fadeOut(tween(120)),
    ) {
        val shown = remember(text) { text ?: "" }
        Box(Modifier.widthIn(max = 320.dp).defaultMinSize(minHeight = 36.dp).background(Color(0xFF292929), RoundedCornerShape(6.dp)).border(1.dp, Lr.BorderSubtle, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(shown, style = MaterialTheme.typography.bodySmall, color = Lr.TextPrimary)
        }
    }
}
