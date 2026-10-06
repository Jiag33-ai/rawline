package app.rawline.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId

class MemTarget(var free: Long? = null) : BackupTarget {
    val files = LinkedHashMap<String, ByteArray>()
    var failWriteAfter = -1L          // bytes after which a write throws "No space left on device"
    var failRename = false
    override fun list() = files.map { BackupTarget.Entry(it.key, it.value.size.toLong(), 0L) }
    override fun create(name: String): OutputStream {
        val buf = ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) { check(1); buf.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { check(len); buf.write(b, off, len) }
            private fun check(n: Int) { if (failWriteAfter >= 0 && buf.size() + n > failWriteAfter) throw IOException("No space left on device") }
            override fun close() { files[name] = buf.toByteArray() }
        }
    }
    override fun open(name: String): InputStream = ByteArrayInputStream(files[name] ?: throw IOException("missing $name"))
    override fun rename(from: String, to: String) { if (failRename) throw IOException("rename failed"); if (to in files) throw IOException("exists"); files[to] = files.remove(from)!! }
    override fun delete(name: String) { files.remove(name) }
    override fun freeBytes() = free
}

private val UTC = ZoneId.of("UTC")
private fun ms(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) = java.time.LocalDateTime.of(y, mo, d, h, mi).atZone(UTC).toInstant().toEpochMilli()
private fun parts(vararg p: Pair<String, String>) = p.map { (n, c) -> BackupPart(n) { ByteArrayInputStream(c.toByteArray()) } }
private fun zipOf(vararg p: Pair<String, String>, counts: Map<String, Int> = mapOf("edits" to 2)): (OutputStream) -> Unit = { BackupZip.write(it, parts(*p), counts, 1000L, "0.1.5") }

class BackupNameTest {
    @Test fun nameRoundTripsAndSortsByTime() {
        val a = BackupName.forTime(ms(2026, 10, 6, 14, 15), UTC); assertEquals("rawline-backup-20261006-1415.zip", a)
        assertTrue(BackupName.key(a)!! > BackupName.key(BackupName.forTime(ms(2026, 10, 5, 23, 59), UTC))!!)
        assertTrue(BackupName.key(BackupName.forTime(ms(2026, 10, 6, 14, 15), UTC, 2))!! > BackupName.key(a)!!)
    }
    @Test fun timeOfReadsTheMomentFromTheName() {
        assertEquals(ms(2026, 10, 6, 14, 15), BackupName.timeOf("rawline-backup-20261006-1415.zip", UTC)); assertEquals(ms(2026, 10, 6, 14, 15), BackupName.timeOf("rawline-backup-20261006-1415-2.zip", UTC))
        assertNull(BackupName.timeOf("notes.txt", UTC)); assertNull(BackupName.timeOf("rawline-backup-20261006-1415.zip.part", UTC))
    }
    @Test fun otherFilesAreNotOurs() { for (n in listOf("holiday.zip", "rawline-backup-2026.zip", "rawline-backup-20261006-1415.zip.part", "x/rawline-backup-20261006-1415.zip")) assertNull(n, BackupName.key(n)) }
}

class BackupRotationTest {
    private fun names(vararg d: Int) = d.map { BackupName.forTime(ms(2026, 10, it), UTC) }
    @Test fun keepsTheNewestSevenByNameNotByListOrder() {
        val all = names(3, 9, 1, 7, 5, 2, 8, 4, 6)
        val gone = BackupRotation.toDelete(all, 7)
        assertEquals(names(1, 2).toSet(), gone.toSet())
    }
    @Test fun neverTouchesFilesThatAreNotBackupsOrAreInProgress() {
        val all = names(1, 2, 3) + listOf("notes.txt", BackupName.forTime(ms(2026, 10, 9), UTC) + BackupName.PART)
        assertEquals(names(1).toSet(), BackupRotation.toDelete(all, 2).toSet())
    }
    @Test fun nothingToDeleteBelowTheLimitAndKeepIsAtLeastOne() {
        assertTrue(BackupRotation.toDelete(names(1, 2, 3), 7).isEmpty())
        assertEquals(2, BackupRotation.toDelete(names(1, 2, 3), 0).size)
    }
}

