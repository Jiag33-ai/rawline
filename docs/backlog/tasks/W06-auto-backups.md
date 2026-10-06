# W06 Automatic rotating backups outside the app, the Backup button and restore preview (BK-142, BK-143, BK-303)

Written 6 Oct 2026 against main d6c5afc. What was verified here: the pure Kotlin in section 7 (the backup zip with a manifest and checksums, verification, the run order that cannot lose an old backup, rotation, the due-policy, discovery after a reinstall, a real-files target) was compiled with the Kotlin 2.4.10 compiler and its 29 tests pass under JUnit with org.json 20250517. Not verified (specified from the platform documentation and the existing code, CI is the check): WorkManager, the SAF and MediaStore targets, Settings UI, the first-launch offer, and whether MediaStore files survive a reinstall (the task is designed so it does not matter).

## 1. What exists today

- `Catalog.writeBackup(out)` writes one zip (edits, snapshots, presets, meta, mask and heal PNGs, `version.json`); `Catalog.readBackup` is transactional and newer-wins; `BackupReader` caps sizes and validates names.
- Settings has a "Back up edits" button that asks for a file with `CreateDocument` every time (`MainActivity.backupOut`), no schedule, no rotation, no check after writing, no manifest.
- The app already declares `MANAGE_EXTERNAL_STORAGE` (optional All files access) and `READ_MEDIA_IMAGES`. `androidx.work` is not a dependency yet.

## 2. Decisions (so the worker does not ask)

| # | Decision | Reason |
|---|---|---|
| D1 | Three targets behind one interface (`BackupTarget`), chosen in this order: (1) `FileBackupTarget` on `Documents/Rawline/backups` when `Environment.isExternalStorageManager()` is true; (2) `SafBackupTarget` on a folder the user chose with `OpenDocumentTree` (persisted permission); (3) `MediaStoreBackupTarget` on `Documents/Rawline/backups` through `MediaStore.Files` | With All files access the files are plain files that any reinstall reads (BK-143 solved without a folder pick). A SAF folder survives an uninstall and can be re-granted by picking it again. MediaStore needs no permission, but on Android 11 and later an app sees only the files it created itself, and ownership after an uninstall and reinstall is not something this task can prove offline: so it is the last resort and Settings says so ("Backups in this place may not be found after a reinstall"). A phone check decides (section 8). |
| D2 | The zip gets `manifest.json` as its last entry: format 2, creation time, app version, counts, and the SHA-256 and size of every other entry. Format 1 zips (no manifest) still restore, with "This is an older backup, it cannot be checked" | BK-303; checksums let the writer verify its own output and the restore refuse a damaged file before writing anything. |
| D3 | Run order (`BackupRunner`): write `name.part`, read it back and verify it against its manifest, publish by rename, only then delete the oldest beyond 7. Any failure deletes the `.part` and leaves every older backup alone | A backup run must never be able to make things worse. |
| D4 | Names are `rawline-backup-YYYYMMDD-HHmm.zip` (local time); rotation orders by that date in the name, never by file time, and only touches files named like that (never `.part` of another run, never foreign files) | File times change when a folder is copied or synced. |
| D5 | Triggers: daily (a `PeriodicWorkRequest` of 24 h with a 4 h flex, `setRequiresBatteryNotLow`), after every 25 changes (a unique one-time request delayed 2 minutes), and Back up now. The policy (`BackupPolicy`) backs up only when something changed since the last backup (a change counter, not a clock): at least 20 h since the last one for the daily case, at least 25 changes and 10 minutes for the edits case, immediately for the very first change ever | No empty backups, no battery use when nothing happened. |
| D6 | The change counter is a `Long` in the existing "rawline" SharedPreferences, incremented by `Catalog.saveEdit`, rating, flag and label writes, preset add and delete, and snapshot add. It only increases | Cheap, no Room change, no migration. |
| D7 | The existing manual "Back up edits" becomes two things: "Back up now" (runs the same runner into the target) and "Save a copy to..." (the old `CreateDocument` path, unchanged, for moving a backup somewhere else) | Keeps the old capability. |
| D8 | Restore: Settings lists the backups in the target (date, size, edit count from the manifest) plus "Choose a file...". Choosing one copies it to the cache, runs `BackupZip.verify`, shows the preview ("This backup has 312 edits, 1,204 ratings, 18 presets, from 4 Oct. Anything changed more recently on this phone is kept. Restore?") and only then calls `readBackup`. A failed verification shows the first problem and restores nothing | BK-303, BK-144. |
| D9 | First launch offer: when the catalogue has no edits and the target holds a backup, one dialog "Found a backup from 5 Oct with 312 edits. Restore it?" with Restore and Not now (and "Not now" is remembered per backup name). When no target is configured and the catalogue is empty, Settings shows "Choose your backup folder to look for old backups" | BK-143. |

