package app.rawline.core.render

import app.rawline.core.model.Look
import kotlin.math.pow

/** Conversions between what the screen shows (camera-look sRGB) and the linear working space the engine stores. */
object ColorSpaces {
    // The same tables the engine holds (RenderTest checks BaseCurve.TABLE and TABLE2 against engine/base_curve.h), so no JNI call is needed here.
    private fun curve(look: Int): FloatArray = BaseCurve.table(look)
    /** display value (0..1) -> sRGB encoded value before the base curve, one table per look version (index = version - 1) */
    private val inverses = arrayOf(lazy { buildInverse(Look.V1) }, lazy { buildInverse(Look.V2) })
    private fun inverse(look: Int): FloatArray = inverses[Look.supported(look) - 1].value
    private fun buildInverse(look: Int): FloatArray {
        val curve = curve(look)
        val inv = FloatArray(256)
        var j = 0
        for (i in 0 until 256) {
            val target = i / 255f
            while (j < 255 && curve[j] < target) j++
            inv[i] = if (j == 0) 0f else {
                val a = curve[j - 1]; val b = curve[j]
                (j - 1 + if (b > a) ((target - a) / (b - a)).coerceIn(0f, 1f) else 1f) / 255f
            }
        }
        return inv
    }

    private fun eotf(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    // linear sRGB (D65) -> ProPhoto (D50), Bradford
    internal val SRGB_TO_WORKING = floatArrayOf(0.5293f, 0.3300f, 0.1406f, 0.0984f, 0.8735f, 0.0282f, 0.0169f, 0.1177f, 0.8656f)

    /**
     * display r,g,b in 0..1 -> linear working space. [useBase] false is for finished pictures (JPEG, HEIC, PNG), which are drawn with
     * an identity base curve: the display value is plain sRGB there, so the camera curve must not be inverted (it darkened every patch).
     * [look] is the edit's look version: it picks the base curve (no default, so a caller cannot forget it).
     */
    fun displayToWorking(r: Float, g: Float, b: Float, out: FloatArray, look: Int, o: Int = 0, useBase: Boolean = true) {
        val inverse = inverse(look)
        fun lin(v: Float): Float {
            if (!useBase) return eotf(v.coerceIn(0f, 1f))
            val x = (v.coerceIn(0f, 1f) * 255f); val i = x.toInt().coerceAtMost(254); val f = x - i
            return eotf(inverse[i] * (1 - f) + inverse[i + 1] * f)
        }
        val lr = lin(r); val lg = lin(g); val lb = lin(b)
        out[o] = SRGB_TO_WORKING[0] * lr + SRGB_TO_WORKING[1] * lg + SRGB_TO_WORKING[2] * lb
        out[o + 1] = SRGB_TO_WORKING[3] * lr + SRGB_TO_WORKING[4] * lg + SRGB_TO_WORKING[5] * lb
        out[o + 2] = SRGB_TO_WORKING[6] * lr + SRGB_TO_WORKING[7] * lg + SRGB_TO_WORKING[8] * lb
    }

    private fun oetf(x: Float) = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

    // ProPhoto -> linear sRGB
    private val inv = floatArrayOf(2.0343f, -0.7273f, -0.3067f, -0.2288f, 1.2317f, -0.0029f, -0.0086f, -0.1533f, 1.1617f)

    /** linear working space -> display r,g,b 0..1 (through the camera look curve, or plain sRGB when [useBase] is false) */
    fun workingToDisplay(r: Float, g: Float, b: Float, out: FloatArray, look: Int, o: Int = 0, useBase: Boolean = true) {
        val curve = curve(look)
        val lr = (inv[0] * r + inv[1] * g + inv[2] * b).coerceIn(0f, 1f)
        val lg = (inv[3] * r + inv[4] * g + inv[5] * b).coerceIn(0f, 1f)
        val lb = (inv[6] * r + inv[7] * g + inv[8] * b).coerceIn(0f, 1f)
        fun disp(l: Float): Float { val e = oetf(l).coerceIn(0f, 1f); if (!useBase) return e; val x = e * 255f; val i = x.toInt().coerceAtMost(254); val f = x - i; return curve[i] * (1 - f) + curve[i + 1] * f }
        out[o] = disp(lr); out[o + 1] = disp(lg); out[o + 2] = disp(lb)
    }
}
