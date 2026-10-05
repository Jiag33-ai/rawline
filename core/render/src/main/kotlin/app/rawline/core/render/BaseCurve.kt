package app.rawline.core.render

import kotlin.math.pow

/**
 * The tone curve the engine applies to every raw after the edit (see base_curve.h; a unit test keeps the two equal),
 * and the maths that lets the user's curve act on what they see instead of on the linear data underneath it.
 */
object BaseCurve {
    val TABLE = floatArrayOf(
        0.00000f, 0.01686f, 0.03369f, 0.04971f, 0.06301f, 0.07448f, 0.08467f, 0.09390f,
        0.10238f, 0.11025f, 0.11762f, 0.12474f, 0.13187f, 0.13900f, 0.14611f, 0.15322f,
        0.16031f, 0.16740f, 0.17448f, 0.18154f, 0.18860f, 0.19564f, 0.20268f, 0.20970f,
        0.21670f, 0.22370f, 0.23068f, 0.23764f, 0.24459f, 0.25152f, 0.25844f, 0.26534f,
        0.27222f, 0.27908f, 0.28593f, 0.29275f, 0.29956f, 0.30635f, 0.31311f, 0.31985f,
        0.32657f, 0.33327f, 0.33995f, 0.34660f, 0.35323f, 0.35983f, 0.36640f, 0.37295f,
        0.37948f, 0.38597f, 0.39244f, 0.39888f, 0.40530f, 0.41168f, 0.41803f, 0.42436f,
        0.43065f, 0.43691f, 0.44314f, 0.44934f, 0.45550f, 0.46163f, 0.46773f, 0.47380f,
        0.47982f, 0.48582f, 0.49178f, 0.49770f, 0.50359f, 0.50944f, 0.51526f, 0.52104f,
        0.52678f, 0.53248f, 0.53815f, 0.54377f, 0.54936f, 0.55491f, 0.56042f, 0.56589f,
        0.57132f, 0.57672f, 0.58207f, 0.58738f, 0.59265f, 0.59788f, 0.60307f, 0.60821f,
        0.61332f, 0.61838f, 0.62341f, 0.62839f, 0.63333f, 0.63823f, 0.64308f, 0.64790f,
        0.65267f, 0.65740f, 0.66209f, 0.66674f, 0.67134f, 0.67590f, 0.68042f, 0.68490f,
        0.68934f, 0.69373f, 0.69809f, 0.70240f, 0.70666f, 0.71089f, 0.71508f, 0.71922f,
        0.72332f, 0.72738f, 0.73140f, 0.73538f, 0.73932f, 0.74322f, 0.74707f, 0.75089f,
        0.75466f, 0.75840f, 0.76209f, 0.76575f, 0.76936f, 0.77294f, 0.77648f, 0.77998f,
        0.78344f, 0.78686f, 0.79024f, 0.79358f, 0.79689f, 0.80016f, 0.80339f, 0.80659f,
        0.80975f, 0.81287f, 0.81595f, 0.81900f, 0.82201f, 0.82499f, 0.82793f, 0.83084f,
        0.83371f, 0.83655f, 0.83936f, 0.84213f, 0.84486f, 0.84757f, 0.85024f, 0.85287f,
        0.85548f, 0.85805f, 0.86059f, 0.86310f, 0.86558f, 0.86803f, 0.87044f, 0.87283f,
        0.87519f, 0.87751f, 0.87981f, 0.88207f, 0.88431f, 0.88652f, 0.88870f, 0.89086f,
        0.89298f, 0.89508f, 0.89715f, 0.89919f, 0.90121f, 0.90320f, 0.90516f, 0.90710f,
        0.90901f, 0.91090f, 0.91276f, 0.91460f, 0.91642f, 0.91821f, 0.91997f, 0.92171f,
        0.92343f, 0.92513f, 0.92680f, 0.92845f, 0.93007f, 0.93168f, 0.93326f, 0.93483f,
        0.93637f, 0.93789f, 0.93939f, 0.94086f, 0.94232f, 0.94376f, 0.94518f, 0.94658f,
        0.94796f, 0.94932f, 0.95066f, 0.95198f, 0.95328f, 0.95457f, 0.95584f, 0.95709f,
        0.95832f, 0.95954f, 0.96073f, 0.96191f, 0.96308f, 0.96423f, 0.96536f, 0.96648f,
        0.96757f, 0.96866f, 0.96973f, 0.97078f, 0.97182f, 0.97284f, 0.97385f, 0.97485f,
        0.97583f, 0.97679f, 0.97774f, 0.97868f, 0.97961f, 0.98052f, 0.98141f, 0.98230f,
        0.98317f, 0.98403f, 0.98487f, 0.98571f, 0.98653f, 0.98734f, 0.98814f, 0.98892f,
        0.98970f, 0.99046f, 0.99121f, 0.99195f, 0.99268f, 0.99339f, 0.99410f, 0.99480f,
        0.99548f, 0.99616f, 0.99682f, 0.99748f, 0.99812f, 0.99876f, 0.99938f, 1.00000f,
    )

    private fun srgbOetf(l: Float) = if (l <= 0.0031308f) 12.92f * l else 1.055f * l.pow(1f / 2.4f) - 0.055f
    private fun srgbEotf(s: Float) = if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)

    private fun sample(t: FloatArray, x: Float): Float {
        val p = x.coerceIn(0f, 1f) * 255f
        val i = p.toInt().coerceAtMost(254)
        return t[i] + (t[i + 1] - t[i]) * (p - i)
    }

    /** Inverse of a non decreasing table: the input whose output is [y]. */
    private fun invert(t: FloatArray, y: Float): Float {
        if (y <= t[0]) return 0f
        if (y >= t[255]) return 1f
        var i = 0
        while (i < 254 && t[i + 1] < y) i++
        val span = t[i + 1] - t[i]
        return (i + if (span <= 1e-7f) 0f else (y - t[i]) / span) / 255f
    }

    /**
     * The shader looks the curve up on its working value (gamma 2.2 of linear light). For a raw the engine then applies
     * [TABLE] and the sRGB encoding, so a curve drawn over the photo, the histogram and the picture on screen must be
     * applied after those steps. This folds them in: working value -> display value -> user curve -> back to working value.
     * With [withBase] false (JPEG, HEIC, PNG) there is no base curve and only the encoding difference is folded in.
     */
    fun toWorking(user: FloatArray, withBase: Boolean): FloatArray = FloatArray(256) { k ->
        val lin = (k / 255f).pow(2.2f)
        val s = srgbOetf(lin)
        val display = if (withBase) sample(TABLE, s) else s
        val edited = sample(user, display)
        val s2 = if (withBase) invert(TABLE, edited) else edited
        srgbEotf(s2).pow(1f / 2.2f)
    }
}
