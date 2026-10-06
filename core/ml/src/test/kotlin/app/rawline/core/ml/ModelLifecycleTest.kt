package app.rawline.core.ml

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelLifecycleTest {
    private class Fake { var closed = false; var running = false; var closedWhileRunning = false }

    @Test fun createsOnceAndReuses() {
        val ex = Executors.newSingleThreadExecutor()
        val made = AtomicInteger()
        val life = ModelLifecycle<Fake>(ex) { it.closed = true }
        val a = life.call { life.current { made.incrementAndGet(); Fake() } }
        val b = life.call { life.current { made.incrementAndGet(); Fake() } }
        assertSame(a, b); assertEquals(1, made.get()); ex.shutdown()
    }

    @Test fun lateCallAfterReleaseThrowsAndBuildsNothing() {
        val ex = Executors.newSingleThreadExecutor()
        val made = AtomicInteger()
        val life = ModelLifecycle<Fake>(ex) { it.closed = true }
        val f = life.call { life.current { made.incrementAndGet(); Fake() } }
        life.release()
        assertThrows(IllegalStateException::class.java) { life.call { life.current { made.incrementAndGet(); Fake() } } }
        assertEquals(1, made.get())
        assertTrue(f.closed) // the release task ran before the later call on the same thread
        ex.shutdown()
    }

    @Test fun releaseDuringRunWaitsForTheRun() {
        val ex = Executors.newSingleThreadExecutor()
        val fake = Fake()
        val life = ModelLifecycle<Fake>(ex) { fake.closedWhileRunning = fake.running; fake.closed = true }
        val started = CountDownLatch(1); val go = CountDownLatch(1)
        val t = Thread { life.call { life.current { fake }; fake.running = true; started.countDown(); go.await(5, TimeUnit.SECONDS); fake.running = false } }
        t.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        life.release()
        assertFalse(fake.closed) // not closed under the running inference
        go.countDown(); t.join()
        ex.shutdown(); assertTrue(ex.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(fake.closed); assertFalse(fake.closedWhileRunning)
    }
}
