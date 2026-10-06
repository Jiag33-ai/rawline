package app.rawline.core.studio.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/** In memory file system. Keys are full paths; directories are implied. */
open class MemFs : Fs {
    val files = LinkedHashMap<String, ByteArray>()
    override fun exists(path: String) = files.containsKey(path)
    override fun read(path: String) = files[path]
    override fun write(path: String, data: ByteArray) { files[path] = data.copyOf() }
    override fun rename(from: String, to: String) { files[to] = files.remove(from) ?: throw IllegalStateException("no $from") }
    override fun delete(path: String) { files.remove(path) }
    override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") && !it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1) }
    override fun dirs(dir: String) = files.keys.filter { it.startsWith("$dir/") && it.substring(dir.length + 1).contains('/') }.map { it.substring(dir.length + 1).substringBefore('/') }.distinct()
    override fun size(path: String) = files[path]?.size?.toLong() ?: 0L
    override fun deleteTree(path: String) { files.keys.removeAll { it == path || it.startsWith("$path/") } }
}

class Crash : RuntimeException("simulated kill")

/** Dies at the [failAt]th mutating operation. A write that dies leaves half the bytes (a torn file); a rename or delete that dies does not happen. */
class CrashFs(private val inner: MemFs, private val failAt: Int) : Fs by inner {
    var ops = 0
    private fun tick(): Boolean = (ops++ == failAt)
    override fun write(path: String, data: ByteArray) { if (tick()) { inner.write(path, data.copyOf(data.size / 2)); throw Crash() }; inner.write(path, data) }
    override fun rename(from: String, to: String) { if (tick()) throw Crash(); inner.rename(from, to) }
    override fun delete(path: String) { if (tick()) throw Crash(); inner.delete(path) }
}

class ProjectStoreTest {
    private val root = "files/studio/p1"
    private fun px(seed: Int, w: Int = 16, h: Int = 12): RawPixels { val r = Random(seed.toLong()); return RawPixels(w, h, ByteArray(w * h * 4).also { r.nextBytes(it) }) }
    private fun doc(vararg ids: String) = Document("p1", "P", 16, 12, layers = ids.map { Layer.Pixel(LayerCommon(it, it), 16, 12) }, modified = 1)

    @Test fun containerRoundTripsEveryByteIncludingLowAlphaColour() {
        // straight RGBA with colour under alpha 0 and 1: premultiplying codecs would lose it, this container must not
        val rgba = ByteArray(16 * 12 * 4) { i -> if (i % 4 == 3) (i / 4 % 3).toByte() else (i * 37).toByte() }
        val p = PixelContainer.decode(PixelContainer.encode(RawPixels(16, 12, rgba)))
        assertArrayEquals(rgba, p.rgba); assertEquals(16, p.w); assertEquals(12, p.h)
    }

    @Test fun containerRejectsGarbageAndCutOffData() {
        val good = PixelContainer.encode(px(1))
        for (bad in listOf(ByteArray(0), ByteArray(40), good.copyOf(good.size / 2), good.copyOf().also { it[0] = 0 })) {
            try { PixelContainer.decode(bad); fail() } catch (e: ProjectFormatException) { assertTrue(e.message!!.isNotEmpty()) }
        }
    }

