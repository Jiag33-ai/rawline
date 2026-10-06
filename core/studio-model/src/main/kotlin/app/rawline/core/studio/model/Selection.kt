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

/** Tiles of one 8-bit plane (mask or selection), write-through to a directory with .part then rename. Sparse: missing = 0. */
class TilePlane(val w: Int, val h: Int, private val dir: File? = null, private val cacheTiles: Int = 64) {
    private val cache = object : LinkedHashMap<TileKey, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<TileKey, ByteArray>) = dir != null && size > cacheTiles && !dirty.contains(e.key)
    }
    private val dirty = HashSet<TileKey>()
    val cached get() = cache.size

    private fun load(k: TileKey): ByteArray? {
        cache[k]?.let { return it }
        val f = dir?.let { File(it, TileGrid.name(k)) }
        if (f == null || !f.exists()) return null
        val r = TileGrid.rectOf(k, w, h)
        return TileCodec.decode(f.readBytes(), r.w * r.h).also { cache[k] = it }
    }
    operator fun get(x: Int, y: Int): Int {
        if (x !in 0 until w || y !in 0 until h) return 0
        val k = TileKey(x / TILE, y / TILE); val t = load(k) ?: return 0
        val r = TileGrid.rectOf(k, w, h); return t[(y - r.y0) * r.w + (x - r.x0)].toInt() and 255
    }
    operator fun set(x: Int, y: Int, v: Int) {
        if (x !in 0 until w || y !in 0 until h) return
        val k = TileKey(x / TILE, y / TILE); val r = TileGrid.rectOf(k, w, h)
        val t = load(k) ?: ByteArray(r.w * r.h).also { cache[k] = it }
        t[(y - r.y0) * r.w + (x - r.x0)] = v.toByte(); dirty.add(k)
    }
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

    /** For a plane with no directory: the tiles changed since the last call, raw bytes (a copy), or null for a tile that became all zero. Clears the dirty set. */
    fun takeDirty(): Map<TileKey, ByteArray?> {
        val out = LinkedHashMap<TileKey, ByteArray?>()
        for (k in dirty.toList().sortedWith(compareBy({ it.ty }, { it.tx }))) { val t = cache[k]; out[k] = if (t == null || t.all { it == 0.toByte() }) null else t.copyOf() }
        dirty.clear(); return out
    }

    /** Raw bytes of every non-zero tile held in memory (a plane with no directory holds all of them). */
    fun allTiles(): Map<TileKey, ByteArray> = cache.filterValues { t -> t.any { it != 0.toByte() } }.mapValues { it.value.copyOf() }

    fun toBytes(): ByteArray { val o = ByteArray(w * h); for (y in 0 until h) for (x in 0 until w) o[y * w + x] = get(x, y).toByte(); return o }
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

/** A selection is a full-canvas 8-bit plane plus its bounding rect. Shapes rasterise to coverage with 4x4 supersampling. */
class Selection(val w: Int, val h: Int, val plane: TilePlane = TilePlane(w, h)) {
    var bounds = IRect(0, 0, 0, 0); private set

    private fun apply(op: SelOp, region: IRect, cover: (Int, Int) -> Int) {
        val clip = region.intersect(IRect(0, 0, w, h))
        // REPLACE clears everything outside the shape too.
        if (op == SelOp.REPLACE) for (y in bounds.y0 until bounds.y1) for (x in bounds.x0 until bounds.x1) plane[x, y] = 0
        if (op == SelOp.REPLACE) bounds = IRect(0, 0, 0, 0)
        for (y in clip.y0 until clip.y1) for (x in clip.x0 until clip.x1) {
            val c = cover(x, y)
            if (c == 0 && op != SelOp.INTERSECT) continue
            plane[x, y] = ByteAlgebra.combine(op, plane[x, y], c)
        }
        if (op == SelOp.INTERSECT) { // everything outside the shape goes to 0
            for (y in bounds.y0 until bounds.y1) for (x in bounds.x0 until bounds.x1)
                if (x < clip.x0 || x >= clip.x1 || y < clip.y0 || y >= clip.y1) plane[x, y] = 0
        }
        bounds = if (op == SelOp.REPLACE || op == SelOp.ADD) bounds.union(clip) else if (op == SelOp.INTERSECT) bounds.intersect(clip) else bounds
        recomputeBoundsIfShrunk(op)
    }
    private fun recomputeBoundsIfShrunk(op: SelOp) {
        if (op != SelOp.SUBTRACT && op != SelOp.INTERSECT) return
        var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
        for (y in bounds.y0 until bounds.y1) for (x in bounds.x0 until bounds.x1) if (plane[x, y] != 0) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x + 1); y1 = max(y1, y + 1)
        }
        bounds = if (x1 <= x0) IRect(0, 0, 0, 0) else IRect(x0, y0, x1, y1)
    }

    private inline fun ss(x: Int, y: Int, inside: (Double, Double) -> Boolean): Int {
        var n = 0
        for (j in 0 until 4) for (i in 0 until 4) if (inside(x + (i + 0.5) / 4, y + (j + 0.5) / 4)) n++
        return (n * 255 + 8) / 16
    }

    fun rect(op: SelOp, x0: Int, y0: Int, x1: Int, y1: Int) =
        apply(op, IRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))) { _, _ -> 255 } // integer rect: no AA

    fun ellipse(op: SelOp, x0: Double, y0: Double, x1: Double, y1: Double) {
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val rx = abs(x1 - x0) / 2; val ry = abs(y1 - y0) / 2
        if (rx <= 0 || ry <= 0) return apply(op, IRect(0, 0, 0, 0)) { _, _ -> 0 }
        apply(op, IRect(floor(cx - rx).toInt(), floor(cy - ry).toInt(), ceil(cx + rx).toInt(), ceil(cy + ry).toInt())) { x, y ->
            ss(x, y) { px, py -> ((px - cx) / rx).pow(2) + ((py - cy) / ry).pow(2) <= 1.0 }
        }
    }

    /** Lasso: even-odd fill of a closed polygon (the last point joins the first). Fewer than 3 points selects nothing. */
    fun lasso(op: SelOp, xs: DoubleArray, ys: DoubleArray) {
        if (xs.size < 3) return apply(op, IRect(0, 0, 0, 0)) { _, _ -> 0 }
        val r = IRect(floor(xs.min()).toInt(), floor(ys.min()).toInt(), ceil(xs.max()).toInt(), ceil(ys.max()).toInt())
        apply(op, r) { x, y -> ss(x, y) { px, py -> evenOdd(xs, ys, px, py) } }
    }
    private fun evenOdd(xs: DoubleArray, ys: DoubleArray, px: Double, py: Double): Boolean {
        var inside = false; var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside
            j = i
        }
        return inside
    }
    fun invert() { for (y in 0 until h) for (x in 0 until w) plane[x, y] = ByteAlgebra.invert(plane[x, y]); bounds = IRect(0, 0, w, h); recomputeBoundsIfShrunk(SelOp.SUBTRACT) }
    fun selectAll() { rect(SelOp.REPLACE, 0, 0, w, h) }
    fun clear() { apply(SelOp.REPLACE, IRect(0, 0, 0, 0)) { _, _ -> 0 } }
    fun isEmpty() = bounds.empty
    /** Selection clip for painting: brush alpha is multiplied by this plane (255 everywhere when there is no selection). */
    fun clipAlpha(x: Int, y: Int, brushAlpha255: Int) = if (isEmpty()) brushAlpha255 else ByteAlgebra.applyMask(brushAlpha255, plane[x, y])
}
