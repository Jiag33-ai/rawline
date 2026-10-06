package app.rawline

import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListRulesTest {
    private fun p(id: Long, rating: Int = 0) = Photo(id, "f", "u$id", "p$id.jpg", 1, 1, Kind.IMAGE, true, rating = rating)

    @Test fun throttleIsSlowerWhileIndexing() {
        assertTrue(ListThrottle.delayMs(true) > ListThrottle.delayMs(false))
    }

    // ---- RescanGate ----
    @Test fun firstRequestStartsAWorkerLaterOnesDoNot() {
        val g = RescanGate()
        assertTrue(g.request(0))
        assertFalse(g.request(1500))       // a pass is running: no second worker, no cancel
        assertFalse(g.request(1500))
    }

    @Test fun aBurstDuringAPassGivesExactlyOneMorePass() {
        val g = RescanGate()
        g.request(0)
        assertEquals(0L, g.next())          // pass 1
        repeat(100) { g.request(1500) }     // 100 media changes while it runs
        assertEquals(1500L, g.next())       // one more pass
        assertNull(g.next())                // then it stops
        assertTrue(g.request(0))            // and a new request starts a fresh worker
    }

    @Test fun anExplicitRequestWinsOverTheDebounce() {
        val g = RescanGate()
        g.request(0); g.next()
        g.request(1500); g.request(0)
        assertEquals(0L, g.next())
    }

    @Test fun releaseLetsALaterRequestStartAgain() {
        val g = RescanGate()
        g.request(0)
        g.release()
        assertTrue(g.request(0))
    }

    // ---- Browse ----
    @Test fun frozenOrderSurvivesAPhotoLeavingTheFilter() {
        val frozen = listOf(1L, 2L, 3L, 4L)
        // photo 2 was un-picked under the "Picks" filter: the live filtered list lost it, the unfiltered list still has it
        val live = listOf(p(1), p(2, rating = 0), p(3), p(4))
        val list = Browse.resolve(frozen, live, fallback = listOf(p(1), p(3), p(4)))
        assertEquals(listOf(1L, 2L, 3L, 4L), list.map { it.id })
        assertEquals(3L, Browse.step(list, 2, +1)?.second?.id)
        assertEquals(1L, Browse.step(list, 2, -1)?.second?.id)
    }

    @Test fun eachPhotoShowsItsCurrentData() {
        val list = Browse.resolve(listOf(1L, 2L), listOf(p(2, 5), p(1, 3)), emptyList())
        assertEquals(listOf(1L, 2L), list.map { it.id })
        assertEquals(listOf(3, 5), list.map { it.rating })
    }

    @Test fun deletedPhotosDropOut() {
        assertEquals(listOf(1L, 3L), Browse.resolve(listOf(1L, 2L, 3L), listOf(p(1), p(3)), emptyList()).map { it.id })
    }

    @Test fun emptyFrozenListFallsBackToTheLiveFilteredList() {
        assertEquals(listOf(7L), Browse.resolve(emptyList(), listOf(p(1), p(7)), fallback = listOf(p(7))).map { it.id })
    }

    @Test fun stepStopsAtTheEndsAndOnUnknownIds() {
        val list = listOf(p(1), p(2))
        assertNull(Browse.step(list, 2, +1))
        assertNull(Browse.step(list, 1, -1))
        assertNull(Browse.step(list, 99, +1))
    }

    @Test fun neighboursAreNextTwoAndPrevious() {
        val list = listOf(p(1), p(2), p(3), p(4))
        assertEquals(listOf(3L, 4L, 1L), Browse.neighbours(list, 2).map { it.id })
        assertTrue(Browse.neighbours(list, 99).isEmpty())
    }
}

class QueueOrderTest {
    private fun j(id: Long, status: Int) = app.rawline.core.data.ExportJobEntity(id = id, photoKey = "k$id", photoUri = "u$id", photoName = "p$id", settingsJson = "{}", status = status)

    @Test fun activeJobsRunOrderFirstThenFinishedNewestFirst() {
        val sorted = QueueOrder.sort(listOf(j(5, 0), j(4, 2), j(3, 1), j(2, 3), j(1, 0)))
        assertEquals(listOf(1L, 3L, 5L, 4L, 2L), sorted.map { it.id })
    }
}
