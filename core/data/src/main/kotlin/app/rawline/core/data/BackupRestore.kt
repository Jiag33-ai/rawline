package app.rawline.core.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a backup file turned out to be before anything is restored from it. */
sealed class RestoreCheck {
    /** A format 2 backup whose every file matches its manifest. */
    class Good(val manifest: BackupManifest) : RestoreCheck()
    /** A format 1 backup (no manifest): readable, but it cannot be checked. [BackupReader] still validates it while restoring. */
    object Older : RestoreCheck()
    /** Refused: nothing is restored. [problem] is the first thing found. */
    class Damaged(val problem: String) : RestoreCheck()

    companion object {
        fun of(v: BackupZip.Verified): RestoreCheck = when {
            v.ok && v.manifest != null -> Good(v.manifest)
            v.older -> Older
            else -> Damaged(v.problems.firstOrNull() ?: "the backup could not be read")
        }
    }
}

/** The words shown before a restore (D8). Pure so the wording is tested. */
object RestoreText {
    private val AU = Locale.Builder().setLanguage("en").setRegion("AU").build()
    private val DAY = DateTimeFormatter.ofPattern("d MMM", AU)
    private fun n(count: Int, one: String, many: String = one + "s") = "%,d %s".format(AU, count, if (count == 1) one else many)

    fun preview(c: RestoreCheck, zone: ZoneId = ZoneId.systemDefault()): String = when (c) {
        is RestoreCheck.Good -> {
            val m = c.manifest
            val from = if (m.created > 0) ", from " + DAY.format(Instant.ofEpochMilli(m.created).atZone(zone)) else ""
            "This backup has ${n(m.counts["edits"] ?: 0, "edit")}, ${n(m.counts["meta"] ?: 0, "rating")}, ${n(m.counts["presets"] ?: 0, "preset")}$from. " +
                "Anything changed more recently on this phone is kept. Restore?"
        }
        RestoreCheck.Older -> "This is an older backup, it cannot be checked. Anything changed more recently on this phone is kept. Restore?"
        is RestoreCheck.Damaged -> "This backup cannot be used: ${c.problem}. Nothing was changed."
    }

    /** One line of the backup list: "4 Oct, 3.2 MB" (plus the edit count when known). */
    fun listLine(whenMs: Long, bytes: Long, edits: Int?, zone: ZoneId = ZoneId.systemDefault()): String =
        DAY.format(Instant.ofEpochMilli(whenMs).atZone(zone)) + ", " + size(bytes) + (edits?.let { ", " + n(it, "edit") } ?: "")

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(AU, bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** "Last backup: today 14:15, 3.2 MB", "yesterday 09:00" or "4 Oct 14:15", and "No backup yet". */
    fun lastLine(lastMs: Long, bytes: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (lastMs <= 0) return "No backup yet"
        val t = Instant.ofEpochMilli(lastMs).atZone(zone); val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val hm = DateTimeFormatter.ofPattern("HH:mm", AU).format(t)
        val day = when (t.toLocalDate()) { today -> "today"; today.minusDays(1) -> "yesterday"; else -> DAY.format(t) }
        return "Last backup: $day $hm" + (if (bytes > 0) ", ${size(bytes)}" else "")
    }
}

/** Where a counter lives. A SharedPreferences adapter in the app, a map in tests. */
interface CounterStore { fun getLong(key: String, default: Long): Long; fun putLong(key: String, value: Long) }

/** The change counter of D6: only ever increases, so "something changed since the last backup" is one comparison. */
class ChangeCounter(private val store: CounterStore, private val key: String = KEY) {
    fun value(): Long = synchronized(lock) { store.getLong(key, 0L) }
    /** Returns the new value. */
    fun bump(by: Int = 1): Long = synchronized(lock) { val v = store.getLong(key, 0L) + by.coerceAtLeast(0); store.putLong(key, v); v }
    companion object {
        const val KEY = "backup_changes"
        /** After this many changes a backup is asked for (the policy still applies its own gap). */
        const val EVERY = 25
        private val lock = Any()
        fun crossedThreshold(before: Long, after: Long, every: Int = EVERY) = after / every > before / every
    }
}

/**
 * Copies a chosen backup into a file this app owns and checks it, so what is shown in the preview is exactly what is restored
 * (a provider cannot change the file between the check and the restore). Nothing touches the catalogue here.
 */
object RestoreStaging {
    /** More than [BackupReader.MAX_TOTAL_BYTES] of content cannot be restored, so a larger file is refused before it fills the cache. */
    const val MAX_FILE_BYTES = BackupReader.MAX_TOTAL_BYTES + (64L shl 20)

    class Staged(val file: File, val check: RestoreCheck) { fun discard() { file.delete() } }

    /** Throws [IOException] when the source cannot be read, is too large or the cache is full; the partial copy is deleted first. */
    fun stage(source: InputStream, cacheDir: File, zone: ZoneId = ZoneId.systemDefault()): Staged {
        cacheDir.mkdirs()
        val f = File(cacheDir, "restore-candidate-" + System.nanoTime() + ".zip")
        try {
            var total = 0L
            source.use { ins -> f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) { val k = ins.read(buf); if (k < 0) break; total += k; if (total > MAX_FILE_BYTES) throw IOException("the file is too large to be a backup"); o.write(buf, 0, k) }
            } }
            val check = f.inputStream().use { RestoreCheck.of(BackupZip.verify(it)) }
            return Staged(f, check)
        } catch (e: Throwable) { f.delete(); throw e }
    }

    /** Stale candidates from a run that was killed (called when Settings opens the restore list). */
    fun clean(cacheDir: File) { cacheDir.listFiles()?.filter { it.name.startsWith("restore-candidate-") }?.forEach { it.delete() } }
}
