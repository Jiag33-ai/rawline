package app.rawline.core.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Where backups live. Implemented over a SAF folder, MediaStore Documents and (in tests) memory. Names are plain file names, no paths. */
interface BackupTarget {
    class Entry(val name: String, val size: Long, val modified: Long)
    fun list(): List<Entry>
    fun create(name: String): OutputStream          // throws IOException (no space, no permission)
    fun open(name: String): InputStream
    fun rename(from: String, to: String)            // atomic publish of a finished backup; must fail if [to] exists
    fun delete(name: String)
    /** Free bytes, or null when unknown. */
    fun freeBytes(): Long?
}

/** `rawline-backup-20261006-1415.zip`: the local date and time the backup was made. The name, not the file time, orders backups (file times change when a folder is copied). */
object BackupName {
    private val F = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val RE = Regex("rawline-backup-(\\d{8}-\\d{4})(-\\d)?\\.zip")
    const val PART = ".part"

    fun forTime(nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), suffix: Int = 0): String =
        "rawline-backup-" + F.format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)) + (if (suffix > 0) "-$suffix" else "") + ".zip"

    /** The moment a backup was made, read from its name (local time of [zone]), or null when the name is not one of ours. */
    fun timeOf(name: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
        RE.matchEntire(name)?.let { LocalDateTime.parse(it.groupValues[1], F).atZone(zone).toInstant().toEpochMilli() }

    /** Sortable key (date, time, suffix) or null when the file is not one of ours. Files that are not ours are never touched. */
    fun key(name: String): String? = RE.matchEntire(name)?.let { it.groupValues[1] + (it.groupValues[2].ifEmpty { "-0" }) }
}

/** The rotation: keep the newest [keep] backups by the date in their names. Anything not named like ours (and any `.part`) is left alone here. */
object BackupRotation {
    fun toDelete(names: List<String>, keep: Int = 7): List<String> {
        val ours = names.mapNotNull { n -> BackupName.key(n)?.let { it to n } }.sortedByDescending { it.first }
        return ours.drop(keep.coerceAtLeast(1)).map { it.second }
    }
}

/** What the policy needs to know. [changes] only ever increases (edits saved, ratings, presets); the counter at the last backup says whether anything is new. */
class BackupState(val lastBackupMs: Long, val changesAtLastBackup: Long, val changes: Long)

enum class Due { NEVER_BACKED_UP, DAILY, MANY_EDITS }

/** When to back up: daily if anything changed, after [editsThreshold] changes at most every [minGapMinutes], and the first time as soon as there is something to lose. */
class BackupPolicy(private val dailyHours: Long = 20, private val editsThreshold: Int = 25, private val minGapMinutes: Long = 10) {
    fun due(s: BackupState, nowMs: Long): Due? {
        val changed = s.changes - s.changesAtLastBackup
        if (changed <= 0) return null
        if (s.lastBackupMs <= 0) return if (changed >= 1) Due.NEVER_BACKED_UP else null
        val since = nowMs - s.lastBackupMs
        if (since < 0) return Due.DAILY                                   // the clock moved back: do not wait for a future time
        if (since >= dailyHours * 3_600_000L) return Due.DAILY
        if (changed >= editsThreshold && since >= minGapMinutes * 60_000L) return Due.MANY_EDITS
        return null
    }
}

/** One entry of a backup as listed in manifest.json. */
class ManifestEntry(val name: String, val bytes: Long, val sha256: String)
class BackupManifest(val format: Int, val created: Long, val appVersion: String, val counts: Map<String, Int>, val entries: List<ManifestEntry>) {
    fun toJson(): String = JSONObject().put("format", format).put("created", created).put("appVersion", appVersion)
        .put("counts", JSONObject().also { o -> counts.toSortedMap().forEach { (k, v) -> o.put(k, v) } })
        .put("entries", JSONArray().also { a -> entries.forEach { a.put(JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256)) } }).toString()

    companion object {
        const val NAME = "manifest.json"
        const val FORMAT = 2     // format 1 is the zip without a manifest (version.json only)
        fun parse(text: String): BackupManifest {
            val o = JSONObject(text)
            val c = o.getJSONObject("counts"); val e = o.getJSONArray("entries")
            return BackupManifest(o.getInt("format"), o.optLong("created"), o.optString("appVersion"),
                c.keys().asSequence().associateWith { c.getInt(it) }, List(e.length()) { i -> e.getJSONObject(i).let { ManifestEntry(it.getString("name"), it.getLong("bytes"), it.getString("sha256")) } })
        }
    }
}