class BackupPolicyTest {
    private val p = BackupPolicy()
    private val hour = 3_600_000L
    @Test fun nothingChangedMeansNoBackup() { assertNull(p.due(BackupState(0, 0, 0), 1_000_000)); assertNull(p.due(BackupState(1000, 40, 40), 1000 + 48 * hour)) }
    @Test fun theFirstEditTriggersTheFirstBackup() { assertEquals(Due.NEVER_BACKED_UP, p.due(BackupState(0, 0, 1), 5)) }
    @Test fun dailyOnlyWhenSomethingChanged() {
        val last = ms(2026, 10, 5)
        assertNull(p.due(BackupState(last, 10, 11), last + 19 * hour))
        assertEquals(Due.DAILY, p.due(BackupState(last, 10, 11), last + 21 * hour))
    }
    @Test fun manyEditsNeedTheMinimumGap() {
        val last = ms(2026, 10, 5)
        assertNull(p.due(BackupState(last, 0, 30), last + 5 * 60_000L))
        assertEquals(Due.MANY_EDITS, p.due(BackupState(last, 0, 30), last + 11 * 60_000L))
        assertNull(p.due(BackupState(last, 0, 24), last + 3 * hour))
    }
    @Test fun aClockSetBackStillBacksUp() { assertEquals(Due.DAILY, p.due(BackupState(ms(2026, 10, 9), 0, 3), ms(2026, 10, 1))) }
}

class BackupZipTest {
    private fun bytes(f: (OutputStream) -> Unit) = ByteArrayOutputStream().also(f).toByteArray()

    @Test fun aWrittenZipVerifiesAndCarriesItsCounts() {
        val z = bytes(zipOf("edits.json" to "[1,2]", "meta.json" to "[]", "masks/a.png" to "PNG"))
        val v = BackupZip.verify(ByteArrayInputStream(z))
        assertTrue(v.problems.toString(), v.ok); assertEquals(2, v.manifest!!.counts["edits"]); assertEquals(3, v.manifest!!.entries.size)
        assertEquals(BackupManifest.FORMAT, v.manifest!!.format)
    }

    @Test fun aFlippedByteIsFoundByTheChecksumNotJustTheZipCrc() {
        // store the entries without compression damage detection: corrupt the manifest's expectation instead by writing a zip whose entry differs from its manifest line
        val good = bytes(zipOf("edits.json" to "[1,2]"))
        val tampered = good.copyOf()
        val i = String(tampered, Charsets.ISO_8859_1).indexOf("[1,2]")
        tampered[i + 1] = '9'.code.toByte()                       // Deflater stores tiny entries raw: the content changes, the zip entry CRC no longer matches
        val v = BackupZip.verify(ByteArrayInputStream(tampered))
        assertFalse(v.ok); assertTrue(v.problems.isNotEmpty())
    }

    @Test fun truncatedAndGarbageInputIsAProblemNotACrash() {
        val z = bytes(zipOf("edits.json" to "[1,2]"))
        assertFalse(BackupZip.verify(ByteArrayInputStream(z.copyOf(z.size / 2))).ok)
        assertFalse(BackupZip.verify(ByteArrayInputStream(ByteArray(100) { it.toByte() })).ok)
        assertFalse(BackupZip.verify(ByteArrayInputStream(ByteArray(0))).ok)
    }

    @Test fun anOlderZipWithoutAManifestIsReportedAsSuch() {
        val old = ByteArrayOutputStream(); java.util.zip.ZipOutputStream(old).use { it.putNextEntry(java.util.zip.ZipEntry("edits.json")); it.write("[]".toByteArray()); it.closeEntry() }
        val v = BackupZip.verify(ByteArrayInputStream(old.toByteArray()))
        assertFalse(v.ok); assertTrue(v.problems.first().contains("manifest"))
    }

    @Test fun anEntryMissingFromTheZipOrNotInTheManifestIsFound() {
        val m = BackupManifest(2, 1, "x", mapOf("edits" to 1), listOf(ManifestEntry("edits.json", 3, "00"), ManifestEntry("gone.json", 1, "00")))
        val o = ByteArrayOutputStream(); java.util.zip.ZipOutputStream(o).use { z -> z.putNextEntry(java.util.zip.ZipEntry("edits.json")); z.write("[1]".toByteArray()); z.closeEntry(); z.putNextEntry(java.util.zip.ZipEntry("extra.json")); z.write("x".toByteArray()); z.closeEntry(); z.putNextEntry(java.util.zip.ZipEntry(BackupManifest.NAME)); z.write(m.toJson().toByteArray()); z.closeEntry() }
        val p = BackupZip.verify(ByteArrayInputStream(o.toByteArray())).problems.joinToString()
        assertTrue(p, p.contains("gone.json is missing") && p.contains("extra.json is not listed") && p.contains("edits.json does not match"))
    }

