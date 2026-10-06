package app.rawline

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.rawline.feature.onboarding.StudioText
import app.rawline.core.cache.ExitReasons
import app.rawline.core.cache.PerfLog
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import app.rawline.core.studio.model.HandOffRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.rawline.core.studio.model.AppMode
import app.rawline.core.studio.model.ExitInfo
import app.rawline.core.studio.model.ExitKind
import app.rawline.core.studio.model.OpenMark
import app.rawline.core.studio.model.KeyValue
import app.rawline.core.studio.model.ModeState
import app.rawline.core.studio.model.StartGuard
import app.rawline.core.studio.render.StudioPerf
import app.rawline.core.studio.render.StudioStats
import app.rawline.core.ui.LrSegmentedToggle
import app.rawline.feature.studio.StudioRoot
import java.io.File

/** Flag on: Studio is part of this build. Timers and errors go to the same PerfLog as Develop, so the Copy report carries them. */
internal val studioPerf = object : StudioPerf {
    override fun record(name: String, value: Long) = PerfLog.record(name, value)
    override fun error(message: String) = PerfLog.error(message)
}

/** The existing "rawline" SharedPreferences. commit(), not apply(): the start guard must be on disk before a crash a moment later. They are tiny writes, a few per mode switch. */
internal class PrefsKeyValue(private val p: SharedPreferences) : KeyValue {
    override fun getString(key: String): String? = p.getString(key, null)
    override fun putString(key: String, value: String) { p.edit().putString(key, value).commit() }
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun putInt(key: String, value: Int) { p.edit().putInt(key, value).commit() }
}

/** Open in Studio: the project made from a Develop photo, waiting for the mode host to switch to Studio and open it. Main thread only. */
internal val pendingHandOff = mutableStateOf<HandOffRequest?>(null)

object StudioEntry {
    const val available = true

    /**
     * The editor menu item "Open in Studio" (spec 3.7, D9). Renders the photo with its edit through the Develop export pipeline (at most 12 MP, sRGB, no output sharpening) on the app scope, then
     * hands the project to the mode host. Develop's recipe file and catalogue are only read; nothing flows back.
     */
    fun openInStudio(context: Context, graph: Graph, photo: Photo, recipe: () -> EditRecipe, notify: (String) -> Unit): (() -> Unit)? = {
        val r = recipe()   // the edit as it is at this tap
        notify("Preparing the picture for Studio")
        graph.appScope.launch {
            val req = try { StudioHandOffRunner.prepare(context.applicationContext, graph, photo, r) } catch (e: CancellationException) { throw e } catch (e: Throwable) { PerfLog.error("open in Studio: ${e.javaClass.simpleName} ${e.message}"); null }
            withContext(Dispatchers.Main) { if (req == null) notify("Could not open this photo in Studio.") else pendingHandOff.value = req }
        }
        Unit
    }

