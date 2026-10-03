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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.rawline.core.model.Photo
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.rawline.core.cache.CrashStore
import app.rawline.core.cache.PerfLog
import app.rawline.core.ui.RawlineTheme
import app.rawline.feature.library.LibraryActions
import app.rawline.feature.library.LibraryScreen
import app.rawline.feature.loupe.LoupeScreen
import app.rawline.feature.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RawlineTheme { Surface(color = MaterialTheme.colorScheme.background) { RawlineRoot() } } }
    }
}

@Composable
private fun RawlineRoot() {
    val context = LocalContext.current
    val graph = (context.applicationContext as RawlineApplication).graph
    val vm: LibraryViewModel = viewModel()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val allPhotos by vm.allPhotos.collectAsStateWithLifecycle()
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val xmp by vm.xmp.collectAsStateWithLifecycle()
    val copied by vm.copied.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    var exportTargets by remember { mutableStateOf<List<Photo>?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.chooseFolder(uri) }
    val backupOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.backupTo(uri) }
    val backupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restoreFrom(uri) }

    LaunchedEffect(message) { message?.let { if (it.isNotEmpty() && nav.currentDestination?.route != "settings") Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    exportTargets?.let { targets ->
        ExportDialog(targets, graph, onDismiss = { exportTargets = null }, onStart = {
            if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        })
    }
    ExportStatusBar(graph)

    NavHost(nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                photos = photos, allCount = allPhotos.size, cameras = cameras, filter = filter, thumbs = graph.thumbs, progress = progress,
                folderLabel = if (folder == null) null else vm.folderLabel(),
                actions = LibraryActions(
                    onPickFolder = { picker.launch(null) },
                    onOpen = { p -> nav.navigate("loupe/${photos.indexOfFirst { it.id == p.id }}") },
                    onSettings = { nav.navigate("settings") },
                    onFilter = { vm.filter.value = it },
                    onRate = { l, r -> vm.rate(l, r) }, onFlag = { l, f -> vm.flag(l, f) }, onLabel = { l, c -> vm.label(l, c) },
                    onCopyEdits = { vm.copyEdits(it) }, onPasteEdits = { l, s -> vm.pasteEdits(l, s) }, onSyncEdits = { f, t -> vm.syncEdits(f, t) },
                    onExport = { exportTargets = it },
                    hasCopied = copied != null,
                ),
            )
        }
        composable("loupe/{index}", arguments = listOf(navArgument("index") { type = NavType.IntType })) { entry ->
            LoupeScreen(
                photos = photos, startIndex = entry.arguments?.getInt("index") ?: 0,
                previews = graph.previews, thumbs = graph.thumbs, showOverlay = overlay,
                onBack = { nav.popBackStack() },
                onEdit = { p -> nav.navigate("edit/${p.id}") },
                onRate = { p, r -> vm.rate(listOf(p), r) }, onFlag = { p, f -> vm.flag(listOf(p), f) }, onLabel = { p, l -> vm.label(listOf(p), l) },
                onDwell = { p -> graph.rawPrefetch.prefetch(p) },
            )
        }
        composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val photo = allPhotos.firstOrNull { it.id == id }
            if (photo != null) EditorHost(photo, graph, onExport = { exportTargets = listOf(it) }, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                versionName = BuildConfig.VERSION_NAME, buildNumber = BuildConfig.BUILD_NUMBER, buildDate = BuildConfig.BUILD_DATE,
                overlayOn = overlay, onOverlayChange = vm::setOverlay,
                onCopyReport = {
                    val v = "Rawline ${BuildConfig.VERSION_NAME} build ${BuildConfig.BUILD_NUMBER} (${BuildConfig.BUILD_DATE})"
                    val text = PerfLog.report(context, v, "Photos in folder: ${allPhotos.size}")
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rawline report", text))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                },
                lastCrash = CrashStore.last(context),
                xmpOn = xmp, onXmpChange = vm::setXmp,
                onBackup = { backupOut.launch("rawline-backup.zip") }, onRestore = { backupIn.launch(arrayOf("application/zip", "application/octet-stream")) },
                message = message,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
