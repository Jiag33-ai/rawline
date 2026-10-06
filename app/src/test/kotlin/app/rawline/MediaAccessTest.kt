package app.rawline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAccessTest {
    @Test fun grantedByPhotosPermissionOrAllFiles() {
        assertEquals(MediaPrompt.GRANTED, MediaAccess.prompt(true, false, false, false))
        assertEquals(MediaPrompt.GRANTED, MediaAccess.prompt(false, true, true, false))
    }

    @Test fun neverAskedMeansAskEvenWithoutRationale() {
        assertEquals(MediaPrompt.ASK, MediaAccess.prompt(false, false, false, false))
    }

    @Test fun deniedOnceCanAskAgain() {
        assertEquals(MediaPrompt.ASK, MediaAccess.prompt(false, false, true, true))
    }

    @Test fun deniedTwiceMeansOpenSettings() {
        assertEquals(MediaPrompt.OPEN_SETTINGS, MediaAccess.prompt(false, false, true, false))
    }

    @Test fun grantingInSettingsClearsTheDeadEnd() {
        // the user was at OPEN_SETTINGS, granted Photos in system Settings and came back: the resume check sees it
        assertEquals(MediaPrompt.OPEN_SETTINGS, MediaAccess.prompt(false, false, true, false))
        assertEquals(MediaPrompt.GRANTED, MediaAccess.prompt(true, false, true, false))
    }

    @Test fun onlyTheFirstLaunchAsksByItself() {
        assertTrue(MediaAccess.autoAsk(MediaPrompt.ASK, askedBefore = false, autoAskedThisRun = false))
        assertFalse(MediaAccess.autoAsk(MediaPrompt.ASK, askedBefore = false, autoAskedThisRun = true))   // rotation while the dialog is up
        assertFalse(MediaAccess.autoAsk(MediaPrompt.ASK, askedBefore = true, autoAskedThisRun = false))   // later launches
        assertFalse(MediaAccess.autoAsk(MediaPrompt.OPEN_SETTINGS, askedBefore = false, autoAskedThisRun = false))
        assertFalse(MediaAccess.autoAsk(MediaPrompt.GRANTED, askedBefore = false, autoAskedThisRun = false))
    }
}