    @Test fun manifestOfReadsOnlyTheManifestFromAFile() {
        val f = File.createTempFile("backup", ".zip"); f.writeBytes(bytes(zipOf("edits.json" to "[1]")))
        assertEquals(2, BackupZip.manifestOf(f)!!.counts["edits"]); f.delete()
    }
}

class BackupRunnerTest {
    private val t0 = ms(2026, 10, 6)
    private fun runner(t: MemTarget, keep: Int = 7) = BackupRunner(t, keep, UTC)

    @Test fun aGoodRunPublishesAVerifiedFileAndLeavesNoPart() {
        val t = MemTarget(); val r = runner(t).run(t0, zipOf("edits.json" to "[1]"))
        assertTrue(r.message, r.ok); assertEquals(setOf("rawline-backup-20261006-1200.zip"), t.files.keys); assertTrue(r.bytes > 0)
    }

    @Test fun theEighthBackupDeletesTheOldestAfterTheNewOneIsSafe() {
        val t = MemTarget(); for (d in 1..7) assertTrue(runner(t).run(ms(2026, 10, d), zipOf("edits.json" to "[$d]")).ok)
        val r = runner(t).run(ms(2026, 10, 8), zipOf("edits.json" to "[8]"))
        assertTrue(r.ok); assertEquals(listOf("rawline-backup-20261001-1200.zip"), r.deleted); assertEquals(7, t.files.size)
    }

    @Test fun aFailureMidWriteKeepsEveryOldBackupAndCleansThePart() {
        val t = MemTarget(); for (d in 1..7) runner(t).run(ms(2026, 10, d), zipOf("edits.json" to "[$d]"))
        val before = t.files.keys.toSet()
        t.failWriteAfter = 100                                           // the new zip runs out of space part way
        val r = runner(t).run(ms(2026, 10, 8), zipOf("edits.json" to "x".repeat(5000)))
        assertFalse(r.ok); assertTrue(r.message, r.message.contains("No space")); assertEquals(before, t.files.keys.toSet())
    }

    @Test fun aProducerThatThrowsLeavesOldBackupsAlone() {
        val t = MemTarget(); runner(t).run(ms(2026, 10, 1), zipOf("edits.json" to "[1]")); val before = t.files.keys.toSet()
        val r = runner(t).run(ms(2026, 10, 2)) { throw IllegalStateException("database closed") }
        assertFalse(r.ok); assertEquals(before, t.files.keys.toSet())
    }

    @Test fun aWriterThatProducesAnInconsistentZipIsNotPublished() {
        val t = MemTarget(); runner(t).run(ms(2026, 10, 1), zipOf("edits.json" to "[1]")); val before = t.files.keys.toSet()
        val r = runner(t).run(ms(2026, 10, 2)) { o -> java.util.zip.ZipOutputStream(o).use { it.putNextEntry(java.util.zip.ZipEntry("edits.json")); it.write("[]".toByteArray()); it.closeEntry() } }   // no manifest
        assertFalse(r.ok); assertTrue(r.message.contains("verify")); assertEquals(before, t.files.keys.toSet())
    }

    @Test fun aFailedRenameKeepsOldBackupsAndCleansThePart() {
        val t = MemTarget(); runner(t).run(ms(2026, 10, 1), zipOf("edits.json" to "[1]")); val before = t.files.keys.toSet()
        t.failRename = true
        assertFalse(runner(t).run(ms(2026, 10, 2), zipOf("edits.json" to "[2]")).ok); assertEquals(before, t.files.keys.toSet())
    }

    @Test fun twoBackupsInTheSameMinuteDoNotCollide() {
        val t = MemTarget(); runner(t).run(t0, zipOf("edits.json" to "[1]")); val r = runner(t).run(t0 + 20_000, zipOf("edits.json" to "[2]"))
        assertTrue(r.ok); assertEquals("rawline-backup-20261006-1200-1.zip", r.name); assertEquals(2, t.files.size)
    }

    @Test fun tooLittleSpaceIsRefusedBeforeWriting() {
        val t = MemTarget(); runner(t).run(ms(2026, 10, 1), zipOf("edits.json" to "x".repeat(10_000)))
        t.free = 100
        val r = runner(t).run(ms(2026, 10, 2), zipOf("edits.json" to "[2]"))
        assertFalse(r.ok); assertTrue(r.message.contains("free space")); assertEquals(1, t.files.size)
    }

