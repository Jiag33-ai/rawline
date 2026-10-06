# W26 Studio S2: tiles, selections (rect / ellipse / lasso), layer masks, Open in Studio

Status at writing: main d6c5afc (S1a, S1b host and GPU brush merged; S1c not started). Depends on S1c for the Studio home screen and `Fs`. Spec: docs/STUDIO_SPEC.md sections 2.1, 2.2 (selection), 2.15, 2.17, 3.7. The Kotlin in section 8 compiled (Kotlin 2.4.10, together with the real `Tiles.kt` from main) and passes 11 host JUnit tests. NOT compiled or run: the GLSL mask pass, the Android UI, the Room/JSON schema changes, the hand-off. Those are written as instructions with exact acceptance, and the worker must run `tools/golden/run-golden.sh` for the shader.

## 1. Scope
In: (A) tiles as the storage unit with a sparse on-disk plane, LRU and kill safety; (B) selection tools rectangle, ellipse, lasso with Replace / Add / Subtract / Intersect, Invert, Select All, Deselect, selection clips brush and fill; (C) layer masks (add, paint, invert, disable, delete) applied in the compositor; (D) schema v2 and migration; (E) canvas cap lift from 12 MP to 100 MP total, 12000 px edge; (F) "Open in Studio" hand-off from Develop.
Out (later): feather, expand/contract, refine edge, magic wand, saved channels, AI selections, tile pixel layers (S2 tiles the masks and the selection first; pixel layers keep the S1 container until step 6 below).

