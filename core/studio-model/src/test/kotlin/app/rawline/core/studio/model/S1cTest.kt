package app.rawline.core.studio.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater

class ModeStateTest {
    @Test fun withTheFlagOffStudioDoesNotExist() {
        val kv = MapKeyValue(); kv.putString(ModeState.KEY, "studio")
        val m = ModeState(false, kv)
        assertEquals(AppMode.DEVELOP, m.startMode()); assertFalse(m.switchVisible(true)); assertEquals(AppMode.DEVELOP, m.switchTo(AppMode.STUDIO))
        assertEquals("studio", kv.getString(ModeState.KEY))   // the stored choice is left alone, so turning the flag on later restores it
    }

    @Test fun theSwitchShowsOnlyOnHomeScreens() {
        val m = ModeState(true, MapKeyValue())
        assertTrue(m.switchVisible(true)); assertFalse(m.switchVisible(false))
    }

    @Test fun theChosenModeIsRemembered() {
        val kv = MapKeyValue()
        ModeState(true, kv).switchTo(AppMode.STUDIO)
        assertEquals(AppMode.STUDIO, ModeState(true, kv).startMode())
        ModeState(true, kv).switchTo(AppMode.DEVELOP)
        assertEquals(AppMode.DEVELOP, ModeState(true, kv).startMode())
    }

    @Test fun twoStartsThatNeverReachTheFirstFrameFallBackToDevelop() {
        val kv = MapKeyValue(); ModeState(true, kv).switchTo(AppMode.STUDIO)   // 1st start attempt counted by the switch
        assertEquals(AppMode.STUDIO, ModeState(true, kv).startMode())           // process died before studioReady: 2nd attempt
        val third = ModeState(true, kv)
        assertEquals(AppMode.DEVELOP, third.startMode())
        assertTrue(third.notice!!.contains("did not start twice"))
        assertEquals(AppMode.DEVELOP, ModeState(true, kv).startMode())          // and stays in Develop until the user switches again
    }

    @Test fun reachingTheFirstFrameResetsTheCount() {
        val kv = MapKeyValue(); ModeState(true, kv).switchTo(AppMode.STUDIO)
        repeat(5) { val m = ModeState(true, kv); assertEquals(AppMode.STUDIO, m.startMode()); m.studioReady() }
    }
}

class CatalogTest {
    private val root = ProjectCatalog.ROOT
    private fun px(seed: Int) = RawPixels(8, 6, ByteArray(8 * 6 * 4).also { Random(seed.toLong()).nextBytes(it) })
    private fun make(fs: Fs, id: String, name: String, modified: Long) {
        val d = Document(id, name, 8, 6, layers = listOf(Layer.Pixel(LayerCommon("l1", "L1"), 8, 6), Layer.Pixel(LayerCommon("l2", "L2"), 8, 6)), modified = modified)
        ProjectStore(fs, "$root/$id").save(d, { px(it.common.id.hashCode()) }, setOf("l1", "l2"))
    }

    @Test fun scanListsNewestFirstWithSizeAndLayerCount() {
        val fs = MemFs(); make(fs, "a", "Old", 10); make(fs, "b", "New", 20)
        val r = ProjectCatalog.scan(fs)
        assertEquals(listOf("b", "a"), r.rows.map { it.id }); assertEquals("New", r.rows[0].name)
        assertEquals(2, r.rows[0].layerCount); assertTrue(r.rows[0].sizeBytes > 0); assertTrue(r.damaged.isEmpty())
    }

    @Test fun aDamagedOrNewerProjectIsReportedAndTheRestStillList() {
        val fs = MemFs(); make(fs, "a", "Good", 10); make(fs, "bad", "Bad", 5); make(fs, "newer", "Newer", 6)
        fs.files.keys.filter { it.startsWith("$root/bad/") }.forEach { fs.files[it] = "x".toByteArray() }
        fs.files["$root/newer/project.json"] = String(fs.files["$root/newer/project.json"]!!).replace("\"schemaVersion\": 2", "\"schemaVersion\": 3").toByteArray()
        val r = ProjectCatalog.scan(fs)
        assertEquals(listOf("a"), r.rows.map { it.id }); assertEquals(setOf("bad", "newer"), r.damaged.toSet())
    }