/** A file going into a backup. [open] is called once, while writing. */
class BackupPart(val name: String, val open: () -> InputStream)

object BackupZip {
    /** Writes [parts] and then manifest.json (last, because it holds the hashes of everything before it). Streams: no part is held whole in memory. */
    fun write(out: OutputStream, parts: List<BackupPart>, counts: Map<String, Int>, nowMs: Long, appVersion: String) {
        val z = ZipOutputStream(out)
        val entries = ArrayList<ManifestEntry>()
        for (p in parts) {
            val md = MessageDigest.getInstance("SHA-256"); var n = 0L
            z.putNextEntry(ZipEntry(p.name))
            p.open().use { ins -> val buf = ByteArray(32 * 1024); while (true) { val k = ins.read(buf); if (k < 0) break; md.update(buf, 0, k); z.write(buf, 0, k); n += k } }
            z.closeEntry()
            entries += ManifestEntry(p.name, n, md.digest().joinToString("") { "%02x".format(it) })
        }
        z.putNextEntry(ZipEntry(BackupManifest.NAME)); z.write(BackupManifest(BackupManifest.FORMAT, nowMs, appVersion, counts, entries).toJson().toByteArray()); z.closeEntry()
        z.finish(); z.flush()
    }

    /** Reads only the manifest (needs a file: the zip directory is at the end). Null for a zip without one (an older backup). */
    fun manifestOf(file: File): BackupManifest? = ZipFile(file).use { z -> z.getEntry(BackupManifest.NAME)?.let { BackupManifest.parse(z.getInputStream(it).readBytes().toString(Charsets.UTF_8)) } }

    /** [older] is true for a readable zip of format 1: no manifest, but a version.json, so it cannot be checked yet is not damaged. */
    class Verified(val ok: Boolean, val manifest: BackupManifest?, val problems: List<String>, val older: Boolean = false)

    /** Streams the whole zip, hashing every entry, and compares with its manifest. Entries not in the manifest, missing ones, size and hash mismatches are problems. Cannot throw on a damaged zip: that is a problem too. */
    fun verify(input: InputStream): Verified {
        val seen = HashMap<String, ManifestEntry>(); var manifest: BackupManifest? = null; val problems = ArrayList<String>()
        try {
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val md = MessageDigest.getInstance("SHA-256"); var n = 0L; val buf = ByteArray(32 * 1024); val keep = if (e.name == BackupManifest.NAME) ByteArrayOutputStream() else null
                    while (true) { val k = z.read(buf); if (k < 0) break; md.update(buf, 0, k); n += k; keep?.write(buf, 0, k); if (n > (2L shl 30)) throw IOException("entry too large") }
                    if (keep != null) manifest = try { BackupManifest.parse(keep.toString(Charsets.UTF_8.name())) } catch (x: Exception) { problems += "manifest.json is damaged"; null }
                    else seen[e.name] = ManifestEntry(e.name, n, md.digest().joinToString("") { "%02x".format(it) })
                }
            }
        } catch (x: Exception) { problems += "the zip is damaged (${x.message})" }
        val m = manifest
        if (m == null) {
            val older = problems.isEmpty() && "version.json" in seen
            if (problems.isEmpty()) problems += "no manifest.json"
            return Verified(false, null, problems, older)
        }
        for (want in m.entries) {
            val got = seen.remove(want.name)
            if (got == null) problems += "${want.name} is missing" else if (got.bytes != want.bytes) problems += "${want.name} has the wrong size" else if (got.sha256 != want.sha256) problems += "${want.name} does not match its checksum"
        }
        for (extra in seen.keys) problems += "$extra is not listed in the manifest"
        return Verified(problems.isEmpty(), m, problems)
    }
}

class BackupRun(val ok: Boolean, val name: String?, val bytes: Long, val deleted: List<String>, val message: String)

/**
 * One backup into a target, in the order that can never lose an existing backup: write to `name.part`, read it back and verify it, publish by rename,
 * and only then delete the oldest beyond [keep]. Any failure deletes the `.part` and leaves every older backup alone.
 */
