package app.rawline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import app.rawline.core.cache.PerfLog
import app.rawline.core.ui.Lr
import app.rawline.core.ui.RawlineTheme
import app.rawline.feature.studio.StudioRoot

/** Debug builds with Studio on: the Studio home on its own (no mode state, no start guard), reached from a long press on the Version row. Back from the home closes it. */
class StudioDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { RawlineTheme { Surface(color = Lr.Canvas) { StudioRoot({}, studioPerf, BuildConfig.VERSION_NAME, onReady = {}, onBackToDevelop = { finish() }) } } }
    }

    override fun onStop() {
        super.onStop()
        PerfLog.flushSoon()
    }
}