    @Test fun duplicateIsAnIndependentCopyWithTheSamePixels() {
        val fs = MemFs(); make(fs, "a", "Holiday", 10)
        val row = ProjectCatalog.duplicate(fs, root, "a", "b", 99)
        assertEquals("Holiday copy", row.name); assertEquals(99L, row.modified); assertEquals(2, row.layerCount)
        val orig = ProjectStore(fs, "$root/a"); val copy = ProjectStore(fs, "$root/b")
        val dOrig = orig.open().document; val dCopy = copy.open().document
        assertEquals("b", dCopy.id)
        for (i in dOrig.layers.indices) assertArrayEquals(orig.load(dOrig.layers[i] as Layer.Pixel)!!.rgba, copy.load(dCopy.layers[i] as Layer.Pixel)!!.rgba)
        ProjectCatalog.delete(fs, root, "a")
        assertEquals(listOf("b"), ProjectCatalog.scan(fs).rows.map { it.id })                           // deleting the original leaves the copy intact
        copy.load(copy.open().document.layers[0] as Layer.Pixel)
    }

    @Test fun renameKeepsPixelsAndRefusesAnEmptyName() {
        val fs = MemFs(); make(fs, "a", "Old", 10)
        ProjectCatalog.rename(fs, root, "a", "  New name  ", 50)
        assertEquals("New name", ProjectCatalog.scan(fs).rows.single().name)
        ProjectCatalog.rename(fs, root, "a", "   ", 60)
        assertEquals("New name", ProjectCatalog.scan(fs).rows.single().name)
        val s = ProjectStore(fs, "$root/a"); s.load(s.open().document.layers[0] as Layer.Pixel)
    }