class BackupRunner(private val target: BackupTarget, private val keep: Int = 7, private val zone: ZoneId = ZoneId.systemDefault()) {
    fun run(nowMs: Long, writeZip: (OutputStream) -> Unit): BackupRun {
        var suffix = 0
        // a run killed part way leaves its `.part` behind: remove those older than a day (or of unknown age; runs never overlap, see BackupCoordinator) before starting
        runCatching { target.list().filter { it.name.endsWith(BackupName.PART) && (it.modified <= 0 || nowMs - it.modified > STALE_PART_MS) }.forEach { target.delete(it.name) } }
        val existing = target.list().map { it.name }.toSet()
        var name = BackupName.forTime(nowMs, zone)
        while (name in existing) name = BackupName.forTime(nowMs, zone, ++suffix)       // two backups in the same minute
        val part = name + BackupName.PART
        if (part in existing) runCatching { target.delete(part) }                                  // a recent leftover with the very name this run will use
        fun fail(msg: String): BackupRun { runCatching { target.delete(part) }; return BackupRun(false, null, 0, emptyList(), msg) }
        val counted = CountingOut()
        try {
            val free = target.freeBytes()
            val lastSize = target.list().filter { BackupName.key(it.name) != null }.maxByOrNull { BackupName.key(it.name)!! }?.size ?: 0L
            if (free != null && lastSize > 0 && free < lastSize * 3 / 2) return fail("Not enough free space for a backup (${lastSize * 3 / 2 / 1024} KB needed)")
            counted.inner = target.create(part)
            writeZip(counted); counted.flush(); counted.close()
        } catch (e: IOException) { return fail("Could not write the backup: ${e.message}") } catch (e: Exception) { return fail("Backup failed: ${e.message}") }
        val v = try { target.open(part).use { BackupZip.verify(it) } } catch (e: Exception) { return fail("Could not read the backup back: ${e.message}") }
        if (!v.ok) return fail("The backup did not verify: ${v.problems.first()}")
        try { target.rename(part, name) } catch (e: Exception) { return fail("Could not finish the backup: ${e.message}") }
        val gone = BackupRotation.toDelete(target.list().map { it.name }, keep).filter { it != name }
        val deleted = gone.filter { runCatching { target.delete(it) }.isSuccess }
        return BackupRun(true, name, counted.count, deleted, "Backup saved")
    }

    private companion object { const val STALE_PART_MS = 24L * 3_600_000L }

    private class CountingOut : OutputStream() {
        var inner: OutputStream? = null; var count = 0L
        override fun write(b: Int) { inner!!.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner!!.write(b, off, len); count += len }
        override fun flush() { inner?.flush() }
        override fun close() { inner?.close() }
    }
}

/** What Settings and the first launch show about the backups already in a target. */
class BackupInfo(val name: String, val size: Long, val whenMs: Long)
object BackupDiscovery {
    fun latest(target: BackupTarget, zone: ZoneId = ZoneId.systemDefault()): BackupInfo? =
        target.list().filter { BackupName.key(it.name) != null }.maxByOrNull { BackupName.key(it.name)!! }?.let {
            val t = Regex("(\\d{8}-\\d{4})").find(it.name)!!.value
            BackupInfo(it.name, it.size, LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")).atZone(zone).toInstant().toEpochMilli())
        }
}

/** A folder of real files: `Documents/Rawline/backups` when the app has All files access (those files survive an uninstall and any reinstall can read them), and in tests. */
class FileBackupTarget(private val dir: File, private val freeSpace: () -> Long? = { dir.usableSpace }) : BackupTarget {
    init { dir.mkdirs() }
    override fun list() = (dir.listFiles() ?: emptyArray()).filter { it.isFile }.map { BackupTarget.Entry(it.name, it.length(), it.lastModified()) }
    override fun create(name: String): OutputStream = java.io.FileOutputStream(File(dir, safe(name)))
    override fun open(name: String): InputStream = java.io.FileInputStream(File(dir, safe(name)))
    override fun rename(from: String, to: String) {
        val dst = File(dir, safe(to)); if (dst.exists()) throw IOException("$to exists")
        if (!File(dir, safe(from)).renameTo(dst)) throw IOException("could not rename $from")
    }
    override fun delete(name: String) { File(dir, safe(name)).delete() }
    override fun freeBytes() = freeSpace()
    /** The real file, for reading a manifest without streaming the whole zip. */
    fun file(name: String): File = File(dir, safe(name))
    private fun safe(n: String): String { require(n.isNotEmpty() && !n.contains('/') && !n.contains('\\') && n != "." && n != "..") { "bad file name" }; return n }
}
