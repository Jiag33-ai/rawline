package app.rawline

import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import app.rawline.core.render.ExportFormat
import app.rawline.core.render.ExportSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ExportNamingTest {
    private fun photo(name: String = "P1055415.RW2", camera: String? = "Panasonic DC-S5M2X", rating: Int = 3) =
        Photo(1, "f", "u", name, 10, 86_400_000L * 365, Kind.RAW, true, takenAt = 0, camera = camera, rating = rating)
    private fun settings(pattern: String, format: ExportFormat = ExportFormat.JPEG) = ExportSettings(format = format, pattern = pattern)

    @Test fun blankPatternUsesTheOriginalNameAndFormatExtension() {
        assertEquals("P1055415.jpg", ExportNaming.fileName(photo(), settings(""), 1))
        assertEquals("P1055415.png", ExportNaming.fileName(photo(), settings("  ", ExportFormat.PNG), 1))
        assertEquals("P1055415.tif", ExportNaming.fileName(photo(), settings("{name}", ExportFormat.TIFF16), 1))
    }

    @Test fun counterIsPaddedToThreeDigits() {
        assertEquals("a-007.jpg", ExportNaming.fileName(photo(), settings("a-{n}"), 7))
        assertEquals("a-1234.jpg", ExportNaming.fileName(photo(), settings("a-{n}"), 1234))
    }

    @Test fun ratingAndCameraAreFilledIn() {
        assertEquals("3-Panasonic-DC-S5M2X.jpg", ExportNaming.fileName(photo(), settings("{rating}-{camera}"), 1))
        assertEquals("camera.jpg", ExportNaming.fileName(photo(camera = null), settings("{camera}"), 1))
    }

    @Test fun dateComesFromModifiedWhenTakenTimeIsUnknown() {
        val prev = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("19710101.jpg", ExportNaming.fileName(photo(), settings("{date}"), 1))
        } finally { TimeZone.setDefault(prev) }
    }

    @Test fun pathSeparatorsAndForbiddenCharactersNeverSurvive() {
        val n = ExportNaming.fileName(photo(), settings("{name}/../x\\y:z*?\"<>|"), 1)
        assertFalse(n, n.any { it in "/\\:*?\"<>|" })
        assertTrue(n, n.endsWith(".jpg"))
    }

    @Test fun aPatternOfOnlyDotsOrSpacesFallsBackToAName() {
        assertEquals("photo.jpg", ExportNaming.fileName(photo(), settings(".."), 1))
        assertEquals("_.jpg", ExportNaming.fileName(photo(), settings("/"), 1))   // a separator becomes an underscore
    }

    @Test fun veryLongNamesAreCut() {
        val n = ExportNaming.fileName(photo(name = "x".repeat(400) + ".RW2"), settings("{name}"), 1)
        assertEquals(ExportNaming.MAX_BASE + 4, n.length)
        assertTrue(n.endsWith(".jpg"))
    }

    @Test fun nameWithoutExtensionIsKeptWhole() {
        assertEquals("scan.jpg", ExportNaming.fileName(photo(name = "scan"), settings("{name}"), 1))
    }
}