    @Test fun deleteRemovesTheWholeDirectoryAndRefusesBadIds() {
        val fs = MemFs(); make(fs, "a", "A", 1); make(fs, "b", "B", 2)
        ProjectCatalog.delete(fs, root, "a")
        assertTrue(fs.files.keys.none { it.startsWith("$root/a/") }); assertTrue(fs.files.keys.any { it.startsWith("$root/b/") })
        for (bad in listOf("", "../x", "a/b")) try { ProjectCatalog.delete(fs, root, bad); org.junit.Assert.fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun idsAreDirectoryNamesAndDifferByTimeAndChance() {
        val a = ProjectCatalog.newId(1_759_700_000_000, Random(1)); val b = ProjectCatalog.newId(1_759_700_000_000, Random(2))
        assertTrue(a.matches(Regex("p[0-9a-z]+"))); assertNotEquals(a, b)
        assertTrue(ProjectCatalog.newId(1_759_700_000_001, Random(1)) > a.substring(0, 1))
    }
}

class NewProjectTest {
    @Test fun smallCanvasesAreUntouchedAndBigOnesFitTheCaps() {
        assertEquals(1080 to 1350, NewProject.fit(1080, 1350))
        assertEquals(4000 to 3000, NewProject.fit(4000, 3000))                           // exactly 12 MP
        val (w, h) = NewProject.fit(6000, 4000)                                           // 24 MP: scaled to fit 12 MP, shape kept
        assertTrue(w.toLong() * h <= 12_000_000L); assertEquals(1.5, w.toDouble() / h, 0.01)
        val (pw, ph) = NewProject.fit(30000, 100)                                         // a panorama: the edge cap decides
        assertEquals(8192, pw); assertTrue(ph >= 1)
    }

    @Test fun presetsAllFit() { for (p in NewProject.presets) assertEquals(p.width to p.height, NewProject.fit(p.width, p.height)) }

    @Test fun blankHasOneEmptyLayerAndTheRightSize() {
        val d = NewProject.blank("p1", "Untitled", 6000, 4000, 5)
        assertEquals(1, d.layers.size); assertNull((d.layers[0] as Layer.Pixel).pixelsFile)
        assertTrue(d.width.toLong() * d.height <= Document.MAX_PIXELS_S1); assertEquals(d.width, (d.layers[0] as Layer.Pixel).width)
    }
}

class FlattenTest {
    private fun scene(w: Int, h: Int): List<RefLayer> {
        val rnd = Random(8)
        fun img(iw: Int, ih: Int, alpha: Boolean) = RefImage(iw, ih, ByteArray(iw * ih * 4).also { rnd.nextBytes(it); if (!alpha) for (i in 3 until it.size step 4) it[i] = 255.toByte() })
        return listOf(RefLayer(img(w, h, false), 0f, 0f, 1f, 1f, BlendMode.NORMAL), RefLayer(img(w / 2, h / 2, true), 5f, 7f, 1.5f, 0.8f, BlendMode.MULTIPLY), RefLayer(img(20, 20, true), 30f, 2f, 1f, 0.6f, BlendMode.SCREEN))
    }

    @Test fun stripsCoverTheCanvasOnceAndStayUnderTheStripCap() {
        for ((w, h) in listOf(1080 to 1350, 8192 to 1464, 4000 to 3000, 100 to 100000)) {
            val s = Flatten.strips(w, h)
            assertEquals(0, s.first()[0]); assertEquals(h, s.last()[0] + s.last()[1]); assertEquals(h, s.sumOf { it[1] })
            for (i in 1 until s.size) assertEquals(s[i - 1][0] + s[i - 1][1], s[i][0])
            assertTrue(s.all { w.toLong() * it[1] <= maxOf(Flatten.MAX_STRIP_PIXELS.toLong(), w.toLong() * 16) })
        }
    }

    @Test fun stripsAssembleToExactlyTheWholeRender() {
        val w = 90; val h = 70; val layers = scene(w, h)
        val whole = ReferenceCompositor.render(layers, 0f, 0f, 1f, w, h)
        val out = ByteArray(w * h * 4)
        Flatten.run(w, h, { y, rows -> ReferenceCompositor.render(layers, 0f, y.toFloat(), 1f, w, rows) }, { y, rows, px -> System.arraycopy(px, 0, out, y * w * 4, rows * w * 4) }, stripRows = 13)
        assertArrayEquals(whole, out)
    }

    @Test fun cancelStopsBetweenStrips() {
        var n = 0
        val done = Flatten.run(10, 100, { _, rows -> ByteArray(10 * rows * 4) }, { _, _, _ -> n++ }, cancelled = { n >= 2 }, stripRows = 10)
        assertFalse(done); assertEquals(2, n)
    }

    @Test fun matteOverWhiteMixesAndMakesOpaque() {
        val px = byteArrayOf(0, 0, 0, 0, 10, 20, 30, 255.toByte(), 100, 100, 100, 128.toByte())
        Flatten.matteOverWhite(px)
        assertEquals(listOf(255, 255, 255, 255), (0..3).map { px[it].toInt() and 255 })      // fully transparent: white
        assertEquals(listOf(10, 20, 30, 255), (4..7).map { px[it].toInt() and 255 })         // opaque: unchanged
        assertEquals(listOf(177, 177, 177, 255), (8..11).map { px[it].toInt() and 255 })     // (100 * 128 + 255 * 127) / 255 = 177
    }
}

class PngWriterTest {
    /** A small independent decoder: signature, every chunk CRC, IHDR, IDAT inflated and un-filtered. */
    private fun decode(png: ByteArray): Triple<Int, Int, ByteArray> {
        val b = java.nio.ByteBuffer.wrap(png)
        val sig = ByteArray(8); b.get(sig)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), sig)
        var w = 0; var h = 0; val idat = ByteArrayOutputStream(); var ended = false; var sawIccp = false
        while (b.remaining() > 0) {
            val len = b.getInt(); val t = ByteArray(4); b.get(t); val d = ByteArray(len); b.get(d); val crc = b.getInt()
            val c = CRC32(); c.update(t); c.update(d); assertEquals("crc of ${String(t)}", c.value.toInt(), crc)
            when (String(t)) { "IHDR" -> { val bb = java.nio.ByteBuffer.wrap(d); w = bb.getInt(); h = bb.getInt(); assertEquals(8, d[8].toInt()); assertEquals(6, d[9].toInt()) }; "IDAT" -> idat.write(d); "IEND" -> ended = true; "iCCP" -> sawIccp = true }
        }
        assertTrue(ended)
        val inf = Inflater(); inf.setInput(idat.toByteArray())
        val raw = ByteArray((w * 4 + 1) * h); var n = 0; while (n < raw.size) { val k = inf.inflate(raw, n, raw.size - n); if (k == 0) break; n += k }
        assertEquals(raw.size, n)
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val f = raw[y * (w * 4 + 1)].toInt(); assertEquals(1, f)
            for (i in 0 until w * 4) out[y * w * 4 + i] = (raw[y * (w * 4 + 1) + 1 + i] + (if (i >= 4) out[y * w * 4 + i - 4] else 0)).toByte()
        }
        return Triple(w, h, out)
    }

    @Test fun roundTripsEveryByteIncludingSemiTransparentColourAndStripBoundaries() {
        val w = 37; val h = 29; val rgba = ByteArray(w * h * 4).also { Random(3).nextBytes(it) }
        for (i in 0 until w * h step 5) { rgba[i * 4 + 3] = 0 }                  // alpha 0 pixels keep their colour in the file (the writer is exact)
        val bos = ByteArrayOutputStream(); val pw = PngWriter(bos, w, h)
        var y = 0; for (rows in intArrayOf(10, 10, 9)) { pw.writeRows(rgba.copyOfRange(y * w * 4, (y + rows) * w * 4), rows); y += rows }
        pw.finish()
        val (dw, dh, px) = decode(bos.toByteArray())
        assertEquals(w, dw); assertEquals(h, dh); assertArrayEquals(rgba, px)
    }

    @Test fun anIncompleteImageCannotBeFinished() {
        val pw = PngWriter(ByteArrayOutputStream(), 4, 4); pw.writeRows(ByteArray(4 * 4 * 2), 2)
        try { pw.finish(); org.junit.Assert.fail() } catch (e: IllegalStateException) {}
    }

    @Test fun anIccProfileIsEmbedded() {
        val bos = ByteArrayOutputStream(); val pw = PngWriter(bos, 2, 1, iccProfile = ByteArray(300) { it.toByte() })
        pw.writeRows(ByteArray(8), 1); pw.finish()
        decode(bos.toByteArray())
        assertTrue(String(bos.toByteArray(), Charsets.ISO_8859_1).contains("iCCP"))
    }
}

