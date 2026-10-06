package app.rawline.core.data.ingest

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class CardImportTest {
    private val imp = File(System.getProperty("imp.dir") ?: "src/test/resources/dng")
    private fun tmp() = Files.createTempDirectory("imp").toFile()
    private fun card(name: String, bytes: ByteArray, mtime: Long = 1_700_000_000_000) = CardFile(name, bytes.size.toLong(), mtime) { ByteArrayInputStream(bytes) }

    @Test fun rawExtensions() {
        assertTrue(ImportNaming.isRaw("P1000123.RW2")); assertTrue(ImportNaming.isRaw("a.dng"))
        assertFalse(ImportNaming.isRaw("a.jpg")); assertFalse(ImportNaming.isRaw("._P1000123.RW2")); assertFalse(ImportNaming.isRaw("noext"))
    }

    @Test fun uniqueNames() {
        assertEquals("a.RW2", ImportNaming.unique("a.RW2", setOf("b.RW2")))
        assertEquals("a_2.RW2", ImportNaming.unique("a.RW2", setOf("A.rw2")))
        assertEquals("a_3.RW2", ImportNaming.unique("a.RW2", setOf("a.RW2", "a_2.RW2")))
        assertEquals("x_2", ImportNaming.unique("x", setOf("x")))
    }

    @Test fun unsafeNamesAreNotTrusted() {
        for (n in listOf("", "..", ".", "a/b.RW2", "..\\x.RW2", "a\u0000.RW2", "x".repeat(201))) assertFalse(n, ImportNaming.isSafe(n))
        assertTrue(ImportNaming.isSafe("P1000123.RW2"))
        val d = tmp(); val r = CopyEngine(d, ImportLedger()).run(listOf(card("../evil.RW2", ByteArray(4)), card("a/b.RW2", ByteArray(4))))
        assertEquals(2, r.failed); assertEquals(0, d.list()!!.size); assertFalse(File(d.parentFile, "evil.RW2").exists())
    }

    @Test fun ledgerRoundTripAndFatResolution() {
        val l = ImportLedger(); val f = card("A.RW2", ByteArray(5), 1_700_000_000_000)
        assertFalse(l.has(f)); l.add(f)
        assertTrue(l.has(card("a.rw2", ByteArray(5), 1_700_000_001_000)))   // 1 s drift on FAT
        assertFalse(l.has(card("a.rw2", ByteArray(6), 1_700_000_000_000)))
        assertTrue(ImportLedger.parse(l.serialise()).has(f))
    }

    @Test fun theLedgerFileSurvivesAKillAndATornLastLine() {
        val dir = tmp(); val file = File(dir, "import-ledger.txt")
        val l1 = ImportLedger.load(file); val a = card("A.RW2", ByteArray(5)); val b = card("B.RW2", ByteArray(6))
        l1.add(a); l1.add(b); l1.add(a)                                  // a repeat adds no line
        assertEquals(2, file.readLines().count { it.isNotBlank() })
        file.appendText("\nc.rw2|7|85000")                               // a line cut short by a kill is a key nothing will match, and the next append starts on a new line
        val l2 = ImportLedger.load(file); assertTrue(l2.has(a)); assertTrue(l2.has(b)); l2.add(card("D.RW2", ByteArray(1)))
        assertTrue(ImportLedger.load(file).has(card("D.RW2", ByteArray(1))))
        assertFalse(ImportLedger.load(File(dir, "missing.txt")).has(a))
    }

    @Test fun copiesVerifiesAndKeepsTheCardFileTime() {
        val d = tmp(); val l = ImportLedger(); val data = ByteArray(1_000_003) { (it * 7).toByte() }
        val r = CopyEngine(d, l).run(listOf(card("A.RW2", data)))
        assertEquals(1, r.copied); assertArrayEquals(data, File(d, "A.RW2").readBytes())
        assertEquals(1_700_000_000_000, File(d, "A.RW2").lastModified())
        assertTrue(d.list()!!.none { it.endsWith(".part") }); assertEquals(data.size.toLong(), r.bytes)
    }

    @Test fun theChecksumCatchesACopyThatDoesNotMatchWhatWasRead() {
        // the write path flips a byte: the part no longer matches the SHA-256 of the bytes that came off the card
        val d = tmp(); val l = ImportLedger()
        val corrupting: (File) -> FileOutputStream = { f -> object : FileOutputStream(f) { var first = true
            override fun write(b: ByteArray, off: Int, len: Int) { if (first && len > 0) { b[off] = (b[off].toInt() xor 1).toByte(); first = false }; super.write(b, off, len) } } }
        val f = card("A.RW2", ByteArray(5000) { 3 })
        val r = CopyEngine(d, l, openPart = corrupting).run(listOf(f))
        assertEquals(listOf(Outcome.FAILED), r.results.map { it.outcome }); assertEquals("verify failed", r.results[0].note)
        assertEquals(0, d.list()!!.size); assertFalse(l.has(f))
    }

    @Test fun secondInsertCopiesNothing() {
        val d = tmp(); val l = ImportLedger(); val fs = listOf(card("A.RW2", ByteArray(10) { 1 }), card("B.RW2", ByteArray(20) { 2 }))
        CopyEngine(d, l).run(fs)
        val r = CopyEngine(d, ImportLedger.parse(l.serialise())).run(fs)
        assertEquals(0, r.copied); assertEquals(2, r.skipped); assertEquals(2, d.list()!!.size)
    }

    @Test fun aLostLedgerStillDoesNotMakeDuplicates() {
        val d = tmp(); val fs = listOf(card("A.RW2", ByteArray(10) { 1 }), card("B.RW2", ByteArray(20) { 2 }))
        CopyEngine(d, ImportLedger()).run(fs)
        val l = ImportLedger()                                           // an empty ledger: the app's data was cleared
        val r = CopyEngine(d, l).run(fs)
        assertEquals(0, r.copied); assertEquals(2, r.skipped); assertEquals(setOf("A.RW2", "B.RW2"), d.list()!!.toSet()); assertTrue(l.has(fs[0]))
    }

    @Test fun sameNameDifferentCardGetsSuffix() {
        val d = tmp(); val l = ImportLedger()
        CopyEngine(d, l).run(listOf(card("P1.RW2", ByteArray(10) { 1 })))
        val r = CopyEngine(d, l).run(listOf(card("P1.RW2", ByteArray(11) { 2 })))
        assertEquals(1, r.copied); assertTrue(File(d, "P1_2.RW2").exists()); assertEquals(10, File(d, "P1.RW2").length())
    }

    @Test fun diskFullUpFrontIsReportedNotThrown() {
        val d = tmp(); val r = CopyEngine(d, ImportLedger(), free = FreeSpace { 1000 }).run(listOf(card("A.RW2", ByteArray(5000))))
        assertEquals(1, r.failed); assertTrue(r.results[0].note.contains("space")); assertEquals(0, d.list()!!.size)
    }

    @Test fun diskFullPartWayThroughALeavesNothingAndTheNextFileStillTries() {
        val d = tmp(); val l = ImportLedger(); var n = 0
        val full: (File) -> FileOutputStream = { f -> if (n++ == 0) object : FileOutputStream(f) { var w = 0L
            override fun write(b: ByteArray, off: Int, len: Int) { w += len; if (w > 300_000) throw IOException("No space left on device"); super.write(b, off, len) } } else FileOutputStream(f) }
        val r = CopyEngine(d, l, openPart = full).run(listOf(card("BIG.RW2", ByteArray(1_000_000) { 5 }), card("SMALL.RW2", ByteArray(100) { 6 })))
        assertEquals(listOf(Outcome.FAILED, Outcome.COPIED), r.results.map { it.outcome }); assertTrue(r.results[0].note.contains("No space"))
        assertEquals(listOf("SMALL.RW2"), d.list()!!.sorted())
    }

    @Test fun aCardPulledMidFileLeavesNoPartAndOthersContinue() {
        val d = tmp(); val l = ImportLedger()
        val bad = CardFile("BAD.RW2", 100, 1) { object : InputStream() { var n = 0
            override fun read(): Int = if (n++ < 50) 1 else throw IOException("card removed") } }
        val r = CopyEngine(d, l).run(listOf(bad, card("OK.RW2", ByteArray(8) { 3 })))
        assertEquals(listOf(Outcome.FAILED, Outcome.COPIED), r.results.map { it.outcome })
        assertEquals(listOf("OK.RW2"), d.list()!!.sorted()); assertFalse(l.has(bad))
        assertTrue(r.reportText().contains("Failed BAD.RW2: card removed"))
    }

    @Test fun aProviderThatThrowsSomethingElseOnlyFailsThatFile() {
        val d = tmp(); val boom = CardFile("A.RW2", 10, 1) { throw IllegalStateException("provider died") }
        val r = CopyEngine(d, ImportLedger()).run(listOf(boom, card("B.RW2", ByteArray(3))))
        assertEquals(listOf(Outcome.FAILED, Outcome.COPIED), r.results.map { it.outcome }); assertEquals("provider died", r.results[0].note)
    }

    @Test fun shortReadIsCaught() {
        val d = tmp(); val f = CardFile("S.RW2", 100, 1) { ByteArrayInputStream(ByteArray(60)) }
        val r = CopyEngine(d, ImportLedger()).run(listOf(f)); assertEquals(1, r.failed); assertEquals(0, d.list()!!.size)
    }

    @Test fun anUnknownSizeIsCopiedAndChecked() {
        val d = tmp(); val data = ByteArray(1234) { 9 }
        val r = CopyEngine(d, ImportLedger()).run(listOf(CardFile("U.RW2", -1, 5) { ByteArrayInputStream(data) }))
        assertEquals(1, r.copied); assertEquals(1234L, r.bytes); assertArrayEquals(data, File(d, "U.RW2").readBytes())
    }

    @Test fun cancelBetweenFilesStopsAndARerunResumes() {
        val d = tmp(); val l = ImportLedger(); var done = 0
        val fs = (1..4).map { card("F$it.RW2", ByteArray(10) { _ -> it.toByte() }) }
        val r1 = CopyEngine(d, l).run(fs, cancelled = { done >= 2 }, progress = { n, _ -> done = n })
        assertEquals(2, r1.copied); assertEquals(2, r1.cancelled); assertTrue(r1.summary().contains("2 not started"))
        val r2 = CopyEngine(d, l).run(fs); assertEquals(2, r2.copied); assertEquals(2, r2.skipped)
    }

    @Test fun cancelInTheMiddleOfAFileDropsOnlyThatFile() {
        val d = tmp(); val l = ImportLedger(); var stop = false
        val big = CardFile("BIG.RW2", 2_000_000, 1) { object : InputStream() { var n = 0L
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (n >= 2_000_000) return -1; val k = minOf(len, 2_000_000 - n.toInt()); n += k; if (n >= 600_000) stop = true; return k } } }
        val r = CopyEngine(d, l).run(listOf(card("A.RW2", ByteArray(10)), big, card("C.RW2", ByteArray(10))), cancelled = { stop })
        assertEquals(listOf(Outcome.COPIED, Outcome.CANCELLED, Outcome.CANCELLED), r.results.map { it.outcome })
        assertEquals(listOf("A.RW2"), d.list()!!.sorted()); assertFalse(l.has(big))
    }

    @Test fun strayPartFilesAreCleaned() {
        val d = tmp(); File(d, "X.RW2.part").writeBytes(ByteArray(3))
        CopyEngine(d, ImportLedger()).run(emptyList()); assertEquals(0, d.list()!!.size)
    }

    @Test fun reportAndSpeed() {
        val r = ImportReport(listOf(FileResult("a", Outcome.COPIED, 100L shl 20), FileResult("b", Outcome.SKIPPED_DONE, 0),
            FileResult("c", Outcome.FAILED, 0, "x"), FileResult("d.dng", Outcome.COPIED, 1, dng = DngProbe(DngSupport.PREVIEW_ONLY, 52546, "jxl"))), 5000)
        assertEquals("2 copied, 1 already imported, 1 failed, 1 preview only (decode not supported)", r.summary())
        assertEquals(20.0, r.mbPerSecond, 0.01); assertEquals(LinkSpeed.OK, r.speed)
        assertEquals(LinkSpeed.SLOW, SpeedClass.classify(19.9)); assertEquals(LinkSpeed.OK, SpeedClass.classify(20.0)); assertEquals(LinkSpeed.FAST, SpeedClass.classify(120.0))
        assertEquals(10, SpeedClass.etaSeconds(100L shl 20, 10.0)); assertEquals(-1, SpeedClass.etaSeconds(5, 0.0))
        val t = r.reportText()
        assertTrue(t, t.contains("20.0 MB/s, speed class OK")); assertTrue(t, t.contains("DNG d.dng: compression 52546, PREVIEW_ONLY")); assertTrue(t.contains("Failed c: x"))
    }

    @Test fun aShortRunDoesNotClaimASpeedClassAndTimesOnlyTheCopy() {
        val short = ImportReport(listOf(FileResult("a", Outcome.COPIED, 10L shl 20)), 900)
        assertNull(short.speed); assertTrue(short.reportText().contains("not enough copying to tell"))
        // the whole run took 10 s but the copy part 4 s: the speed is the card's, not diluted by the rest
        assertEquals(25.0, ImportReport(listOf(FileResult("a", Outcome.COPIED, 100L shl 20)), 10_000, 4_000).mbPerSecond, 0.01)
        assertNull(ImportReport(emptyList(), 0).speed)
    }

    @Test fun aDngCopyIsProbedAndShownInTheReport() {
        val d = tmp(); val head = File(imp, "comp52546.dng").readBytes()
        val r = CopyEngine(d, ImportLedger()).run(listOf(card("IMG_1.dng", head), card("IMG_2.DNG", File(imp, "comp8.dng").readBytes()), card("P1.RW2", ByteArray(40))))
        assertEquals(52546, r.results[0].dng!!.compression); assertEquals(DngSupport.PREVIEW_ONLY, r.results[0].dng!!.support)
        assertEquals(DngSupport.SUPPORTED, r.results[1].dng!!.support); assertNull(r.results[2].dng)
        assertEquals(1, r.previewOnly); assertTrue(r.reportText().contains("DNG IMG_1.dng: compression 52546, PREVIEW_ONLY"))
    }

    private fun probe(n: String) = DngProber.probeFile(File(imp, n))
    @Test fun probesSyntheticDngs() {
        assertEquals(DngSupport.SUPPORTED, probe("base_dng.dng").support)
        assertEquals(DngSupport.SUPPORTED, probe("comp1.dng").support)
        assertEquals(DngSupport.SUPPORTED, probe("comp7.dng").support)
        assertEquals(DngSupport.SUPPORTED, probe("comp8.dng").support); assertEquals(8, probe("comp8.dng").compression)
        assertEquals(52546, probe("comp52546.dng").compression); assertEquals(DngSupport.PREVIEW_ONLY, probe("comp52546.dng").support)
        assertEquals(DngSupport.PREVIEW_ONLY, probe("comp9.dng").support); assertEquals(9, probe("comp9.dng").compression)
        assertEquals(DngSupport.PREVIEW_ONLY, probe("comp34892.dng").support); assertEquals(34892, probe("comp34892.dng").compression)
    }

    @Test fun theHeadAndTheFileGiveTheSameAnswer() {
        for (n in listOf("comp1.dng", "comp8.dng", "comp52546.dng")) assertEquals(n, probe(n).compression, DngProber.probe(File(imp, n).readBytes()).compression)
    }

    @Test fun anIfdAtTheEndOfAHugeFileIsFound() {
        // a little endian TIFF whose IFD0 sits at an offset far beyond 1 MiB (as in a file that writes its directory after the image data)
        val off = 5_000_000; val f = File.createTempFile("far", ".dng")
        RandomAccessFile(f, "rw").use { r ->
            r.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)); r.write(le32(off)); r.seek(off.toLong())
            val entries = listOf(Triple(254, 4, 0), Triple(256, 3, 100), Triple(257, 3, 100), Triple(259, 3, 52546), Triple(262, 3, 34892), Triple(50706, 1, 0x00000401))
            r.write(le16(entries.size)); for ((tag, type, v) in entries) { r.write(le16(tag)); r.write(le16(type)); r.write(le32(1)); r.write(le32(v)) }; r.write(le32(0))
        }
        val p = DngProber.probeFile(f); assertEquals(DngSupport.PREVIEW_ONLY, p.support); assertEquals(52546, p.compression)
        assertEquals(DngSupport.UNREADABLE, DngProber.probe(f.inputStream().use { it.readNBytes(1 shl 20) }).support)   // a head only would not have seen it
        f.delete()
    }
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    @Test fun nonDngAndTruncated() {
        assertEquals(DngSupport.NOT_DNG, DngProber.probe("RIFF....".toByteArray()).support)
        assertEquals(DngSupport.NOT_DNG, DngProber.probe(ByteArray(3)).support)
        val head = File(imp, "comp8.dng").readBytes().copyOf(20)
        assertEquals(DngSupport.UNREADABLE, DngProber.probe(head).support)
        assertEquals(DngSupport.UNREADABLE, DngProber.probeFile(File(imp, "does-not-exist.dng")).support)
    }
}
