package app.rawline

import app.rawline.backup.TargetChoice
import app.rawline.backup.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetChoiceTest {
    @Test fun allFilesAccessWinsThenAFolderThenMediaStore() {
        assertEquals(TargetKind.FILES, TargetChoice.pick(allFilesAccess = true, folderUsable = true))
        assertEquals(TargetKind.FILES, TargetChoice.pick(true, false))
        assertEquals(TargetKind.FOLDER, TargetChoice.pick(false, true))
        assertEquals(TargetKind.MEDIASTORE, TargetChoice.pick(false, false))
    }

    @Test fun onlyMediaStoreIsCalledWeakAndEveryPlaceHasWords() {
        assertTrue(TargetChoice.isWeak(TargetKind.MEDIASTORE)); assertFalse(TargetChoice.isWeak(TargetKind.FILES)); assertFalse(TargetChoice.isWeak(TargetKind.FOLDER))
        assertTrue(TargetChoice.label(TargetKind.MEDIASTORE).contains("may not be found after a reinstall"))
        for (k in TargetKind.values()) assertFalse(TargetChoice.label(k).contains('—'))
    }
}
