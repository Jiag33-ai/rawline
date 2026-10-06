package app.rawline.core.studio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeReportTest {
    @Test fun theFallbackIsRememberedForTheReportUntilStudioIsChosenAgain() {
        val kv = MapKeyValue(); ModeState(true, kv).switchTo(AppMode.STUDIO)
        assertFalse(ModeState(true, kv).lastStartFellBack())
        ModeState(true, kv).startMode()                       // second attempt, still allowed
        val third = ModeState(true, kv)
        assertEquals(AppMode.DEVELOP, third.startMode())
        assertTrue(third.lastStartFellBack()); assertTrue(ModeState(true, kv).lastStartFellBack())
        ModeState(true, kv).switchTo(AppMode.STUDIO)
        assertFalse(ModeState(true, kv).lastStartFellBack())
    }

    @Test fun anIncompleteStartIsCountedByTheSwitchAndClearedByTheFirstFrame() {
        val kv = MapKeyValue(); val m = ModeState(true, kv)
        m.switchTo(AppMode.STUDIO)
        assertEquals(1, kv.getInt(StartGuard.PENDING, 0))
        m.studioReady()
        assertEquals(0, kv.getInt(StartGuard.PENDING, 0))
        m.switchTo(AppMode.DEVELOP)                            // leaving Studio never leaves a pending start behind
        assertEquals(0, kv.getInt(StartGuard.PENDING, 0))
    }
}
