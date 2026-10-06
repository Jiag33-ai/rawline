package app.rawline.core.studio.model

import org.junit.Assert.*
import org.junit.Test

class W32RouterTest {
    private fun ev(p: Phase, id: Int, kind: PointerKind, t: Long) = InputEvent(p, id, 1f, 1f, 1f, kind, t)
    @Test fun aMissedHoverExitLapsesAfterTwoSeconds() {
        val r = InputRouter(); r.onHover(true, 100)
        assertTrue(r.onEvent(ev(Phase.DOWN, 1, PointerKind.FINGER, 1_000)).isEmpty())      // still near: a palm
        r.onEvent(ev(Phase.UP, 1, PointerKind.FINGER, 1_100))
        val a = r.onEvent(ev(Phase.DOWN, 2, PointerKind.FINGER, 3_600_000))                // an hour later, no exit ever came
        assertEquals(listOf("StrokeStart"), a.map { it::class.simpleName })
    }
    @Test fun hoverMovesKeepTheRuleAlive() {
        val r = InputRouter(); for (t in 0L..10_000L step 500) r.onHover(true, t)         // the pen hovers for ten seconds
        assertTrue(r.onEvent(ev(Phase.DOWN, 1, PointerKind.FINGER, 10_400)).isEmpty())
        assertEquals(1, r.onEvent(ev(Phase.DOWN, 7, PointerKind.STYLUS, 10_450)).size)       // the pen itself still draws
    }
    @Test fun exitStillGivesTheShortGrace() {
        val r = InputRouter(); r.onHover(true, 0); r.onHover(false, 1_000)
        assertTrue(r.onEvent(ev(Phase.DOWN, 1, PointerKind.FINGER, 1_500)).isEmpty())
        r.onEvent(ev(Phase.UP, 1, PointerKind.FINGER, 1_550))
        assertEquals(1, r.onEvent(ev(Phase.DOWN, 2, PointerKind.FINGER, 1_600)).size)
    }
}
