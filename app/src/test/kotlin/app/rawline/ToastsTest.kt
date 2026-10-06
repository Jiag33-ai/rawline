package app.rawline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastsTest {
    @Test fun errorsLastLongerThanConfirmations() {
        val ok = Toasts.make("Added to export queue", null)
        val bad = Toasts.make("Backup failed: disk full", ok)
        assertFalse(ok.isError); assertTrue(bad.isError)
        assertEquals(Toasts.SHORT_MS, Toasts.durationMs(ok))
        assertEquals(Toasts.ERROR_MS, Toasts.durationMs(bad))
    }

    @Test fun sameTextTwiceGetsANewSequenceSoTheTimerRestarts() {
        val a = Toasts.make("Added to export queue", null)
        val b = Toasts.make("Added to export queue", a)
        assertEquals(a.text, b.text)
        assertNotEquals(a.seq, b.seq)
    }

    @Test fun partialFailureCountsAsAnError() {
        assertTrue(Toasts.isError("Pasted onto 3 photos, skipped 1 with an unreadable edit"))
        assertTrue(Toasts.isError("The edit on a.RW2 could not be read, so nothing was copied"))
        assertFalse(Toasts.isError("Report copied"))
    }

    @Test fun offsetsClearTheBarsOfEachScreen() {
        assertTrue(Toasts.bottomOffsetDp("loupe/{index}") > Toasts.bottomOffsetDp("queue"))
        assertTrue(Toasts.bottomOffsetDp("photos") > Toasts.bottomOffsetDp("settings"))
        assertEquals(16, Toasts.bottomOffsetDp(null))
    }
}

class ToastActionTest {
    @Test fun aToastWithUndoStaysAsLongAsAnError() {
        val t = Toasts.make("Rated 1 photo 3 stars", null, action = "Undo")
        assertEquals("Undo", t.action)
        assertEquals(Toasts.ERROR_MS, Toasts.durationMs(t))
        assertFalse(t.isError)
    }
}
