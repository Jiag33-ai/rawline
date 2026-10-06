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
