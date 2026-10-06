package app.rawline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ExportNoticeTest {
    @Test fun allSavedUsesTheRightPlural() {
        assertEquals("1 photo saved", ExportNotice.finished(1, 0)!!.body)
        assertEquals("38 photos saved", ExportNotice.finished(38, 0)!!.body)
    }

    @Test fun aFailedBatchIsNotSilent() {
        val n = ExportNotice.finished(0, 50)!!
        assertEquals("Export failed", n.title)
        assertTrue(n.body.contains("50 photos"))
    }

    @Test fun partialFailureSaysBothCounts() {
        val n = ExportNotice.finished(38, 12)!!
        assertEquals("38 saved, 12 failed. Open the queue for the reasons.", n.body)
        assertTrue(n.title.contains("problems"))
    }

    @Test fun cancelledBatchPostsNothing() { assertNull(ExportNotice.finished(0, 0)) }

    @Test fun providerAndClassNamesBecomePlainSentences() {
        assertTrue(ExportErrors.plain(IllegalStateException("No place to save")).contains("save folder is no longer available"))
        assertTrue(ExportErrors.plain(IOException("write failed: ENOSPC (No space left on device)")).contains("out of storage"))
        assertEquals("Something went wrong while saving this photo.", ExportErrors.plain(NullPointerException()))
        assertEquals("Something went wrong while saving this photo.", ExportErrors.plain(RuntimeException("java.lang.IllegalStateException: x")))
    }

    @Test fun usefulMessagesAreKept() {
        assertEquals("The edit on a.RW2 could not be read", ExportErrors.plain(IOException("The edit on a.RW2 could not be read")))
        assertEquals("The photo was written but could not be made visible in Gallery", ExportErrors.plain(IOException("The photo was written but could not be made visible in Gallery")))
    }
}
