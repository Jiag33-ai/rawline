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