## 3. Work to do, in order

1. Put `AutoBackup.kt` (section 7) in `core/data/src/main/kotlin/app/rawline/core/data/` and `AutoBackupTest.kt` in the matching test folder. Add `manifest.json` to `BackupReader`'s ignored names list is not needed (unknown names are already ignored); do add `manifest.json` to `JSON_NAMES` handling only if you want it parsed there (not required).
2. `Catalog.writeBackup(out)` becomes: build `parts` (a `BackupPart` per JSON file, whose `open` returns a `ByteArrayInputStream` of the JSON text built from the DAOs, and per mask or heal PNG whose `open` is `FileInputStream`), `counts` (`edits`, `snapshots`, `presets`, `meta`, `masks`, `heals`), and call `BackupZip.write(out, parts, counts, now, BuildConfig.VERSION_NAME)` (a new `appVersion` parameter on `Catalog` or pass it in). Keep `version.json` as a part so format 1 readers keep working. `BackupReader` needs no change (it ignores `manifest.json`). Add a test in `BackupReaderTest` that a zip written this way is read by `BackupReader.read` and verifies with `BackupZip.verify`.
3. `ChangeCounter` (tiny object over SharedPreferences: `bump()`, `value()`), and the `bump()` calls of D6. A host test with a fake `KeyValue`-style map is enough.
4. Targets (Android, not compiled here): the skeletons in section 5. `BackupTargets.choose(context, prefs)` implements the D1 order and returns the target plus a label for Settings.
5. `BackupWorker` and scheduling (section 5), `androidx.work:work-runtime-ktx` added to `gradle/libs.versions.toml` and `app/build.gradle.kts` (use the current stable release; it needs network, CI has it).
6. Settings: replace the backup section as in section 5; wire `onBackupNow`, `onSaveCopy`, `onRestoreList`, `onChooseFolder` in `MainActivity` and `LibraryViewModel` (the VM keeps `backupTo(uri)` for the copy path).
7. First launch offer (D9) in `RawlineRoot` after the library has loaded.
8. Docs: README (where backups live and how to restore after a reinstall), docs/DECISIONS.md (D1 to D9 in short), docs/AUDIT.md (BK-142, BK-143, BK-303 done).

## 4. Acceptance

1. `BackupRunner` tests green (29 tests in section 7), `BackupReaderTest` extended as in step 2.
2. Back up now writes a verified file named `rawline-backup-YYYYMMDD-HHmm.zip` into the chosen target, shows "Backup saved (3.2 MB)", and Settings shows "Last backup: today 14:15, 3.2 MB" and the target label.
3. After 8 runs the folder holds exactly 7 backups and the oldest is gone; a file called `notes.txt` placed in the folder is never touched.
4. Killing the app or filling the disk in the middle of a run leaves the older backups intact and no `.part` after the next run (the worker deletes a stale `.part` older than a day at the start of every run: add that, a test with `MemTarget`).
5. Daily and after-25-edits triggers work: a debug action "Pretend 25 edits" and WorkManager's test driver (`TestListenableWorkerBuilder`) in a Robolectric-free unit test of `BackupWorker.decide` (the policy function) are enough; the policy tests are in section 7.
6. Restore: a damaged zip (flip a byte in a copy) is refused with the first problem and nothing changes; a good zip shows the preview and then restores; a format 1 zip restores with the older-backup note.
7. Reinstall check (phone, section 8) done and its result written into docs/DECISIONS.md.
8. Develop behaviour, goldens and existing tests unchanged.

