# W15 Card import (SD / USB-C reader), engine plus DNG decode risk

Covers BK-096, BK-344, BK-431 (engine). UI is W17. Status at writing: main d6c5afc. Nothing here is merged. The Kotlin below was compiled with Kotlin 2.4.10 and run on the host JVM: 14 JUnit tests pass. The Android parts (SAF tree walk, USB attach broadcast, foreground service) are skeletons and were NOT compiled.

## 1. Goal
Jai plugs a card reader into the S24 Ultra, taps Import, and the RAWs land in `DCIM/Rawline` on the phone, verified, with no duplicates on the second insert, then show up in the Library. The same engine serves the S5IIX card (RW2) and Samsung Expert RAW (DNG).

## 2. Decisions (no questions left)
- D1 Destination: `/storage/emulated/0/DCIM/Rawline/<yyyy-MM-dd>/` via plain `java.io.File` (the app already holds All-files access, so `DeviceScanner` finds the copies by MediaStore after a scan). One folder per import day. `CopyEngine` is given that folder.
- D2 Source: SAF tree from `ACTION_OPEN_DOCUMENT_TREE` (the card appears as a volume), walk `DCIM/**`, keep `ImportNaming.isRaw`. Each `CardFile.open` is `contentResolver.openInputStream(docUri)`. Persist the tree URI so the next insert needs one tap. USB attach broadcast is a convenience only (D5).
- D3 Safety order: write `<name>.part`, fsync, re-read and compare SHA-256 against the bytes streamed from the card, rename, set mtime to the card's mtime, then add to the ledger. A kill at any point leaves only `.part` files, deleted at the next run. The card is never written to or deleted from (no "move" mode in this task).
- D4 Dedupe: `ImportLedger` (file `files/import-ledger.txt`, one key per line: lowercase name, size, mtime/2s because FAT stores 2 s). It is separate from `Photo.keyOf` (`name|size|modified`) on purpose: after the copy the mtime is restored, so `keyOf` of the copy equals the card file's and the Library does not see duplicates; the ledger additionally stops re-copying files Jai has since deleted from the phone only if he leaves "remember imported" on (default on, a Settings toggle, W17).
- D5 Trigger: manual button "Import from card" in Library menu and Settings. Optionally a `USB_DEVICE_ATTACHED` intent filter later; not in this task.
- D6 Speed: `SpeedClass.classify` on the sustained rate after 2 s; SLOW (under 20 MB/s) shows "Slow reader or card, this will take about N minutes" with `etaSeconds`. Never claim a measured number without the phone's Copy report: the engine writes `ImportReport` into the existing Copy report (W17 adds the line).
- D7 Unsupported DNG: copied anyway (never lose a file), flagged `PREVIEW_ONLY`, Library shows a "Preview only" badge and the editor opens the embedded preview read-only with the text "This DNG type cannot be developed yet". Never a silent grey frame (current behaviour: LibRaw `unpack` returns -2 and the failure is silent; see BK entries logged with this file).
- D8 Foreground service type `dataSync` with a progress notification and a Cancel action (`cancelled = { flag }`). Cancel keeps finished files.

## 3. Samsung Expert RAW DNG decode risk
Measured with a host build of LibRaw 0.22.2 configured like the app (USE_ZLIB on, no USE_JPEG, no DNG SDK) on synthetic DNGs differing only in the Compression tag (files made by scratchpad/imp, same pixels):

| Compression | Meaning | open_file | unpack |
|---|---|---|---|
| 1, 7 | none, lossless JPEG | OK | OK |
| 8 | deflate | OK | OK (needs USE_ZLIB, on since 2c85bf2) |
| 52546 | JPEG XL | OK | -2 UNSUPPORTED_FORMAT (`jxl_dng_load_raw_placeholder`) |
| 9 | VC-5 | OK | -2 |
| 34892 | lossy JPEG | -2 | not reached (stub without USE_JPEG) |

Risk statement: I do not have a real Expert RAW file, so I do not know which compression the S24 Ultra writes. Samsung Expert RAW is documented as linear DNG; recent Android camera stacks use JPEG XL for 16-bit linear DNG (Android 14 added it), so JXL is the most likely case and it cannot be developed today. This is a hypothesis to test, not a finding.

