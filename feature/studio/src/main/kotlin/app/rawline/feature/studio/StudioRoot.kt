package app.rawline.feature.studio

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import app.rawline.core.ui.LrTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.rawline.core.studio.model.SpaceCheck
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.Layer
import app.rawline.core.studio.model.LayerCommon
import app.rawline.core.studio.model.NewProject
import app.rawline.core.studio.model.NewerSchemaException
import app.rawline.core.studio.model.ProjectCatalog
import app.rawline.core.studio.model.ProjectFormatException
import app.rawline.core.studio.model.RawPixels
import app.rawline.core.studio.render.PhotoImport
import app.rawline.core.studio.render.StudioEnv
import app.rawline.core.studio.render.StudioGl
import app.rawline.core.studio.render.StudioPerf
import app.rawline.core.studio.render.StudioProjects
import app.rawline.core.studio.render.StudioSession
import app.rawline.core.studio.render.StudioStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Open(val session: StudioSession, val gl: StudioGl)

/**
 * Studio as the app sees it: the project home, and the canvas for one open project. [modeSwitch] is the Develop | Studio control, drawn in the home's top bar only (never over the canvas).
 * [onReady] is called once the home has drawn its first frame (it resets the start guard). Back on the home goes to Develop.
 *
 * Leaving this composable (switching to Develop) disposes the open session, its GL view and the compositor, so Studio's GPU and native memory go back to Develop (BK-399). Projects stay on disk.
 * Every file, database and codec step runs on Dispatchers.IO; the main thread only builds the session objects.
 */
@Composable
fun StudioRoot(modeSwitch: @Composable () -> Unit, perf: StudioPerf, appVersion: String, onReady: () -> Unit, onBackToDevelop: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    val vm: StudioHomeViewModel = viewModel(factory = viewModelFactory { initializer { StudioHomeViewModel(application, perf) } })
    val projects = remember { StudioProjects(context.filesDir, appVersion) }
    var open by remember { mutableStateOf<Open?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun openSession(doc: Document, pixels: Map<String, RawPixels>, onDisk: Boolean, recovered: Boolean) {
        val gl = StudioGl(perf)
        val env = StudioEnv.production(perf::record, perf::error)
        val s = StudioSession(projects.fs(), projects.rootOf(doc.id), gl, env, doc, pixels, onDisk, recovered, appVersion)
        gl.inputStamp = s::takeInputStamp
        s.start()
        open = Open(s, gl)
    }

    fun openExisting(id: String) {
        if (busy != null || open != null) return
        scope.launch {
            busy = "Opening"; message = null
            val r = withContext(Dispatchers.IO) { runCatching { projects.open(id) } }
            busy = null
            r.onSuccess { openSession(it.document, emptyMap(), onDisk = true, recovered = it.recovered) }
                .onFailure { e ->
                    message = when (e) {
                        is NewerSchemaException -> "This project was made by a newer version of Rawline."   // not opened, so never written back
                        is ProjectFormatException -> e.message ?: "This project is damaged."
                        else -> "Could not open the project."
                    }
                }
        }
    }

    fun newBlank(p: NewProject.Preset) {
        if (busy != null || open != null) return
        SpaceCheck.problem(context.filesDir.usableSpace, 1L * 1024 * 1024)?.let { message = it; return }
        val now = System.currentTimeMillis()
        val doc = NewProject.blank(ProjectCatalog.newId(now), "Untitled", p.width, p.height, now)
        openSession(doc, emptyMap(), onDisk = false, recovered = false)
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && busy == null && open == null) scope.launch {
            busy = "Reading the picture"; message = null
            val px = withContext(Dispatchers.IO) { PhotoImport.decode(context, uri) }
            val space = px?.let { SpaceCheck.problem(context.filesDir.usableSpace, it.w.toLong() * it.h * 4 / 2) }
            if (px == null) { busy = null; message = "Could not read that picture." }
            else if (space != null) { busy = null; message = space }
            else {
                val now = System.currentTimeMillis()
                val layer = Layer.Pixel(LayerCommon("l1", "Photo"), px.w, px.h)
                val doc = Document(ProjectCatalog.newId(now), "Photo", px.w, px.h, layers = listOf(layer), created = now, modified = now)
                busy = null
                openSession(doc, mapOf("l1" to px), onDisk = false, recovered = false)
            }
        }
    }

    // the project list is read again each time the canvas closes (new size, new thumbnail, a project that was just made)
    LaunchedEffect(open == null) { if (open == null) vm.reload() }
    // the screen is left (to Develop, or the canvas closed): the session writes what is unsaved and lets go of the GL queue; the GPU gauges read zero again
    DisposableEffect(open) {
        val o = open
        onDispose { if (o != null) { o.session.release(); o.gl.release(); StudioStats.clear(); perf.record("studio_texture_mb", 0) } }
    }

    val o = open
    if (o != null) {
        StudioCanvasScreen(o.session, o.gl, projects, perf, onExit = { open = null }, modifier = modifier)
    } else {
        BackHandler { onBackToDevelop() }
        Box(modifier.fillMaxSize()) {
            StudioHome(vm, modeSwitch, busy, HomeActions(onOpen = ::openExisting, onNewBlank = ::newBlank, onNewFromPhoto = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }))
        }
        message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { LrTextButton(onClick = { message = null }) { Text("OK") } }) }
        // the first frame of the home is on screen: the start guard resets (a crash before this point counts as a failed Studio start)
        LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; onReady() }
    }
}
