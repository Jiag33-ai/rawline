package app.rawline.feature.loupe

import org.junit.Assert.assertEquals
import org.junit.Test

class InfoTextTest {
    @Test fun unknownValuesAreLeftOut() {
        assertEquals("", InfoText.facts(0, 0, 0))
        assertEquals("4 MB", InfoText.facts(0, 0, 4 * 1024 * 1024L))
        assertEquals("6000 x 4000 px", InfoText.facts(6000, 4000, 0))
    }

    @Test fun smallFilesShowKilobytesNotZeroMegabytes() {
        assertEquals("300 KB", InfoText.size(300 * 1024L))
        assertEquals("1 KB", InfoText.size(10))
        assertEquals("1 MB", InfoText.size(1024 * 1024L))
    }

    @Test fun bothFactsAreJoined() {
        assertEquals("6000 x 4000 px   25 MB", InfoText.facts(6000, 4000, 25L * 1024 * 1024))
    }
}
