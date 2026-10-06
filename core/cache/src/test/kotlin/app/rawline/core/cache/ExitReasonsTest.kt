package app.rawline.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitReasonsTest {
    @Test fun reasonsThatMatterOnAPhoneAreNamedPlainly() {
        assertEquals("NATIVE CRASH", ExitReasons.reasonName(5))
        assertEquals("ANR (app not responding)", ExitReasons.reasonName(6))
        assertEquals("killed because the phone ran low on memory", ExitReasons.reasonName(3))
        assertEquals("Java crash", ExitReasons.reasonName(4))
        assertEquals("unknown (99)", ExitReasons.reasonName(99))
    }

    @Test fun importanceBands() {
        assertEquals("foreground", ExitReasons.importanceName(100))
        assertEquals("foreground service", ExitReasons.importanceName(125))
        assertEquals("cached or background", ExitReasons.importanceName(400))
        assertEquals("gone", ExitReasons.importanceName(1000))
    }

    @Test fun lineHasTimeAgoReasonImportanceAndMemory() {
        val line = ExitReasons.format(ExitReasons.Record(1_700_000_000_000, 5, 100, "signal 11", 540 * 1024, 900 * 1024, 11, "signal SIGSEGV"), 1_700_000_000_000 + 600_000)
        assertTrue(line, "10 min ago" in line)
        assertTrue(line, "NATIVE CRASH" in line)
        assertTrue(line, "was foreground" in line)
        assertTrue(line, "pss 540 MB, rss 900 MB" in line)
        assertTrue(line, "status 11" in line)
        assertTrue(line, "\n    signal SIGSEGV" in line)
    }

    @Test fun tombstoneHintsKeepSignalsAndLibraryNamesButNeverDirectories() {
        val junk = byteArrayOf(0, 1, 2, 0x7f, 0)
        val body = "SIGSEGV".toByteArray() + junk + "SEGV_MAPERR".toByteArray() + junk +
            "/data/app/~~abc123==/app.rawline-xyz==/lib/arm64/libraw.so".toByteArray() + junk + "libc.so".toByteArray() + junk + "hi".toByteArray()
        val hints = ExitReasons.tombstoneHints(junk + body)
        assertEquals("signal SIGSEGV, SEGV_MAPERR; libraries seen libraw.so, libc.so", hints)
        assertFalse("abc123" in hints || "/data" in hints)
    }

    @Test fun tombstoneHintsOfNoiseIsEmpty() {
        assertEquals("", ExitReasons.tombstoneHints(ByteArray(300) { (it % 7).toByte() }))
    }
}