class IndexDiffTest {
    private fun row(id: String, modified: Long = 1, size: Long = 10) = ProjectRow(id, id, 8, 6, modified, 2, size, false)

    @Test fun anIdenticalIndexNeedsNoChange() { assertTrue(IndexDiff.compute(listOf(row("a"), row("b")), listOf(row("b"), row("a"))).isEmpty) }

    @Test fun newChangedAndVanishedProjectsAreFound() {
        val d = IndexDiff.compute(listOf(row("a"), row("b", modified = 1), row("gone")), listOf(row("a"), row("b", modified = 2), row("new")))
        assertEquals(setOf("b", "new"), d.upserts.map { it.id }.toSet()); assertEquals(listOf("gone"), d.deletes)
    }

    @Test fun aWipedIndexIsRebuiltFromTheDirectories() {
        val fs = MemFs()
        for (id in listOf("a", "b")) ProjectStore(fs, "${ProjectCatalog.ROOT}/$id").save(Document(id, id, 8, 6, layers = listOf(Layer.Pixel(LayerCommon("l", "l"), 8, 6)), modified = 5), { null }, setOf("l"))
        val d = IndexDiff.compute(emptyList(), ProjectCatalog.scan(fs).rows)
        assertEquals(2, d.upserts.size); assertTrue(d.deletes.isEmpty())
    }
}
