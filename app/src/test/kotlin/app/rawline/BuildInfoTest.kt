package app.rawline

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInfoTest {
    @Test
    fun versionNameLooksRight() {
        assertTrue(BuildConfig.VERSION_NAME.matches(Regex("""\d+\.\d+\.\d+""")))
    }

    @Test
    fun buildDateIsIso() {
        assertTrue(BuildConfig.BUILD_DATE.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }
}
