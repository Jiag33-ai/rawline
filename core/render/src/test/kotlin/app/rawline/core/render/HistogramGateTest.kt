package app.rawline.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramGateTest {
    @Test fun theFirstRequestRunsAtOnce() {
        assertEquals(0L, HistogramGate().check(5_000))
    }

    @Test fun aDragOfOneTickEvery16msRunsAboutTenTimesASecondNotSixtyTwo() {
        val g = HistogramGate()
        var runs = 0
        var t = 1_000L
        while (t < 2_000L) { if (g.check(t) == 0L) runs++; t += 16 }   // 62 ticks in a second
        assertTrue("ran $runs times", runs in 8..11)
    }

    @Test fun theWaitTellsHowLongUntilTheNextRunAndThenItRuns() {
        val g = HistogramGate(100)
        assertEquals(0L, g.check(1_000))
        val wait = g.check(1_030)
        assertEquals(70L, wait)               // the caller schedules a frame in 70 ms
        assertEquals(0L, g.check(1_030 + wait))   // that frame runs the final histogram of the drag
    }

    @Test fun aRefusedRequestDoesNotPushTheNextRunBack() {
        val g = HistogramGate(100)
        g.check(0)
        g.check(40); g.check(80)
        assertEquals(0L, g.check(100))
    }
}
