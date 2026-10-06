package app.rawline.core.studio.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.*

/** Studio S2 pure maths: tiles and selections. No android.* imports. */

// ---------------- tiles ----------------
const val TILE = Tiles.SIZE  // one definition of the tile edge (Tiles.kt, S1b)

data class TileKey(val tx: Int, val ty: Int)
data class IRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) { // x1,y1 exclusive
    val empty get() = x1 <= x0 || y1 <= y0
    val w get() = max(0, x1 - x0); val h get() = max(0, y1 - y0)
    fun intersect(o: IRect) = IRect(max(x0, o.x0), max(y0, o.y0), min(x1, o.x1), min(y1, o.y1))
    fun union(o: IRect) = if (empty) o else if (o.empty) this else IRect(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))
}

object TileGrid {
    fun cols(w: Int) = (w + TILE - 1) / TILE
    fun rows(h: Int) = (h + TILE - 1) / TILE
    fun keysFor(r: IRect, w: Int, h: Int): List<TileKey> {
        val c = r.intersect(IRect(0, 0, w, h)); if (c.empty) return emptyList()
        val out = ArrayList<TileKey>()
        for (ty in c.y0 / TILE..(c.y1 - 1) / TILE) for (tx in c.x0 / TILE..(c.x1 - 1) / TILE) out.add(TileKey(tx, ty))
        return out
    }
    /** Pixel rect of a tile, clipped to the canvas (edge tiles are smaller; stored at their clipped size). */
    fun rectOf(k: TileKey, w: Int, h: Int) = IRect(k.tx * TILE, k.ty * TILE, min(w, (k.tx + 1) * TILE), min(h, (k.ty + 1) * TILE))
    fun name(k: TileKey) = "t_${k.tx}_${k.ty}"
}

/** Tile payload: byte 0 = kind. 0 = constant (1 byte value follows), 1 = deflate of raw bytes. Absent tile = all zero. */
object TileCodec {
    fun encode(raw: ByteArray): ByteArray {
        val first = raw[0]
        if (raw.all { it == first }) return byteArrayOf(0, first)
        val d = Deflater(6); d.setInput(raw); d.finish()
        val bo = ByteArrayOutputStream(); bo.write(1)
        val buf = ByteArray(8192)
        while (!d.finished()) { val n = d.deflate(buf); bo.write(buf, 0, n) }
        d.end(); return bo.toByteArray()
    }
    fun decode(enc: ByteArray, size: Int): ByteArray {
        when (enc[0].toInt()) {
            0 -> return ByteArray(size) { enc[1] }
            1 -> {
                val inf = Inflater(); inf.setInput(enc, 1, enc.size - 1)
                val out = ByteArray(size); var n = 0
                while (n < size && !inf.finished()) { val r = inf.inflate(out, n, size - n); if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break; n += r }
                inf.end(); require(n == size) { "tile truncated: $n of $size" }
                return out
            }
            else -> throw IllegalArgumentException("unknown tile kind ${enc[0]}")
        }
    }
}

/** One tile of a plane before and after an edit (undo and redo of selection and mask edits, decision D8). [x], [y], [w], [h] are the tile's pixels, clipped to the plane. */
class PlaneDelta(val key: TileKey, val x: Int, val y: Int, val w: Int, val h: Int, val before: ByteArray, val after: ByteArray) {
    val bytes: Long get() = (before.size + after.size).toLong()
}

/**
 * Tiles of one 8-bit plane (mask or selection). With a [dir] it writes through to that directory (.part then rename) and keeps a bounded cache; with no directory it holds every
 * tile in memory and the owner persists the tiles from [takeDirty] (the session does, through ProjectStore). Sparse: a missing tile is 0.
 */
