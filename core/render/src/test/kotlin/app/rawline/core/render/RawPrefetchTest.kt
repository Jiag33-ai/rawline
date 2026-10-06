package app.rawline.core.render

import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RawPrefetchTest {
    private fun photo(id: Long, kind: Kind = Kind.RAW) = Photo(id, "", "", "p$id", 1, 1, kind, true)

    private fun awaitTrue(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!cond()) { check(System.nanoTime() < end) { "timed out: $what" }; Thread.sleep(2) }
    }

    @Test fun aDecodeThatFinishesAfterCancelIsFreedNotStored() {
        val started = CountDownLatch(1); val proceed = CountDownLatch(1)
        val freed = Collections.synchronizedList(ArrayList<Long>())
        val pf = RawPrefetch(3, { started.countDown(); proceed.await(5, TimeUnit.SECONDS); 42L }, { freed += it }, Dispatchers.IO.limitedParallelism(1))
        pf.prefetch(photo(1))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        pf.cancel()                    // the library to editor transition: the decode is still running
        proceed.countDown()
        awaitTrue("handle freed") { freed.contains(42L) }
        assertEquals(0L, pf.take(photo(1)))   // and it was never stored for a later take
        assertEquals(listOf(42L), freed.toList())
    }

    @Test fun aFinishedDecodeIsKeptAndHandedOverOnce() {
        val freed = Collections.synchronizedList(ArrayList<Long>())
        val done = CountDownLatch(1)
        val pf = RawPrefetch(3, { done.countDown(); 7L }, { freed += it }, Dispatchers.IO.limitedParallelism(1))
        pf.prefetch(photo(5))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        awaitTrue("stored") { pf.take(photo(5)).also { if (it != 0L) assertEquals(7L, it) } != 0L }
        assertEquals(0L, pf.take(photo(5)))
        assertTrue(freed.isEmpty())
    }

    @Test fun theOldestResultsAreFreedBeyondTheCapacity() {
        val freed = Collections.synchronizedList(ArrayList<Long>())
        var next = 100L
        val pf = RawPrefetch(2, { next++ }, { freed += it }, Dispatchers.IO.limitedParallelism(1))
        for (i in 1L..4L) { pf.prefetch(photo(i)); awaitTrue("decode $i") { next == 100L + i } ; Thread.sleep(20) }
        awaitTrue("two freed") { freed.size == 2 }
        assertEquals(listOf(100L, 101L), freed.toList())
    }

    @Test fun cancelFreesWhatIsReadyAndForgetsWhatWasWanted() {
        val freed = Collections.synchronizedList(ArrayList<Long>())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val pf = RawPrefetch(3, { calls.incrementAndGet(); 9L }, { freed += it }, Dispatchers.IO.limitedParallelism(1))
        pf.prefetch(photo(1))
        awaitTrue("decoded") { calls.get() == 1 }
        Thread.sleep(30)
        pf.cancel()
        assertEquals(listOf(9L), freed.toList())
        pf.prefetch(photo(1))          // wanted again after a cancel
        awaitTrue("decoded again") { calls.get() == 2 }
    }

    @Test fun picturesThatAreNotRawAreIgnored() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val pf = RawPrefetch(3, { calls.incrementAndGet(); 1L }, {}, Dispatchers.IO.limitedParallelism(1))
        pf.prefetch(photo(1, Kind.IMAGE))
        Thread.sleep(50)
        assertEquals(0, calls.get())
    }
}
