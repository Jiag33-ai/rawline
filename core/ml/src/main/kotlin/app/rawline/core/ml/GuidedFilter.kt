package app.rawline.core.ml

/** Edge-aware smoothing of a mask against an image (He et al. guided filter, box filter implementation). */
object GuidedFilter {
    fun apply(guide: FloatArray, src: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = box(guide, w, h, radius)
        val meanP = box(src, w, h, radius)
        val ip = FloatArray(n) { guide[it] * src[it] }
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val corrIP = box(ip, w, h, radius)
        val corrII = box(ii, w, h, radius)
        val a = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrII[i] - meanI[i] * meanI[i]
            val covIP = corrIP[i] - meanI[i] * meanP[i]
            a[i] = covIP / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = box(a, w, h, radius)
        val meanB = box(b, w, h, radius)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /** Mean over a (2r+1) square using running sums. */
    fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            var sum = 0f
            val row = y * w
            for (x in 0..minOf(r, w - 1)) sum += src[row + x]
            for (x in 0 until w) {
                val lo = x - r; val hi = x + r
                val cnt = minOf(hi, w - 1) - maxOf(lo, 0) + 1
                tmp[row + x] = sum / cnt
                if (hi + 1 < w) sum += src[row + hi + 1]
                if (lo >= 0) sum -= src[row + lo]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in 0..minOf(r, h - 1)) sum += tmp[y * w + x]
            for (y in 0 until h) {
                val lo = y - r; val hi = y + r
                val cnt = minOf(hi, h - 1) - maxOf(lo, 0) + 1
                out[y * w + x] = sum / cnt
                if (hi + 1 < h) sum += tmp[(hi + 1) * w + x]
                if (lo >= 0) sum -= tmp[lo * w + x]
            }
        }
        return out
    }
}
