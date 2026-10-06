package app.rawline.core.render

/**
 * Spaces histogram renders out: while a slider moves, the editor asks for one on every tick, but a histogram only needs to keep up
 * with what the eye can follow. [check] says whether one may run now or how long to wait; the caller schedules one more frame for
 * the end of the wait, so the final state of a drag always gets its histogram.
 */
internal class HistogramGate(private val minGapMs: Long = MIN_GAP_MS) {
    private var lastRun = Long.MIN_VALUE / 2

    /** 0 when a histogram may be computed now (the run is recorded), otherwise the milliseconds to wait. */
    fun check(nowMs: Long): Long {
        val wait = lastRun + minGapMs - nowMs
        if (wait <= 0) { lastRun = nowMs; return 0 }
        return wait
    }

    companion object { const val MIN_GAP_MS = 100L }
}
