package app.rawline.core.render

/**
 * Half float conversion in plain Kotlin (the same rules as engine/halfs.h): round to nearest, NaN to 0, anything beyond 65504
 * (including 65520 to 65535 and Infinity) to the largest finite half instead of Infinity. Works on a host JVM, unlike
 * android.util.Half, which the unit tests can only stub.
 */
object Halfs {
    private const val LARGEST = 0x7BFF

    fun toHalf(f: Float): Short {
        val x = java.lang.Float.floatToRawIntBits(f)
        val sign = (x ushr 16) and 0x8000
        val exp = ((x ushr 23) and 0xFF) - 127 + 15
        var mant = x and 0x7FFFFF
        if (exp >= 31) return (if (((x ushr 23) and 0xFF) == 0xFF && mant != 0) 0 else sign or LARGEST).toShort()
        if (exp <= 0) {
            if (exp < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - exp
            var h = mant ushr shift
            if (((mant ushr (shift - 1)) and 1) != 0) h++
            return (sign or h).toShort()
        }
        var h = (exp shl 10) or (mant ushr 13)
        if ((mant and 0x1000) != 0) h++
        if (h >= 0x7C00) h = LARGEST   // the carry out of the largest binade would be Infinity
        return (sign or h).toShort()
    }

    fun toFloat(h: Short): Float {
        val v = h.toInt() and 0xFFFF
        val sign = (v and 0x8000) shl 16
        var exp = (v ushr 10) and 0x1F
        var mant = v and 0x3FF
        val bits: Int
        if (exp == 0) {
            if (mant == 0) bits = sign
            else {
                exp = 1
                while ((mant and 0x400) == 0) { mant = mant shl 1; exp-- }
                mant = mant and 0x3FF
                bits = sign or ((exp + 127 - 15) shl 23) or (mant shl 13)
            }
        } else if (exp == 31) bits = sign or 0x7F800000 or (mant shl 13)
        else bits = sign or ((exp + 127 - 15) shl 23) or (mant shl 13)
        return java.lang.Float.intBitsToFloat(bits)
    }
}