    /**
     * ModeHost: Develop or Studio, remembered between runs. Two Studio starts that never drew the home fall back to Develop with a notice (BK-409). The switch is handed to the two home
     * screens only; the loupe, the editor and the canvas never get it. Each mode keeps its saved state while the other is shown (Develop returns to the screen it was on).
     * A notification that asks for a Develop screen switches to Develop first.
     */
    @Composable
    fun Root(openRoute: String?, develop: @Composable (modeSwitch: (@Composable () -> Unit)?) -> Unit) {
        val context = LocalContext.current
        val prefs = remember { (context.applicationContext as RawlineApplication).graph.prefs }
        val modeState = remember { ModeState(BuildConfig.STUDIO_ENABLED, PrefsKeyValue(prefs)) }
        var mode by remember {
            // BK-506: a swipe away during the first frame of Studio is not a failed start. Only when a start is pending is the previous exit read (one cheap call); null when Android has no record.
            if (modeState.startPending()) modeState.judgeLastStart(ExitReasons.last(context)?.let { ExitInfo(ExitKind.fromReason(it.reason), it.time) })
            mutableStateOf(modeState.startMode())
        }
        val openMark = remember { OpenMark(PrefsKeyValue(prefs)) }
        val holder = rememberSaveableStateHolder()
        LaunchedEffect(Unit) { modeState.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); modeState.clearNotice() } }
        LaunchedEffect(openRoute) { if (openRoute != null && mode == AppMode.STUDIO) mode = modeState.switchTo(AppMode.DEVELOP) }
        val handOff = pendingHandOff.value
        LaunchedEffect(handOff) { if (handOff != null && mode != AppMode.STUDIO) mode = modeState.switchTo(AppMode.STUDIO) }
        val toggle: @Composable () -> Unit = {
            LrSegmentedToggle(listOf("Develop", "Studio"), if (mode == AppMode.DEVELOP) 0 else 1, { mode = modeState.switchTo(if (it == 0) AppMode.DEVELOP else AppMode.STUDIO) })
        }
        when (mode) {
            AppMode.DEVELOP -> holder.SaveableStateProvider("develop") { develop(if (modeState.switchVisible(true)) toggle else null) }
            AppMode.STUDIO -> holder.SaveableStateProvider("studio") {
                StudioRoot(toggle, studioPerf, BuildConfig.VERSION_NAME, onReady = { modeState.studioReady() }, onBackToDevelop = { mode = modeState.switchTo(AppMode.DEVELOP) },
                    openMark = openMark, onActive = { modeState.studioActive() }, handOff = handOff, onHandOffTaken = { pendingHandOff.value = null })
            }
        }
    }

    /** The Studio block of the Copy report (spec 3 of the S1c task). Reads a few small files: call off the main thread. */
    fun reportSection(context: Context, prefs: SharedPreferences): Pair<String, String>? {
        val kv = PrefsKeyValue(prefs)
        val projects = File(context.filesDir, "studio").listFiles { f -> f.isDirectory }?.size ?: 0
        val lines = StringBuilder()
        lines.append("enabled yes (build flag)\n")
        lines.append("mode ${AppMode.fromKey(kv.getString(ModeState.KEY)).key}\n")
        lines.append("projects $projects\n")
        lines.append("starts pending ${kv.getInt(StartGuard.PENDING, 0)}\n")
        lines.append("last fallback to Develop ${if (kv.getInt(ModeState.LAST_FALLBACK, 0) == 1) "yes" else "no"}\n")
        // compositors made and not yet freed: 1 while a canvas is open, 0 after leaving Studio (anything else is a leak)
        lines.append("GL compositors alive ${runCatching { app.rawline.core.nativelib.StudioNative.liveHandles().toString() }.getOrDefault("unknown")}")
        StudioStats.describe()?.let { lines.append('\n').append(it) } ?: lines.append("\nNo project is open (GPU textures 0 MB)")
        return "Studio" to lines.toString()
    }

    /** Debug builds only: a long press on the Version row opens the stand alone Studio screen (src/debugStudioOn). Release builds with Studio have the mode switch instead. */
    fun debugLongPress(context: Context): (() -> Unit)? =
        if (BuildConfig.DEBUG) ({ context.startActivity(Intent().setClassName(context, "app.rawline.StudioDebugActivity")) }) else null

    fun settingsNote(): String? = "Studio is new. It is a layered painting space next to Develop: switch with Develop | Studio at the top of Photos. " +
        "Make a canvas or start from a photo, paint on layers, then export a flattened JPEG or PNG. Your photos and edits in Develop are not touched."

    /** The Studio screen of the welcome flow: what Studio is, in plain words (strings_studio_onboarding.xml, which only a build with Studio has). */
    @Composable
    fun onboardingText(): StudioText? = StudioText(stringResource(R.string.title_studio), stringResource(R.string.body_studio), stringResource(R.string.note_studio))
}
