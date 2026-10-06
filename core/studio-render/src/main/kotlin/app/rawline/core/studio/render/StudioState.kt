package app.rawline.core.studio.render

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.Document
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class Tool { MOVE, SCALE, BRUSH, ERASER }

/** Straight colour 0..1 in the document's blend space (decision D6). */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun array() = floatArrayOf(r, g, b)
}

enum class SaveState(val label: String) {
    SAVED("saved"), DIRTY("changes not saved yet"), SAVING("saving"), FAILED("last save failed, will try again"),
}

class UiMessage(val id: Long, val text: String)

enum class Phase { LOADING, READY, ERROR }

/** Everything the screen draws. Immutable; the session replaces it atomically. Tool settings are written by the UI thread at once, the document parts by the session's model thread. */
data class StudioState(
    val phase: Phase = Phase.LOADING,
    val error: String? = null,
    val document: Document,
    val activeId: String,
    val tool: Tool = Tool.BRUSH,
    val brush: Brush = Brush(),
    val eraser: Brush = Brush(erase = true),
    val colour: Rgb = Rgb(0f, 0f, 0f),
    val background: Rgb = Rgb(1f, 1f, 1f),
    val recent: List<Rgb> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val thumbs: Map<String, Thumb> = emptyMap(),
    /** Layers that hold pixels (deleting one asks first). */
    val nonBlank: Set<String> = emptySet(),
    val save: SaveState = SaveState.SAVED,
    val message: UiMessage? = null,
    val recovered: Boolean = false,
    val zoomPercent: Int = 100,
)

/** Threads and clocks of a session, so the tests can run it on one thread with a fake clock. */
class StudioEnv(
    /** Every piece of session state is touched on this one thread only (decode, history, commit, thumbnails). Never the main thread, never the GL thread. */
    val model: Executor,
    /** Layer encoding and file writes. */
    val saver: Executor,
    /** Runs [Runnable] after the delay (ms) on any thread. */
    val later: (Long, Runnable) -> Unit,
    val clock: () -> Long = System::currentTimeMillis,
    val nanos: () -> Long = System::nanoTime,
    val report: (String, Long) -> Unit = { _, _ -> },
    val error: (String) -> Unit = {},
) {
    companion object {
        private fun pool(name: String) = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, name) }.apply { allowCoreThreadTimeOut(true) }

        fun production(report: (String, Long) -> Unit, error: (String) -> Unit): StudioEnv {
            val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "studio-timer").apply { isDaemon = true } }.apply { setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
            return StudioEnv(pool("studio-model"), pool("studio-save"), { ms, r -> timer.schedule(r, ms, TimeUnit.MILLISECONDS) }, report = report, error = error)
        }
    }
}