Spike (Jai, phone only, 5 minutes): take 3 photos in Expert RAW (a daylight scene, a night scene, a Pro mode 50 MP), copy them to `DCIM/Rawline`, then run the app's Copy report after W17's probe line lands. The line prints per file: compression number and SUPPORTED / PREVIEW_ONLY. Decision table once known:
- All 7 or 8: nothing more to do.
- 52546: pick one of (a) vendor libjxl (BSD, build via FetchContent from a non-GitHub mirror if reachable; GitHub is blocked in the sandbox, so the pin must come from a release tarball host that the sandbox can reach, to be checked) plus a small `jxl_dng_load_raw` in a LibRaw patch, estimated 2 to 4 days; (b) Android's `ImageDecoder` JXL path where the platform has it (API 34+, S24U has it): decode to RGBA_F16 and feed the engine as linear, skipping LibRaw colour for those files, estimated 1 day, needs a device test; (c) leave PREVIEW_ONLY. Recommended order: (b) first because the S24U runs a platform that decodes it, (a) only if (b) loses precision.
- 34892: build LibRaw with USE_JPEG and libjpeg-turbo; separate task.
Acceptance for the spike is the three compression numbers pasted in the thread. No engine change in W15.

## 4. Android skeletons (not compiled)
```kotlin
// feature/import/CardSource.kt
class SafCardSource(private val cr: ContentResolver, private val tree: Uri) {
    fun list(): List<CardFile> {
        val out = ArrayList<CardFile>()
        fun walk(doc: Uri, id: String) {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            cr.query(kids, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val cid = c.getString(0); val name = c.getString(1)
                    if (c.getString(4) == Document.MIME_TYPE_DIR) walk(doc, cid)
                    else if (ImportNaming.isRaw(name)) {
                        val u = DocumentsContract.buildDocumentUriUsingTree(tree, cid)
                        out.add(CardFile(name, c.getLong(2), c.getLong(3)) { cr.openInputStream(u)!! })
                    }
                }
            }
        }
        walk(tree, DocumentsContract.getTreeDocumentId(tree)); return out
    }
}
// app/ImportService.kt: Service with foregroundServiceType="dataSync", FOREGROUND_SERVICE_DATA_SYNC permission,
// runs CopyEngine on Dispatchers.IO, notifies progress, on finish triggers DeviceScanner.scan() and posts ImportReport.summary().
```
Manifest: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>` and `<service android:name=".ImportService" android:foregroundServiceType="dataSync" android:exported="false"/>`.

## 5. Where the files go
- `core/data/src/main/kotlin/app/rawline/core/data/ingest/CardImport.kt` (section 7), inside the Owns set `core/data/ingest/**`.
- `core/data/src/test/kotlin/app/rawline/core/data/ingest/CardImportTest.kt` (section 7), inside the Owns set `core/data/ingest/**`. The probe test reads seven fixtures that are just the first 8192 bytes of the synthetic DNGs (IFDs sit at the start; verified, all 14 tests pass on the truncated copies). Create them with `head -c 8192 comp8.dng > core/data/src/test/resources/dng/comp8.dng` for base_dng, comp1, comp7, comp8, comp9, comp34892, comp52546 (source files: scratchpad/imp/, made by rewriting tag 259 of one W21-style DNG; if lost, rebuild with the W21 `make_dng.py` and patch the Compression value). Total 56 KB. Test reads `System.getProperty("imp.dir")`: set it in `core/data/build.gradle.kts` `tasks.withType<Test> { systemProperty("imp.dir", file("src/test/resources/dng").path) }`.
- Check the `core/data` module already allows a plain JVM test source set with JUnit 4 (it has `PhotoKeyTest` in `core/model`; confirm for `core/data`).

## 6. Acceptance
1. `./gradlew :core:data:testDebugUnitTest` passes all 14 tests.
2. On the phone: import 20 RW2 from a card; Library shows 20 new items; insert again, report says "0 copied, 20 already imported".
3. Pull the card mid-copy: report shows failed rows, no `.part` left after the next run, finished files intact.
4. Cancel from the notification keeps finished files.
5. The Copy report line lists MB/s and the speed class. No speed claim without it.

## 7. Embedded source
### CardImport.kt
```kotlin
package app.rawline.core.data.ingest

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/** Pure JVM pieces of the card import (BK-096/344/431). No android.* imports, so host tests run them. */

enum class DngSupport { SUPPORTED, PREVIEW_ONLY, NOT_DNG, UNREADABLE }

data class DngProbe(val support: DngSupport, val compression: Int, val reason: String)

