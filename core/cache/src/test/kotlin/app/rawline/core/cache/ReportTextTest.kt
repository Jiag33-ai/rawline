package app.rawline.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ReportTextTest {
    @Test fun pathIsCutToTheFileName() {
        val r = ReportText.redact("open failed: /storage/emulated/0/DCIM/Camera/P1055415.RW2: ENOENT")
        assertTrue(r, "P1055415.RW2" in r)
        assertFalse(r, "storage" in r || "DCIM" in r)
    }

    @Test fun contentAddressesAndSecretsAreHidden() {
        val r = ReportText.redact("saved content://com.android.externalstorage.documents/tree/primary%3ADCIM for token=abc123 and Password: hunter2")
        assertFalse(r, "externalstorage" in r || "abc123" in r || "hunter2" in r)
        assertTrue(r, "[address]" in r)
    }

    @Test fun ordinaryTextIsNotTouched() {
        val s = "model sam: 3/4 done, a/b ratio, 12 MB of 512 MB, n=1200 (n=3)"
        assertEquals(s, ReportText.redact(s))
    }

    @Test fun headAndTailKeepsBothEndsAndStaysShort() {
        val s = "A".repeat(5000) + "MIDDLE".repeat(1000) + "Caused by: root"
        val cut = ReportText.headAndTail(s, 100, 100)
        assertTrue(cut.startsWith("A".repeat(100)))
        assertTrue(cut.endsWith("Caused by: root"))
        assertTrue(cut.length < 300)
        assertTrue("characters cut" in cut)
        assertEquals(s.take(10), ReportText.headAndTail(s.take(10), 100, 100))
    }

    @Test fun rootCauseFollowsTheChainAndSurvivesACycle() {
        val root = IllegalStateException("root")
        assertSame(root, ReportText.rootCause(RuntimeException("a", RuntimeException("b", root))))
        val a = RuntimeException("a"); val b = RuntimeException("b", a); a.initCause(b)
        ReportText.rootCause(a)   // must terminate
    }

    @Test fun stampAndAgo() {
        assertEquals("1970-01-01 00:00:00 +0000", ReportText.stamp(0, TimeZone.getTimeZone("UTC")))
        assertEquals("10s ago", ReportText.ago(0, 10_000))
        assertEquals("5 min ago", ReportText.ago(0, 300_000))
        assertEquals("3 h ago", ReportText.ago(0, 3 * 3600_000L))
        assertEquals("4 days ago", ReportText.ago(0, 4 * 86400_000L))
        assertEquals("0s ago", ReportText.ago(5_000, 1_000))   // a clock that went backwards
    }
}
