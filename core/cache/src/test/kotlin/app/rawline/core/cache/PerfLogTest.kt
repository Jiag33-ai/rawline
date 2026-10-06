package app.rawline.core.cache

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class PerfLogTest {
    @Before fun reset() = PerfLog.resetForTest()
    @After fun after() = PerfLog.resetForTest()

    @Test fun variableCountsInANameBecomeATagNotAKey() {
        assertEquals("device_scan_ms" to "n=1200", PerfLog.key("device_scan_ms (n=1200)"))
        assertEquals("export_render_ms" to "6000x4000", PerfLog.key("export_render_ms (6000x4000)"))
        assertEquals("swipe_cold_ms" to "", PerfLog.key("swipe_cold_ms"))
    }

    @Test fun manyCountsGiveOneLineWithTheLatestTag() {
        (1..1000).forEach { PerfLog.record("device_scan_ms (n=$it)", it.toLong()) }
        val lines = PerfLog.summary().lines()
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("device_scan_ms: n=500 "))   // capped at 500 samples
        assertTrue(lines[0], lines[0].endsWith("last=(n=1000)"))
    }

    @Test fun medianP95AndMaxAreRight() {
        (1..100).forEach { PerfLog.record("x", it.toLong()) }
        assertEquals("x: n=100 median=51 p95=96 max=100", PerfLog.summary())
    }

    @Test fun distinctKeysAreBounded() {
        (1..1000).forEach { PerfLog.record("key_$it", 1) }
        assertEquals(200, PerfLog.summary().lines().size)
    }

    @Test fun errorsAreTimestampedAndNewestKept() {
        var t = 1_700_000_000_000
        PerfLog.clock = { t }
        (1..30).forEach { t += 1000; PerfLog.error("e$it") }
        val last20 = PerfLog.recentErrors().lines()
        assertEquals(20, last20.size)
        assertTrue(last20.first().endsWith("e11"))
        assertTrue(last20.last().endsWith("e30"))
        assertTrue(last20.first(), Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{4}  e11$""").matches(last20.first()))
        assertEquals(30, PerfLog.errorCount())
    }

    @Test fun errorHoldIsBoundedAt100() {
        (1..250).forEach { PerfLog.error("e$it") }
        assertEquals(100, PerfLog.errorCount())
    }

    @Test fun parallelRecordingLosesNothingAndNeverThrows() {
        val go = CountDownLatch(1)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val ts = (1..8).map { n -> thread { go.await(); try { repeat(3000) { PerfLog.record("p$n", 1); PerfLog.record("shared", 2); PerfLog.error("e") } } catch (e: Throwable) { failures.incrementAndGet() } } }
        go.countDown(); ts.forEach { it.join() }
        assertEquals(0, failures.get())
        val s = PerfLog.summary()
        assertTrue(s, "shared: n=500 " in s)
        assertEquals(9, s.lines().size)
    }

    @Test fun lastSessionSurvivesInThePreviousFile() {
        val dir = Files.createTempDirectory("perflog").toFile()
        try {
            PerfLog.attach(dir)
            assertNull(PerfLog.previousSession())
            PerfLog.record("index_per_file_ms", 42)
            PerfLog.error("export P1.RW2: boom")
            PerfLog.event("memory trim level 80")
            PerfLog.flushNow()
            // process dies; the next start attaches to the same folder
            PerfLog.resetForTest()
            PerfLog.attach(dir)
            val prev = PerfLog.previousSession()
            assertNotNull(prev)
            assertTrue(prev!!, "export P1.RW2: boom" in prev)
            assertTrue(prev, "memory trim level 80" in prev)
            assertTrue(prev, "index_per_file_ms: n=1 median=42" in prev)
            assertTrue(prev, prev.startsWith("Saved "))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aSessionThatWroteNothingDoesNotEraseTheLastOnesSnapshot() {
        val dir = Files.createTempDirectory("perflog").toFile()
        try {
            PerfLog.attach(dir); PerfLog.error("first session error"); PerfLog.flushNow()
            PerfLog.resetForTest(); PerfLog.attach(dir)          // second session, writes nothing
            PerfLog.resetForTest(); PerfLog.attach(dir)          // third session
            assertTrue(PerfLog.previousSession()!!.contains("first session error"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun snapshotHasNoCurrentFileUntilSomethingIsWritten() {
        val dir = Files.createTempDirectory("perflog").toFile()
        try { PerfLog.attach(dir); assertFalse(java.io.File(dir, "session.txt").exists()) } finally { dir.deleteRecursively() }
    }
}
