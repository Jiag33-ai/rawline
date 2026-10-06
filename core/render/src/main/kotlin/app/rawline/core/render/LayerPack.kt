package app.rawline.core.render

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Deflated copies of mask layers: brush and AI masks are mostly 0 or 255, so a full set costs well under a megabyte. */
object LayerPack {
    class Packed(val data: ByteArray, val w: Int, val h: Int)

    fun pack(alpha: ByteArray, w: Int, h: Int): Packed {
        val d = Deflater(Deflater.BEST_SPEED)
        try {
            d.setInput(alpha); d.finish()
            val out = ByteArrayOutputStream(maxOf(256, alpha.size / 16))
            val buf = ByteArray(8192)
            while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
            return Packed(out.toByteArray(), w, h)
        } finally { d.end() }
    }

    fun unbundle(p: Packed): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(p.data)
            val out = ByteArray(p.w * p.h)
            var off = 0
            while (off < out.size && !inf.finished()) {
                val n = inf.inflate(out, off, out.size - off)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                off += n
            }
            return out
        } finally { inf.end() }
    }

    inline fun unpack(p: Packed, use: (ByteArray, Int, Int) -> Unit) = use(unbundle(p), p.w, p.h)
}