/** Reads only the TIFF IFD chain (first 1 MiB is enough for every DNG seen); never decodes pixels. */
object DngProber {
    private const val PHOTO_CFA = 32803
    private const val PHOTO_LINEAR = 34892

    fun probe(head: ByteArray): DngProbe {
        if (head.size < 8) return DngProbe(DngSupport.NOT_DNG, -1, "too short")
        val le = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> true
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> false
            else -> return DngProbe(DngSupport.NOT_DNG, -1, "no TIFF header")
        }
        fun u16(o: Int): Int? = if (o < 0 || o + 2 > head.size) null else
            if (le) (head[o].toInt() and 255) or ((head[o + 1].toInt() and 255) shl 8)
            else ((head[o].toInt() and 255) shl 8) or (head[o + 1].toInt() and 255)
        fun u32(o: Int): Long? {
            val a = u16(o) ?: return null; val b = u16(o + 2) ?: return null
            return if (le) a.toLong() or (b.toLong() shl 16) else (a.toLong() shl 16) or b.toLong()
        }
        if (u16(2) != 42) return DngProbe(DngSupport.NOT_DNG, -1, "bad TIFF magic")
        data class Ifd(val comp: Int, val photo: Int, val pixels: Long, val hasDngVersion: Boolean)
        val ifds = ArrayList<Ifd>()
        var sawDng = false; var truncated = false
        val queue = ArrayDeque<Long>(); val seen = HashSet<Long>()
        u32(4)?.let { queue.add(it) }
        while (queue.isNotEmpty() && ifds.size < 16) {
            val off = queue.removeFirst()
            if (!seen.add(off) || off > Int.MAX_VALUE) continue
            val n = u16(off.toInt()) ?: return DngProbe(DngSupport.UNREADABLE, -1, "IFD beyond probe window")
            var comp = 0; var photo = 0; var w = 0L; var h = 0L
            for (i in 0 until n) {
                val e = off.toInt() + 2 + i * 12
                val tag = u16(e); val type = u16(e + 2); val cnt = u32(e + 4)
                if (tag == null || type == null || cnt == null) { truncated = true; break }
                val inline: Long = if (type == 3) (u16(e + 8) ?: 0).toLong() else (u32(e + 8) ?: 0)
                when (tag) {
                    259 -> comp = inline.toInt()
                    262 -> photo = inline.toInt()
                    256 -> w = inline
                    257 -> h = inline
                    50706 -> sawDng = true
                    330 -> { // SubIFDs: one LONG inline, or an array at an offset
                        if (cnt == 1L) queue.add(inline)
                        else for (k in 0 until minOf(cnt, 8L).toInt()) u32(inline.toInt() + 4 * k)?.let { queue.add(it) }
                    }
                }
            }
            ifds.add(Ifd(comp, photo, w * h, sawDng))
            val next = u32(off.toInt() + 2 + n * 12)
            if (next != null && next != 0L) queue.add(next)
        }
        if (!sawDng && truncated) return DngProbe(DngSupport.UNREADABLE, -1, "IFD beyond probe window")
        if (!sawDng) return DngProbe(DngSupport.NOT_DNG, -1, "no DNGVersion tag")
        val raw = ifds.filter { it.photo == PHOTO_CFA || it.photo == PHOTO_LINEAR }.maxByOrNull { it.pixels }
            ?: return DngProbe(DngSupport.UNREADABLE, -1, "no raw IFD found")
        return when (raw.comp) {
            1, 7, 8 -> DngProbe(DngSupport.SUPPORTED, raw.comp, "uncompressed, lossless JPEG or deflate")
            52546 -> DngProbe(DngSupport.PREVIEW_ONLY, raw.comp, "JPEG XL raw (Expert RAW style): decode needs libjxl or the DNG SDK")
            9 -> DngProbe(DngSupport.PREVIEW_ONLY, raw.comp, "VC-5 raw: decode not available")
            34892 -> DngProbe(DngSupport.PREVIEW_ONLY, raw.comp, "lossy JPEG DNG: needs a JPEG-enabled LibRaw build")
            else -> DngProbe(DngSupport.PREVIEW_ONLY, raw.comp, "unknown compression ${raw.comp}")
        }
    }

    fun probeFile(f: File): DngProbe = try {
        f.inputStream().use { val b = ByteArray(1 shl 20); val n = readFully(it, b); probe(b.copyOf(n)) }
    } catch (e: Exception) { DngProbe(DngSupport.UNREADABLE, -1, e.message ?: "io") }

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }
}

