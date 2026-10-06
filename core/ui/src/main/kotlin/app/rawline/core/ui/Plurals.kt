package app.rawline.core.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Counts with the right noun ("1 photo", "2 photos"). */
object Plurals {
    fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
    fun photos(n: Int) = count(n, "photo")
    fun edits(n: Int) = count(n, "edit")
}

/** Australian day month year ("3 October 2026"). One place so the grid headings and the viewer's info agree. */
object DateText {
    private val AU = Locale.Builder().setLanguage("en").setRegion("AU").build()
    private val long = DateTimeFormatter.ofPattern("d MMMM yyyy", AU)
    private const val withTime = "d MMM yyyy HH:mm"

    fun day(d: LocalDate): String = d.format(long)

    /** "3 Oct 2026 14:05" in the phone's time zone. */
    fun dateTime(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern(withTime, AU))
}
