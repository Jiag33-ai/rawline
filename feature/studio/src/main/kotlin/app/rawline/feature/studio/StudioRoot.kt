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
import app.rawline.core.studio.model.OpenMark
import app.rawline.core.studio.model.RawPick
import app.rawline.core.studio.model.SpaceCheck
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.widget.Toast
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.HandOffRequest
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
fun StudioRoot(
    modeSwitch: @Composable () -> Unit, perf: StudioPerf, appVersion: String, onReady: () -> Unit, onBackToDevelop: () -> Unit, modifier: Modifier = Modifier,
    /** BK-504: the project that was open when the last session ended. A normal Close clears it, a kill leaves it, and the home offers it as "Continue". Null: no Continue card. */
    openMark: OpenMark? = null,
    /** BK-504: Studio is in use (a project opened, the app paused, Close): the host stamps the time that decides the start mode. */
    onActive: () -> Unit = {},
    /** Open in Studio from Develop: a new project made from a photo and its recipe, opened as soon as this screen is up. Null when there is none. */
    handOff: HandOffRequest? = null,
    /** The hand off was taken (opened or refused): the host forgets it so it is never opened twice. */
    onHandOffTaken: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    val vm: StudioHomeViewModel = viewModel(factory = viewModelFactory { initializer { StudioHomeViewModel(application, perf) } })
    val projects = remember { StudioProjects(context.filesDir, appVersion) }
    var open by remember { mutableStateOf<Open?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val closedByUser = remember { booleanArrayOf(false) }

    fun openSession(doc: Document, pixels: Map<String, RawPixels>, onDisk: Boolean, recovered: Boolean, afterFirstSave: ((java.io.File) -> Unit)? = null) {
        val gl = StudioGl(perf)
        val env = StudioEnv.production(perf::record, perf::error)
        val s = StudioSession(projects.fs(), projects.rootOf(doc.id), gl, env, doc, pixels, onDisk, recovered, appVersion)
        if (afterFirstSave != null) s.afterFirstSave = { afterFirstSave(java.io.File(context.filesDir, projects.rootOf(doc.id))) }
        gl.inputStamp = s::takeInputStamp
        s.start()
        openMark?.open(doc.id)
        onActive()
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
            // BK-505: the file's name and type say whether this is a RAW file, so the message can say where RAW photos open
            val (name, mime, result) = withContext(Dispatchers.IO) { val (n, m) = PhotoImport.describe(context, uri); Triple(n, m, PhotoImport.decodeResult(context, uri)) }
            val px = result.pixels
            val space = px?.let { SpaceCheck.problem(context.filesDir.usableSpace, it.w.toLong() * it.h * 4 / 2) }
            if (px == null) { busy = null; message = RawPick.failure(name, mime, result.errorCode, result.tooLarge) }
            else if (space != null) { busy = null; message = space }
            else {
                RawPick.outcome(name, mime, decoded = true).message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }   // a DNG: Android's colours, not Develop's
                val now = System.currentTimeMillis()
                val layer = Layer.Pixel(LayerCommon("l1", "Photo"), px.w, px.h)
                val doc = Document(ProjectCatalog.newId(now), "Photo", px.w, px.h, layers = listOf(layer), created = now, modified = now)
                busy = null
                openSession(doc, mapOf("l1" to px), onDisk = false, recovered = false)
            }
        }
    }

    // Open in Studio: the project is made from the photo Develop rendered; the folder gets its source and recipe copies after the first save
    LaunchedEffect(handOff) {
        val h = handOff ?: return@LaunchedEffect
        if (open == null && busy == null) {
            val bytes = h.pixels.values.sumOf { it.rgba.size.toLong() }
            val space = SpaceCheck.problem(context.filesDir.usableSpace, bytes / 2)
            if (space != null) message = space else openSession(h.document, h.pixels, onDisk = false, recovered = false, afterFirstSave = h.afterFirstSave)
        }
        onHandOffTaken()
    }
    // the project list is read again each time the canvas closes (new size, new thumbnail, a project that was just made)
    LaunchedEffect(open == null) { if (open == null) vm.reload() }
    // the screen is left (to Develop, or the canvas closed): the session writes what is unsaved and lets go of the GL queue; the GPU gauges read zero again
    DisposableEffect(open) {
        val o = open
        onDispose {
            if (o != null) {
                o.session.release(); o.gl.release(); StudioStats.clear(); perf.record("studio_texture_mb", 0)
                if (closedByUser[0]) onActive()
                closedByUser[0] = false
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { onActive() }

    val o = open
    // read when the home appears (and again after a Close), not on every recomposition
    val continueId = remember(o == null) { if (o == null) openMark?.id() else null }
    if (o != null) {
        StudioCanvasScreen(o.session, o.gl, projects, perf, onExit = {
            // a normal Close clears the mark (the project itself is saved by the session's flush and release); leaving to Develop through the switch, or a kill, leaves it so Studio can offer "Continue"
            closedByUser[0] = true; openMark?.close(); open = null
        }, modifier = modifier)
    } else {
        BackHandler { onBackToDevelop() }
        Box(modifier.fillMaxSize()) {
            StudioHome(vm, modeSwitch, busy, continueId, HomeActions(onOpen = ::openExisting, onNewBlank = ::newBlank, onNewFromPhoto = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }))
        }
        message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { LrTextButton(onClick = { message = null }) { Text("OK") } }) }
        // the first frame of the home is on screen: the start guard resets (a crash before this point counts as a failed Studio start)
        LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; onReady() }
    }
}
