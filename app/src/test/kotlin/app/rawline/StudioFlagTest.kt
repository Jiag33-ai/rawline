package app.rawline

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioFlagTest {
    /**
     * The BuildConfig flag and the source set that was compiled always agree: with the flag on the real StudioEntry is there, with it off the stub. (That a default build has the flag off
     * is the studio.enabled file; the studio-flag CI job builds both values and inspects the APKs.)
     */
    @Test fun theFlagAndTheCompiledEntryAgree() { assertEquals(BuildConfig.STUDIO_ENABLED, StudioEntry.available) }

    @Test fun theSettingsNoteExistsOnlyWhenStudioDoes() { assertEquals(BuildConfig.STUDIO_ENABLED, StudioEntry.settingsNote() != null) }
}
