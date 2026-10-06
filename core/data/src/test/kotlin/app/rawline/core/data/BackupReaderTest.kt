package app.rawline.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupReaderTest {
    private lateinit var staging: File
    @Before fun setUp() { staging = File.createTempFile("restore", "").also { it.delete() } }
    @After fun tearDown() { staging.deleteRecursively() }

    private fun png(w: Int = 4, h: Int = 4, size: Int = 64): ByteArray {
        val b = ByteArray(maxOf(size, 24))
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()).copyInto(b)
        for (i in 0 until 4) { b[16 + i] = (w shr (24 - 8 * i)).toByte(); b[20 + i] = (h shr (24 - 8 * i)).toByte() }
        return b.copyOf(size)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return ByteArrayInputStream(o.toByteArray())
    }

    private fun text(s: String) = s.toByteArray()
    private val edits = """[{"key":"a.rw2|10|20","json":"{\"v\":1}","t":5}]"""

    @Test fun readsAValidBackup() {
        val c = BackupReader.read(zip(
            "version.json" to text("""{"format":1}"""), "edits.json" to text(edits),
            "meta.json" to text("""[{"key":"a.rw2|10|20","rating":3,"flag":1,"label":2,"t":9}]"""),
            "masks/m_1.png" to png(), "heals/h_1.png" to png(),
        ), staging)
        assertEquals(1, c.edits.size); assertEquals(5L, c.edits[0].updatedAt)
        assertEquals(9L, c.metas[0].updatedAt)
        assertEquals(2, c.staged.size)
        assertTrue(c.staged.all { it.file.exists() })
    }

    @Test fun oldBackupWithoutMetaTimesReadsAsZero() {
        val c = BackupReader.read(zip("meta.json" to text("""[{"key":"k","rating":1,"flag":0,"label":0}]""")), staging)
        assertEquals(0L, c.metas[0].updatedAt)
    }

    @Test fun unknownAndTraversalNamesAreIgnored() {
        val c = BackupReader.read(zip(
            "../evil.png" to png(), "masks/../evil.png" to png(), "masks/sub/x.png" to png(), "masks/.hidden.png" to png(),
            "masks/x.txt" to text("hi"), "other.bin" to text("x"), "/abs/edits.json" to text("[]"),
        ), staging)
        assertTrue(c.staged.isEmpty())
        assertFalse(File(staging.parentFile, "evil.png").exists())
        assertNull(BackupReader.imageName("masks/../x.png")); assertNull(BackupReader.imageName("heals/a/b.png"))
        assertEquals("masks" to "ok_1-2.png", BackupReader.imageName("masks/ok_1-2.png"))
    }

    private fun fails(block: () -> Unit) { try { block(); fail("expected BackupException") } catch (e: BackupException) { } }

    @Test fun notAPngIsRejectedAndRemoved() {
        fails { BackupReader.read(zip("masks/a.png" to text("not a png at all, just text padded out to length............")), staging) }
        assertFalse(File(staging, "masks/a.png").exists())
    }

    @Test fun hugeClaimedDimensionsAreRejected() {
        fails { BackupReader.read(zip("heals/a.png" to png(w = 100_000, h = 100_000)), staging) }
        fails { BackupReader.read(zip("heals/a.png" to png(w = 16_000, h = 16_000)), staging) }  // 256 MP
        fails { BackupReader.read(zip("heals/a.png" to png(w = 0, h = 5)), staging) }
        fails { BackupReader.read(zip("heals/a.png" to png(size = 20)), staging) }              // truncated
    }

    @Test fun damagedJsonIsRejected() {
        fails { BackupReader.read(zip("edits.json" to text("{not json")), staging) }
        fails { BackupReader.read(zip("edits.json" to text("""[{"key":"k","json":"not an object","t":1}]""")), staging) }
        fails { BackupReader.read(zip("meta.json" to text("""[{"key":"k"}]""")), staging) }
    }

    @Test fun oversizedEntryStopsAtTheCap() {
        // 70 MB of zeros compresses to about 70 KB: the zip itself is tiny, the entry is over the 64 MB JSON cap
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("edits.json")); val chunk = ByteArray(1 shl 20); repeat(70) { z.write(chunk) }; z.closeEntry() }
        assertTrue(o.size() < 1_000_000)
        fails { BackupReader.read(ByteArrayInputStream(o.toByteArray()), staging) }
    }

    @Test fun newerWinsForEdits() {
        val existing = mapOf("a" to EditEntity("a", "{}", 10), "b" to EditEntity("b", "{}", 10))
        val kept = BackupMerge.newerEdits(existing, listOf(EditEntity("a", "{}", 5), EditEntity("b", "{}", 20), EditEntity("c", "{}", 0)))
        assertEquals(listOf("b", "c"), kept.map { it.key })
    }

    @Test fun newerWinsForMeta() {
        val existing = mapOf("a" to MetaEntity("a", 5, 1, 1, 100), "b" to MetaEntity("b", 1, 0, 0, 0), "z" to MetaEntity("z", 1, 0, 0, 0))
        val incoming = listOf(
            MetaEntity("a", 1, 0, 0, 50),    // older backup: rating 5 set on the phone later stays
            MetaEntity("b", 3, 0, 0, 7),     // newer than a never-touched row: applies
            MetaEntity("z", 4, 0, 0, 0),     // legacy backup without times never replaces an existing row
            MetaEntity("n", 2, 0, 0, 0),     // missing here: inserted
        )
        assertEquals(listOf("b", "n"), BackupMerge.newerMetas(existing, incoming).map { it.key })
    }
}

