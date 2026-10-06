package app.rawline

import app.rawline.core.data.SidecarResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarNoticeTest {
    @Test fun nothingToSayWhenEverySidecarWasWrittenOrXmpIsOff() {
        assertNull(SidecarNotice.text(SidecarResult.NONE))
        assertNull(SidecarNotice.text(SidecarResult(5, 0, 0)))
    }

    @Test fun cameraRollPhotosAreReportedNotSilentlySkipped() {
        val t = SidecarNotice.text(SidecarResult(0, 1, 0))
        assertNotNull(t)
        assertTrue(t!!.contains("1 photo ") && !t.contains("1 photos"))
        assertTrue(t.contains("folders you added"))
    }

    @Test fun failuresAreReported() {
        assertTrue(SidecarNotice.text(SidecarResult(2, 0, 3))!!.contains("3 photos"))
        assertTrue(SidecarNotice.text(SidecarResult(0, 4, 2))!!.contains("4 photos"))
    }
}