class TilePlane(val w: Int, val h: Int, private val dir: File? = null, private val cacheTiles: Int = 64) {
    private val cache = object : LinkedHashMap<TileKey, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<TileKey, ByteArray>) = dir != null && size > cacheTiles && !dirty.contains(e.key)
    }
    private val dirty = HashSet<TileKey>()
    private var rec: HashMap<TileKey, ByteArray>? = null
    val cached get() = cache.size

    private fun load(k: TileKey): ByteArray? {
        cache[k]?.let { return it }
        val f = dir?.let { File(it, TileGrid.name(k)) }
        if (f == null || !f.exists()) return null
        val r = TileGrid.rectOf(k, w, h)
        return TileCodec.decode(f.readBytes(), r.w * r.h).also { cache[k] = it }
    }
    /** The tile to write into: created (zero) when absent. The first write of a tile while recording keeps its before bytes. */
    private fun forWrite(k: TileKey): ByteArray {
        val r = TileGrid.rectOf(k, w, h)
        val t = load(k) ?: ByteArray(r.w * r.h).also { cache[k] = it }
        rec?.let { if (!it.containsKey(k)) it[k] = t.copyOf() }
        dirty.add(k)
        return t
    }
    operator fun get(x: Int, y: Int): Int {
        if (x !in 0 until w || y !in 0 until h) return 0
        val k = TileKey(x / TILE, y / TILE); val t = load(k) ?: return 0
        val r = TileGrid.rectOf(k, w, h); return t[(y - r.y0) * r.w + (x - r.x0)].toInt() and 255
    }
    operator fun set(x: Int, y: Int, v: Int) {
        if (x !in 0 until w || y !in 0 until h) return
        val k = TileKey(x / TILE, y / TILE); val r = TileGrid.rectOf(k, w, h)
        forWrite(k)[(y - r.y0) * r.w + (x - r.x0)] = v.toByte()
    }

    /** Rewrites every pixel of [rect] (clipped to the plane) as `f(x, y, current)`, a tile at a time. */
    fun transform(rect: IRect, f: (Int, Int, Int) -> Int) {
        val c = rect.intersect(IRect(0, 0, w, h)); if (c.empty) return
        for (k in TileGrid.keysFor(c, w, h)) {
            val r = TileGrid.rectOf(k, w, h); val x0 = maxOf(c.x0, r.x0); val x1 = minOf(c.x1, r.x1); val y0 = maxOf(c.y0, r.y0); val y1 = minOf(c.y1, r.y1)
            val t = forWrite(k)
            for (y in y0 until y1) { var i = (y - r.y0) * r.w + (x0 - r.x0); for (x in x0 until x1) { t[i] = f(x, y, t[i].toInt() and 255).toByte(); i++ } }
        }
    }

    /** Smallest rectangle holding every non-zero pixel inside [within] (empty when there is none). Tiles that are not stored are skipped. */
    fun nonZeroBounds(within: IRect = IRect(0, 0, w, h)): IRect {
        val c = within.intersect(IRect(0, 0, w, h)); if (c.empty) return IRect(0, 0, 0, 0)
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (k in TileGrid.keysFor(c, w, h)) {
            val t = load(k) ?: continue; val r = TileGrid.rectOf(k, w, h)
            for (y in maxOf(c.y0, r.y0) until minOf(c.y1, r.y1)) { val row = (y - r.y0) * r.w
                for (x in maxOf(c.x0, r.x0) until minOf(c.x1, r.x1)) if (t[row + x - r.x0].toInt() != 0) { if (x < x0) x0 = x; if (x + 1 > x1) x1 = x + 1; if (y < y0) y0 = y; if (y + 1 > y1) y1 = y + 1 }
            }
        }
        return if (x1 <= x0 || y1 <= y0) IRect(0, 0, 0, 0) else IRect(x0, y0, x1, y1)
    }

    /** Starts keeping the before bytes of every tile an edit touches; [endRecord] returns what changed. One recording at a time. */
    fun beginRecord() { check(rec == null) { "already recording" }; rec = HashMap() }
    /** One [PlaneDelta] per tile whose bytes differ from before, ordered by tile row then column. */
    fun endRecord(): List<PlaneDelta> {
        val before = rec ?: return emptyList(); rec = null
        val out = ArrayList<PlaneDelta>()
        for (k in before.keys.sortedWith(compareBy({ it.ty }, { it.tx }))) {
            val b = before.getValue(k); val a = cache[k]?.copyOf() ?: ByteArray(b.size)
            if (a.contentEquals(b)) continue
            val r = TileGrid.rectOf(k, w, h); out.add(PlaneDelta(k, r.x0, r.y0, r.w, r.h, b, a))
        }
        return out
    }
    /** Copy of one tile at its clipped size (zeros when absent). */
    fun copyTile(k: TileKey): ByteArray { val r = TileGrid.rectOf(k, w, h); return load(k)?.copyOf() ?: ByteArray(r.w * r.h) }
    /** Replaces a whole tile (undo, redo, mask fills). An all zero tile of a plane with no directory is dropped, so it stays sparse. */
    fun writeTile(k: TileKey, raw: ByteArray) {
        val r = TileGrid.rectOf(k, w, h); require(raw.size == r.w * r.h) { "tile ${TileGrid.name(k)} is ${raw.size} bytes, expected ${r.w * r.h}" }
        rec?.let { if (!it.containsKey(k)) it[k] = copyTile(k) }
        cache[k] = raw.copyOf(); dirty.add(k)
    }
    /** Marks every tile held in memory as changed (they are written again at the next save). */
    fun markAllDirty() { dirty.addAll(cache.keys) }
    /** An independent copy (duplicate layer). Only for a plane with no directory. */
    fun copy(): TilePlane { val p = TilePlane(w, h); for ((k, v) in cache) p.cache[k] = v.copyOf(); return p }
    /** Sets every pixel to [v]. */
    fun fill(v: Int) { transform(IRect(0, 0, w, h)) { _, _, _ -> v } }

    /** Writes dirty tiles. All-zero tiles are deleted from disk, so an empty selection is zero bytes. */
    fun flush() {
        val d = dir ?: run { dirty.clear(); return }
        d.mkdirs()
        for (k in dirty.toList()) {
            val t = cache[k] ?: continue; val f = File(d, TileGrid.name(k))
            if (t.all { it == 0.toByte() }) { f.delete(); continue }
            val part = File(d, TileGrid.name(k) + ".part"); part.writeBytes(TileCodec.encode(t))
            if (!part.renameTo(f)) { part.delete(); throw java.io.IOException("rename ${f.name}") }
        }
        dirty.clear()
        while (cache.size > cacheTiles) { val it = cache.keys.iterator(); it.next(); it.remove() }
    }
    /** Puts a decoded tile in without marking it dirty (loading from a store that is not a [File] directory). Edge tiles are stored at their clipped size. */
    fun putTile(k: TileKey, raw: ByteArray) { val r = TileGrid.rectOf(k, w, h); require(raw.size == r.w * r.h) { "tile ${TileGrid.name(k)} is ${raw.size} bytes, expected ${r.w * r.h}" }; cache[k] = raw }

    /** For a plane with no directory: the tiles changed since the last call, raw bytes (a copy), or null for a tile that became all zero (and is dropped from memory). Clears the dirty set. */
    fun takeDirty(): Map<TileKey, ByteArray?> {
        val out = LinkedHashMap<TileKey, ByteArray?>()
        for (k in dirty.toList().sortedWith(compareBy({ it.ty }, { it.tx }))) {
            val t = cache[k]
            if (t == null || t.all { it == 0.toByte() }) { out[k] = null; if (dir == null) cache.remove(k) } else out[k] = t.copyOf()
        }
        dirty.clear(); return out
    }

    /** Raw bytes of every non-zero tile held in memory (a plane with no directory holds all of them). */
    fun allTiles(): Map<TileKey, ByteArray> = cache.filterValues { t -> t.any { it != 0.toByte() } }.mapValues { it.value.copyOf() }

    /** The whole plane as w * h bytes, row 0 first. */
    fun toBytes(): ByteArray {
        val o = ByteArray(w * h)
        for (ty in 0 until TileGrid.rows(h)) for (tx in 0 until TileGrid.cols(w)) {
            val k = TileKey(tx, ty); val t = load(k) ?: continue; val r = TileGrid.rectOf(k, w, h)
            for (y in 0 until r.h) System.arraycopy(t, y * r.w, o, (r.y0 + y) * w + r.x0, r.w)
        }
        return o
    }
}