/** One source file on the card. */
data class CardFile(val name: String, val size: Long, val modifiedMs: Long, val open: () -> InputStream)

object ImportNaming {
    private val RAW_EXT = setOf("rw2", "dng", "orf", "cr2", "cr3", "nef", "arw", "raf")
    fun isRaw(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in RAW_EXT && !name.startsWith(".")

    /** Same name, different content: `P1000123.RW2` -> `P1000123_2.RW2`. Case-insensitive because the target may be exFAT or sdcardfs. */
    fun unique(name: String, taken: Set<String>): String {
        val low = taken.map { it.lowercase(Locale.ROOT) }.toHashSet()
        if (name.lowercase(Locale.ROOT) !in low) return name
        val stem = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
        var i = 2
        while (true) {
            val cand = if (ext.isEmpty()) "${stem}_$i" else "${stem}_$i.$ext"
            if (cand.lowercase(Locale.ROOT) !in low) return cand
            i++
        }
    }
}

/** Remembers what was imported so a second insert of the same card copies nothing. Key ignores the destination mtime. */
class ImportLedger(private val lines: MutableSet<String> = linkedSetOf()) {
    fun key(f: CardFile) = "${f.name.lowercase(Locale.ROOT)}|${f.size}|${f.modifiedMs / 2000}" // FAT stores 2 s resolution
    fun has(f: CardFile) = key(f) in lines
    fun add(f: CardFile) { lines.add(key(f)) }
    fun serialise(): String = lines.joinToString("\n")
    companion object { fun parse(s: String) = ImportLedger(s.lineSequence().filter { it.isNotBlank() }.toCollection(linkedSetOf())) }
}

enum class Outcome { COPIED, SKIPPED_DONE, FAILED, CANCELLED }
data class FileResult(val name: String, val outcome: Outcome, val bytes: Long, val note: String = "", val dng: DngProbe? = null)
data class ImportReport(val results: List<FileResult>, val millis: Long) {
    val copied get() = results.count { it.outcome == Outcome.COPIED }
    val skipped get() = results.count { it.outcome == Outcome.SKIPPED_DONE }
    val failed get() = results.count { it.outcome == Outcome.FAILED }
    val bytes get() = results.filter { it.outcome == Outcome.COPIED }.sumOf { it.bytes }
    val previewOnly get() = results.count { it.dng?.support == DngSupport.PREVIEW_ONLY }
    val mbPerSecond get() = if (millis <= 0) 0.0 else bytes / 1048576.0 / (millis / 1000.0)
    fun summary(): String = buildString {
        append("$copied copied, $skipped already imported, $failed failed")
        if (previewOnly > 0) append(", $previewOnly preview only (decode not supported)")
    }
}

fun interface FreeSpace { fun usable(): Long }

/**
 * Copies one file at a time: write `<name>.part`, fsync, verify by re-reading SHA-256 of source and part, rename.
 * A killed run leaves only .part files, which the next run deletes; finished files are never touched.
 */
class CopyEngine(
    private val destDir: File,
    private val ledger: ImportLedger,
    private val free: FreeSpace = FreeSpace { destDir.usableSpace },
    private val reserveBytes: Long = 64L shl 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun run(files: List<CardFile>, cancelled: () -> Boolean = { false }, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportReport {
        val t0 = clock()
        destDir.mkdirs()
        destDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        val out = ArrayList<FileResult>()
        files.forEachIndexed { i, f ->
            progress(i, files.size)
            if (cancelled()) { out.add(FileResult(f.name, Outcome.CANCELLED, 0)); return@forEachIndexed }
            if (ledger.has(f)) { out.add(FileResult(f.name, Outcome.SKIPPED_DONE, 0)); return@forEachIndexed }
            if (free.usable() < f.size + reserveBytes) { out.add(FileResult(f.name, Outcome.FAILED, 0, "not enough space on the phone")); return@forEachIndexed }
            out.add(copyOne(f))
        }
        progress(files.size, files.size)
        return ImportReport(out, clock() - t0)
    }

    private fun copyOne(f: CardFile): FileResult {
        val name = ImportNaming.unique(f.name, destDir.list()?.toSet().orEmpty())
        val part = File(destDir, "$name.part"); val dest = File(destDir, name)
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.open().use { input -> part.outputStream().use { o -> pump(input, o, md); o.flush(); o.fd.sync() } }
            val srcSum = md.digest()
            if (part.length() != f.size) { part.delete(); return FileResult(f.name, Outcome.FAILED, 0, "short read (${part.length()} of ${f.size} bytes)") }
            val back = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { pump(it, null, back) }
            if (!back.digest().contentEquals(srcSum)) { part.delete(); return FileResult(f.name, Outcome.FAILED, 0, "verify failed") }
            if (!part.renameTo(dest)) { part.delete(); return FileResult(f.name, Outcome.FAILED, 0, "rename failed") }
            dest.setLastModified(f.modifiedMs) // keep mtime so Photo.keyOf stays stable
            ledger.add(f)
            val probe = if (name.lowercase(Locale.ROOT).endsWith(".dng")) DngProber.probeFile(dest) else null
            FileResult(name, Outcome.COPIED, f.size, dng = probe)
        } catch (e: java.io.IOException) {
            part.delete(); FileResult(f.name, Outcome.FAILED, 0, e.message ?: "io error")
        }
    }

    private fun pump(i: InputStream, o: OutputStream?, md: MessageDigest) {
        val b = ByteArray(256 * 1024)
        while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n); o?.write(b, 0, n) }
    }
}

