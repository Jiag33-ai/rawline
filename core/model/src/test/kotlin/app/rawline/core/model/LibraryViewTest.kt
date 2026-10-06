package app.rawline.core.model

import org.junit.Assert.*
import org.junit.Test

class LibraryViewTest {
    private fun photo(id: Long, kind: Kind) = Photo(id, "f", "u$id", "p$id.${if (kind == Kind.RAW) "rw2" else "jpg"}", 1, id, kind, true)

    @Test fun rawOnlyKeepsRawAndKeepsTheOrder() {
        val l = listOf(photo(1, Kind.RAW), photo(2, Kind.IMAGE), photo(3, Kind.RAW), photo(4, Kind.IMAGE))
        assertEquals(listOf(3L, 1L), LibraryFilter(rawOnly = true).apply(l).map { it.id })
        assertEquals(listOf(4L, 3L, 2L, 1L), LibraryFilter().apply(l).map { it.id })
        assertEquals(listOf(1L, 3L), LibraryFilter(rawOnly = true, sort = SortOrder.OLDEST).apply(l).map { it.id })
    }
    @Test fun theRawViewIsNotCountedAsABuiltFilter() {
        assertFalse(LibraryFilter(rawOnly = true).isActive); assertTrue(LibraryFilter(rawOnly = true, minRating = 1).isActive)
    }
    @Test fun defaultViewFollowsTheChoiceThenTheContent() {
        assertTrue(DefaultView.rawOnly(null, 1)); assertFalse(DefaultView.rawOnly(null, 0))
        assertTrue(DefaultView.rawOnly(ViewChoice.RAW, 0)); assertFalse(DefaultView.rawOnly(ViewChoice.ALL, 500))
        assertEquals(ViewChoice.RAW, ViewChoice.parse("raw")); assertNull(ViewChoice.parse("x")); assertNull(ViewChoice.parse(null))
    }
    @Test fun whatsNewShowsOnceAndOnlyWhenTheNewDefaultAppliesWithoutAChoice() {
        assertTrue(WhatsNew.shouldShow(0, true, null))
        assertFalse(WhatsNew.shouldShow(WhatsNew.NOTE_VERSION, true, null))     // seen
        assertFalse(WhatsNew.shouldShow(0, false, null))                         // no RAW on the phone: nothing changed for this user
        assertFalse(WhatsNew.shouldShow(0, true, ViewChoice.ALL))                // already chose
    }

    // ---- OrderGate
    @Test fun firstDisplayIsTheFirstList() { val g = OrderGate(); assertEquals(listOf(3L, 2L, 1L), g.update(listOf(3, 2, 1), false, 0)); assertEquals(0, g.resorts) }

