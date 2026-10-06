package app.rawline.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HoldCounterTest {
    @Test fun twoScreensHoldIndependently() {
        val h = HoldCounter()
        assertEquals(1, h.acquire())     // the viewer
        assertEquals(2, h.acquire())     // the library while indexing
        assertEquals(1, h.release())     // the library leaves: the viewer still holds the screen on
        assertEquals(0, h.release())
    }

    @Test fun releasingMoreThanAcquiredStaysAtZero() {
        val h = HoldCounter()
        assertEquals(0, h.release())
        assertEquals(1, h.acquire())
    }
}