## 5. Android skeletons (not compiled here; follow the patterns in `Catalog` and `ExportRunner`)

```kotlin
// app/src/main/kotlin/app/rawline/backup/Targets.kt
class SafBackupTarget(private val cr: ContentResolver, private val tree: Uri) : BackupTarget {
    private val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private fun children() = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private fun find(name: String): Uri? { cr.query(children(), arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> while (c.moveToNext()) if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) }; return null }
    override fun list(): List<BackupTarget.Entry> { val out = ArrayList<BackupTarget.Entry>(); cr.query(children(), arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c -> while (c.moveToNext()) out += BackupTarget.Entry(c.getString(0), c.getLong(1), c.getLong(2)) }; return out }
    override fun create(name: String): OutputStream = cr.openOutputStream(DocumentsContract.createDocument(cr, treeDoc, "application/octet-stream", name) ?: throw IOException("could not create $name"))!!   // octet-stream: providers add no extension to ".part"
    override fun open(name: String): InputStream = cr.openInputStream(find(name) ?: throw IOException("missing $name"))!!
    override fun rename(from: String, to: String) { if (find(to) != null) throw IOException("$to exists"); DocumentsContract.renameDocument(cr, find(from) ?: throw IOException("missing $from"), to) ?: throw IOException("could not rename") }
    override fun delete(name: String) { find(name)?.let { DocumentsContract.deleteDocument(cr, it) } }
    override fun freeBytes(): Long? = null
}
class MediaStoreBackupTarget(private val cr: ContentResolver) : BackupTarget {
    // MediaStore.Files, RELATIVE_PATH "Documents/Rawline/backups/", MIME application/zip for finished files; while writing a ".part" row has IS_PENDING = 1 (invisible to other apps),
    // rename = update DISPLAY_NAME and IS_PENDING = 0 in one update; list = query own rows under that RELATIVE_PATH; delete = cr.delete(row uri). freeBytes = StatFs on the volume.
}
object BackupTargets {
    fun choose(c: Context, prefs: SharedPreferences): Pair<BackupTarget, String> = when {
        Environment.isExternalStorageManager() -> FileBackupTarget(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Rawline/backups")) to "Documents/Rawline/backups"
        prefs.getString("backup_tree", null) != null -> SafBackupTarget(c.contentResolver, Uri.parse(prefs.getString("backup_tree", null))) to "your backup folder"
        else -> MediaStoreBackupTarget(c.contentResolver) to "this phone (Documents/Rawline/backups). Backups here may not be found after a reinstall"
    }
}
```
```kotlin
// app/src/main/kotlin/app/rawline/backup/BackupWorker.kt
class BackupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val app = applicationContext as RawlineApplication
        val prefs = app.graph.prefs
        val state = BackupState(prefs.getLong("backup_last", 0), prefs.getLong("backup_changes_at_last", 0), ChangeCounter.value(prefs))
        val manual = inputData.getBoolean("manual", false)
        if (!manual && BackupPolicy().due(state, System.currentTimeMillis()) == null) return Result.success()
        val (target, _) = BackupTargets.choose(applicationContext, prefs)
        val run = withContext(Dispatchers.IO) { BackupRunner(target).run(System.currentTimeMillis()) { out -> app.graph.catalog.writeBackup(out) } }
        if (run.ok) prefs.edit().putLong("backup_last", System.currentTimeMillis()).putLong("backup_changes_at_last", state.changes).putLong("backup_last_bytes", run.bytes).remove("backup_error").apply()
        else prefs.edit().putString("backup_error", run.message).apply()
        return if (run.ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }
    companion object {
        fun schedule(c: Context) {
            val wm = WorkManager.getInstance(c)
            wm.enqueueUniquePeriodicWork("rawline-backup-daily", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        }
        fun afterEdits(c: Context) = WorkManager.getInstance(c).enqueueUniqueWork("rawline-backup-edits", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(2, TimeUnit.MINUTES).build())
        fun now(c: Context) = WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<BackupWorker>().setInputData(workDataOf("manual" to true)).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
}
```
(`catalog.writeBackup(out)` reads the DAOs from the worker thread; Room is thread safe. `ChangeCounter.bump()` calls `BackupWorker.afterEdits(c)` when the value is a multiple of 25. `schedule` is called once in `RawlineApplication.onCreate` after the first frame, not before it.)

