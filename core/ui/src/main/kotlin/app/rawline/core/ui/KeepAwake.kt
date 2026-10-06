package app.rawline.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** Counts the screens that want the display kept on, so one screen leaving never switches it off under another. */
class HoldCounter {
    private var n = 0
    @Synchronized fun acquire(): Int { n++; return n }
    @Synchronized fun release(): Int { if (n > 0) n--; return n }
}

private val holds = HoldCounter()

/**
 * Keeps the screen on while [enabled] and this composable is on screen (culling photos, importing, exporting). Scoped: it is
 * released when the screen leaves composition, so the normal screen timeout applies everywhere else.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        if (enabled) view.keepScreenOn = holds.acquire() > 0
        onDispose { if (enabled) view.keepScreenOn = holds.release() > 0 }
    }
}