/** A zip written the way Catalog.writeBackup writes it (BackupZip with a manifest) is read by the hardened reader and verifies. */
class BackupWrittenZipTest {
    private fun png(): ByteArray {
        val b = ByteArray(64)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()).copyInto(b)
        b[19] = 4; b[23] = 4
        return b
    }
    private fun written(): ByteArray {
        val parts = listOf(
            "version.json" to """{"format":1,"created":1}""",
            "edits.json" to """[{"key":"a.rw2|10|20","json":"{\"v\":1}","t":5}]""",
            "snapshots.json" to "[]", "presets.json" to """[{"name":"P","json":"{}","t":2}]""",
            "meta.json" to """[{"key":"a.rw2|10|20","rating":3,"flag":1,"label":2,"t":9}]""",
        ).map { (n, t) -> BackupPart(n) { ByteArrayInputStream(t.toByteArray()) } } + BackupPart("masks/m_1.png") { ByteArrayInputStream(png()) }
        return ByteArrayOutputStream().also { BackupZip.write(it, parts, mapOf("edits" to 1, "snapshots" to 0, "presets" to 1, "meta" to 1, "masks" to 1, "heals" to 0), 1000L, "0.1.5") }.toByteArray()
    }

    @Test fun theReaderIgnoresTheManifestAndReadsEverythingElse() {
        val staging = File.createTempFile("restore", "").also { it.delete() }
        try {
            val c = BackupReader.read(ByteArrayInputStream(written()), staging)
            assertEquals(1, c.edits.size); assertEquals(1, c.presets.size); assertEquals(3, c.metas[0].rating); assertEquals(1, c.staged.size)
        } finally { staging.deleteRecursively() }
    }

    @Test fun theSameZipVerifiesAndTheCheckCallsItGood() {
        val v = BackupZip.verify(ByteArrayInputStream(written()))
        assertTrue(v.problems.toString(), v.ok)
        val check = RestoreCheck.of(v)
        assertTrue(check is RestoreCheck.Good)
        assertEquals(1, (check as RestoreCheck.Good).manifest.counts["edits"])
    }

    @Test fun aFormat1ZipIsOlderNotDamaged_andGarbageIsDamaged() {
        val old = ByteArrayOutputStream(); ZipOutputStream(old).use { it.putNextEntry(ZipEntry("version.json")); it.write("""{"format":1}""".toByteArray()); it.closeEntry(); it.putNextEntry(ZipEntry("edits.json")); it.write("[]".toByteArray()); it.closeEntry() }
        assertTrue(RestoreCheck.of(BackupZip.verify(ByteArrayInputStream(old.toByteArray()))) === RestoreCheck.Older)
        for (bad in listOf(ByteArray(0), ByteArray(100) { it.toByte() }, written().copyOf(40))) assertTrue(RestoreCheck.of(BackupZip.verify(ByteArrayInputStream(bad))) is RestoreCheck.Damaged)
        // a zip with some other content and no version.json is not an old backup of ours
        val other = ByteArrayOutputStream(); ZipOutputStream(other).use { it.putNextEntry(ZipEntry("photo.txt")); it.write("x".toByteArray()); it.closeEntry() }
        assertTrue(RestoreCheck.of(BackupZip.verify(ByteArrayInputStream(other.toByteArray()))) is RestoreCheck.Damaged)
    }

    @Test fun aFlippedByteMeansNothingIsRestored() {
        val z = written(); z[44] = (z[44].toInt() xor 0x55).toByte()      // inside the first entry's data (30 byte header and the 12 byte name come first)
        val c = RestoreCheck.of(BackupZip.verify(ByteArrayInputStream(z)))
        assertTrue(c is RestoreCheck.Damaged)
        assertTrue(RestoreText.preview(c).endsWith("Nothing was changed."))
    }
}

class RestoreTextTest {
    private val utc = java.time.ZoneId.of("UTC")
    private fun ms(d: Int, h: Int = 12, mi: Int = 0) = java.time.LocalDateTime.of(2026, 10, d, h, mi).atZone(utc).toInstant().toEpochMilli()