// ---------------- selections ----------------
enum class SelOp { REPLACE, ADD, SUBTRACT, INTERSECT }

/** Exact integer byte algebra, same formulas as the shader (mask.frag in S2): all round to nearest, ties up. */
object ByteAlgebra {
    private fun mul(a: Int, b: Int) = (a * b + 127) / 255
    fun combine(op: SelOp, cur: Int, new: Int): Int = when (op) {
        SelOp.REPLACE -> new
        SelOp.ADD -> cur + new - mul(cur, new)
        SelOp.SUBTRACT -> mul(cur, 255 - new)
        SelOp.INTERSECT -> mul(cur, new)
    }
    fun invert(a: Int) = 255 - a
    /** Layer mask applied to straight alpha: alpha' = round(alpha * mask / 255). */
    fun applyMask(alpha255: Int, mask: Int) = mul(alpha255, mask)
}

/** Shape coverage by 4x4 supersampling, `(n * 255 + 8) / 16` for n of 16 samples inside. The `*Reference` functions are the plain per pixel definitions the fast paths are tested against. */
object Raster {
    private fun cov(n: Int) = (n * 255 + 8) / 16

    /** Coverage of the ellipse in the region [r] (row major, r.w by r.h), by the definition: every pixel tests its 16 samples. For tests and small shapes. */
    fun ellipseReference(r: IRect, x0: Double, y0: Double, x1: Double, y1: Double): ByteArray {
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val rx = abs(x1 - x0) / 2; val ry = abs(y1 - y0) / 2
        val out = ByteArray(r.w * r.h)
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
            var n = 0
            for (j in 0 until 4) for (i in 0 until 4) if (insideEllipse(x + (i + 0.5) / 4, y + (j + 0.5) / 4, cx, cy, rx, ry)) n++
            out[(y - r.y0) * r.w + (x - r.x0)] = cov(n).toByte()
        }
        return out
    }
    private fun insideEllipse(px: Double, py: Double, cx: Double, cy: Double, rx: Double, ry: Double) = ((px - cx) / rx).pow(2) + ((py - cy) / ry).pow(2) <= 1.0

    /**
     * The same coverage a sub row at a time: each of the 4 sample rows of a pixel row meets the ellipse in one run of samples, found by arithmetic and then moved by whole samples until
     * the exact test of [ellipseReference] agrees at both ends. Cost is O(rows * width), not 16 tests per pixel.
     */
    fun ellipse(r: IRect, x0: Double, y0: Double, x1: Double, y1: Double): ByteArray {
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val rx = abs(x1 - x0) / 2; val ry = abs(y1 - y0) / 2
        val out = ByteArray(r.w * r.h); if (r.empty) return out
        val cnt = IntArray(r.w)
        val sLo = r.x0 * 4; val sHi = r.x1 * 4   // sample index s covers x = (s + 0.5) / 4
        for (y in r.y0 until r.y1) {
            java.util.Arrays.fill(cnt, 0)
            var any = false
            for (j in 0 until 4) {
                val py = y + (j + 0.5) / 4
                val dy = (py - cy) / ry; if (dy * dy > 1.0) continue
                val half = rx * sqrt(1.0 - dy * dy)
                var a = ceil((cx - half) * 4 - 0.5).toInt().coerceIn(sLo, sHi); var b = (ceil((cx + half) * 4 - 0.5).toInt() - 1).coerceIn(sLo - 1, sHi - 1)   // approximate run [a, b]
                // exact fix up: the samples inside form one run, so move each end by whole samples until the exact test agrees
                while (a > sLo && insideEllipse((a - 1 + 0.5) / 4, py, cx, cy, rx, ry)) a--
                while (b < sHi - 1 && insideEllipse((b + 1 + 0.5) / 4, py, cx, cy, rx, ry)) b++
                while (a <= b && !insideEllipse((a + 0.5) / 4, py, cx, cy, rx, ry)) a++
                while (b >= a && !insideEllipse((b + 0.5) / 4, py, cx, cy, rx, ry)) b--
                if (b < a) continue
                any = true
                addRun(cnt, r.x0, a, b)
            }
            if (any) { val row = (y - r.y0) * r.w; for (i in 0 until r.w) out[row + i] = cov(cnt[i]).toByte() }
        }
        return out
    }

    /** Adds the samples [a, b] (global sample indices, 4 per pixel) of one sample row to the per pixel counts. */
    private fun addRun(cnt: IntArray, px0: Int, a: Int, b: Int) {
        val pa = Math.floorDiv(a, 4); val pb = Math.floorDiv(b, 4)
        if (pa == pb) { cnt[pa - px0] += b - a + 1; return }
        cnt[pa - px0] += (pa + 1) * 4 - a
        for (p in pa + 1 until pb) cnt[p - px0] += 4
        cnt[pb - px0] += b - pb * 4 + 1
    }

    private fun evenOdd(xs: DoubleArray, ys: DoubleArray, px: Double, py: Double): Boolean {
        var inside = false; var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside
            j = i
        }
        return inside
    }

    /** Even-odd polygon coverage by the definition (every pixel, 16 samples, every edge). For tests and small shapes. */
    fun lassoReference(r: IRect, xs: DoubleArray, ys: DoubleArray): ByteArray {
        val out = ByteArray(r.w * r.h)
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
            var n = 0
            for (j in 0 until 4) for (i in 0 until 4) if (evenOdd(xs, ys, x + (i + 0.5) / 4, y + (j + 0.5) / 4)) n++
            out[(y - r.y0) * r.w + (x - r.x0)] = cov(n).toByte()
        }
        return out
    }

    /**
     * The same coverage by scan conversion: for each of the 4 sample rows of a pixel row the edges that cross it are intersected once, sorted, and the samples between the 1st and 2nd,
     * 3rd and 4th ... crossings are inside (the even-odd rule), so the cost is O(rows * (edges + width)) and not O(pixels * 16 * edges).
     */
    fun lasso(r: IRect, xs: DoubleArray, ys: DoubleArray): ByteArray {
        val out = ByteArray(r.w * r.h); if (r.empty) return out
        val n = xs.size; val cross = DoubleArray(n); val cnt = IntArray(r.w)
        val sLo = r.x0 * 4; val sHi = r.x1 * 4
        for (y in r.y0 until r.y1) {
            java.util.Arrays.fill(cnt, 0); var any = false
            for (j in 0 until 4) {
                val py = y + (j + 0.5) / 4
                var m = 0; var q = n - 1
                for (i in 0 until n) { if ((ys[i] > py) != (ys[q] > py)) cross[m++] = (xs[q] - xs[i]) * (py - ys[i]) / (ys[q] - ys[i]) + xs[i]; q = i }
                if (m < 2) continue
                java.util.Arrays.sort(cross, 0, m)
                var k = 0
                while (k + 1 < m) {   // inside where cross[k] <= px < cross[k + 1] (px < crossing counts it as still ahead)
                    val a = ceil(cross[k] * 4 - 0.5).toInt().coerceIn(sLo, sHi); val b = ceil(cross[k + 1] * 4 - 0.5).toInt().coerceIn(sLo, sHi) - 1
                    if (b >= a) { addRun(cnt, r.x0, a, b); any = true }
                    k += 2
                }
            }
            if (any) { val row = (y - r.y0) * r.w; for (i in 0 until r.w) out[row + i] = cov(cnt[i]).toByte() }
        }
        return out
    }
}

