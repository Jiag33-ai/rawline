package app.rawline.core.studio.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class S2SchemaTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.bufferedReader().readText()
    private val root = "files/studio/p2"

    @Test fun versionOneSampleMigratesWithNoMasksAndNoSelection() {
        val d = ProjectJson.read(res("sample_v1.json"))
        assertNull(d.selection); assertTrue(d.layers.all { it.common.mask == null && it.common.origin == null })
        assertTrue(ProjectJson.write(d, "x").contains("\"schemaVersion\": 2"))
        val m = Migrations.v1ToV2(JSONObject(res("sample_v1.json")))
        assertEquals(2, m.getInt("schemaVersion")); assertEquals(JSONObject(res("sample_v1.json")).getJSONArray("layers").toString(), m.getJSONArray("layers").toString())
    }

    @Test fun versionTwoSampleReadsMaskSelectionAndOrigin() {
        val d = ProjectJson.read(res("sample_v2.json"))
        assertEquals(SelectionRef("sel"), d.selection)
        assertEquals("develop", d.layers[0].common.origin); assertNull(d.layers[0].common.mask)
        assertEquals(MaskRef("mask/paint", enabled = false, inverted = true, linked = true), d.layers[1].common.mask)
        val text = ProjectJson.write(d, "0.1.140")
        assertEquals(d, ProjectJson.read(text)); assertEquals(text, ProjectJson.write(ProjectJson.read(text), "0.1.140"))
    }

    @Test fun aVersionOneReaderRefusesAVersionTwoFile() {
        // the v1 rule (NewerSchemaException) applied to a pretend reader: version 3 is newer than this app's 2
        try { ProjectJson.read(res("sample_v2.json").replace("\"schemaVersion\": 2", "\"schemaVersion\": 3")); fail() } catch (e: NewerSchemaException) { assertEquals(3, e.version) }
    }

    @Test fun unsafeTileDirectoriesAreRefused() {
        for (bad in listOf("../x", "/abs", "a//b", "")) {
            try { ProjectJson.read(res("sample_v2.json").replace("mask/paint", bad)); fail("accepted '$bad'") } catch (e: ProjectFormatException) { /* ok */ }
        }
    }

    @Test fun maskOperations() {
        var d = Document("p2", "P", 16, 12, layers = listOf(Layer.Pixel(LayerCommon("a", "A"), 16, 12)))
        d = LayerOps.addMask(d, "a"); assertEquals(MaskRef("mask/a"), d.layer("a")!!.common.mask)
        try { LayerOps.addMask(d, "a"); fail() } catch (e: IllegalArgumentException) {}
        d = LayerOps.setMaskInverted(LayerOps.setMaskEnabled(d, "a", false), "a", true)
        assertEquals(MaskRef("mask/a", enabled = false, inverted = true), d.layer("a")!!.common.mask)
        d = LayerOps.duplicate(d, "a", "b"); assertEquals("mask/b", d.layer("b")!!.common.mask!!.dir)
        d = LayerOps.deleteMask(d, "a"); assertNull(d.layer("a")!!.common.mask)
        try { LayerOps.setMaskEnabled(d, "a", true); fail() } catch (e: IllegalArgumentException) {}
    }

    // ---- kill harness over tile directories ----

    private fun doc(mask: Boolean = false, sel: Boolean = false, modified: Long = 1) = Document("p2", "P", 600, 300,
        layers = listOf(Layer.Pixel(LayerCommon("a", "A", mask = if (mask) MaskRef("mask/a") else null), 600, 300)),
        modified = modified, selection = if (sel) SelectionRef("sel") else null)

    private fun plane(seed: Long, tilesTouched: Int): TilePlane {
        val r = Random(seed); val p = TilePlane(600, 300)
        repeat(tilesTouched) { val tx = r.nextInt(3); val ty = r.nextInt(2); for (i in 0 until 50) p[tx * 256 + r.nextInt(80), ty * 256 + r.nextInt(40)] = 1 + r.nextInt(255) }
        return p
    }

    /** One user edit: tile writes then the JSON save (the order the session uses). Returns the document after. */
    private class Edit(val name: String, val dir: String?, val tiles: (TilePlane) -> Unit, val doc: Document)

    private fun px(): RawPixels = RawPixels(600, 300, ByteArray(600 * 300 * 4))

    private fun run(store: ProjectStore, e: Edit, plane: TilePlane?) {
        if (e.dir != null && plane != null) { e.tiles(plane); store.saveTiles(e.dir, plane.takeDirty()) }
        store.save(e.doc, { px() }, setOf("a"))
    }

    private fun check(name: String, edits: List<Edit>, dirOf: (Document) -> String?) {
        // dry run learns the committed state (tile planes) after every edit
        val states = ArrayList<Map<TileKey, ByteArray>>()   // plane tiles of the edited dir after edit i
        val dry = MemFs(); val dryStore = ProjectStore(dry, root); val dryPlane = TilePlane(600, 300)
        states.add(emptyMap())
        var prevDoc: Document? = null
        for ((i, e) in edits.withIndex()) {
            // kill points: run the edit on a copy of the dry state with a counting fs, then once per op with a kill
            val probe = MemFs().also { it.files.putAll(dry.files) }
            val counter = CrashFs(probe, Int.MAX_VALUE)
            val pp = TilePlane(600, 300).also { for ((k, v) in dryPlane.allTiles()) it.putTile(k, v) }
            run(ProjectStore(counter, root), e, pp)
            val total = counter.ops
            for (k in 0 until total) {
                val fs = MemFs().also { it.files.putAll(dry.files) }
                val kp = TilePlane(600, 300).also { for ((tk, v) in dryPlane.allTiles()) it.putTile(tk, v) }
                try { run(ProjectStore(CrashFs(fs, k), root), e, kp); fail("$name edit $i survived kill $k of $total") } catch (c: Crash) {}
                var opened: OpenResult? = null
                try { opened = ProjectStore(fs, root).open() } catch (x: ProjectFormatException) { if (prevDoc != null) fail("$name edit $i kill $k: does not open: ${x.message}") }
                if (opened == null) continue
                assertTrue("$name edit $i kill $k: unexpected generation ${opened.document.modified}", opened.document.modified == e.doc.modified || opened.document.modified == prevDoc?.modified)
                val dir = dirOf(opened.document)
                if (dir != null) {
                    val damaged = IntArray(1)
                    val got = ProjectStore(fs, root).loadPlane(dir, 600, 300, damaged)
                    assertEquals("$name edit $i kill $k: damaged tiles", 0, damaged[0])
                    // every tile is exactly what it was before the edit or exactly what it is after (never torn, nothing older)
                    val before = states.last(); val after = pp.allTiles()
                    for (tx in 0..2) for (ty in 0..1) {
                        val key = TileKey(tx, ty); val g = got.allTiles()[key]
                        val b = before[key]; val a = after[key]
                        val okTile = (g == null && (b == null || a == null)) || (g != null && ((b != null && g.contentEquals(b)) || (a != null && g.contentEquals(a))))
                        assertTrue("$name edit $i kill $k tile $key is neither the old nor the new bytes", okTile)
                    }
                }
            }
            run(dryStore, e, dryPlane)
            states.add(dryPlane.allTiles()); prevDoc = e.doc
        }
        // finished store: opens current, no stray .part anywhere
        assertEquals(OpenedFrom.CURRENT, ProjectStore(dry, root).open().from)
        assertTrue(dry.files.keys.none { it.endsWith(".part") || it.endsWith(".tmp") })
    }

    @Test fun killedAtEveryOperationPaintMaskStroke() {
        val edits = ArrayList<Edit>(); var m = 1L
        edits.add(Edit("add", "mask/a", { it.let { p -> for (y in 0 until 300) for (x in 0 until 600) p[x, y] = 255 } }, doc(mask = true, modified = ++m)))
        for (i in 1..6) edits.add(Edit("stroke$i", "mask/a", { p -> val r = Random(i.toLong()); repeat(80) { p[r.nextInt(600), r.nextInt(300)] = r.nextInt(256) } }, doc(mask = true, modified = ++m)))
        check("paint mask stroke", edits) { it.layers[0].common.mask?.dir }
    }

    @Test fun killedAtEveryOperationAddSelection() {
        val edits = ArrayList<Edit>(); var m = 1L
        edits.add(Edit("sel1", "sel", { p -> val s = Selection(600, 300, p); s.rect(SelOp.REPLACE, 10, 10, 400, 200) }, doc(sel = true, modified = ++m)))
        edits.add(Edit("sel2", "sel", { p -> val s = Selection(600, 300, p); s.ellipse(SelOp.ADD, 300.0, 100.0, 590.0, 290.0) }, doc(sel = true, modified = ++m)))
        edits.add(Edit("deselect", "sel", { p -> for (y in 0 until 300) for (x in 0 until 600) p[x, y] = 0 }, doc(sel = false, modified = ++m)))
        check("add selection", edits) { it.selection?.dir }
    }

    @Test fun killedAtEveryOperationDeleteMask() {
        val edits = ArrayList<Edit>(); var m = 1L
        edits.add(Edit("add", "mask/a", { p -> for (y in 0 until 300) for (x in 0 until 300) p[x, y] = 200 }, doc(mask = true, modified = ++m)))
        edits.add(Edit("delete", null, {}, doc(mask = false, modified = ++m)))
        edits.add(Edit("later", null, {}, doc(mask = false, modified = ++m)))
        check("delete mask", edits) { it.layers[0].common.mask?.dir }
    }

    @Test fun tilesNobodyRefersToAreCollectedOnlyAfterTheDroppingJsonIsCommitted() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val p = plane(5, 3)
        store.saveTiles("mask/a", p.takeDirty()); store.save(doc(mask = true, modified = 2), { px() }, setOf("a"))
        val files = fs.files.keys.filter { it.startsWith("$root/mask/a/") }; assertTrue(files.isNotEmpty())
        store.save(doc(mask = false, modified = 3), { px() }, setOf())              // dropped, but .bak still names it
        assertEquals(files.toSet(), fs.files.keys.filter { it.startsWith("$root/mask/a/") }.toSet())
        store.save(doc(mask = false, modified = 4), { px() }, setOf())              // now neither generation does
        assertTrue(fs.files.keys.none { it.startsWith("$root/mask/") })
        // a stray .part inside a kept dir goes
        store.saveTiles("sel", plane(6, 2).takeDirty()); fs.files["$root/sel/t_0_0.part"] = byteArrayOf(1, 2)
        store.save(doc(sel = true, modified = 5), { px() }, setOf()); assertTrue(fs.files.keys.none { it.endsWith(".part") })
    }

    @Test fun loadPlaneSkipsDamagedTilesAndCountsThem() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        store.saveTiles("sel", plane(7, 3).takeDirty())
        val name = fs.files.keys.first { it.startsWith("$root/sel/t_") }; fs.files[name] = byteArrayOf(1, 9, 9)
        val bad = IntArray(1); store.loadPlane("sel", 600, 300, bad); assertEquals(1, bad[0])
    }

    @Test fun copyTilesDuplicatesAMaskDirectory() {
        val fs = MemFs(); val store = ProjectStore(fs, root); val p = plane(8, 3); val before = p.allTiles().let { _ -> p.takeDirty() }
        store.saveTiles("mask/a", before); store.copyTiles("mask/a", "mask/b")
        assertEquals(store.loadPlane("mask/a", 600, 300).toBytes().toList(), store.loadPlane("mask/b", 600, 300).toBytes().toList())
    }
}
