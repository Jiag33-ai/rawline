package app.rawline.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PluralsTest {
    @Test fun oneIsSingular() {
        assertEquals("1 photo", Plurals.photos(1))
        assertEquals("1 edit", Plurals.edits(1))
    }

    @Test fun zeroAndManyArePlural() {
        assertEquals("0 photos", Plurals.photos(0))
        assertEquals("2 photos", Plurals.photos(2))
        assertEquals("1204 photos", Plurals.photos(1204))
    }

    @Test fun irregularNounsCanBeGiven() {
        assertEquals("1 library", Plurals.count(1, "library", "libraries"))
        assertEquals("3 libraries", Plurals.count(3, "library", "libraries"))
    }

    @Test fun datesAreDayMonthYearAustralian() {
        assertEquals("3 October 2026", DateText.day(LocalDate.of(2026, 10, 3)))
        assertEquals("1 January 2025", DateText.day(LocalDate.of(2025, 1, 1)))
    }

    @Test fun dateTimeUsesDayFirstAndTwentyFourHour() {
        val ms = java.time.ZonedDateTime.of(2026, 10, 3, 14, 5, 0, 0, ZoneId.of("Australia/Adelaide")).toInstant().toEpochMilli()
        assertEquals("3 Oct 2026 14:05", DateText.dateTime(ms, ZoneId.of("Australia/Adelaide")))
    }
}
