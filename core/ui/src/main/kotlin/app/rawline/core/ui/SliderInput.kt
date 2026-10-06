package app.rawline.core.ui

import java.util.Locale

/** Typing a slider value: what the field starts with and how what the user typed is read. */
object SliderInput {
    /** The plain number the slider holds, with no unit or sign decoration ("5500 K" was never parseable). */
    fun initial(value: Float, decimals: Int): String =
        if (decimals <= 0) Math.round(value).toString() else String.format(Locale.US, "%.${decimals}f", value)

    /**
     * Reads what was typed. Units and spaces are ignored ("12 K", "+1.5 EV"), a decimal comma works, and the typographic minus
     * counts as a minus. Returns null when it is not a number.
     */
    fun parse(text: String): Float? {
        val t = text.trim().replace('\u2212', '-').replace('\u2013', '-').replace(',', '.')
        val cleaned = t.filter { it.isDigit() || it == '.' || it == '-' || it == '+' }
        if (cleaned.isEmpty() || cleaned.drop(1).any { it == '-' || it == '+' }) return null
        return cleaned.toFloatOrNull()?.takeIf { it.isFinite() }
    }

    /** The +/- button for keyboards with no minus key. */
    fun flipSign(text: String): String {
        val t = text.trim()
        return when {
            t.startsWith("-") -> t.drop(1)
            t.startsWith("+") -> "-" + t.drop(1)
            else -> "-$t"
        }
    }
}
