package app.rawline.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashStoreTest {
    private fun deep(n: Int): Throwable {
        var e: Throwable = IllegalStateException("ROOTCAUSE-MARKER")
        repeat(n) { i -> e = RuntimeException("wrapper $i with a fairly long message to fill the trace ".repeat(3), e) }
        return e
    }

    @Test fun rootCauseSurvivesADeepTraceThatIsMuchLongerThanTheCap() {
        val e = deep(150)
        assertTrue("test trace must exceed the old 6000 char cut", e.stackTraceToString().length > 20000)
        val text = CrashStore.format(0, "0.1.5 build 5 release", "main", e, "java 10 MB of 512 MB", "")
        assertTrue(text, "Root cause: java.lang.IllegalStateException: ROOTCAUSE-MARKER" in text)
        // the last "Caused by" is at the end of a Kotlin trace and must be in the tail
        assertTrue(text.trimEnd().lines().any { "Caused by: java.lang.IllegalStateException: ROOTCAUSE-MARKER" in it })
        assertTrue(text.length < CrashStore.TRACE_HEAD + CrashStore.TRACE_TAIL + 1500)
    }

    @Test fun headerHasTimeBuildThreadMemoryAndLastPhoto() {
        val text = CrashStore.format(1_700_000_000_000, "0.1.9 build 9 debug", "GLThread", RuntimeException("x"), "java 1 MB of 2 MB", "P1.RW2 6000x4000")
        val lines = text.lines()
        assertTrue(lines[0].startsWith("Crash at 2023-11-"))
        assertTrue("Build: 0.1.9 build 9 debug" in lines)
        assertTrue("Thread: GLThread" in lines)
        assertTrue("Memory: java 1 MB of 2 MB" in lines)
        assertTrue("Last photo opened: P1.RW2 6000x4000" in lines)
    }

    @Test fun pathsInAnExceptionMessageAreRedactedButPhotoNameStays() {
        val text = CrashStore.format(0, "b", "t", java.io.FileNotFoundException("/storage/emulated/0/DCIM/IMG_1.RW2: open failed"), "m", "")
        assertTrue("IMG_1.RW2" in text)
        assertFalse("emulated" in text)
    }

    @Test fun keepsTheNewestThreeNewestFirst() {
        val dir = Files.createTempDirectory("crashes").toFile()
        try {
            (1..5).forEach { CrashStore.write(dir, "crash $it") }
            assertEquals(listOf("crash 5", "crash 4", "crash 3"), CrashStore.all(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun emptyDirHasNoCrashes() {
        val dir = Files.createTempDirectory("crashes").toFile()
        try { assertTrue(CrashStore.all(dir).isEmpty()) } finally { dir.deleteRecursively() }
    }

    @Test fun oldBuildCrashIsNotShownAsCurrent() {
        val text = CrashStore.format(0, "0.1.5 build 5 release", "main", RuntimeException("x"), "m", "")
        assertTrue(CrashStore.sameBuild(text, "0.1.5 build 5 release"))
        assertFalse(CrashStore.sameBuild(text, "0.1.6 build 6 release"))
        assertFalse(CrashStore.sameBuild(text, "0.1.5 build 5 debug"))
    }
}