    @Test fun saveThenOpenReturnsTheSameDocumentAndPixels() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val p = mapOf("a" to px(1), "b" to px(2))
        val saved = store.save(doc("a", "b"), { p[it.common.id] }, setOf("a", "b"))
        val r = ProjectStore(fs, root).open()
        assertEquals(OpenedFrom.CURRENT, r.from); assertEquals(saved, r.document)
        assertArrayEquals(p["a"]!!.rgba, store.load(r.document.layer("a") as Layer.Pixel)!!.rgba)
    }

    @Test fun anUnchangedLayerIsNotWrittenAgain() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        var d = store.save(doc("a", "b"), { px(it.common.id.hashCode()) }, setOf("a", "b"))
        val before = fs.files.keys.filter { it.startsWith("$root/layers/") }.toSet()
        var calls = 0
        d = store.save(d.copy(modified = 2), { calls++; px(99) }, setOf("b"))   // only b changed
        assertEquals(1, calls)
        val after = fs.files.keys.filter { it.startsWith("$root/layers/") }
        assertTrue(before.any { it.contains("/a-") && it in after })
        assertEquals(3, after.size)   // a untouched, the new b, and the old b which the .bak generation still refers to
        d = store.save(d.copy(modified = 3), { px(98) }, setOf("b"))
        assertEquals(3, fs.files.keys.count { it.startsWith("$root/layers/") })   // now the first b is no longer needed by either generation
    }

    @Test fun aLayerWithoutPixelsHasNoFileAndOpensAsNull() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val saved = store.save(doc("a"), { null }, setOf("a"))
        assertNull((saved.layers[0] as Layer.Pixel).pixelsFile)
        assertNull(store.load(ProjectStore(fs, root).open().document.layers[0] as Layer.Pixel))
    }

    @Test fun twoGenerationsAreKeptAndTheOlderOneStillOpens() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val d1 = store.save(doc("a"), { px(1) }, setOf("a"))
        store.save(d1.copy(modified = 2), { px(2) }, setOf("a"))
        assertTrue(fs.exists("$root/project.json.bak"))
        fs.files["$root/project.json"] = "{ torn".toByteArray()      // the newest file is damaged
        val r = ProjectStore(fs, root).open()
        assertEquals(OpenedFrom.BACKUP, r.from); assertTrue(r.recovered); assertEquals(1L, r.document.modified)
    }

    @Test fun aNewerSchemaIsNeverOpenedAsAFallback() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        store.save(doc("a"), { px(1) }, setOf("a"))
        fs.files["$root/project.json"] = String(fs.files["$root/project.json"]!!).replace("\"schemaVersion\": 2", "\"schemaVersion\": 9").toByteArray()
        try { ProjectStore(fs, root).open(); fail() } catch (e: NewerSchemaException) { assertEquals(9, e.version) }
    }

    @Test fun missingLayerFileMakesThatGenerationUnusable() {
        val fs = MemFs(); val store = ProjectStore(fs, root)
        val d1 = store.save(doc("a"), { px(1) }, setOf("a"))
        store.save(d1.copy(modified = 2), { px(2) }, setOf("a"))
        val newest = (ProjectStore(fs, root).open().document.layers[0] as Layer.Pixel).pixelsFile!!
        fs.files.remove("$root/$newest")
        assertEquals(1L, ProjectStore(fs, root).open().document.modified)
    }

    @Test fun nothingSavedIsAnError() {
        try { ProjectStore(MemFs(), root).open(); fail() } catch (e: ProjectFormatException) { assertTrue(e.message!!.contains("no project")) }
    }

    /**
     * The exit test of S1b: a long run of strokes (each a save of one layer, sometimes adding or deleting a layer), killed at every single
     * mutating file operation of every save. After each kill the project must open, to the state before that save or the state after it,
     * with every layer's pixels readable. Nothing older is ever shown, and no kill point loses a committed save.
     */
    @Test fun killedAtEveryFileOperationTheProjectAlwaysOpensAndLosesAtMostTheLastStroke() {
        val rnd = Random(42)
        val states = ArrayList<Pair<Document, Map<String, ByteArray>>>()   // committed (document as saved, pixels by layer id)
        // dry run to learn the operations of each save, recording states
        val dry = MemFs(); val dryStore = ProjectStore(dry, root)
        var d = doc("l0"); var pix = mapOf("l0" to px(0).rgba)
        val opsPerSave = ArrayList<Int>()
        val steps = ArrayList<Triple<Document, Map<String, ByteArray>, Set<String>>>()
        for (i in 1..40) {
            val ids = d.layers.map { it.common.id }
            when {
                i % 9 == 0 && ids.size < 5 -> { d = LayerOps.add(d, Layer.Pixel(LayerCommon("l$i", "l$i"), 16, 12)); pix = pix + ("l$i" to px(i).rgba) }
                i % 13 == 0 && ids.size > 1 -> { val gone = ids.first(); d = LayerOps.delete(d, gone); pix = pix - gone }
                else -> { val target = ids[rnd.nextInt(ids.size)]; pix = pix + (target to px(1000 + i).rgba) }
            }
            d = d.copy(modified = d.modified + 1)
            steps.add(Triple(d, pix, d.layers.map { it.common.id }.toSet()))
        }
        var prevSaved: Document? = null
        var prevPix: Map<String, ByteArray>? = null
        for ((idx, st) in steps.withIndex()) {
            val (docNow, pixNow, _) = st
            // count the mutating ops of this save on a copy of the current state
            val probe = MemFs().also { it.files.putAll(dry.files) }
            val counter = CrashFs(probe, Int.MAX_VALUE)
            val changed = if (prevPix == null) pixNow.keys else pixNow.keys.filter { prevPix!![it] == null || !prevPix!![it]!!.contentEquals(pixNow[it]) }.toSet()
            val savedProbe = ProjectStore(counter, root).save(docNow, { RawPixels(16, 12, pixNow[it.common.id]!!) }, changed.toSet())
            val total = counter.ops
            for (k in 0 until total) {
                val fs = MemFs().also { it.files.putAll(dry.files) }
                try { ProjectStore(CrashFs(fs, k), root).save(docNow, { RawPixels(16, 12, pixNow[it.common.id]!!) }, changed.toSet()); fail("save $idx survived kill point $k of $total") } catch (e: Crash) {}
                var r: OpenResult? = null
                try { r = ProjectStore(fs, root).open() } catch (e: ProjectFormatException) { if (prevSaved != null) fail("save $idx killed at $k: project does not open: ${e.message}") }
                if (r != null) {
                    val ok = (prevSaved != null && r.document.modified == prevSaved!!.modified) || r.document.modified == savedProbe.modified
                    assertTrue("save $idx killed at op $k: opened an unexpected generation (modified ${r.document.modified})", ok)
                    val expectPix = if (r.document.modified == savedProbe.modified) pixNow else prevPix!!
                    for (l in r.document.layers) {
                        val got = ProjectStore(fs, root).load(l as Layer.Pixel)!!.rgba
                        assertArrayEquals("save $idx kill $k layer ${l.common.id}", expectPix[l.common.id], got)
                    }
                }
            }
            // now do the save for real on the dry run
            prevSaved = dryStore.save(docNow, { RawPixels(16, 12, pixNow[it.common.id]!!) }, changed.toSet())
            prevPix = pixNow
            opsPerSave.add(total)
        }
        assertTrue("every save has several file operations to kill: $opsPerSave", opsPerSave.all { it >= 5 })
        // and the finished store opens to the last state
        val last = ProjectStore(dry, root).open()
        assertEquals(steps.last().first.modified, last.document.modified)
        assertEquals(OpenedFrom.CURRENT, last.from)
    }
}