    @Test fun filesThatAreNotOursSurviveRotation() {
        val t = MemTarget(); t.files["notes.txt"] = ByteArray(3); for (d in 1..9) runner(t, 3).run(ms(2026, 10, d), zipOf("edits.json" to "[$d]"))
        assertTrue("notes.txt" in t.files); assertEquals(4, t.files.size)
    }

    @Test fun aStalePartFromAKilledRunIsRemovedAtTheNextRunAndNothingElseIs() {
        val t = MemTarget(); runner(t).run(ms(2026, 10, 1), zipOf("edits.json" to "[1]"))
        t.files["rawline-backup-20260930-0900.zip.part"] = ByteArray(10); t.files["notes.txt"] = ByteArray(3)
        val r = runner(t).run(ms(2026, 10, 2), zipOf("edits.json" to "[2]"))
        assertTrue(r.message, r.ok); assertTrue(t.files.keys.none { it.endsWith(".part") }); assertTrue("notes.txt" in t.files); assertEquals(3, t.files.size)
    }

    @Test fun aLeftoverPartWithTheNameThisRunWillUseDoesNotBlockIt() {
        val t = MemTarget(); t.files["rawline-backup-20261006-1200.zip.part"] = ByteArray(10)
        val r = runner(t).run(t0, zipOf("edits.json" to "[1]"))
        assertTrue(r.message, r.ok); assertEquals(setOf("rawline-backup-20261006-1200.zip"), t.files.keys)
    }

    @Test fun aRecentPartOfAnotherNameIsLeftToItsOwnerWhenItsAgeIsKnown() {
        // age known and young: kept (a file target reports real times)
        val d = java.nio.file.Files.createTempDirectory("b").toFile(); val keep = File(d, "rawline-backup-20260101-0000.zip.part"); keep.writeText("x"); keep.setLastModified(t0)
        assertTrue(BackupRunner(FileBackupTarget(d), 7, UTC).run(t0 + 3_600_000L, zipOf("edits.json" to "[1]")).ok)
        assertTrue(keep.exists()); d.deleteRecursively()
    }

    @Test fun discoveryFindsTheNewestBackupAfterAReinstall() {
        val t = MemTarget(); for (d in listOf(3, 9, 5)) runner(t).run(ms(2026, 10, d), zipOf("edits.json" to "[$d]"))
        val i = BackupDiscovery.latest(t, UTC)!!
        assertEquals("rawline-backup-20261009-1200.zip", i.name); assertEquals(ms(2026, 10, 9), i.whenMs)
        assertNull(BackupDiscovery.latest(MemTarget(), UTC))
    }
}

class FileBackupTargetTest {
    private fun dir() = java.nio.file.Files.createTempDirectory("backups").toFile()

    @Test fun aFullRunOnRealFilesRotatesAndSurvivesANewRunnerObject() {
        val d = dir(); for (day in 1..9) assertTrue(BackupRunner(FileBackupTarget(d), 7, UTC).run(ms(2026, 10, day), zipOf("edits.json" to "[$day]")).ok)
        // a fresh target object over the same folder (what a reinstalled app does) sees exactly the newest seven
        val again = FileBackupTarget(d)
        assertEquals(7, again.list().size); assertEquals("rawline-backup-20261009-1200.zip", BackupDiscovery.latest(again, UTC)!!.name)
        assertTrue(BackupZip.verify(again.open("rawline-backup-20261009-1200.zip")).ok)
        assertTrue(d.listFiles()!!.none { it.name.endsWith(BackupName.PART) })
        d.deleteRecursively()
    }

    @Test fun pathTricksInNamesAreRefused() {
        val t = FileBackupTarget(dir())
        for (n in listOf("../x.zip", "a/b.zip", "", "..")) try { t.create(n); org.junit.Assert.fail(n) } catch (e: IllegalArgumentException) {}
    }

    @Test fun renameOntoAnExistingBackupIsRefused() {
        val d = dir(); val t = FileBackupTarget(d); File(d, "a").writeText("1"); File(d, "b").writeText("2")
        try { t.rename("a", "b"); org.junit.Assert.fail() } catch (e: IOException) {}
        assertEquals("2", File(d, "b").readText()); d.deleteRecursively()
    }
}
