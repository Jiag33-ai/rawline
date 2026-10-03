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
    val progress by vm.progress.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.chooseFolder(uri) }

    NavHost(nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                photos = photos, thumbs = graph.thumbs, progress = progress,
                folderLabel = if (folder == null) null else vm.folderLabel(),
                onPickFolder = { picker.launch(null) },
                onOpen = { nav.navigate("loupe/$it") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("loupe/{index}", arguments = listOf(navArgument("index") { type = NavType.IntType })) { entry ->
            LoupeScreen(
                photos = photos, startIndex = entry.arguments?.getInt("index") ?: 0,
                previews = graph.previews, thumbs = graph.thumbs, showOverlay = overlay,
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                versionName = BuildConfig.VERSION_NAME, buildNumber = BuildConfig.BUILD_NUMBER, buildDate = BuildConfig.BUILD_DATE,
                overlayOn = overlay, onOverlayChange = vm::setOverlay,
                onCopyReport = {
                    val v = "Rawline ${BuildConfig.VERSION_NAME} build ${BuildConfig.BUILD_NUMBER} (${BuildConfig.BUILD_DATE})"
                    val text = PerfLog.report(context, v, "Photos in folder: ${photos.size}")
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rawline report", text))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                },
                lastCrash = CrashStore.last(context),
                onBack = { nav.popBackStack() },
            )
        }
    }
}
