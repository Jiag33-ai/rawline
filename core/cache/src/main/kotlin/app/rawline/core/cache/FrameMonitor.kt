package app.rawline.core.cache

import android.view.Choreographer

/** Counts frames while active; frames over 16.7 ms are reported as jank. */
object FrameMonitor {
    private var last = 0L
    private var frames = 0
    private var jank = 0
    private var running = false
    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            if (last != 0L) {
                frames++
                if ((t - last) > 16_700_000L * 1.5) jank++
            }
            last = t
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true; last = 0; frames = 0; jank = 0
        Choreographer.getInstance().postFrameCallback(cb)
    }

    fun stop(label: String) {
        if (!running) return
        running = false
        if (frames > 10) {
            PerfLog.record("$label frames", frames.toLong())
            PerfLog.record("$label jank_frames(>25ms)", jank.toLong())
        }
    }
}
