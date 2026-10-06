package app.rawline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import app.rawline.core.cache.PerfLog
import app.rawline.core.studio.render.StudioPerf
import app.rawline.core.studio.render.StudioProjects
import app.rawline.core.ui.Lr
import app.rawline.core.ui.RawlineTheme
import app.rawline.feature.studio.StudioHost

/** Hosts the Studio screens until S1c adds the home screen and the mode switch. Debug builds only. Timers and errors go to the same PerfLog as Develop, so Copy report carries them. */
class StudioDebugActivity : ComponentActivity() {
    private val perf = object : StudioPerf {
        override fun record(name: String, value: Long) = PerfLog.record(name, value)
        override fun error(message: String) = PerfLog.error(message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val projects = StudioProjects(filesDir, BuildConfig.VERSION_NAME)
        setContent { RawlineTheme { Surface(color = Lr.Canvas) { StudioHost(projects, perf, onExit = { finish() }) } } }
    }

    override fun onStop() {
        super.onStop()
        PerfLog.flushSoon()
    }
}
