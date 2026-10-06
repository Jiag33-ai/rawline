package app.rawline.feature.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.NewerSchemaException
import app.rawline.core.studio.model.ProjectFormatException
import app.rawline.core.studio.model.RawPixels
import app.rawline.core.studio.render.PhotoImport
import app.rawline.core.studio.render.StudioEnv
import app.rawline.core.studio.render.StudioGl
import app.rawline.core.studio.render.StudioPerf
import app.rawline.core.studio.render.StudioProjects
import app.rawline.core.studio.render.StudioSession
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LocalLoader
import app.rawline.core.ui.LrTextButton
import app.rawline.core.ui.PrimaryButton
import app.rawline.core.ui.SecondaryButton
import app.rawline.core.ui.TouchChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Open(val session: StudioSession, val gl: StudioGl)

/**
 * Entry point of Studio until S1c brings the real home screen: a start screen (new blank project with the 12 megapixel cap shown, a new project from a photo, open the last
 * project) and the canvas. Every disk and codec step runs on Dispatchers.IO; the main thread only builds the session and shows it.
 */
@Composable
fun StudioHost(projects: StudioProjects, perf: StudioPerf, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var open by remember { mutableStateOf<Open?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<String?>(null) }
    var newDialog by remember { mutableStateOf(false) }
    LaunchedEffect(open == null) { if (open == null) latest = withContext(Dispatchers.IO) { projects.latestId() } }

    fun openSession(doc: Document, pixels: Map<String, RawPixels>, onDisk: Boolean, recovered: Boolean) {
        val gl = StudioGl(perf)
        val env = StudioEnv.production(perf::record, perf::error)
        val s = StudioSession(projects.fs(), projects.rootOf(doc.id), gl, env, doc, pixels, onDisk, recovered)
        gl.inputStamp = s::takeInputStamp
        s.start()
        open = Open(s, gl)
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = "Reading the picture"; message = null
            val px = withContext(Dispatchers.IO) { PhotoImport.decode(context, uri) }
            busy = null
            if (px == null) message = "Could not read that picture."
            else {
                val np = withContext(Dispatchers.IO) { projects.newFromPhoto(px) }
                openSession(np.document, np.pixels, onDisk = false, recovered = false)
            }
        }
    }

    // the screen is left: the session writes what is unsaved and lets go of the GL queue
    DisposableEffect(open) { val o = open; onDispose { if (o != null) { o.session.release(); o.gl.release() } } }

    val o = open
    if (o != null) {
        StudioCanvasScreen(o.session, o.gl, onExit = { open = null }, modifier)
    } else {
        BackHandler { onExit() }
        Column(modifier.fillMaxSize().background(Lr.Canvas).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Studio", style = MaterialTheme.typography.titleLarge, color = Lr.TextPrimary)
            Text("Debug entry. The home screen arrives with S1c.", style = MaterialTheme.typography.bodySmall, color = Lr.TextMuted)
            Spacer(Modifier.height(20.dp))
            PrimaryButton("New blank project", { newDialog = true }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            SecondaryButton("New project from a photo", { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Open the last project", {
                val id = latest ?: return@SecondaryButton
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
            }, Modifier.fillMaxWidth(), enabled = latest != null)
            Spacer(Modifier.height(16.dp))
            if (busy != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { LocalLoader(size = 20.dp); Text(busy ?: "", color = Lr.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, color = Lr.Error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (newDialog) NewProjectDialog(
        onCreate = { w, h ->
            newDialog = false
            scope.launch {
                busy = "Making the canvas"; message = null
                val np = withContext(Dispatchers.IO) { projects.newBlank(w, h) }
                busy = null
                openSession(np.document, np.pixels, onDisk = false, recovered = false)
            }
        },
        onDismiss = { newDialog = false },
    )
}

private val presets = listOf("Square" to (2048 to 2048), "Photo 3:2" to (3000 to 2000), "Largest" to (4000 to 3000))

/** Canvas size: three presets and two typed fields, with the 12 megapixel cap shown and the reason when a size is refused. */
@Composable
private fun NewProjectDialog(onCreate: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf("2048") }
    var h by remember { mutableStateOf("2048") }
    val wi = w.trim().toIntOrNull(); val hi = h.trim().toIntOrNull()
    val problem = StudioProjects.sizeProblem(wi, hi)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The canvas can be up to 12 megapixels (for example 4000 x 3000) for now.", style = MaterialTheme.typography.bodySmall, color = Lr.TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((label, size) in presets) TouchChip(label, wi == size.first && hi == size.second, { w = size.first.toString(); h = size.second.toString() })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SizeField("Width", w, { w = it }, Modifier.weight(1f))
                    Text("x", color = Lr.TextMuted)
                    SizeField("Height", h, { h = it }, Modifier.weight(1f))
                }
                Text(problem ?: "${"%.1f".format(wi!!.toLong() * hi!! / 1e6)} megapixels", style = MaterialTheme.typography.bodySmall, color = if (problem != null) Lr.Error else Lr.TextMuted)
            }
        },
        confirmButton = { LrTextButton(onClick = { if (problem == null) onCreate(wi!!, hi!!) }, enabled = problem == null) { Text("Create") } },
        dismissButton = { LrTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SizeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    BasicTextField(
        value, { onChange(it.filter { c -> c.isDigit() }.take(5)) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Lr.TextPrimary, fontSize = 14.sp), cursorBrush = SolidColor(Lr.Focus),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier.height(40.dp).clip(RoundedCornerShape(4.dp)).background(Lr.Input).border(1.dp, Lr.InputBorder, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp)
            .semantics { contentDescription = label },
        decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() } },
    )
}
