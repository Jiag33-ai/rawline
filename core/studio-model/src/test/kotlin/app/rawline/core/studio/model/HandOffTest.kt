package app.rawline.core.studio.model

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class HandOffTest {
    private fun tree(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) } }

    @Test fun planFitsTheCanvasNamesTheLayerAndMarksItsOrigin() {
        val p = HandOffPlan.make("p1", "P1055415.RW2", 8000, 6000, previewOnly = false, nowMs = 5)
        assertEquals("P1055415", p.document.name)
        assertTrue(p.width.toLong() * p.height <= Document.MAX_PIXELS_S1 && maxOf(p.width, p.height) <= Document.MAX_EDGE)
        assertEquals(8000f / 6000f, p.width.toFloat() / p.height, 0.01f)
        val l = p.document.layers.single() as Layer.Pixel
        assertEquals("Photo", l.common.name); assertEquals("develop", l.common.origin); assertEquals(p.width, l.width)
        assertEquals("Preview only", (HandOffPlan.make("p2", "x.dng", 100, 80, true, 1).document.layers.single()).common.name)
        assertEquals("Photo", HandOffPlan.make("p3", "", 100, 80, false, 1).document.name)
        assertEquals(100, HandOffPlan.make("p4", "small.jpg", 100, 80, false, 1).width)   // a small picture is not scaled
    }

    @Test fun theOriginSurvivesTheProjectFile() {
        val d = HandOffPlan.make("p1", "a.jpg", 40, 30, false, 1).document
        assertEquals("develop", (ProjectJson.read(ProjectJson.write(d, "x")).layers.single()).common.origin)
    }

    @Test fun theSourceIsCopiedOnceByContentAndTheRecipeIsAnAtomicCopy() {
        val dir = Files.createTempDirectory("ho").toFile(); val bytes = ByteArray(300_000) { (it * 31).toByte() }
        val a = HandOffFiles.copySource(ByteArrayInputStream(bytes), dir, "RW2")
        assertTrue(a.startsWith("source/") && a.endsWith(".rw2")); assertArrayEquals(bytes, File(dir, a).readBytes())
        assertEquals("the same original is not stored twice", a, HandOffFiles.copySource(ByteArrayInputStream(bytes), dir, "rw2"))
        assertEquals(1, File(dir, "source").list()!!.size)
        assertTrue(HandOffFiles.copySource(ByteArrayInputStream(byteArrayOf(1)), dir, "../../x!").endsWith(".x"))   // an odd extension cannot leave the folder
        HandOffFiles.writeRecipe(dir, "{\"schemaVersion\":1}"); assertEquals("{\"schemaVersion\":1}", File(dir, "recipe.json").readText())
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    /** The spec rule that Develop is untouched: a whole hand off reads and writes nothing of Develop's, byte for byte. */
    @Test fun aHandOffLeavesDevelopsRecipeFileAndCatalogueByteIdentical() {
        val files = Files.createTempDirectory("app").toFile()
        File(files, "databases").mkdirs()
        File(files, "databases/rawline.db").writeBytes(ByteArray(5000) { (it * 7).toByte() })        // the catalogue
        File(files, "databases/rawline.db-wal").writeBytes(ByteArray(900) { it.toByte() })
        File(files, "recipes").mkdirs(); File(files, "recipes/photo-42.json").writeText("{\"schemaVersion\":1,\"adjust\":{\"exposure\":0.5}}")
        File(files, "masks").mkdirs(); File(files, "masks/m1.bin").writeBytes(ByteArray(100))
        val before = tree(files)

        // the hand off, as the app runs it: plan, copy the source, save the project (pixels as a layer), write the recipe copy
        val plan = HandOffPlan.make("pnew", "photo-42.RW2", 400, 300, false, 10)
        val projectDir = File(files, "studio/pnew")
        val fs = JavaFs(files)
        val px = RawPixels(plan.width, plan.height, ByteArray(plan.width * plan.height * 4) { 200.toByte() })
        ProjectStore(fs, "studio/pnew").save(plan.document, { px }, setOf(HandOffPlan.LAYER_ID))
        HandOffFiles.copySource(ByteArrayInputStream(ByteArray(10_000) { it.toByte() }), projectDir, "rw2")
        HandOffFiles.writeRecipe(projectDir, File(files, "recipes/photo-42.json").readText())

        val after = tree(files)
        for ((path, hash) in before) assertEquals("Develop file $path changed", hash, after[path])
        val added = after.keys - before.keys
        assertTrue("everything new is under studio/pnew/: $added", added.isNotEmpty() && added.all { it.startsWith("studio${File.separator}pnew${File.separator}") })
        assertTrue(File(projectDir, "recipe.json").readText().contains("exposure"))
        assertEquals("develop", ProjectStore(fs, "studio/pnew").open().document.layers.single().common.origin)
    }

    @Test fun duplicatingAProjectKeepsItsMasksAndSelection() {
        val fs = MemFs(); val root = "files/studio"
        val doc = Document("a", "A", 300, 200, layers = listOf(Layer.Pixel(LayerCommon("l1", "L", mask = MaskRef("mask/l1")), 300, 200)), created = 1, modified = 1, selection = SelectionRef("sel"))
        val store = ProjectStore(fs, "$root/a")
        val mask = TilePlane(300, 200).also { it.fill(255); it[5, 5] = 7 }; val sel = TilePlane(300, 200).also { it.transform(IRect(10, 10, 100, 100)) { _, _, _ -> 255 } }
        store.saveTiles("mask/l1", mask.takeDirty()); store.saveTiles("sel", sel.takeDirty())
        store.save(doc, { RawPixels(300, 200, ByteArray(300 * 200 * 4)) }, setOf("l1"))
        ProjectCatalog.duplicate(fs, root, "a", "b", 99)
        val b = ProjectStore(fs, "$root/b")
        assertArrayEquals(mask.toBytes(), b.loadPlane("mask/l1", 300, 200).toBytes()); assertArrayEquals(sel.toBytes(), b.loadPlane("sel", 300, 200).toBytes())
        assertEquals(SelectionRef("sel"), b.open().document.selection)
    }
}