/** A selection is a full-canvas 8-bit plane plus its bounding rect. Shapes rasterise to coverage with 4x4 supersampling. */
class Selection(val w: Int, val h: Int, val plane: TilePlane = TilePlane(w, h)) {
    var bounds = IRect(0, 0, 0, 0); private set

    /** Sets the bounds after the plane was changed from outside (undo and redo restore tiles). */
    fun restoreBounds(r: IRect) { bounds = r }
    /** Scans the plane for its bounds (opening a saved selection). */
    fun recomputeBounds() { bounds = plane.nonZeroBounds() }

    private fun apply(op: SelOp, region: IRect, cover: ((Int, Int) -> Int)?, constant: Int = 255) {
        val clip = region.intersect(IRect(0, 0, w, h))
        // REPLACE clears everything outside the shape too.
        if (op == SelOp.REPLACE) { plane.transform(bounds) { _, _, _ -> 0 }; bounds = IRect(0, 0, 0, 0) }
        if (!clip.empty) {
            if (cover == null) {   // a constant shape (the rectangle): no per pixel call
                when (op) {
                    SelOp.REPLACE -> plane.transform(clip) { _, _, _ -> constant }
                    SelOp.ADD -> plane.transform(clip) { _, _, cur -> ByteAlgebra.combine(op, cur, constant) }
                    SelOp.SUBTRACT -> plane.transform(clip) { _, _, cur -> ByteAlgebra.combine(op, cur, constant) }
                    SelOp.INTERSECT -> plane.transform(clip) { _, _, cur -> ByteAlgebra.combine(op, cur, constant) }
                }
            } else {
                plane.transform(clip) { x, y, cur ->
                    val c = cover(x, y)
                    if (c == 0 && op != SelOp.INTERSECT) cur else ByteAlgebra.combine(op, cur, c)
                }
            }
        }
        if (op == SelOp.INTERSECT) { // everything outside the shape goes to 0
            val b = bounds
            if (clip.empty) plane.transform(b) { _, _, _ -> 0 }
            else {
                plane.transform(IRect(b.x0, b.y0, b.x1, minOf(b.y1, clip.y0))) { _, _, _ -> 0 }
                plane.transform(IRect(b.x0, maxOf(b.y0, clip.y1), b.x1, b.y1)) { _, _, _ -> 0 }
                plane.transform(IRect(b.x0, maxOf(b.y0, clip.y0), minOf(b.x1, clip.x0), minOf(b.y1, clip.y1))) { _, _, _ -> 0 }
                plane.transform(IRect(maxOf(b.x0, clip.x1), maxOf(b.y0, clip.y0), b.x1, minOf(b.y1, clip.y1))) { _, _, _ -> 0 }
            }
        }
        bounds = if (op == SelOp.REPLACE || op == SelOp.ADD) bounds.union(clip) else if (op == SelOp.INTERSECT) bounds.intersect(clip) else bounds
        recomputeBoundsIfShrunk(op)
    }
    private fun recomputeBoundsIfShrunk(op: SelOp) {
        if (op != SelOp.SUBTRACT && op != SelOp.INTERSECT) return
        bounds = plane.nonZeroBounds(bounds)
    }

