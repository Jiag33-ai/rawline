package app.rawline.core.studio.render

/**
 * Decision D5: S1 has no tile cache, so every layer is a full texture. Layer textures (4 bytes a pixel), the ping-pong pair (2 x 8 bytes a pixel of the output),
 * the resolve target (4) and the stroke buffer (2 bytes a pixel of the largest layer) must stay under [LIMIT_BYTES].
 */
object MemoryGuard {
    const val LIMIT_BYTES = 600L * 1024 * 1024
    const val MESSAGE = "Not enough memory for another layer at this size."
    /** Output size assumed before the surface exists (a 3120 x 1440 phone). */
    const val DEFAULT_OUT_W = 3120
    const val DEFAULT_OUT_H = 1440

    fun estimate(layerSizes: List<Pair<Int, Int>>, outW: Int, outH: Int): Long {
        var n = 0L; var biggest = 0L
        for ((w, h) in layerSizes) { n += w.toLong() * h * 4; biggest = maxOf(biggest, w.toLong() * h) }
        n += 2L * 8 * outW * outH + 4L * outW * outH + 2L * biggest
        return n
    }

    fun allows(layerSizes: List<Pair<Int, Int>>, outW: Int, outH: Int): Boolean = estimate(layerSizes, outW, outH) <= LIMIT_BYTES
}