## 2. Decisions (no questions left)
- D1 Tile edge is `Tiles.SIZE` (256). `TILE` in the new file aliases it. One definition.
- D2 Masks and the selection are 8-bit planes stored as `TilePlane`: a tile is `0x00 value` (constant) or `0x01 deflate bytes`. Absent tile means zero. Pixel layers keep the S1 `PixelContainer` until S2 step 6, which converts layer pixels to RGBA8 tiles through the same `TileCodec` with 4 channels (`TileCodec` already takes any byte length). WebP is revisited only on a device test (PM decision D1 earlier): measure decode ms per tile on the S24U with `BitmapFactory` against deflate and record it in docs/PERF.md; switch only if deflate is slower than 3 ms per 256 KB tile or the project is over twice the size.
- D3 Selection semantic: a mask of the full canvas (not the layer). Empty selection (bounds empty) means "no selection", which clips nothing. A selection of zero coverage is therefore the same as none (matches the spec rule "no selection = whole layer").
- D4 Rectangles are integer and have no anti-aliasing; ellipse and lasso use 4x4 supersampling, coverage `(n*255+8)/16`.
- D5 Byte algebra is exact and tested (table in `ByteAlgebra`): ADD = a+b-round(ab/255), SUBTRACT = round(a(255-b)/255), INTERSECT = round(ab/255), REPLACE = b. The same formulas go in `mask.frag` for GPU combines; a golden test checks CPU vs GPU within 1 level.
- D6 Layer mask rules: mask lives in the layer's own coordinate space and scales with the layer. Compositor: `as' = as * opacity * m` (spec 2.1). Mask value 255 everywhere = no effect, byte-identical to no mask (assert in a golden: a layer with an all-255 mask renders identically to the same layer without one).
- D7 Painting on a layer with a mask paints pixels by default; a toggle "paint mask" on the layer row switches the brush target to the mask (the brush writes alpha with the same stamp code but into the R8 plane; black = hide, white = reveal). Selection clip multiplies the brush alpha first (`Selection.clipAlpha`).
- D8 Undo of selection and mask edits stores the dirty tile list with before and after tile bytes (same history entry kind as pixel deltas). Selection changes are history entries.
- D9 Hand-off (3.7): Develop's overflow item "Open in Studio" (guarded by `STUDIO_ENABLED`) calls `StudioHandOff.create(photoKey, recipeJson)` which: creates a new project, copies the source into the project dir (hashed immutable file), stores the recipe JSON copy, renders through the Develop pipeline at preview scale first and full scale after, as a pixel layer in S2 (smart object arrives in S4: the layer is flagged `origin = "develop"` in JSON only), then switches mode. It writes only under `files/studio/`. Nothing flows back. A RAW that is `PREVIEW_ONLY` (see W15 DNG probe) hands off its embedded preview and the layer is named "Preview only".
- D10 Cap: `Document.MAX_PIXELS_S1` becomes `MAX_PIXELS = 100_000_000` and `MAX_EDGE = 12000` stays; the memory guard is the tile cache (LRU, budget 1.2 GB GPU in the spec; start with 600 MB and raise only on a Copy report). Until step 6 lands, layers over 12 MP still use the container and are refused at the old cap with the existing message.

## 3. Schema v2 (project.json) and migration
Add per layer an optional `mask` object: `{ "dir": "mask/<layerId>", "enabled": true, "inverted": false, "linked": true }` (absent = no mask). Add optional top-level `selection`: `{ "dir": "sel" }` (absent = no selection; the selection is saved so a crash does not lose it). Tile directories sit beside project.json: `mask/<layerId>/t_<tx>_<ty>`, `sel/t_<tx>_<ty>`. Tile files are written `.part` then renamed (already in `TilePlane.flush`).
- `SCHEMA_VERSION = 2`. `Migrations.v1ToV2(json)`: set `schemaVersion = 2` and change nothing else (v1 files have no masks). Test over a stored v1 sample in `core/studio-model/src/test/resources` (take the one S1b already stores). A v1 reader opening a v2 file already opens read-only via `NewerSchemaException`, which is the intended behaviour.
- ProjectStore.gc: a tile directory not referenced by the JSON is deleted only after the JSON that stops referencing it is committed (same rule as hashed files). Tests: kill at every op for "paint mask stroke", "add selection", "delete mask" using the existing kill harness; open must always succeed and lose at most the last uncommitted edit.

## 4. Order of work (each step ends green: `./gradlew :core:studio-model:test`, golden, CI)
1. Add `Selection.kt` and `SelectionTest.kt` from section 8 unchanged. Run. (30 minutes.)
2. Schema v2 + migration + sample file + kill tests (section 3).
3. `mask.frag` / compositor change: sampler `uMask` (R8, bound only when the layer has an enabled mask), uniform `uHasMask`, `uMaskInvert`; result alpha multiplied by `m` (or `1-m`). Extend `ReferenceCompositor.kt` to match, then add goldens: `studio_mask_half` (a left-half-white mask), `studio_mask_gradient` (0..255 ramp), `studio_mask_all255_identity`, `studio_mask_inverted`, `studio_mask_disabled`. Tolerance 1 level on Mesa. Mutation test: flip the invert flag in the shader and confirm the golden fails (do not commit the mutation).
4. Selection engine in the GL layer: upload `Selection.plane` as R8 texture over the canvas (dirty tiles only), brush and fill shaders multiply by it. Golden: `studio_sel_clip` (stroke across an ellipse selection, outside untouched).
5. UI (Compose): tool tray entries Rectangle, Ellipse, Lasso; segmented Replace / Add / Subtract / Intersect above the tray (persistent, no keyboard modifiers); Invert, Select All, Deselect in the Select menu; marching ants (first cut: a 1 px outline drawn from `bounds` for rect and a contour of the 1/4 resolution threshold for the rest; animated dash); layer row gets a mask thumbnail, tap toggles enabled, long press menu: Add mask (white / black / from selection), Invert, Delete, Paint mask. Touch targets 48 dp. Strings in Australian English.
6. Pixel layers to RGBA8 tiles and the 100 MP cap (separate PR, device-tested, see D2 and D10).
7. Hand-off (D9) with a test that the Develop recipe file and catalogue are byte-identical before and after (the spec rule that Develop is untouched); flag off build must still pass the unchanged Develop goldens.

## 5. Acceptance
Host: all 11 selection tests, schema migration, kill tests. GPU (llvmpipe): the five mask goldens, `studio_sel_clip`, existing S1 goldens unchanged. Phone (Copy report required for any speed claim): rect select on a 24 MP canvas under 150 ms to update the outline (spec 2.2 target, unmeasured), lasso of 200 points under 150 ms, mask paint stroke frame time p95 recorded, tile decode ms recorded (D2).
Honesty block for the PR text: say plainly which of these were run (host, golden) and which only on paper.

## 6. Risks
- Lasso rasterisation is O(bbox area x 16 x vertices) on the CPU as written; for a 24 MP full-canvas lasso with 200 points this is far too slow. Mitigation inside the file: it is the reference. The shipped path rasterises on the GPU (triangle fan with stencil even-odd into the R8 plane) and the CPU version is the golden reference at test sizes only. Do not call the CPU lasso on large canvases.
- `Selection.apply` REPLACE walks the old bounds; fine for host, replace by a GPU clear in step 4.
- Memory: a full 100 MP mask is 100 MB; sparse tiles keep typical masks small, but `invert()` makes every tile non-zero. For the GPU path invert is a uniform flag (`uMaskInvert`) rather than rewriting tiles; the CPU `invert()` is for tests.
- Hand-off of a 45 MP RW2 at full scale as RGBA16F is 360 MB for one layer: render at preview scale first, then tiles; never hold the whole frame (D10 budget).

## 7. Where the files go
`core/studio-model/src/main/kotlin/app/rawline/core/studio/model/Selection.kt`, test in `.../src/test/kotlin/.../SelectionTest.kt`. No new dependencies (java.util.zip only).

## 8. Embedded source (verified: compiled with the real Tiles.kt, 11 tests pass)
### Selection.kt
```kotlin
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
        override fun removeEldestEntry(e: MutableMap.MutableEntry<TileKey, ByteArray>) = size > cacheTiles && !dirty.contains(e.key)
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
```
### SelectionTest.kt
```kotlin
package app.rawline.core.studio.model

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SelectionTest {
    private fun tmp() = Files.createTempDirectory("s2").toFile()

    @Test fun gridKeysAndEdgeTiles() {
        assertEquals(listOf(TileKey(0, 0)), TileGrid.keysFor(IRect(0, 0, 256, 256), 1000, 1000))
        assertEquals(4, TileGrid.keysFor(IRect(255, 255, 257, 257), 1000, 1000).size)
        assertEquals(emptyList<TileKey>(), TileGrid.keysFor(IRect(2000, 0, 2100, 5), 1000, 1000))
        assertEquals(4, TileGrid.cols(1000)); assertEquals(IRect(768, 768, 1000, 1000), TileGrid.rectOf(TileKey(3, 3), 1000, 1000))
        assertEquals(1, TileGrid.cols(256)); assertEquals(2, TileGrid.cols(257))
    }
    @Test fun codecRoundTrip() {
        val c = ByteArray(1000) { 7 }; val e = TileCodec.encode(c); assertEquals(2, e.size); assertArrayEquals(c, TileCodec.decode(e, 1000))
        val r = java.util.Random(1); val n = ByteArray(5000).also { r.nextBytes(it) }; assertArrayEquals(n, TileCodec.decode(TileCodec.encode(n), 5000))
        val g = ByteArray(65536) { (it / 256).toByte() }; assertTrue(TileCodec.encode(g).size < 3000); assertArrayEquals(g, TileCodec.decode(TileCodec.encode(g), 65536))
        try { TileCodec.decode(TileCodec.encode(n).copyOf(100), 5000); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun planePersistsSparseAndDropsZeroTiles() {
        val d = tmp(); val p = TilePlane(700, 500, d)
        p[10, 10] = 200; p[699, 499] = 9; p[300, 300] = 5; p[300, 300] = 0; p.flush()
        assertEquals(setOf("t_0_0", "t_2_1"), d.list()!!.toSet())          // t_1_1 became all zero so it is not stored
        val q = TilePlane(700, 500, d); assertEquals(200, q[10, 10]); assertEquals(9, q[699, 499]); assertEquals(0, q[300, 300]); assertEquals(0, q[-1, 0])
    }
    @Test fun lruKeepsDirtyTiles() {
        val d = tmp(); val p = TilePlane(256 * 8, 256, d, cacheTiles = 2)
        for (t in 0 until 8) p[t * 256, 0] = 100 + t
        assertEquals(8, p.cached)                 // dirty tiles are never evicted before flush
        p.flush(); assertTrue(p.cached <= 2)
        for (t in 0 until 8) assertEquals(100 + t, p[t * 256, 0])
    }
    @Test fun killBeforeRenameLeavesOldTile() {
        val d = tmp(); val p = TilePlane(256, 256, d); p[1, 1] = 50; p.flush()
        File(d, "t_0_0.part").writeBytes(byteArrayOf(1, 2, 3))   // simulated kill mid-write
        assertEquals(50, TilePlane(256, 256, d)[1, 1])
    }
    @Test fun algebraTable() {
        val c = ByteAlgebra
        assertEquals(255, c.combine(SelOp.ADD, 255, 77)); assertEquals(77, c.combine(SelOp.ADD, 0, 77)); assertEquals(192, c.combine(SelOp.ADD, 128, 128))
        assertEquals(0, c.combine(SelOp.SUBTRACT, 255, 255)); assertEquals(128, c.combine(SelOp.SUBTRACT, 255, 127)); assertEquals(100, c.combine(SelOp.SUBTRACT, 100, 0))
        assertEquals(64, c.combine(SelOp.INTERSECT, 128, 128)); assertEquals(0, c.combine(SelOp.INTERSECT, 255, 0)); assertEquals(33, c.combine(SelOp.INTERSECT, 255, 33))
        assertEquals(10, c.combine(SelOp.REPLACE, 200, 10)); assertEquals(0, c.invert(255)); assertEquals(128, c.applyMask(255, 128)); assertEquals(0, c.applyMask(255, 0))
        for (a in 0..255) for (b in 0..255) for (op in SelOp.values()) assertTrue(c.combine(op, a, b) in 0..255)
        for (a in 0..255) { assertEquals(a, c.combine(SelOp.ADD, a, 0)); assertEquals(a, c.combine(SelOp.INTERSECT, a, 255)); assertEquals(a, c.combine(SelOp.SUBTRACT, a, 0)) }
    }
    @Test fun rectOps() {
        val s = Selection(100, 100); s.rect(SelOp.REPLACE, 10, 10, 50, 50)
        assertEquals(IRect(10, 10, 50, 50), s.bounds); assertEquals(255, s.plane[10, 10]); assertEquals(0, s.plane[50, 50])
        s.rect(SelOp.ADD, 40, 40, 70, 70); assertEquals(IRect(10, 10, 70, 70), s.bounds); assertEquals(255, s.plane[69, 69])
        s.rect(SelOp.SUBTRACT, 0, 0, 100, 30); assertEquals(IRect(10, 30, 70, 70), s.bounds); assertEquals(0, s.plane[20, 20])
        s.rect(SelOp.INTERSECT, 45, 45, 200, 200); assertEquals(IRect(45, 45, 70, 70), s.bounds); assertEquals(0, s.plane[44, 50]); assertEquals(255, s.plane[45, 45])
        s.rect(SelOp.REPLACE, 0, 0, 5, 5); assertEquals(IRect(0, 0, 5, 5), s.bounds); assertEquals(0, s.plane[50, 50])
        s.rect(SelOp.REPLACE, 90, 90, 500, 500); assertEquals(IRect(90, 90, 100, 100), s.bounds)  // clipped to canvas
        s.rect(SelOp.SUBTRACT, 0, 0, 100, 100); assertTrue(s.isEmpty())
        s.rect(SelOp.REPLACE, 8, 8, 2, 2); assertEquals(IRect(2, 2, 8, 8), s.bounds)                // reversed corners
    }
    @Test fun ellipseCoverageAndArea() {
        val s = Selection(200, 200); s.ellipse(SelOp.REPLACE, 20.0, 20.0, 180.0, 120.0)
        var sum = 0L; for (y in 0 until 200) for (x in 0 until 200) sum += s.plane[x, y]
        val exact = Math.PI * 80 * 50; assertEquals(exact, sum / 255.0, exact * 0.003)
        assertEquals(255, s.plane[100, 70]); assertEquals(0, s.plane[21, 21]); assertEquals(0, s.plane[100, 125])
        assertTrue(s.plane[20, 70] in 1..254 || s.plane[20, 70] == 255)   // edge pixel is partial or full, never out of range
        val edge = (0 until 200).count { s.plane[it, 70] in 1..254 }; assertTrue("edge=$edge", edge in 0..6); assertTrue((0 until 200).count { s.plane[it, 45] in 1..254 } in 2..8)
    }
    @Test fun lassoTriangleSquareAndSelfIntersect() {
        val s = Selection(100, 100)
        s.lasso(SelOp.REPLACE, doubleArrayOf(10.0, 90.0, 10.0), doubleArrayOf(10.0, 10.0, 90.0))
        var sum = 0L; for (y in 0 until 100) for (x in 0 until 100) sum += s.plane[x, y]; assertEquals(3200.0, sum / 255.0, 20.0)
        s.lasso(SelOp.REPLACE, doubleArrayOf(10.0, 50.0, 50.0, 10.0), doubleArrayOf(10.0, 10.0, 50.0, 50.0))
        assertEquals(255, s.plane[30, 30]); assertEquals(IRect(10, 10, 50, 50), s.bounds)
        // bow-tie: even-odd leaves the crossing wedge filled and both lobes filled
        s.lasso(SelOp.REPLACE, doubleArrayOf(0.0, 60.0, 0.0, 60.0), doubleArrayOf(0.0, 0.0, 60.0, 60.0))
        assertEquals(255, s.plane[30, 5]); assertEquals(255, s.plane[30, 55]); assertEquals(0, s.plane[5, 30]); assertEquals(0, s.plane[55, 30])
        s.lasso(SelOp.REPLACE, doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0)); assertTrue(s.isEmpty())
    }
    @Test fun invertAndClip() {
        val s = Selection(10, 10); assertEquals(200, s.clipAlpha(3, 3, 200))           // no selection: no clip
        s.rect(SelOp.REPLACE, 0, 0, 5, 10); assertEquals(200, s.clipAlpha(2, 2, 200)); assertEquals(0, s.clipAlpha(7, 2, 200))
        s.invert(); assertEquals(0, s.clipAlpha(2, 2, 200)); assertEquals(200, s.clipAlpha(7, 2, 200)); assertEquals(IRect(5, 0, 10, 10), s.bounds)
        s.selectAll(); s.clear(); assertTrue(s.isEmpty()); assertEquals(0, s.plane[3, 3])
    }
    @Test fun selectionOnDiskTiles() {
        val d = tmp(); val s = Selection(600, 600, TilePlane(600, 600, d)); s.rect(SelOp.REPLACE, 100, 100, 400, 400); s.plane.flush()
        assertEquals(4, d.list()!!.size)                                      // tiles (0,0)(1,0)(0,1)(1,1); each fully mixed
        val q = TilePlane(600, 600, d); assertEquals(255, q[399, 399]); assertEquals(0, q[400, 400]); assertEquals(0, q[99, 150])
    }
}
```
