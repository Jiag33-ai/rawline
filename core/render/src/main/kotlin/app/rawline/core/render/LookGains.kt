package app.rawline.core.render

import app.rawline.core.model.Look

/**
 * Which source gain an edit's look version asks of a decoded image. The decoder reports two numbers once ([Native.rawGains][app.rawline.core.nativelib.Native.rawGains]):
 * index 0 is the factor LibRaw's own white point rule would have applied (look 1 reproduces every edit made before looks existed),
 * index 1 is the white balance gain that brings a neutral back to the level it has at unity white balance (look 2). A finished
 * picture (JPEG, HEIC, PNG) has neither, so its gain is always 1.
 */
object LookGains {
    fun srcGain(look: Int, gains: FloatArray, finishedPicture: Boolean = false): Float {
        if (finishedPicture || gains.size < 2) return 1f
        val g = gains[if (Look.supported(look) == Look.V1) 0 else 1]
        return if (g > 0f && g.isFinite()) g else 1f
    }
}
