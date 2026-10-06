package app.rawline.core.data.ingest

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/** Pure JVM pieces of the card import (BK-096, 344, 431). No android.* imports, so the host tests run them. */

/** One source file on the card. [size] is -1 when the card does not say (then the length of what was read cannot be checked, only that the copy matches it); [modifiedMs] is 0 when unknown. */
class CardFile(val name: String, val size: Long, val modifiedMs: Long, val open: () -> InputStream)

object ImportNaming {
    private val RAW_EXT = setOf("rw2", "dng", "orf", "cr2", "cr3", "nef", "arw", "raf")
    fun isRaw(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in RAW_EXT && !name.startsWith(".")

    /** A name a card (or a hostile provider) may hand over that is still safe as one file name in our folder. */
    fun isSafe(name: String) = name.isNotEmpty() && name.length <= 200 && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 }

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

/**
 * Remembers what was imported so a second insert of the same card copies nothing. The key ignores the destination (the copy keeps the card
 * file's time, so `Photo.keyOf` of the copy equals the card's). With [load] every added key is appended to the file at once, so a kill
 * loses nothing that was finished.
 */
class ImportLedger(private val lines: MutableSet<String> = linkedSetOf(), private val persist: ((String) -> Unit)? = null) {
    fun key(f: CardFile) = "${f.name.lowercase(Locale.ROOT)}|${f.size}|${f.modifiedMs / 2000}"     // FAT stores 2 s resolution
    fun has(f: CardFile) = key(f) in lines
    fun add(f: CardFile) { val k = key(f); if (lines.add(k)) persist?.invoke(k) }
    val size get() = lines.size
    fun serialise(): String = lines.joinToString("\n")

    companion object {
        fun parse(s: String) = ImportLedger(s.lineSequence().filter { it.isNotBlank() }.toCollection(linkedSetOf()))

        /** Reads [file] (missing or unreadable is an empty ledger) and appends to it from then on. A failed append is ignored: the copy is still on the phone and the next run finds it by name, size and time. */
        fun load(file: File): ImportLedger {
            val text = try { if (file.isFile) file.readText() else "" } catch (e: IOException) { "" }
            val base = parse(text)
            return ImportLedger(base.lines) { key -> try { FileOutputStream(file, true).use { it.write(("\n" + key).toByteArray()); it.fd.sync() } } catch (e: IOException) { } }
        }
    }
}

enum class Outcome { COPIED, SKIPPED_DONE, FAILED, CANCELLED }
class FileResult(val name: String, val outcome: Outcome, val bytes: Long, val note: String = "", val dng: DngProbe? = null)

enum class LinkSpeed { SLOW, OK, FAST }
object SpeedClass {
    /** Sustained MB/s over at least 2 s of copying. Below 20 usually means a USB 2 reader or a slow card. */
    fun classify(mbPerSecond: Double) = when { mbPerSecond < 20 -> LinkSpeed.SLOW; mbPerSecond < 80 -> LinkSpeed.OK; else -> LinkSpeed.FAST }
    fun etaSeconds(remainingBytes: Long, mbPerSecond: Double): Long = if (mbPerSecond <= 0.01) -1 else (remainingBytes / 1048576.0 / mbPerSecond).toLong()
}

/**
 * What one run did. [millis] is the whole run; [copyMillis] is only the time spent reading the card and writing the copy (the verify re-read comes
 * from the phone's own storage and would flatter a slow reader), so [mbPerSecond] is the card's speed.
 */
class ImportReport(val results: List<FileResult>, val millis: Long, val copyMillis: Long = millis) {
    val copied get() = results.count { it.outcome == Outcome.COPIED }
    val skipped get() = results.count { it.outcome == Outcome.SKIPPED_DONE }
    val failed get() = results.count { it.outcome == Outcome.FAILED }
    val cancelled get() = results.count { it.outcome == Outcome.CANCELLED }
    val bytes get() = results.filter { it.outcome == Outcome.COPIED }.sumOf { it.bytes }
    val previewOnly get() = results.count { it.dng?.support == DngSupport.PREVIEW_ONLY }
    val mbPerSecond get() = if (copyMillis <= 0) 0.0 else bytes / 1048576.0 / (copyMillis / 1000.0)
    /** Null until there were 2 s of copying: a shorter run says nothing about the link. */
    val speed: LinkSpeed? get() = if (copyMillis >= 2000 && bytes > 0) SpeedClass.classify(mbPerSecond) else null

    fun summary(): String = buildString {
        append("$copied copied, $skipped already imported, $failed failed")
        if (cancelled > 0) append(", $cancelled not started (cancelled)")
        if (previewOnly > 0) append(", $previewOnly preview only (decode not supported)")
    }

