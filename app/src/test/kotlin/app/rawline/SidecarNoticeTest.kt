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

class ImportNoticeTest {
    @Test fun saysHowManyWithTheRightPlural() {
        org.junit.Assert.assertEquals("Imported 1 photo", ImportNotice.text(1, 3))
        org.junit.Assert.assertEquals("Imported 12 photos", ImportNotice.text(12, 3))
    }

    @Test fun nothingAddedSaysWhy() {
        assertTrue(ImportNotice.text(0, 0).startsWith("Nothing new to import"))
    }

    @Test fun warnsNearTheGrantCap() {
        assertTrue(ImportNotice.text(5, ImportNotice.WARN_AT).contains("Add a folder"))
        assertTrue(!ImportNotice.text(5, ImportNotice.WARN_AT - 1).contains("Add a folder"))
    }
}