    private fun applyBuffer(op: SelOp, region: IRect, buf: ByteArray) {
        val clip = region.intersect(IRect(0, 0, w, h))
        // the buffer was rasterised for [region] clipped to the canvas by the caller, so its row stride is clip.w
        apply(op, region, if (clip.empty) null else { x, y -> buf[(y - clip.y0) * clip.w + (x - clip.x0)].toInt() and 255 }, 0)
    }

    fun rect(op: SelOp, x0: Int, y0: Int, x1: Int, y1: Int) =
        apply(op, IRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)), null, 255) // integer rect: no AA

    fun ellipse(op: SelOp, x0: Double, y0: Double, x1: Double, y1: Double) {
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val rx = abs(x1 - x0) / 2; val ry = abs(y1 - y0) / 2
        if (rx <= 0 || ry <= 0) return apply(op, IRect(0, 0, 0, 0), null, 0)
        val region = IRect(floor(cx - rx).toInt(), floor(cy - ry).toInt(), ceil(cx + rx).toInt(), ceil(cy + ry).toInt())
        applyBuffer(op, region, Raster.ellipse(region.intersect(IRect(0, 0, w, h)), x0, y0, x1, y1))
    }

    /** Lasso: even-odd fill of a closed polygon (the last point joins the first). Fewer than 3 points selects nothing. */
    fun lasso(op: SelOp, xs: DoubleArray, ys: DoubleArray) {
        if (xs.size < 3) return apply(op, IRect(0, 0, 0, 0), null, 0)
        val region = IRect(floor(xs.min()).toInt(), floor(ys.min()).toInt(), ceil(xs.max()).toInt(), ceil(ys.max()).toInt())
        applyBuffer(op, region, Raster.lasso(region.intersect(IRect(0, 0, w, h)), xs, ys))
    }
    fun invert() { plane.transform(IRect(0, 0, w, h)) { _, _, v -> ByteAlgebra.invert(v) }; bounds = plane.nonZeroBounds() }
    fun selectAll() { rect(SelOp.REPLACE, 0, 0, w, h) }
    fun clear() { apply(SelOp.REPLACE, IRect(0, 0, 0, 0), null, 0) }
    fun isEmpty() = bounds.empty
    /** Selection clip for painting: brush alpha is multiplied by this plane (255 everywhere when there is no selection). */
    fun clipAlpha(x: Int, y: Int, brushAlpha255: Int) = if (isEmpty()) brushAlpha255 else ByteAlgebra.applyMask(brushAlpha255, plane[x, y])
}