enum class LinkSpeed { SLOW, OK, FAST }
object SpeedClass {
    /** Sustained MB/s over at least 2 s of copying. Below 20 usually means a USB 2 reader or a slow card. */
    fun classify(mbPerSecond: Double) = when { mbPerSecond < 20 -> LinkSpeed.SLOW; mbPerSecond < 80 -> LinkSpeed.OK; else -> LinkSpeed.FAST }
    fun etaSeconds(remainingBytes: Long, mbPerSecond: Double): Long =
        if (mbPerSecond <= 0.01) -1 else (remainingBytes / 1048576.0 / mbPerSecond).toLong()
}
```
### CardImportTest.kt
```kotlin
package app.rawline.core.data.ingest

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class CardImportTest {
    private val imp = File(System.getProperty("imp.dir") ?: "..")
    private fun tmp() = Files.createTempDirectory("imp").toFile()
    private fun card(name: String, bytes: ByteArray, mtime: Long = 1_700_000_000_000) =
        CardFile(name, bytes.size.toLong(), mtime) { ByteArrayInputStream(bytes) }

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
    @Test fun ledgerRoundTripAndFatResolution() {
        val l = ImportLedger(); val f = card("A.RW2", ByteArray(5), 1_700_000_000_000)
        assertFalse(l.has(f)); l.add(f)
        assertTrue(l.has(card("a.rw2", ByteArray(5), 1_700_000_001_000)))   // 1 s drift on FAT
        assertFalse(l.has(card("a.rw2", ByteArray(6), 1_700_000_000_000)))
        assertTrue(ImportLedger.parse(l.serialise()).has(f))
    }
    @Test fun copiesVerifiesAndKeepsMtime() {
        val d = tmp(); val l = ImportLedger(); val data = ByteArray(1_000_003) { (it * 7).toByte() }
        val r = CopyEngine(d, l).run(listOf(card("A.RW2", data)))
        assertEquals(1, r.copied); assertArrayEquals(data, File(d, "A.RW2").readBytes())
        assertEquals(1_700_000_000_000, File(d, "A.RW2").lastModified())
        assertTrue(d.list()!!.none { it.endsWith(".part") })
    }
    @Test fun secondInsertCopiesNothing() {
        val d = tmp(); val l = ImportLedger(); val fs = listOf(card("A.RW2", ByteArray(10) { 1 }), card("B.RW2", ByteArray(20) { 2 }))
        CopyEngine(d, l).run(fs)
        val r = CopyEngine(d, ImportLedger.parse(l.serialise())).run(fs)
        assertEquals(0, r.copied); assertEquals(2, r.skipped); assertEquals(2, d.list()!!.size)
    }
    @Test fun sameNameDifferentCardGetsSuffix() {
        val d = tmp(); val l = ImportLedger()
        CopyEngine(d, l).run(listOf(card("P1.RW2", ByteArray(10) { 1 })))
        val r = CopyEngine(d, l).run(listOf(card("P1.RW2", ByteArray(11) { 2 })))
        assertEquals(1, r.copied); assertTrue(File(d, "P1_2.RW2").exists()); assertEquals(10, File(d, "P1.RW2").length())
    }
    @Test fun diskFullIsReportedNotThrown() {
        val d = tmp(); val r = CopyEngine(d, ImportLedger(), free = FreeSpace { 1000 }).run(listOf(card("A.RW2", ByteArray(5000))))
        assertEquals(1, r.failed); assertTrue(r.results[0].note.contains("space")); assertEquals(0, d.list()!!.size)
    }
    @Test fun readErrorMidFileLeavesNoPartAndOthersContinue() {
        val d = tmp(); val l = ImportLedger()
        val bad = CardFile("BAD.RW2", 100, 1) { object : InputStream() { var n = 0
            override fun read(): Int = if (n++ < 50) 1 else throw IOException("card removed") } }
        val r = CopyEngine(d, l).run(listOf(bad, card("OK.RW2", ByteArray(8) { 3 })))
        assertEquals(listOf(Outcome.FAILED, Outcome.COPIED), r.results.map { it.outcome })
        assertTrue(d.list()!!.sorted() == listOf("OK.RW2")); assertFalse(l.has(bad))
    }
    @Test fun shortReadIsCaught() {
        val d = tmp(); val f = CardFile("S.RW2", 100, 1) { ByteArrayInputStream(ByteArray(60)) }
        val r = CopyEngine(d, ImportLedger()).run(listOf(f)); assertEquals(1, r.failed); assertEquals(0, d.list()!!.size)
    }
    @Test fun cancelStopsAndRerunResumes() {
        val d = tmp(); val l = ImportLedger(); var calls = 0
        val fs = (1..4).map { card("F$it.RW2", ByteArray(10) { _ -> it.toByte() }) }
        val r1 = CopyEngine(d, l).run(fs, cancelled = { calls++ >= 2 })
        assertEquals(2, r1.copied); assertEquals(2, r1.results.count { it.outcome == Outcome.CANCELLED })
        val r2 = CopyEngine(d, l).run(fs); assertEquals(2, r2.copied); assertEquals(2, r2.skipped)
    }
    @Test fun strayPartFilesAreCleaned() {
        val d = tmp(); File(d, "X.RW2.part").writeBytes(ByteArray(3))
        CopyEngine(d, ImportLedger()).run(emptyList()); assertEquals(0, d.list()!!.size)
    }
    @Test fun reportAndSpeed() {
        val r = ImportReport(listOf(FileResult("a", Outcome.COPIED, 100L shl 20), FileResult("b", Outcome.SKIPPED_DONE, 0),
            FileResult("c", Outcome.FAILED, 0, "x"), FileResult("d.dng", Outcome.COPIED, 1, dng = DngProbe(DngSupport.PREVIEW_ONLY, 52546, "jxl"))), 5000)
        assertEquals("2 copied, 1 already imported, 1 failed, 1 preview only (decode not supported)", r.summary())
        assertEquals(20.0, r.mbPerSecond, 0.01)
        assertEquals(LinkSpeed.SLOW, SpeedClass.classify(19.9)); assertEquals(LinkSpeed.OK, SpeedClass.classify(20.0)); assertEquals(LinkSpeed.FAST, SpeedClass.classify(120.0))
        assertEquals(10, SpeedClass.etaSeconds(100L shl 20, 10.0)); assertEquals(-1, SpeedClass.etaSeconds(5, 0.0))
    }
    private fun probe(n: String) = DngProber.probeFile(File(imp, n))
    @Test fun probesSyntheticDngs() {
        assertEquals(DngSupport.SUPPORTED, probe("base_dng.dng").support)
        assertEquals(DngSupport.SUPPORTED, probe("comp1.dng").support)
        assertEquals(DngSupport.SUPPORTED, probe("comp7.dng").support)
        assertEquals(DngSupport.SUPPORTED, probe("comp8.dng").support)
        assertEquals(52546, probe("comp52546.dng").compression); assertEquals(DngSupport.PREVIEW_ONLY, probe("comp52546.dng").support)
        assertEquals(DngSupport.PREVIEW_ONLY, probe("comp9.dng").support)
        assertEquals(DngSupport.PREVIEW_ONLY, probe("comp34892.dng").support)
    }
    @Test fun nonDngAndTruncated() {
        assertEquals(DngSupport.NOT_DNG, DngProber.probe("RIFF....".toByteArray()).support)
        assertEquals(DngSupport.NOT_DNG, DngProber.probe(ByteArray(3)).support)
        val head = File(imp, "comp8.dng").readBytes().copyOf(20)
        assertEquals(DngSupport.UNREADABLE, DngProber.probe(head).support)
    }
}
```