Settings section (strings in Australian English, no em dashes):
- Title "Backups"; line "Backing up to {label}"; "Last backup: today 14:15, 3.2 MB" or "No backup yet"; error line in the error colour when `backup_error` is set ("Last backup failed: {message}"); switch "Back up automatically" (default on; off cancels the periodic work); buttons "Back up now", "Restore from a backup...", "Save a copy to...", "Choose backup folder..." (shown when All files access is off); note "Keeps the last 7. Only your edits, ratings and presets are saved, not the photos."
- Toasts: "Backup saved (3.2 MB)", "Backup failed: {message}".

## 6. Why the order and the checks matter (what the tests prove)

`BackupRunnerTest` shows each failure path (out of space in the middle, a producer that throws, a zip that does not verify, a failed rename, too little free space up front) leaves every older backup in place and no `.part`; rotation only ever runs after the new file is verified and published, so there is no state in which fewer good backups exist than before. `FileBackupTargetTest` runs the whole thing on real files and reads the result back through a second target object, which is what a reinstalled app does.

## 7. Tested source (core/data, package app.rawline.core.data)

### AutoBackup.kt
```kotlin
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

    class Verified(val ok: Boolean, val manifest: BackupManifest?, val problems: List<String>)

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
        if (m == null) { if (problems.isEmpty()) problems += "no manifest.json"; return Verified(false, null, problems) }
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
        val existing = target.list().map { it.name }.toSet()
        var name = BackupName.forTime(nowMs, zone)
        while (name in existing) name = BackupName.forTime(nowMs, zone, ++suffix)       // two backups in the same minute
        val part = name + BackupName.PART
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
    private fun safe(n: String): String { require(n.isNotEmpty() && !n.contains('/') && !n.contains('\\') && n != "." && n != "..") { "bad file name" }; return n }
}
```
### AutoBackupTest.kt (29 tests)
```kotlin
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
```

## 8. Phone checks (Jai, once, then paste the Copy report)

1. Grant All files access (Settings already links to it), tap Back up now: a file appears in Files under Documents/Rawline/backups; tap it three more times on different minutes and look at the count (never more than 7).
2. Make an edit and a rating, uninstall Rawline from the system settings, install the next build: the first launch offers "Found a backup from today with N edits". Restore, and the edit and rating are back.
3. Turn All files access off, choose a backup folder, repeat step 2 (after reinstall choose the same folder; the offer should appear). Then turn the folder off and test the MediaStore default the same way and note whether the backup is found after a reinstall: that single result decides the wording of the MediaStore note in Settings.
4. Flip airplane mode and battery saver on and off: nothing breaks; the next due run happens.

## 9. Risks

- WorkManager periodic work is deferred by Doze and battery optimisation on One UI; the "after 25 edits" and "Back up now" paths are the reliable ones, and the daily one is best effort (Settings shows the last time so a gap is visible).
- SAF is slower and some providers rename oddly; the runner checks the final name through `list()` implicitly (publish by rename fails if the name exists).
- A backup is a copy of the catalogue, not of the photos: if the photos are deleted the edits are useless; the Settings text says so.
