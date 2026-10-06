package app.rawline

import org.junit.Assert.assertFalse
import org.junit.Test

class StudioFlagTest {
    /** Studio stays off unless a build passes -PstudioEnabled=true (the default CI and release builds do not). */
    @Test fun studioIsOffByDefault() { assertFalse(BuildConfig.STUDIO_ENABLED) }
}
