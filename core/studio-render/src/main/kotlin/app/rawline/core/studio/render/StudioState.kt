package app.rawline.core.studio.render

import app.rawline.core.studio.model.Brush
import app.rawline.core.studio.model.CanvasView
import app.rawline.core.studio.model.Document
import app.rawline.core.studio.model.IRect
import app.rawline.core.studio.model.SelOp
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class Tool { MOVE, SCALE, BRUSH, ERASER, RECT_SELECT, ELLIPSE_SELECT, LASSO_SELECT }

/** Where a new layer mask starts: all white (reveals everything), all black (hides everything), or the selection. */
enum class MaskFill { WHITE, BLACK, FROM_SELECTION }

/** The selection as the screen draws it: its bounds and the outline as x0, y0, x1, y1 segments in canvas pixels (marching ants). [version] changes with every edit. */
class SelectionView(val bounds: IRect, val contour: FloatArray, val version: Int)

/** The shape being dragged with a selection tool, in canvas pixels: two corners for a rectangle or an ellipse, the points so far for a lasso. */
class SelectionDrag(val tool: Tool, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val xs: FloatArray = FloatArray(0), val ys: FloatArray = FloatArray(0))

/** Straight colour 0..1 in the document's blend space (decision D6). */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun array() = floatArrayOf(r, g, b)
}

enum class SaveState(val label: String) {
    SAVED("saved"), DIRTY("changes not saved yet"), SAVING("saving"), FAILED("last save failed, will try again"), NO_SPACE("not saved: the phone is almost full"),
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
    /** S2: how the next selection combines with the current one. Stays until changed (no keyboard modifiers on a phone). */
    val selectionOp: SelOp = SelOp.REPLACE,
    /** S2: the current selection, or null for none. */
    val selection: SelectionView? = null,
    /** S2: the shape being dragged, or null. */
    val selectionDrag: SelectionDrag? = null,
    /** S2: the brush and eraser paint the active layer's mask instead of its pixels (only while that layer has a mask). */
    val paintMask: Boolean = false,
    /** S2: grey thumbnails of layer masks, by layer id. */
    val maskThumbs: Map<String, Thumb> = emptyMap(),
    /** S2: the canvas view, for the selection outline. */
    val view: CanvasView = CanvasView(),
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
    /** The project thumbnail is rendered and written here (it waits for the GPU, so it must not share the save thread or the timer). */
    val thumb: Executor = saver,
) {
    companion object {
        private fun pool(name: String) = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, name) }.apply { allowCoreThreadTimeOut(true) }

        fun production(report: (String, Long) -> Unit, error: (String) -> Unit): StudioEnv {
            val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "studio-timer").apply { isDaemon = true } }.apply { setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
            return StudioEnv(pool("studio-model"), pool("studio-save"), { ms, r -> timer.schedule(r, ms, TimeUnit.MILLISECONDS) }, report = report, error = error, thumb = pool("studio-thumb"))
        }
    }
}