    @Test fun indexingThatReordersEverythingDoesNotMoveTheGridWhileTheUserScrolls() {
        val g = OrderGate(1500); val n = 300
        val copyOrder = (n downTo 1).map { it.toLong() }                       // copy order: file times
        val first = g.update(copyOrder, active = true, nowMs = 0)
        var t = 0L
        val shooting = ArrayList(copyOrder)                                    // the indexer reads capture times in 30 batches over 3 s: rows move
        for (batch in 1..30) {
            t += 100
            val moved = shooting.shuffled(java.util.Random(batch.toLong())).take(10)
            shooting.removeAll(moved.toSet()); shooting.addAll(0, moved)
            assertEquals("batch $batch", first, g.update(ArrayList(shooting), active = true, nowMs = t))
        }
        assertEquals(0, g.resorts)
        assertEquals(first, g.update(ArrayList(shooting), active = false, nowMs = t))              // the finger is lifted: the wait starts
        assertEquals(first, g.update(ArrayList(shooting), active = false, nowMs = t + 1499))      // idle for under 1.5 s: still held
        assertEquals(shooting, g.update(ArrayList(shooting), active = false, nowMs = t + 1500))   // idle for 1.5 s: one change, applied once
        assertEquals(1, g.resorts)
        assertEquals(shooting, g.update(ArrayList(shooting), active = false, nowMs = t + 9000)); assertEquals(1, g.resorts)
    }
    @Test fun removalsApplyAtOnceNewPhotosWait() {
        val g = OrderGate(1500); g.update(listOf(5, 4, 3, 2, 1), true, 0)
        assertEquals(listOf(5L, 4L, 2L, 1L), g.update(listOf(6, 5, 4, 2, 1), true, 100))     // 3 was deleted, 6 is new and waits
        assertEquals(listOf(5L, 4L, 2L, 1L), g.update(listOf(6, 5, 4, 2, 1), false, 2000))     // the touch ended: the wait starts
        assertEquals(listOf(6L, 5L, 4L, 2L, 1L), g.update(listOf(6, 5, 4, 2, 1), false, 3500))
        assertEquals(0, g.resorts)                                                           // a new photo on top is not a reorder
    }
    @Test fun aLongScrollEndingWithNoListChangeStillWaitsAfterTheFingerLifts() {
        val g = OrderGate(1500); g.update(listOf(3, 2, 1), true, 0)        // the scroll starts
        g.update(listOf(3, 2, 1), true, 20_000)                              // ... and runs for 20 s
        assertEquals(listOf(3L, 2L, 1L), g.update(listOf(1, 2, 3), false, 20_100))   // lifted; a reorder arrived at the same moment: wait
        assertEquals(listOf(1L, 2L, 3L), g.update(listOf(1, 2, 3), false, 21_600))
    }
    @Test fun aSelectionOrATouchKeepsTheHoldRunning() {
        val g = OrderGate(1500); g.update(listOf(3, 2, 1), true, 0)
        assertEquals(listOf(3L, 2L, 1L), g.update(listOf(1, 2, 3), true, 1000))
        assertEquals(listOf(3L, 2L, 1L), g.update(listOf(1, 2, 3), false, 2000))             // the touch ended at 2000
        assertEquals(listOf(3L, 2L, 1L), g.update(listOf(1, 2, 3), false, 3499))
        assertEquals(listOf(1L, 2L, 3L), g.update(listOf(1, 2, 3), false, 3500))
    }
    @Test fun whenTheGridIsNotOnScreenTheChangeAppliesAtOnce() {
        val g = OrderGate(1500); g.update(listOf(3, 2, 1), true, 0)
        g.update(listOf(3, 2, 1), false, 500); assertEquals(listOf(1L, 2L, 3L), g.update(listOf(1, 2, 3), false, 1_000_000))        // a photo has been open for minutes: idle long ago
        val h = OrderGate(1500); h.update(listOf(3, 2, 1), true, 0)
        assertEquals(listOf(1L, 2L, 3L), h.update(listOf(1, 2, 3), false, 10, onScreen = false))   // a tap opened a photo: at once, no wait
    }
    @Test fun refreshAndSourceChangeShowTheSortedListNow() {
        val g = OrderGate(); g.update(listOf(3, 2, 1), true, 0)
        assertEquals(listOf(1L, 2L, 3L), g.refresh(listOf(1, 2, 3))); assertEquals(1, g.resorts)
        assertEquals(listOf(9L, 8L), g.refresh(listOf(9, 8)))                                // an unrelated list: nothing in common, not counted as a reorder
        assertEquals(1, g.resorts)
    }
    @Test fun anEmptyDisplayedListTakesTheNewListAtOnce() { val g = OrderGate(); assertTrue(g.update(emptyList(), true, 0).isEmpty()); assertEquals(listOf(1L), g.update(listOf(1), true, 10)) }
    @Test fun anotherListWithNothingInCommonIsShownAtOnceAndNotCountedAsAReorder() {
        val g = OrderGate(1500); g.update(listOf(3, 2, 1), true, 0)
        assertEquals(listOf(9L, 8L), g.update(listOf(9, 8), true, 10)); assertEquals(0, g.resorts)
    }
    @Test fun holdingIsTrueOnlyWhileAChangeWaits() {
        val g = OrderGate(1500); g.update(listOf(3, 2, 1), true, 0)
        assertFalse(g.holding(listOf(3, 2, 1)))
        g.update(listOf(1, 2, 3), true, 100); assertTrue(g.holding(listOf(1, 2, 3)))
        g.update(listOf(1, 2, 3), false, 5000); assertTrue(g.holding(listOf(1, 2, 3)))     // the touch ended at 5000
        g.update(listOf(1, 2, 3), false, 6500); assertFalse(g.holding(listOf(1, 2, 3)))
    }
    @Test fun aListThatLosesAndGainsPhotosKeepsTheHeldOrderOfTheSurvivors() {
        val g = OrderGate(1500); g.update(listOf(5, 4, 3, 2, 1), true, 0)
        assertEquals(listOf(5L, 3L, 1L), g.update(listOf(7, 1, 3, 5), true, 50))      // 4 and 2 deleted, 7 new, survivors reordered: all held
        assertEquals(listOf(5L, 3L, 1L), g.update(listOf(7, 1, 3, 5), false, 5000))
        assertEquals(listOf(7L, 1L, 3L, 5L), g.update(listOf(7, 1, 3, 5), false, 6500)); assertEquals(1, g.resorts)
    }

    // ---- HeldOrder
    @Test fun heldOrderEmitsOnlyWhenSomethingChanged() {
        val h = HeldOrder(OrderGate(1500)); val a = listOf(3L, 2L, 1L)
        assertTrue(h.step(a, "k", a, false, true, 0).changed)
        assertFalse(h.step(a, "k", a, false, true, 300).changed)                       // a timer tick with nothing new
        val b = listOf(3L, 2L, 1L)
        assertTrue(h.step(b, "k", b, false, true, 400).changed)                        // a new list object with the same order: the tiles' data may differ, emit
    }
    @Test fun heldOrderWaitsWhileBusyThenAppliesAfterTheQuietTime() {
        val h = HeldOrder(OrderGate(1500)); val a = listOf(3L, 2L, 1L); val b = listOf(1L, 2L, 3L)
        h.step(a, "k", a, true, true, 0)
        val s1 = h.step(b, "k", b, true, true, 100); assertEquals(a, s1.ids); assertTrue(s1.waiting)
        val s2 = h.step(b, "k", b, false, true, 200); assertEquals(a, s2.ids); assertTrue(s2.waiting)      // finger lifted: the wait starts
        val s3 = h.step(b, "k", b, false, true, 1700); assertEquals(b, s3.ids); assertFalse(s3.waiting); assertTrue(s3.changed)
        assertEquals(1, h.gate.resorts)
    }
    @Test fun heldOrderShowsANewFilterOrSourceAtOnce() {
        val h = HeldOrder(OrderGate(1500)); val a = listOf(3L, 2L, 1L); val b = listOf(1L, 2L, 3L, 4L)
        h.step(a, "k1", a, true, true, 0)
        val s = h.step(b, "k2", b, true, true, 10); assertEquals(b, s.ids); assertFalse(s.waiting)          // busy, but the key changed
    }
    @Test fun heldOrderAppliesAtOnceWhenTheGridIsNotOnScreen() {
        val h = HeldOrder(OrderGate(1500)); val a = listOf(3L, 2L, 1L); val b = listOf(1L, 2L, 3L)
        h.step(a, "k", a, true, true, 0)
        assertEquals(b, h.step(b, "k", b, false, false, 5).ids)
    }
}