    @Test fun previewSaysWhatIsInsideAndThatNewerThingsAreKept() {
        val m = BackupManifest(2, ms(4), "0.1.5", mapOf("edits" to 312, "meta" to 1204, "presets" to 18), emptyList())
        assertEquals("This backup has 312 edits, 1,204 ratings, 18 presets, from 4 Oct. Anything changed more recently on this phone is kept. Restore?", RestoreText.preview(RestoreCheck.Good(m), utc))
        val one = BackupManifest(2, 0, "", mapOf("edits" to 1, "meta" to 1, "presets" to 1), emptyList())
        assertEquals("This backup has 1 edit, 1 rating, 1 preset. Anything changed more recently on this phone is kept. Restore?", RestoreText.preview(RestoreCheck.Good(one), utc))
        assertTrue(RestoreText.preview(RestoreCheck.Older).startsWith("This is an older backup, it cannot be checked."))
    }

    @Test fun lastBackupLine() {
        assertEquals("No backup yet", RestoreText.lastLine(0, 0, ms(6), utc))
        assertEquals("Last backup: today 14:15, 3.2 MB", RestoreText.lastLine(ms(6, 14, 15), 3_400_000, ms(6, 18), utc))
        assertEquals("Last backup: yesterday 09:00", RestoreText.lastLine(ms(5, 9), 0, ms(6, 8), utc))
        assertEquals("Last backup: 1 Oct 09:00, 512 KB", RestoreText.lastLine(ms(1, 9), 524_288, ms(6, 8), utc))
    }

    @Test fun listLine() { assertEquals("4 Oct, 3.2 MB, 312 edits", RestoreText.listLine(ms(4), 3_400_000, 312, utc)) }
}

class ChangeCounterTest {
    private class MapStore : CounterStore { val m = HashMap<String, Long>(); override fun getLong(key: String, default: Long) = m[key] ?: default; override fun putLong(key: String, value: Long) { m[key] = value } }

    @Test fun onlyEverIncreasesAndPersistsInTheStore() {
        val s = MapStore(); val c = ChangeCounter(s)
        assertEquals(0, c.value()); assertEquals(1, c.bump()); assertEquals(3, c.bump(2)); assertEquals(3, ChangeCounter(s).value())
        assertEquals(3, c.bump(-5))
    }

    @Test fun thresholdIsCrossedOncePerTwentyFive() {
        assertFalse(ChangeCounter.crossedThreshold(0, 24)); assertTrue(ChangeCounter.crossedThreshold(24, 25)); assertFalse(ChangeCounter.crossedThreshold(25, 49)); assertTrue(ChangeCounter.crossedThreshold(49, 50))
        assertTrue(ChangeCounter.crossedThreshold(20, 60))
    }

    @Test fun concurrentBumpsAreNotLost() {
        val c = ChangeCounter(MapStore()); val ts = List(8) { Thread { repeat(500) { c.bump() } } }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        assertEquals(4000, c.value())
    }
}

class RestoreStagingTest {
    private fun dir() = java.nio.file.Files.createTempDirectory("stage").toFile()
    private fun good(): ByteArray = ByteArrayOutputStream().also { BackupZip.write(it, listOf(BackupPart("edits.json") { ByteArrayInputStream("[]".toByteArray()) }), mapOf("edits" to 0), 5L, "x") }.toByteArray()

    @Test fun aGoodFileIsCopiedChecked_andDiscardedOnRequest() {
        val d = dir(); val s = RestoreStaging.stage(ByteArrayInputStream(good()), d)
        assertTrue(s.check is RestoreCheck.Good); assertTrue(s.file.exists()); s.discard(); assertFalse(s.file.exists()); d.deleteRecursively()
    }

    @Test fun aDamagedFileIsStagedButRefused() {
        val d = dir(); val z = good(); z[44] = (z[44].toInt() xor 0x55).toByte()
        val s = RestoreStaging.stage(ByteArrayInputStream(z), d)
        assertTrue(s.check is RestoreCheck.Damaged); d.deleteRecursively()
    }

    @Test fun aSourceThatFailsHalfWayLeavesNothingBehind() {
        val d = dir()
        val bad = object : java.io.InputStream() { var n = 0; override fun read(): Int { if (n++ > 100) throw IOException("card removed"); return 1 } }
        try { RestoreStaging.stage(bad, d); org.junit.Assert.fail() } catch (e: IOException) { assertEquals("card removed", e.message) }
        assertEquals(0, d.listFiles()!!.size); d.deleteRecursively()
    }

    @Test fun cleanRemovesOnlyStaleCandidates() {
        val d = dir(); File(d, "restore-candidate-1.zip").writeText("x"); File(d, "other.txt").writeText("y")
        RestoreStaging.clean(d); assertEquals(listOf("other.txt"), d.listFiles()!!.map { it.name }); d.deleteRecursively()
    }
}