    /** The block of the Copy report. Numbers come from the run, nothing is estimated. */
    fun reportText(): String = buildString {
        append(summary())
        if (bytes > 0) append("\nCopied ${"%.1f".format(Locale.ROOT, bytes / 1048576.0)} MB in ${"%.1f".format(Locale.ROOT, copyMillis / 1000.0)} s of reading and writing: ${"%.1f".format(Locale.ROOT, mbPerSecond)} MB/s, speed class ${speed?.name ?: "not enough copying to tell (under 2 s)"}")
        results.filter { it.outcome == Outcome.FAILED }.take(5).forEach { append("\nFailed ${it.name}: ${it.note}") }
        results.filter { it.dng != null }.take(10).forEach { r -> append("\nDNG ${r.name}: compression ${r.dng!!.compression}, ${r.dng.support.name} (${r.dng.reason})") }
    }
}

fun interface FreeSpace { fun usable(): Long }

/**
 * Copies one file at a time: write `<name>.part`, fsync, read the part back and compare its SHA-256 with the one of the bytes streamed from the
 * card, rename, give the copy the card file's time, then add it to the ledger. A kill at any point leaves only `.part` files, which the next run
 * deletes; finished files are never touched. The card is only read. [cancelled] is asked between files and between 256 KB chunks.
 * Resume: run the same card again; what is in the ledger (or already in the folder with the same name, size and time) is skipped.
 */
class CopyEngine(
    private val destDir: File,
    private val ledger: ImportLedger,
    private val free: FreeSpace = FreeSpace { destDir.usableSpace },
    private val reserveBytes: Long = 64L shl 20,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Opens the `.part` for writing; a seam so a test can run the phone out of space part way through a file. */
    private val openPart: (File) -> FileOutputStream = { FileOutputStream(it) },
) {
    private class Cancelled : Exception()

    fun run(files: List<CardFile>, cancelled: () -> Boolean = { false }, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportReport {
        val t0 = clock()
        destDir.mkdirs()
        destDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        val out = ArrayList<FileResult>()
        var copyNanos = 0L
        files.forEachIndexed { i, f ->
            progress(i, files.size)
            when {
                cancelled() -> out.add(FileResult(f.name, Outcome.CANCELLED, 0))
                !ImportNaming.isSafe(f.name) -> out.add(FileResult(f.name, Outcome.FAILED, 0, "the card gave an unusable file name"))
                ledger.has(f) -> out.add(FileResult(f.name, Outcome.SKIPPED_DONE, 0))
                alreadyHere(f) -> { ledger.add(f); out.add(FileResult(f.name, Outcome.SKIPPED_DONE, 0, "already on the phone")) }
                f.size >= 0 && free.usable() < f.size + reserveBytes -> out.add(FileResult(f.name, Outcome.FAILED, 0, "not enough space on the phone"))
                else -> { val t = System.nanoTime(); val r = copyOne(f, cancelled); copyNanos += System.nanoTime() - t; out.add(r) }
            }
        }
        progress(files.size, files.size)
        return ImportReport(out, clock() - t0, copyNanos / 1_000_000)
    }

    /** The same name with the same size and time (to the FAT 2 s) is the same file, whether or not the ledger remembers it. */
    private fun alreadyHere(f: CardFile): Boolean {
        val d = File(destDir, f.name)
        return f.size >= 0 && d.isFile && d.length() == f.size && Math.abs(d.lastModified() - f.modifiedMs) < 2000
    }

    private fun copyOne(f: CardFile, cancelled: () -> Boolean): FileResult {
        val name = ImportNaming.unique(f.name, destDir.list()?.toSet().orEmpty())
        val part = File(destDir, "$name.part"); val dest = File(destDir, name)
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.open().use { input -> openPart(part).use { o -> pump(input, o, md, cancelled); o.flush(); o.fd.sync() } }
            val srcSum = md.digest()
            if (f.size >= 0 && part.length() != f.size) { part.delete(); return FileResult(f.name, Outcome.FAILED, 0, "short read (${part.length()} of ${f.size} bytes)") }
            val back = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { pump(it, null, back, { false }) }
            if (!back.digest().contentEquals(srcSum)) { part.delete(); return FileResult(f.name, Outcome.FAILED, 0, "verify failed") }
            if (!part.renameTo(dest)) { part.delete(); return FileResult(f.name, Outcome.FAILED, 0, "rename failed") }
            val timeKept = f.modifiedMs <= 0 || dest.setLastModified(f.modifiedMs)           // (a card that gives no time keeps the time of the copy) keeps Photo.keyOf of the copy equal to the card file's
            ledger.add(f)
            val probe = if (name.lowercase(Locale.ROOT).endsWith(".dng")) DngProber.probeFile(dest) else null
            FileResult(name, Outcome.COPIED, dest.length(), if (timeKept) "" else "the file time could not be kept", probe)
        } catch (e: Cancelled) {
            part.delete(); FileResult(f.name, Outcome.CANCELLED, 0)
        } catch (e: Exception) {                                         // a card pulled mid file, a full phone, a provider that throws: this file fails, the rest go on
            part.delete(); FileResult(f.name, Outcome.FAILED, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun pump(i: InputStream, o: OutputStream?, md: MessageDigest, cancelled: () -> Boolean) {
        val b = ByteArray(256 * 1024)
        while (true) {
            if (cancelled()) throw Cancelled()
            val n = i.read(b)
            if (n < 0) break
            md.update(b, 0, n); o?.write(b, 0, n)
        }
    }
}
