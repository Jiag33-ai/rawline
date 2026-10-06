package app.rawline.core.studio.render

import app.rawline.core.studio.model.RawPixels

/** A layer thumbnail as non-premultiplied ARGB ints (what `Bitmap.createBitmap(int[], ...)` takes). */
class Thumb(val w: Int, val h: Int, val argb: IntArray)

object Thumbs {
    /** A grey thumbnail of a mask: white where it reveals, black where it hides, nearest sampling (at most [maxEdge] on the long side). */
    fun makeMask(p: app.rawline.core.studio.model.TilePlane, maxEdge: Int = 64): Thumb {
        val scale = maxOf(p.w, p.h).toDouble() / maxEdge
        val tw = if (scale <= 1.0) p.w else maxOf(1, Math.round(p.w / scale).toInt()); val th = if (scale <= 1.0) p.h else maxOf(1, Math.round(p.h / scale).toInt())
        val out = IntArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            val v = p[minOf(p.w - 1, ((tx + 0.5) * p.w / tw).toInt()), minOf(p.h - 1, ((ty + 0.5) * p.h / th).toInt())]
            out[ty * tw + tx] = (255 shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Thumb(tw, th, out)
    }

    /** Box averages the layer down so its longest edge is at most [maxEdge], sampling 3 x 3 points per output pixel (a 12 MP layer costs about 60 000 reads). Colour is averaged alpha weighted. */
    fun make(p: RawPixels, maxEdge: Int = 96): Thumb {
        val scale = maxOf(p.w, p.h).toDouble() / maxEdge
        val tw = if (scale <= 1.0) p.w else maxOf(1, Math.round(p.w / scale).toInt())
        val th = if (scale <= 1.0) p.h else maxOf(1, Math.round(p.h / scale).toInt())
        val out = IntArray(tw * th)
        val src = p.rgba
        for (ty in 0 until th) for (tx in 0 until tw) {
            val x0 = tx.toDouble() * p.w / tw; val x1 = (tx + 1).toDouble() * p.w / tw
            val y0 = ty.toDouble() * p.h / th; val y1 = (ty + 1).toDouble() * p.h / th
            var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
            for (j in 0 until 3) for (i in 0 until 3) {
                val sx = minOf(p.w - 1, (x0 + (x1 - x0) * (i + 0.5) / 3).toInt()); val sy = minOf(p.h - 1, (y0 + (y1 - y0) * (j + 0.5) / 3).toInt())
                val o = (sy * p.w + sx) * 4
                val al = (src[o + 3].toInt() and 255) / 255.0
                a += al; r += (src[o].toInt() and 255) * al; g += (src[o + 1].toInt() and 255) * al; b += (src[o + 2].toInt() and 255) * al
            }
            val ai = Math.round(a / 9 * 255).toInt()
            out[ty * tw + tx] = if (a <= 0.0) 0 else (ai shl 24) or (Math.round(r / a).toInt().coerceIn(0, 255) shl 16) or (Math.round(g / a).toInt().coerceIn(0, 255) shl 8) or Math.round(b / a).toInt().coerceIn(0, 255)
        }
        return Thumb(tw, th, out)
    }
}
