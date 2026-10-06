package app.rawline.core.data.ingest

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.ByteBuffer

enum class DngSupport { SUPPORTED, PREVIEW_ONLY, NOT_DNG, UNREADABLE }

data class DngProbe(val support: DngSupport, val compression: Int, val reason: String)

/**
 * Reads the TIFF IFD chain of a DNG and reports the Compression tag (259) of the raw image, never decoding pixels (BK-431, W15 section 3).
 * Reads small windows at the offsets the file names, so an IFD that sits at the end of a 50 MB file is found too; a plain byte array (the head
 * of a file) works the same way and stops at its end. Pure JVM, so the host tests run it.
 */
object DngProber {
    private const val PHOTO_CFA = 32803
    private const val PHOTO_LINEAR = 34892
    private const val MAX_IFDS = 16
    private const val MAX_ENTRIES = 4096

    /** The first bytes of a file. An IFD beyond them is UNREADABLE. */
    fun probe(head: ByteArray): DngProbe = probeWith { off, n -> if (off < 0 || off + n > head.size) null else head.copyOfRange(off.toInt(), off.toInt() + n) }

    fun probeFile(f: File): DngProbe = try { RandomAccessFile(f, "r").use { probeChannel(it.channel) } } catch (e: Exception) { DngProbe(DngSupport.UNREADABLE, -1, e.message ?: "io") }

    /** For a file opened from a content URI (`FileInputStream(parcelFileDescriptor.fileDescriptor).channel`). Does not close [ch]. */
    fun probeChannel(ch: FileChannel): DngProbe {
        val size = try { ch.size() } catch (e: Exception) { return DngProbe(DngSupport.UNREADABLE, -1, e.message ?: "io") }
        return try {
            probeWith { off, n ->
                if (off < 0 || n < 0 || off + n > size) null else {
                    val b = ByteBuffer.allocate(n); var pos = off
                    while (b.hasRemaining()) { val r = ch.read(b, pos); if (r < 0) return@probeWith null; pos += r }
                    b.array()
                }
            }
        } catch (e: java.io.IOException) { DngProbe(DngSupport.UNREADABLE, -1, e.message ?: "io") }
    }

    /** [read] returns exactly n bytes at the offset, or null when that range is not available. */
    private fun probeWith(read: (Long, Int) -> ByteArray?): DngProbe {
        val h = read(0, 8) ?: return DngProbe(DngSupport.NOT_DNG, -1, "too short")
        val le = when {
            h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> true
            h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> false
            else -> return DngProbe(DngSupport.NOT_DNG, -1, "no TIFF header")
        }
        fun u16(b: ByteArray, o: Int): Int = if (le) (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) else ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)
        fun u32(b: ByteArray, o: Int): Long = if (le) (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)) else ((u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong())
        if (u16(h, 2) != 42) return DngProbe(DngSupport.NOT_DNG, -1, "bad TIFF magic")

        class Ifd(val comp: Int, val photo: Int, val pixels: Long)
        val ifds = ArrayList<Ifd>()
        var sawDng = false; var lost = false
        val queue = ArrayDeque<Long>(); val seen = HashSet<Long>()
        queue.add(u32(h, 4))
        var first = true
        while (queue.isNotEmpty() && ifds.size < MAX_IFDS) {
            val off = queue.removeFirst()
            if (!seen.add(off)) continue
            val isFirst = first; first = false
            val count = read(off, 2)?.let { u16(it, 0) }
            val body = if (count == null || count > MAX_ENTRIES) null else read(off + 2, count * 12 + 4)
            if (count == null || body == null) {
                if (isFirst) return DngProbe(DngSupport.UNREADABLE, -1, "IFD outside the readable part of the file")
                lost = true; continue
            }
            var comp = 0; var photo = 0; var w = 0L; var hgt = 0L
            for (i in 0 until count) {
                val e = i * 12
                val tag = u16(body, e); val type = u16(body, e + 2); val cnt = u32(body, e + 4)
                val inline: Long = if (type == 3) u16(body, e + 8).toLong() else u32(body, e + 8)
                when (tag) {
                    259 -> comp = inline.toInt()
                    262 -> photo = inline.toInt()
                    256 -> w = inline
                    257 -> hgt = inline
                    50706 -> sawDng = true
                    330 -> if (cnt == 1L) queue.add(inline) else {      // SubIFDs: an array of offsets elsewhere in the file
                        val k = minOf(cnt, 8L).toInt()
                        val arr = read(inline, 4 * k)
                        if (arr == null) lost = true else for (j in 0 until k) queue.add(u32(arr, 4 * j))
                    }
                }
            }
            ifds.add(Ifd(comp, photo, w * hgt))
            val next = u32(body, count * 12)
            if (next != 0L) queue.add(next)
        }
        if (!sawDng) return if (lost) DngProbe(DngSupport.UNREADABLE, -1, "IFD outside the readable part of the file") else DngProbe(DngSupport.NOT_DNG, -1, "no DNGVersion tag")
        val raw = ifds.filter { it.photo == PHOTO_CFA || it.photo == PHOTO_LINEAR }.maxByOrNull { it.pixels }
            ?: return DngProbe(DngSupport.UNREADABLE, -1, if (lost) "raw IFD outside the readable part of the file" else "no raw IFD found")
        return when (raw.comp) {
            1, 7, 8 -> DngProbe(DngSupport.SUPPORTED, raw.comp, "uncompressed, lossless JPEG or deflate")
            52546 -> DngProbe(DngSupport.PREVIEW_ONLY, raw.comp, "JPEG XL raw (Expert RAW style): decode needs libjxl or the DNG SDK")
            9 -> DngProbe(DngSupport.PREVIEW_ONLY, raw.comp, "VC-5 raw: decode not available")
            34892 -> DngProbe(DngSupport.PREVIEW_ONLY, raw.comp, "lossy JPEG DNG: needs a JPEG-enabled LibRaw build")
            else -> DngProbe(DngSupport.PREVIEW_ONLY, raw.comp, "unknown compression ${raw.comp}")
        }
    }
}
